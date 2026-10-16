package v6

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
	"time"
)

const maxRegistryBytes = 256 << 10

type fileFormat struct {
	Version         int         `json:"version"`
	StationID       string      `json:"stationId"`
	RegistryVersion uint64      `json:"registryVersion"`
	Devices         []deviceRec `json:"devices"`
	Revocations     []string    `json:"revocations"`
	Invitations     []inviteRec `json:"invitations"`
	Enroll          *enrollRec  `json:"enroll,omitempty"`
}

type deviceRec struct {
	ID        string `json:"id"`
	PublicKey string `json:"publicKey"`
	Name      string `json:"name"`
	Kind      string `json:"kind"`
	Revoked   bool   `json:"revoked"`
}

type inviteRec struct {
	ID           string    `json:"id"`
	Inviter      string    `json:"inviter"`
	Kind         string    `json:"kind"`
	Expires      time.Time `json:"expires"`
	AttemptsLeft int       `json:"attemptsLeft"`
}

type enrollRec struct {
	Hash    string    `json:"hash"`
	Expires time.Time `json:"expires"`
}

type registry struct {
	dir   string
	lock  *os.File
	mu    sync.Mutex
	data  fileFormat
	fault func(string) error
}

func openRegistry(dir string) (*registry, error) {
	if err := os.MkdirAll(dir, 0700); err != nil {
		return nil, err
	}
	info, err := os.Lstat(dir)
	if err != nil || !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
		return nil, errors.New("state directory must be a real directory")
	}
	if err := os.Chmod(dir, 0700); err != nil {
		return nil, err
	}
	reg := &registry{dir: dir}
	lockName := filepath.Join(dir, "registry.lock")
	if info, err := os.Lstat(lockName); err == nil && !info.Mode().IsRegular() {
		return nil, errors.New("invalid registry lock")
	}
	reg.lock, err = os.OpenFile(lockName, os.O_CREATE|os.O_RDWR, 0600)
	if err != nil {
		return nil, err
	}
	if err := os.Chmod(lockName, 0600); err != nil {
		reg.close()
		return nil, err
	}
	if err := syscall.Flock(int(reg.lock.Fd()), syscall.LOCK_EX|syscall.LOCK_NB); err != nil {
		reg.close()
		return nil, errors.New("registry is already in use")
	}
	name := filepath.Join(dir, "registry.json")
	body, err := readPrivate(name)
	if os.IsNotExist(err) {
		entries, listErr := os.ReadDir(dir)
		if listErr != nil {
			reg.close()
			return nil, listErr
		}
		for _, entry := range entries {
			if entry.Name() != "registry.lock" {
				reg.close()
				return nil, errors.New("refuse to create a registry in a non-empty directory")
			}
		}
		fresh, freshErr := newRegistry()
		if freshErr != nil {
			reg.close()
			return nil, freshErr
		}
		if err := reg.persist(fresh); err != nil {
			reg.close()
			return nil, err
		}
		reg.data = fresh
		return reg, nil
	}
	if err != nil {
		reg.close()
		return nil, err
	}
	parsed, err := decodeRegistry(body)
	if err != nil {
		reg.close()
		return nil, err
	}
	reg.data = parsed
	return reg, nil
}

func newRegistry() (fileFormat, error) {
	var raw [16]byte
	if _, err := rand.Read(raw[:]); err != nil {
		return fileFormat{}, err
	}
	return fileFormat{
		Version:         1,
		StationID:       hex.EncodeToString(raw[:]),
		RegistryVersion: 1,
		Devices:         []deviceRec{},
		Revocations:     []string{},
		Invitations:     []inviteRec{},
	}, nil
}

func (r *registry) close() {
	if r.lock != nil {
		_ = syscall.Flock(int(r.lock.Fd()), syscall.LOCK_UN)
		_ = r.lock.Close()
		r.lock = nil
	}
}

func (r *registry) snapshot() fileFormat {
	r.mu.Lock()
	defer r.mu.Unlock()
	next, err := cloneFile(r.data)
	if err != nil {
		return r.data
	}
	return next
}

