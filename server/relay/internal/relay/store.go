package relay

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"syscall"
	"time"
)

const maxCachedBytes = 32 << 20

// Epochs let us discard old receipts without ever accepting their IDs again.
// A receipt is committed before dispatch; bodies are stored separately.
type diskStore struct {
	dir     string
	lock    *os.File
	epoch   string
	created time.Time
	err     error
	// Tests inject a failure at an actual persistence boundary.
	fault func(string) error
}

type diskMeta struct {
	Version  int       `json:"version"`
	Identity string    `json:"identity"`
	Epoch    string    `json:"epoch"`
	Created  time.Time `json:"created"`
}

type receipt struct {
	ID             string    `json:"id"`
	Digest         [32]byte  `json:"digest"`
	ResponseDigest [32]byte  `json:"responseDigest"`
	State          string    `json:"state"`
	Status         int       `json:"status"`
	Cached         bool      `json:"cached"`
	Created        time.Time `json:"created"`
	Completed      time.Time `json:"completed"`
	Started        time.Time `json:"started"`
}

func validHex(value string) bool {
	if len(value) != 32 || value != strings.ToLower(value) {
		return false
	}
	_, err := hex.DecodeString(value)
	return err == nil
}

func (s *diskStore) validID(id string) bool {
	return len(id) == 65 && id[32] == '.' && validHex(id[:32]) && validHex(id[33:])
}

func (s *diskStore) currentID(id string) bool { return s.validID(id) && id[:32] == s.epoch }

func randomEpoch() (string, error) {
	var raw [16]byte
	if _, err := rand.Read(raw[:]); err != nil {
		return "", err
	}
	return hex.EncodeToString(raw[:]), nil
}

func openStore(config Config) (*diskStore, error) {
	if config.StateDir == "" {
		return nil, errors.New("persistent state directory is required")
	}
	if err := os.MkdirAll(config.StateDir, 0700); err != nil {
		return nil, err
	}
	info, err := os.Lstat(config.StateDir)
	if err != nil || !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
		return nil, errors.New("state directory must be a real directory")
	}
	if err := os.Chmod(config.StateDir, 0700); err != nil {
		return nil, err
	}
	s := &diskStore{dir: config.StateDir}
	lockName := filepath.Join(s.dir, "lock")
	if info, err := os.Lstat(lockName); err == nil && !info.Mode().IsRegular() {
		return nil, errors.New("invalid state lock")
	}
	s.lock, err = os.OpenFile(lockName, os.O_CREATE|os.O_RDWR, 0600)
	if err != nil {
		return nil, err
	}
	if err = syscall.Flock(int(s.lock.Fd()), syscall.LOCK_EX|syscall.LOCK_NB); err != nil {
		s.lock.Close()
		return nil, errors.New("state directory is already in use")
	}
	fail := func(err error) (*diskStore, error) { s.close(); return nil, err }
	identity := sha256.Sum256([]byte(config.DeviceID + "\x00" + config.PhoneToken + "\x00" + config.DesktopToken))
	want := hex.EncodeToString(identity[:])
	var meta diskMeta
	body, err := readPrivate(filepath.Join(s.dir, "state.json"), 4096)
	if os.IsNotExist(err) {
		// Missing metadata beside receipts is corruption, not a fresh pairing.
		entries, listErr := os.ReadDir(s.dir)
		if listErr != nil {
			return fail(listErr)
		}
		for _, entry := range entries {
			if entry.Name() != "lock" {
				return fail(errors.New("state metadata is missing; preserve the directory for recovery"))
			}
		}
		s.epoch, err = randomEpoch()
		if err != nil {
			return fail(err)
		}
		s.created = time.Now().UTC()
		meta = diskMeta{1, want, s.epoch, s.created}
		if err = s.writeJSON("state.json", meta); err != nil {
			return fail(err)
		}
	} else {
		if err != nil || json.Unmarshal(body, &meta) != nil || meta.Version != 1 || meta.Identity != want || !validHex(meta.Epoch) || meta.Created.IsZero() {
			return fail(errors.New("invalid or incompatible relay state; credentials and state must be restored together"))
		}
		s.epoch, s.created = meta.Epoch, meta.Created
	}
	return s, nil
}

func (s *diskStore) close() {
	if s.lock != nil {
		_ = syscall.Flock(int(s.lock.Fd()), syscall.LOCK_UN)
		_ = s.lock.Close()
		s.lock = nil
	}
}

func readPrivate(name string, limit int64) ([]byte, error) {
	info, err := os.Lstat(name)
	if err != nil {
		return nil, err
	}
	if !info.Mode().IsRegular() || info.Size() > limit || info.Mode().Perm()&0077 != 0 {
		return nil, errors.New("invalid private state file")
	}
	file, err := os.Open(name)
	if err != nil {
		return nil, err
	}
	defer file.Close()
	return io.ReadAll(io.LimitReader(file, limit+1))
}

func (s *diskStore) checkpoint(stage string) error {
	if s.err != nil {
		return s.err
	}
	if s.fault != nil {
		return s.fault(stage)
	}
	return nil
}

