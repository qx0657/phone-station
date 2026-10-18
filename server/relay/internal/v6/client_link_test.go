package v6

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"net"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"phone-station/link"
)

func TestRelay6DialAuth(t *testing.T) {
	server, _, base, _, pin := listenTLS(t)
	mac := newIdentity(t)
	registerDevice(t, server, mac.spki)
	addr := strings.TrimPrefix(base, "https://")
	badPin := make([]byte, len(pin))
	badPin[0] = pin[0] ^ 0xff
	if _, err := link.DialRelay(addr, badPin, linkIdentity(mac), "main", "test"); err == nil || err.Error() != "pin" {
		t.Fatal("pin")
	}
	opened := make([]*link.RelaySession, 0, 2)
	defer func() {
		for _, session := range opened {
			session.Close()
		}
	}()
	for _, purpose := range []string{"main", "media"} {
		session, err := link.DialRelay(addr, pin, linkIdentity(mac), purpose, "test")
		if err != nil {
			t.Fatal(err)
		}
		opened = append(opened, session)
		if !session.Ready() {
			t.Fatal("ready")
		}
	}
	for _, session := range opened {
		session.Close()
	}
	opened = nil
	liar := linkIdentity(mac)
	other := newIdentity(t)
	liar.Sign = func(message []byte) ([]byte, error) {
		sum := sha256.Sum256(message)
		return ecdsa.SignASN1(rand.Reader, other.key, sum[:])
	}
	if _, err := link.DialRelay(addr, pin, liar, "main", "test"); err == nil || err.Error() != "bad_signature" {
		t.Fatal(err)
	}
	if _, err := link.DialRelay(addr, pin, linkIdentity(newIdentity(t)), "main", "test"); err == nil || err.Error() != "unknown_device" {
		t.Fatal(err)
	}
	revoked := newIdentity(t)
	registerDevice(t, server, revoked.spki)
	if err := server.reg.revoke(hex.EncodeToString(revoked.id[:])); err != nil {
		t.Fatal(err)
	}
	revokedLiar := linkIdentity(revoked)
	revokedLiar.Sign = liar.Sign
	if _, err := link.DialRelay(addr, pin, revokedLiar, "main", "test"); err == nil || err.Error() != "bad_signature" {
		t.Fatal(err)
	}
	if _, err := link.DialRelay(addr, pin, linkIdentity(revoked), "main", "test"); err == nil || err.Error() != "revoked" {
		t.Fatal(err)
	}
}

func TestRelay6DialEnroll(t *testing.T) {
	server, _, base, _, pin := listenTLS(t)
	code, err := server.IssueEnroll()
	if err != nil {
		t.Fatal(err)
	}
	raw, err := hex.DecodeString(code)
	if err != nil || len(raw) != 16 {
		t.Fatal("code")
	}
	mac := newIdentity(t)
	addr := strings.TrimPrefix(base, "https://")
	if _, err := link.DialRelayEnroll(addr, pin, linkIdentity(mac), raw[:15], "办公室", "main"); err == nil || err.Error() != "enroll" {
		t.Fatal(err)
	}
	session, err := link.DialRelayEnroll(addr, pin, linkIdentity(mac), raw, "办公室", "main")
	if err != nil || !session.Ready() {
		t.Fatal(err)
	}
	session.Close()
	if _, err := link.DialRelayEnroll(addr, pin, linkIdentity(newIdentity(t)), raw, "办公室", "main"); err == nil || err.Error() != "rejected" {
		t.Fatal(err)
	}
	again, err := link.DialRelay(addr, pin, linkIdentity(mac), "main", "test")
	if err != nil || !again.Ready() {
		t.Fatal(err)
	}
	again.Close()
}