// mutate edits a copy. The previous file and the in-memory registry stay in place until the new bytes are fsynced and renamed.
func (r *registry) mutate(fn func(*fileFormat) error) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	next, err := cloneFile(r.data)
	if err != nil {
		return err
	}
	if err := fn(&next); err != nil {
		return err
	}
	normalize(&next)
	if err := r.persist(next); err != nil {
		return errPersist
	}
	r.data = next
	return nil
}

func (r *registry) useAttempt(id string, now time.Time) error {
	return r.mutate(func(f *fileFormat) error {
		inv := findInvite(f, id)
		if inv == nil || !inv.Expires.After(now) || inv.AttemptsLeft < 1 {
			return errRejected
		}
		inv.AttemptsLeft--
		return nil
	})
}

func (r *registry) refund(id string) error {
	return r.mutate(func(f *fileFormat) error {
		inv := findInvite(f, id)
		if inv == nil || inv.AttemptsLeft >= 5 {
			return nil
		}
		inv.AttemptsLeft++
		return nil
	})
}

func (r *registry) approve(p pendingJoin) error {
	return r.mutate(func(f *fileFormat) error {
		inv := findInvite(f, p.invitation)
		if inv == nil || inv.Inviter != p.inviter || inv.Kind != p.kind {
			return errRejected
		}
		key := encodeKey(p.spki)
		if device := findDevice(f, p.deviceID); device != nil {
			if !device.Revoked {
				return errRejected
			}
			device.Name = p.name
			device.Kind = p.kind
			device.PublicKey = key
			device.Revoked = false
		} else if len(f.Devices) >= 32 {
			return errRejected
		} else {
			f.Devices = append(f.Devices, deviceRec{ID: p.deviceID, PublicKey: key, Name: p.name, Kind: p.kind})
		}
		f.Revocations = removeString(f.Revocations, p.deviceID)
		f.Invitations = removeInvite(f.Invitations, p.invitation)
		f.RegistryVersion++
		return nil
	})
}

func (r *registry) revoke(id string) error {
	return r.mutate(func(f *fileFormat) error {
		device := findDevice(f, id)
		if device == nil || device.Revoked {
			return errRejected
		}
		device.Revoked = true
		if !contains(f.Revocations, id) {
			f.Revocations = append(f.Revocations, id)
		}
		f.RegistryVersion++
		return nil
	})
}

func (r *registry) issueEnroll(now time.Time) (string, error) {
	var raw [16]byte
	if _, err := rand.Read(raw[:]); err != nil {
		return "", err
	}
	sum := sha256.Sum256(raw[:])
	err := r.mutate(func(f *fileFormat) error {
		f.Enroll = &enrollRec{Hash: hex.EncodeToString(sum[:]), Expires: now.UTC().Add(30 * time.Minute)}
		return nil
	})
	if err != nil {
		return "", err
	}
	return hex.EncodeToString(raw[:]), nil
}

func (r *registry) persist(next fileFormat) error {
	if r.fault != nil {
		if err := r.fault("before-write"); err != nil {
			return err
		}
	}
	normalize(&next)
	body, err := json.Marshal(next)
	if err != nil {
		return err
	}
	file, err := os.CreateTemp(r.dir, ".pending-")
	if err != nil {
		return err
	}
	defer func() { _ = file.Close(); _ = os.Remove(file.Name()) }()
	if err := file.Chmod(0600); err != nil {
		return err
	}
	if _, err := file.Write(body); err != nil {
		return err
	}
	if r.fault != nil {
		if err := r.fault("before-fsync"); err != nil {
			return err
		}
	}
	if err := file.Sync(); err != nil {
		return err
	}
	if err := file.Close(); err != nil {
		return err
	}
	if r.fault != nil {
		if err := r.fault("before-rename"); err != nil {
			return err
		}
	}
	if err := os.Rename(file.Name(), filepath.Join(r.dir, "registry.json")); err != nil {
		return err
	}
	dir, err := os.Open(r.dir)
	if err != nil {
		return err
	}
	defer dir.Close()
	return dir.Sync()
}