func (s *diskStore) atomic(name string, body []byte) (err error) {
	if err = s.checkpoint("before-write"); err != nil {
		return err
	}
	file, err := os.CreateTemp(s.dir, ".pending-")
	if err != nil {
		return err
	}
	defer func() { _ = file.Close(); _ = os.Remove(file.Name()) }()
	if err = file.Chmod(0600); err != nil {
		return err
	}
	if _, err = file.Write(body); err != nil {
		return err
	}
	if err = s.checkpoint("before-fsync"); err != nil {
		return err
	}
	if err = file.Sync(); err != nil {
		return err
	}
	if err = file.Close(); err != nil {
		return err
	}
	if err = s.checkpoint("before-rename"); err != nil {
		return err
	}
	if err = os.Rename(file.Name(), filepath.Join(s.dir, name)); err != nil {
		return err
	}
	if err = s.checkpoint("after-rename"); err != nil {
		return err
	}
	return s.syncDir()
}

func (s *diskStore) syncDir() error {
	dir, err := os.Open(s.dir)
	if err != nil {
		return err
	}
	defer dir.Close()
	return dir.Sync()
}

func (s *diskStore) writeJSON(name string, value any) error {
	body, err := json.Marshal(value)
	if err != nil {
		return err
	}
	return s.atomic(name, body)
}

func (s *diskStore) name(id string) string {
	sum := sha256.Sum256([]byte(id))
	return hex.EncodeToString(sum[:])
}

func (s *diskStore) save(op *operation) error {
	r := receipt{op.id, op.digest, op.responseDigest, op.state, op.response.Status, op.response.Body != nil, op.createdAt, op.completedAt, op.startedAt}
	return s.writeJSON(s.name(op.id)+".json", r)
}

func (s *diskStore) rotate(config Config, now time.Time) error {
	if now.Sub(s.created) < resultKeep {
		return nil
	}
	epoch, err := randomEpoch()
	if err != nil {
		return err
	}
	identity := sha256.Sum256([]byte(config.DeviceID + "\x00" + config.PhoneToken + "\x00" + config.DesktopToken))
	if err = s.writeJSON("state.json", diskMeta{1, hex.EncodeToString(identity[:]), epoch, now.UTC()}); err != nil {
		return err
	}
	s.epoch, s.created = epoch, now.UTC()
	return nil
}

func (s *diskStore) load() (map[string]*operation, error) {
	entries, err := os.ReadDir(s.dir)
	if err != nil {
		return nil, err
	}
	operations := make(map[string]*operation)
	cachedBytes, cachedCount := 0, 0
	for _, entry := range entries {
		name := entry.Name()
		if name == "state.json" || name == "lock" || strings.HasPrefix(name, ".pending-") || strings.HasSuffix(name, ".body") {
			continue
		}
		if !strings.HasSuffix(name, ".json") || len(name) != 69 {
			return nil, errors.New("unexpected file in relay state")
		}
		body, err := readPrivate(filepath.Join(s.dir, name), 4096)
		var r receipt
		if err != nil || json.Unmarshal(body, &r) != nil || !s.validID(r.ID) || s.name(r.ID)+".json" != name || r.Created.IsZero() {
			return nil, errors.New("invalid operation receipt")
		}
		if _, exists := operations[r.ID]; exists || len(operations) >= maxRemembered {
			return nil, errors.New("operation receipt limit exceeded")
		}
		op := &operation{id: r.ID, digest: r.Digest, responseDigest: r.ResponseDigest, state: r.State, createdAt: r.Created, completedAt: r.Completed, startedAt: r.Started, response: responseEnvelope{Status: r.Status}, done: make(chan struct{})}
		switch op.state {
		case "queued", "running":
			op.state, op.completedAt = "unknown", time.Now().UTC()
			if err = s.save(op); err != nil {
				return nil, err
			}
		case "complete":
			if op.completedAt.IsZero() || r.Status < 200 || r.Status > 599 {
				return nil, errors.New("invalid completed receipt")
			}
			if r.Cached {
				data, err := readPrivate(filepath.Join(s.dir, s.name(op.id)+".body"), maxBodyBytes)
				if err != nil || sha256.Sum256(data) != r.ResponseDigest || !json.Valid(data) {
					return nil, errors.New("missing or corrupt cached result")
				}
				op.response.Body = data
				cachedBytes += len(data)
				cachedCount++
				if cachedBytes > maxCachedBytes || cachedCount > maxCachedResponses {
					return nil, errors.New("cached response budget exceeded")
				}
			}
		case "expired", "unknown":
			if op.completedAt.IsZero() {
				return nil, errors.New("invalid terminal receipt")
			}
		default:
			return nil, fmt.Errorf("unsupported receipt state")
		}
		close(op.done)
		op.doneClosed = true
		operations[op.id] = op
	}
	return operations, nil
}

func (s *diskStore) remove(name string) error {
	if err := os.Remove(filepath.Join(s.dir, name)); err != nil && !os.IsNotExist(err) {
		return err
	}
	return s.syncDir()
}