func TestRelay6DialJoin(t *testing.T) {
	server, _, base, _, pin := listenTLS(t)
	code, err := server.IssueEnroll()
	if err != nil {
		t.Fatal(err)
	}
	raw, err := hex.DecodeString(code)
	if err != nil || len(raw) != 16 {
		t.Fatal("code")
	}
	mac := newIdentity(t)
	phone := newIdentity(t)
	addr := strings.TrimPrefix(base, "https://")
	session, err := link.DialRelayEnroll(addr, pin, linkIdentity(mac), raw, "办公室", "main")
	if err != nil || !session.Ready() {
		t.Fatal(err)
	}
	defer session.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	joined := make(chan error, 1)
	err = session.OfferPhone(ctx, linkIdentity(mac), link.PeerKey{ID: phone.id, SPKI: phone.spki}, "https://"+addr, hex.EncodeToString(pin), func(text string) error {
		go func() {
			item, err := link.ParseInvite(text)
			if err != nil {
				joined <- err
				return
			}
			invitation, err := hex.DecodeString(item.ID)
			key, err2 := hex.DecodeString(item.Key)
			if err != nil || err2 != nil {
				joined <- errors.New("invite")
				return
			}
			opened, err := link.DialRelayJoin(addr, pin, linkIdentity(phone), invitation, key, "手机", "main")
			if err != nil {
				joined <- err
				return
			}
			opened.Close()
			joined <- nil
		}()
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	select {
	case err := <-joined:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(10 * time.Second):
		t.Fatal("join")
	}
	again, err := link.DialRelay(addr, pin, linkIdentity(phone), "main", "test")
	if err != nil || !again.Ready() {
		t.Fatal(err)
	}
	again.Close()
	other := newIdentity(t)
	rejected := make(chan error, 1)
	err = session.OfferPhone(ctx, linkIdentity(mac), link.PeerKey{ID: phone.id, SPKI: phone.spki}, "https://"+addr, hex.EncodeToString(pin), func(text string) error {
		go func() {
			item, err := link.ParseInvite(text)
			if err != nil {
				rejected <- err
				return
			}
			invitation, _ := hex.DecodeString(item.ID)
			key, _ := hex.DecodeString(item.Key)
			_, err = link.DialRelayJoin(addr, pin, linkIdentity(other), invitation, key, "手机", "main")
			rejected <- err
		}()
		return nil
	})
	if err == nil || err.Error() != "rejected" {
		t.Fatal(err)
	}
	select {
	case err := <-rejected:
		if err == nil || err.Error() != "rejected" {
			t.Fatal(err)
		}
	case <-time.After(10 * time.Second):
		t.Fatal("reject")
	}
}

func TestRelay6LoopbackRefusesOtherAddresses(t *testing.T) {
	for _, addr := range []string{"0.0.0.0:0", "[::]:0", "203.0.113.5:24443", "127.0.0.1", ""} {
		if _, err := ServeLoopback(t.TempDir(), addr); err == nil || err.Error() != "listen" {
			t.Fatal(addr)
		}
	}
	loop, err := ServeLoopback(t.TempDir(), "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer loop.Close()
	host, _, err := net.SplitHostPort(loop.Addr)
	if err != nil || host != "127.0.0.1" {
		t.Fatal(loop.Addr)
	}
	mac := newIdentity(t)
	registerDevice(t, loop.Server, mac.spki)
	session, err := link.DialRelay(loop.Addr, loop.Pin, linkIdentity(mac), "main", "test")
	if err != nil {
		t.Fatal(err)
	}
	session.Close()
	pinned, err := link.DialRelayPinned(loop.Addr, loop.Pin, linkIdentity(mac), "main", "test")
	if err != nil {
		t.Fatal(err)
	}
	defer pinned.Close()
	if !pinned.Ready() {
		t.Fatal("ready")
	}
}

func TestRelay6Call(t *testing.T) {
	loop, err := ServeLoopback(t.TempDir(), "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer loop.Close()
	mac := newIdentity(t)
	phone := newIdentity(t)
	registerDevice(t, loop.Server, mac.spki)
	registerDevice(t, loop.Server, phone.spki)
	macRelay, err := link.DialRelay(loop.Addr, loop.Pin, linkIdentity(mac), "main", "test")
	if err != nil {
		t.Fatal(err)
	}
	phoneRelay, err := link.DialRelay(loop.Addr, loop.Pin, linkIdentity(phone), "main", "test")
	if err != nil {
		t.Fatal(err)
	}
	macSession := macRelay.Bind(phone.id, true)
	phoneSession := phoneRelay.Bind(mac.id, false)
	if macSession == nil || phoneSession == nil {
		t.Fatal("bind")
	}
	defer macSession.Close()
	defer phoneSession.Close()
	done := make(chan error, 1)
	go func() {
		done <- phoneSession.Respond(func(op, method string, params []byte) (string, []byte) {
			if op != "0123456789abcdef0123456789abcdef" || method != "terminal.open" || string(params) != "{}" {
				return "failed", nil
			}
			return "complete", []byte("running")
		})
	}()
	state, body, err := macSession.Call(context.Background(), "0123456789abcdef0123456789abcdef", "terminal.open", []byte("{}"))
	if err != nil || state != "complete" || string(body) != "running" {
		t.Fatalf("call %s %q %v", state, body, err)
	}
	phoneSession.Close()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("respond")
	}
}

func TestRelay6SealHidesBody(t *testing.T) {
	for _, purpose := range []string{"main", "media"} {
		t.Run(purpose, func(t *testing.T) {
			sealHidesBody(t, purpose)
		})
	}
}

func sealHidesBody(t *testing.T, purpose string) {
	loop, err := ServeLoopback(t.TempDir(), "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer loop.Close()
	mac := newIdentity(t)
	phone := newIdentity(t)
	registerDevice(t, loop.Server, mac.spki)
	registerDevice(t, loop.Server, phone.spki)
	secret := []byte("seal-body-9f3a")
	var mu sync.Mutex
	var seen [][]byte
	loop.Server.watch(func(payload []byte) {
		mu.Lock()
		seen = append(seen, append([]byte(nil), payload...))
		mu.Unlock()
	})
	macRelay, err := link.DialRelay(loop.Addr, loop.Pin, linkIdentity(mac), purpose, "test")
	if err != nil {
		t.Fatal(err)
	}
	phoneRelay, err := link.DialRelay(loop.Addr, loop.Pin, linkIdentity(phone), purpose, "test")
	if err != nil {
		t.Fatal(err)
	}
	macSession := macRelay.Bind(phone.id, true)
	phoneSession := phoneRelay.Bind(mac.id, false)
	if macSession == nil || phoneSession == nil {
		t.Fatal("bind")
	}
	defer macSession.Close()
	defer phoneSession.Close()
	if err := macSession.RequireSeal(linkIdentity(mac), phone.spki); err != nil {
		t.Fatal(err)
	}
	if err := phoneSession.RequireSeal(linkIdentity(phone), mac.spki); err != nil {
		t.Fatal(err)
	}
	done := make(chan error, 1)
	go func() {
		done <- phoneSession.Respond(func(op, method string, params []byte) (string, []byte) {
			if op != "0123456789abcdef0123456789abcdef" || method != "clipboard.set" || !bytes.Equal(params, secret) {
				return "failed", nil
			}
			return "complete", secret
		})
	}()
	state, body, err := macSession.Call(context.Background(), "0123456789abcdef0123456789abcdef", "clipboard.set", secret)
	if err != nil || state != "complete" || !bytes.Equal(body, secret) {
		t.Fatalf("call %s %q %v", state, body, err)
	}
	phoneSession.Close()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("respond")
	}
	mu.Lock()
	defer mu.Unlock()
	if len(seen) == 0 {
		t.Fatal("forward")
	}
	for _, payload := range seen {
		if bytes.Contains(payload, secret) {
			t.Fatal("visible")
		}
	}
}

func TestRelay6PlaintextDoesNotRun(t *testing.T) {
	for _, purpose := range []string{"main", "media"} {
		t.Run(purpose, func(t *testing.T) {
			plaintextDoesNotRun(t, purpose)
		})
	}
}

func plaintextDoesNotRun(t *testing.T, purpose string) {
	loop, err := ServeLoopback(t.TempDir(), "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer loop.Close()
	mac := newIdentity(t)
	phone := newIdentity(t)
	registerDevice(t, loop.Server, mac.spki)
	registerDevice(t, loop.Server, phone.spki)
	macRelay, err := link.DialRelay(loop.Addr, loop.Pin, linkIdentity(mac), purpose, "test")
	if err != nil {
		t.Fatal(err)
	}
	phoneRelay, err := link.DialRelay(loop.Addr, loop.Pin, linkIdentity(phone), purpose, "test")
	if err != nil {
		t.Fatal(err)
	}
	macSession := macRelay.Bind(phone.id, true)
	phoneSession := phoneRelay.Bind(mac.id, false)
	if macSession == nil || phoneSession == nil {
		t.Fatal("bind")
	}
	defer macSession.Close()
	defer phoneSession.Close()
	if err := phoneSession.RequireSeal(linkIdentity(phone), mac.spki); err != nil {
		t.Fatal(err)
	}
	called := false
	done := make(chan error, 1)
	go func() {
		err := phoneSession.Respond(func(string, string, []byte) (string, []byte) {
			called = true
			return "complete", nil
		})
		phoneSession.Close()
		done <- err
	}()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if _, _, err := macSession.Call(ctx, "0123456789abcdef0123456789abcdef", "clipboard.set", []byte("{}")); err == nil {
		t.Fatal("plaintext")
	}
	phoneSession.Close()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("respond")
	}
	if called {
		t.Fatal("executed")
	}
}

func TestRelay6ForJava(t *testing.T) {
	dir := os.Getenv("PHONE_LINK_REMOTE_DIR")
	if dir == "" {
		t.Skip()
	}
	info, err := os.Stat(filepath.Join(dir, "spki"))
	if err != nil || info.Size() == 0 || info.Size() > 400 {
		t.Fatal("spki")
	}
	raw, err := os.ReadFile(filepath.Join(dir, "spki"))
	if err != nil {
		t.Fatal("spki")
	}
	spki, err := base64.StdEncoding.DecodeString(strings.TrimSpace(string(raw)))
	if err != nil || !validKey(spki) {
		t.Fatal("spki")
	}
	server, _, base, _, pin := listenTLS(t)
	registerDevice(t, server, spki)
	if raw, err := os.ReadFile(filepath.Join(dir, "peer.spki")); err == nil {
		peerKey, err := base64.StdEncoding.DecodeString(strings.TrimSpace(string(raw)))
		if err != nil || !validKey(peerKey) {
			t.Fatal("peer")
		}
		registerDevice(t, server, peerKey)
	}
	if raw, err := os.ReadFile(filepath.Join(dir, "revoked.spki")); err == nil {
		revokedKey, err := base64.StdEncoding.DecodeString(strings.TrimSpace(string(raw)))
		if err != nil || !validKey(revokedKey) {
			t.Fatal("revoked")
		}
		registerDevice(t, server, revokedKey)
		id := deviceID(revokedKey)
		if err := server.reg.revoke(hex.EncodeToString(id[:])); err != nil {
			t.Fatal(err)
		}
	}
	body := strings.TrimPrefix(base, "https://") + "\n" + hex.EncodeToString(pin) + "\n"
	tmp := filepath.Join(dir, "listen.tmp")
	if err := os.WriteFile(tmp, []byte(body), 0600); err != nil {
		t.Fatal("listen")
	}
	if err := os.Rename(tmp, filepath.Join(dir, "listen")); err != nil {
		t.Fatal("listen")
	}
	if raw, err := os.ReadFile(filepath.Join(dir, "join.spki")); err == nil {
		phoneSPKI, err := base64.StdEncoding.DecodeString(strings.TrimSpace(string(raw)))
		if err != nil || !validKey(phoneSPKI) {
			t.Fatal("join")
		}
		go approveJoin(t, server, strings.TrimPrefix(base, "https://"), pin, phoneSPKI, dir)
	}
	deadline := time.Now().Add(60 * time.Second)
	done := filepath.Join(dir, "done")
	for time.Now().Before(deadline) {
		if _, err := os.Stat(done); err == nil {
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatal("client")
}

func TestRelay6ForCross(t *testing.T) {
	dir := os.Getenv("PHONE_LINK_CROSS_DIR")
	if dir == "" {
		t.Skip()
	}
	mac := readCrossKey(t, filepath.Join(dir, "mac.spki"))
	phone := readCrossKey(t, filepath.Join(dir, "phone.spki"))
	server, _, base, _, pin := listenTLS(t)
	registerDevice(t, server, mac)
	registerDevice(t, server, phone)
	body := strings.TrimPrefix(base, "https://") + "\n" + hex.EncodeToString(pin) + "\n"
	tmp := filepath.Join(dir, "listen.tmp")
	if err := os.WriteFile(tmp, []byte(body), 0600); err != nil {
		t.Fatal("listen")
	}
	if err := os.Rename(tmp, filepath.Join(dir, "listen")); err != nil {
		t.Fatal("listen")
	}
	deadline := time.Now().Add(60 * time.Second)
	done := filepath.Join(dir, "done")
	for time.Now().Before(deadline) {
		if _, err := os.Stat(done); err == nil {
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatal("client")
}

func approveJoin(t *testing.T, server *Server, addr string, pin, phoneSPKI []byte, dir string) {
	code, err := server.IssueEnroll()
	if err != nil {
		writeJoinErr(dir, "enroll")
		return
	}
	raw, err := hex.DecodeString(code)
	if err != nil || len(raw) != 16 {
		writeJoinErr(dir, "enroll")
		return
	}
	mac := newIdentity(t)
	session, err := link.DialRelayEnroll(addr, pin, linkIdentity(mac), raw, "办公室", "main")
	if err != nil {
		writeJoinErr(dir, "enroll")
		return
	}
	defer session.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()
	err = session.OfferPhone(ctx, linkIdentity(mac), link.PeerKey{ID: deviceID(phoneSPKI), SPKI: phoneSPKI}, "https://"+addr, hex.EncodeToString(pin), func(text string) error {
		return os.WriteFile(filepath.Join(dir, "invite"), []byte(text), 0600)
	})
	if err != nil {
		writeJoinErr(dir, err.Error())
	}
}

func writeJoinErr(dir, code string) {
	if strings.ContainsAny(code, " \n\r") || len(code) > 32 {
		code = "join"
	}
	_ = os.WriteFile(filepath.Join(dir, "join.err"), []byte(code+"\n"), 0600)
}

func readCrossKey(t *testing.T, path string) []byte {
	t.Helper()
	info, err := os.Stat(path)
	if err != nil || info.Size() == 0 || info.Size() > 400 {
		t.Fatal("spki")
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatal("spki")
	}
	spki, err := base64.StdEncoding.DecodeString(strings.TrimSpace(string(raw)))
	if err != nil || !validKey(spki) {
		t.Fatal("spki")
	}
	return spki
}

func registerDevice(t *testing.T, server *Server, spki []byte) {
	t.Helper()
	if !validKey(spki) {
		t.Fatal("key")
	}
	id := deviceID(spki)
	err := server.reg.mutate(func(f *fileFormat) error {
		hexID := hex.EncodeToString(id[:])
		if findDevice(f, hexID) != nil || len(f.Devices) >= 32 {
			return errRejected
		}
		f.Devices = append(f.Devices, deviceRec{ID: hexID, PublicKey: encodeKey(spki), Name: "test", Kind: "phone"})
		f.RegistryVersion++
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
}

func linkIdentity(id identity) link.Identity {
	return link.Identity{
		ID:   id.id,
		SPKI: append([]byte(nil), id.spki...),
		Sign: func(message []byte) ([]byte, error) {
			sum := sha256.Sum256(message)
			return ecdsa.SignASN1(rand.Reader, id.key, sum[:])
		},
	}
}
