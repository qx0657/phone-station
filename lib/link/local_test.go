package link

import (
	"bytes"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"errors"
	"io"
	"net"
	"testing"
	"time"
)

func TestLocalHandshakeReady(t *testing.T) {
	phone, mac := newPair(t)
	listener := listenPair(t, phone, mac)
	defer listener.Close()
	accepted := acceptAsync(listener)
	session, err := DialLocal(listener.Addr(), mac.identity, phone.peer)
	if err != nil {
		t.Fatal(err)
	}
	defer session.Close()
	remote := awaitSession(t, accepted)
	defer remote.Close()
	if !session.Ready() || !remote.Ready() || session.Peer() != phone.identity.ID || remote.Peer() != mac.identity.ID {
		t.Fatal("ready")
	}
	done := make(chan struct{})
	go func() {
		_ = remote.Serve()
		close(done)
	}()
	body := []byte(`{"type":"call","op":"ready"}`)
	if err := session.Send(body); err != nil {
		t.Fatal(err)
	}
	deadline := time.Now().Add(2 * time.Second)
	var got []byte
	for time.Now().Before(deadline) {
		chunks := remote.Engine().Inbox()
		if len(chunks) > 0 {
			got = chunks[0].Payload
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	if !bytes.Equal(got, body) {
		t.Fatal("message")
	}
	if err := session.Close(); err != nil && !errors.Is(err, net.ErrClosed) {
		t.Fatal(err)
	}
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("serve")
	}
	if !remote.Ended() || !session.Ended() {
		t.Fatal("ended")
	}
	if err := session.Send(body); err == nil {
		t.Fatal("send after close")
	}
}

func TestLocalBadSignature(t *testing.T) {
	phone, mac := newPair(t)
	other, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	mac.identity.Sign = signer(other)
	listener := listenPair(t, phone, mac)
	defer listener.Close()
	accepted := acceptAsync(listener)
	_, err = DialLocal(listener.Addr(), mac.identity, phone.peer)
	if err == nil || err.Error() != "bad_signature" {
		t.Fatal(err)
	}
	if _, err := awaitSessionErr(accepted); err == nil || err.Error() != "bad_signature" {
		t.Fatal(err)
	}
}

func TestLocalUnknownMac(t *testing.T) {
	phone, mac := newPair(t)
	stranger := mustIdentity(t)
	listener := listenPair(t, phone, mac)
	defer listener.Close()
	accepted := acceptAsync(listener)
	_, err := DialLocal(listener.Addr(), stranger, phone.peer)
	if err == nil || err.Error() != "unknown_device" {
		t.Fatal(err)
	}
	if _, err := awaitSessionErr(accepted); err == nil || err.Error() != "unknown_device" {
		t.Fatal(err)
	}
}

func TestLocalUpgradeRejected(t *testing.T) {
	phone, mac := newPair(t)
	listener := listenPair(t, phone, mac)
	defer listener.Close()
	if err := AdmitLocal("GET", "/v2/link", "127.0.0.1:1", false); err != nil {
		t.Fatal(err)
	}
	if err := AdmitLocal("GET", "/v2/link", "192.0.2.8", false); err == nil || err.Error() != "host" {
		t.Fatal(err)
	}
	if err := AdmitLocal("GET", "/v2/link", "127.0.0.1:9", true); err == nil || err.Error() != "origin" {
		t.Fatal(err)
	}
	accepted := acceptAsync(listener)
	status := rawUpgrade(t, listener.Addr(), "192.0.2.8", false)
	if status != "403" {
		t.Fatalf("host %s", status)
	}
	if _, err := awaitSessionErr(accepted); err == nil || err.Error() != "host" {
		t.Fatal(err)
	}
	accepted = acceptAsync(listener)
	status = rawUpgrade(t, listener.Addr(), "127.0.0.1", true)
	if status != "403" {
		t.Fatalf("origin %s", status)
	}
	if _, err := awaitSessionErr(accepted); err == nil || err.Error() != "origin" {
		t.Fatal(err)
	}
}

func TestLocalDisconnectEndsSession(t *testing.T) {
	phone, mac := newPair(t)
	listener := listenPair(t, phone, mac)
	defer listener.Close()
	accepted := acceptAsync(listener)
	session, err := DialLocal(listener.Addr(), mac.identity, phone.peer)
	if err != nil {
		t.Fatal(err)
	}
	remote := awaitSession(t, accepted)
	done := make(chan struct{})
	go func() {
		_ = remote.Serve()
		close(done)
	}()
	session.Close()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("disconnect")
	}
	if !remote.Ended() || remote.Ready() != true {
		t.Fatal("session")
	}
}

type party struct {
	identity Identity
	peer     PeerKey
}

func newPair(t *testing.T) (phone, mac party) {
	t.Helper()
	phone.identity = mustIdentity(t)
	mac.identity = mustIdentity(t)
	phone.peer = PeerKey{ID: phone.identity.ID, SPKI: phone.identity.SPKI}
	mac.peer = PeerKey{ID: mac.identity.ID, SPKI: mac.identity.SPKI}
	return phone, mac
}

func listenPair(t *testing.T, phone, mac party) *LocalListener {
	t.Helper()
	listener, err := ListenLocal(phone.identity, []PeerKey{mac.peer})
	if err != nil {
		t.Fatal(err)
	}
	return listener
}

func mustIdentity(t *testing.T) Identity {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	spki, err := x509.MarshalPKIXPublicKey(&key.PublicKey)
	if err != nil {
		t.Fatal(err)
	}
	id := DeviceID(spki)
	var device [16]byte
	copy(device[:], id)
	return Identity{ID: device, SPKI: spki, Sign: signer(key)}
}

func signer(key *ecdsa.PrivateKey) func([]byte) ([]byte, error) {
	return func(message []byte) ([]byte, error) {
		sum := sha256.Sum256(message)
		return ecdsa.SignASN1(rand.Reader, key, sum[:])
	}
}

func acceptAsync(listener *LocalListener) <-chan acceptResult {
	out := make(chan acceptResult, 1)
	go func() {
		session, err := listener.Accept()
		out <- acceptResult{session, err}
	}()
	return out
}

type acceptResult struct {
	session *LocalSession
	err     error
}

func awaitSession(t *testing.T, ch <-chan acceptResult) *LocalSession {
	t.Helper()
	select {
	case result := <-ch:
		if result.err != nil {
			t.Fatal(result.err)
		}
		return result.session
	case <-time.After(5 * time.Second):
		t.Fatal("accept")
		return nil
	}
}

func awaitSessionErr(ch <-chan acceptResult) (*LocalSession, error) {
	select {
	case result := <-ch:
		return result.session, result.err
	case <-time.After(5 * time.Second):
		return nil, errors.New("accept")
	}
}

func rawUpgrade(t *testing.T, addr, host string, origin bool) string {
	t.Helper()
	conn, err := net.Dial("tcp", addr)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(2 * time.Second))
	body := "GET /v2/link HTTP/1.1\r\nHost: " + host + "\r\n"
	if origin {
		body += "Origin: https://example.test\r\n"
	}
	body += "Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n"
	if _, err := io.WriteString(conn, body); err != nil {
		t.Fatal(err)
	}
	buf := make([]byte, 64)
	n, err := conn.Read(buf)
	if err != nil && n == 0 {
		t.Fatal(err)
	}
	line := string(buf[:n])
	if len(line) < 12 {
		t.Fatal(line)
	}
	return statusCode(line[:min(len(line), 32)])
}

func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}