func cloneFile(in fileFormat) (fileFormat, error) {
	body, err := json.Marshal(in)
	if err != nil {
		return fileFormat{}, err
	}
	return decodeRegistry(body)
}

func decodeRegistry(body []byte) (fileFormat, error) {
	dec := json.NewDecoder(strings.NewReader(string(body)))
	dec.DisallowUnknownFields()
	var parsed fileFormat
	if err := dec.Decode(&parsed); err != nil {
		return fileFormat{}, errors.New("invalid registry")
	}
	if dec.More() {
		return fileFormat{}, errors.New("invalid registry")
	}
	if err := validateRegistry(parsed); err != nil {
		return fileFormat{}, err
	}
	normalize(&parsed)
	return parsed, nil
}

func validateRegistry(f fileFormat) error {
	if f.Version != 1 || !validHex(f.StationID) || f.RegistryVersion == 0 || len(f.Devices) > 32 || len(f.Invitations) > 4 {
		return errors.New("invalid registry")
	}
	seen := map[string]bool{}
	for _, device := range f.Devices {
		if seen[device.ID] || !validHex(device.ID) || (device.Kind != "mac" && device.Kind != "phone") || !validName(device.Name) {
			return errors.New("invalid registry")
		}
		spki, err := decodeKey(device.PublicKey)
		if err != nil {
			return errors.New("invalid registry")
		}
		id := deviceID(spki)
		if hex.EncodeToString(id[:]) != device.ID {
			return errors.New("invalid registry")
		}
		seen[device.ID] = true
	}
	for _, id := range f.Revocations {
		if !validHex(id) {
			return errors.New("invalid registry")
		}
	}
	for _, inv := range f.Invitations {
		if !validHex(inv.ID) || !seen[inv.Inviter] || (inv.Kind != "mac" && inv.Kind != "phone") || inv.AttemptsLeft < 0 || inv.AttemptsLeft > 5 || inv.Expires.IsZero() {
			return errors.New("invalid registry")
		}
	}
	if f.Enroll != nil && (len(f.Enroll.Hash) != 64 || strings.ToLower(f.Enroll.Hash) != f.Enroll.Hash || f.Enroll.Expires.IsZero()) {
		return errors.New("invalid registry")
	}
	return nil
}

func normalize(f *fileFormat) {
	if f.Devices == nil {
		f.Devices = []deviceRec{}
	}
	if f.Revocations == nil {
		f.Revocations = []string{}
	}
	if f.Invitations == nil {
		f.Invitations = []inviteRec{}
	}
}

func findDevice(f *fileFormat, id string) *deviceRec {
	for i := range f.Devices {
		if f.Devices[i].ID == id {
			return &f.Devices[i]
		}
	}
	return nil
}

func findInvite(f *fileFormat, id string) *inviteRec {
	for i := range f.Invitations {
		if f.Invitations[i].ID == id {
			return &f.Invitations[i]
		}
	}
	return nil
}

func removeInvite(list []inviteRec, id string) []inviteRec {
	out := make([]inviteRec, 0, len(list))
	for _, inv := range list {
		if inv.ID != id {
			out = append(out, inv)
		}
	}
	return out
}

func removeString(list []string, id string) []string {
	out := make([]string, 0, len(list))
	for _, item := range list {
		if item != id {
			out = append(out, item)
		}
	}
	return out
}

func contains(list []string, id string) bool {
	for _, item := range list {
		if item == id {
			return true
		}
	}
	return false
}

func readPrivate(name string) ([]byte, error) {
	info, err := os.Lstat(name)
	if err != nil {
		return nil, err
	}
	if !info.Mode().IsRegular() || info.Mode()&os.ModeSymlink != 0 || info.Size() > maxRegistryBytes || info.Mode().Perm()&0077 != 0 {
		return nil, errors.New("invalid registry file")
	}
	file, err := os.Open(name)
	if err != nil {
		return nil, err
	}
	defer file.Close()
	return io.ReadAll(io.LimitReader(file, maxRegistryBytes+1))
}
