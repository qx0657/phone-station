package v6

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"errors"
	"io"
	"log"
	"math/big"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/coder/websocket"
	"phone-station/link"
)

type lockedBuf struct {
	mu sync.Mutex
	bytes.Buffer
}

func (b *lockedBuf) Write(p []byte) (int, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.Buffer.Write(p)
}

func (b *lockedBuf) String() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.Buffer.String()
}

type identity struct {
	key  *ecdsa.PrivateKey
	spki []byte
	id   [16]byte
}

func newIdentity(t *testing.T) identity {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	spki, err := x509.MarshalPKIXPublicKey(&key.PublicKey)
	if err != nil {
		t.Fatal(err)
	}
	return identity{key: key, spki: spki, id: deviceID(spki)}
}

func sign(t *testing.T, key *ecdsa.PrivateKey, message []byte) string {
	t.Helper()
	sum := sha256.Sum256(message)
	sig, err := ecdsa.SignASN1(rand.Reader, key, sum[:])
	if err != nil {
		t.Fatal(err)
	}
	return hex.EncodeToString(sig)
}

func startTLS(t *testing.T) (*Server, *http.Client, string, *lockedBuf) {
	t.Helper()
	server, client, base, buf, _ := listenTLS(t)
	return server, client, base, buf
}

func listenTLS(t *testing.T) (*Server, *http.Client, string, *lockedBuf, []byte) {
	t.Helper()
	dir := t.TempDir()
	server, err := Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	buf := &lockedBuf{}
	server.mu.Lock()
	server.log = buf
	server.mu.Unlock()
	cert, pool := testCert(t)
	listener, err := tls.Listen("tcp", "127.0.0.1:0", &tls.Config{Certificates: []tls.Certificate{cert}, MinVersion: tls.VersionTLS12})
	if err != nil {
		t.Fatal(err)
	}
	httpServer := &http.Server{Handler: server, ReadHeaderTimeout: 5 * time.Second, ErrorLog: log.New(io.Discard, "", 0)}
	go httpServer.Serve(listener)
	client := &http.Client{Transport: &http.Transport{TLSClientConfig: &tls.Config{RootCAs: pool, ServerName: "127.0.0.1", MinVersion: tls.VersionTLS12}}}
	t.Cleanup(func() {
		httpServer.Close()
		server.Close()
	})
	leaf, err := x509.ParseCertificate(cert.Certificate[0])
	if err != nil {
		t.Fatal(err)
	}
	sum := sha256.Sum256(leaf.RawSubjectPublicKeyInfo)
	return server, client, "https://" + listener.Addr().String(), buf, sum[:]
}

func testCert(t *testing.T) (tls.Certificate, *x509.CertPool) {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	template := &x509.Certificate{
		SerialNumber: big.NewInt(1),
		Subject:      pkix.Name{CommonName: "127.0.0.1"},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(time.Hour),
		IPAddresses:  []net.IP{net.ParseIP("127.0.0.1")},
		KeyUsage:     x509.KeyUsageDigitalSignature,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
	}
	der, err := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	cert, err := tls.X509KeyPair(
		pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}),
		pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: mustPKCS8(t, key)}),
	)
	if err != nil {
		t.Fatal(err)
	}
	pool := x509.NewCertPool()
	if !pool.AppendCertsFromPEM(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})) {
		t.Fatal("pool")
	}
	return cert, pool
}

func mustPKCS8(t *testing.T, key *ecdsa.PrivateKey) []byte {
	t.Helper()
	body, err := x509.MarshalPKCS8PrivateKey(key)
	if err != nil {
		t.Fatal(err)
	}
	return body
}

func wsURL(base string) string {
	return "wss://" + strings.TrimPrefix(base, "https://") + "/v2/link"
}

func dialErr(client *http.Client, raw string) (*websocket.Conn, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	conn, _, err := websocket.Dial(ctx, raw, &websocket.DialOptions{HTTPClient: client})
	return conn, err
}

func dial(t *testing.T, client *http.Client, raw string) *websocket.Conn {
	t.Helper()
	conn, err := dialErr(client, raw)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { conn.CloseNow() })
	return conn
}

func send(t *testing.T, conn *websocket.Conn, msg message) {
	t.Helper()
	if err := sendErr(conn, msg); err != nil {
		t.Fatal(err)
	}
}

func sendErr(conn *websocket.Conn, msg message) error {
	body, err := json.Marshal(msg)
	if err != nil {
		return err
	}
	raw, err := link.Encode(link.Frame{Kind: link.KindLink, Payload: body}, link.MainPayload)
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	return conn.Write(ctx, websocket.MessageBinary, raw)
}

func readJSON(t *testing.T, conn *websocket.Conn) message {
	t.Helper()
	msg, err := readJSONErr(conn)
	if err != nil {
		t.Fatal(err)
	}
	return msg
}

func readJSONErr(conn *websocket.Conn) (message, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	frame, err := readFrame(ctx, conn, link.MediaPayload)
	if err != nil {
		return message{}, err
	}
	if frame.Kind != link.KindLink {
		return message{}, errors.New("kind")
	}
	return parseControl(frame.Payload)
}

func readData(t *testing.T, conn *websocket.Conn) link.Frame {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	frame, err := readFrame(ctx, conn, link.MainPayload)
	if err != nil {
		t.Fatal(err)
	}
	return frame
}

func sendData(t *testing.T, conn *websocket.Conn, peer [16]byte, payload []byte) {
	t.Helper()
	raw, err := link.Encode(link.Frame{Kind: link.KindMessage, Peer: peer, Payload: payload}, link.MainPayload)
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := conn.Write(ctx, websocket.MessageBinary, raw); err != nil {
		t.Fatal(err)
	}
}

func helloParts(t *testing.T, conn *websocket.Conn) (message, []byte, []byte) {
	t.Helper()
	hello := readJSON(t, conn)
	if hello.Type != "hello" || hello.Protocol != 2 {
		t.Fatalf("hello %#v", hello)
	}
	station, err := hex.DecodeString(hello.StationID)
	nonce, err2 := hex.DecodeString(hello.Nonce)
	if err != nil || err2 != nil || len(station) != 16 || len(nonce) != 32 {
		t.Fatal("hello bytes")
	}
	return hello, station, nonce
}

func enrollMac(t *testing.T, client *http.Client, base, code string, mac identity) *websocket.Conn {
	t.Helper()
	conn := dial(t, client, wsURL(base))
	_, station, nonce := helloParts(t, conn)
	rawCode, err := hex.DecodeString(code)
	if err != nil {
		t.Fatal(err)
	}
	transcript, err := link.EnrollMessage(station, nonce, mac.id[:], rawCode, "main")
	if err != nil {
		t.Fatal(err)
	}
	send(t, conn, message{
		Type: "enroll", Code: code, PublicKey: base64.StdEncoding.EncodeToString(mac.spki),
		Name: "办公室", Kind: "mac", Purpose: "main", Sig: sign(t, mac.key, transcript),
	})
	ready := readJSON(t, conn)
	if ready.Type != "ready" || ready.RegistryVersion == 0 {
		t.Fatalf("ready %#v", ready)
	}
	registry := readJSON(t, conn)
	if registry.Type != "registry" || len(registry.Devices) != 1 {
		t.Fatalf("registry %#v", registry)
	}
	return conn
}

func TestRelay6TLS(t *testing.T) {
	server, client, base, buf := startTLS(t)
	code, err := server.IssueEnroll()
	if err != nil {
		t.Fatal(err)
	}
	mac := newIdentity(t)
	phone := newIdentity(t)
	macConn := enrollMac(t, client, base, code, mac)
	replay := dial(t, client, wsURL(base))
	_, station, nonce := helloParts(t, replay)
	rawCode, _ := hex.DecodeString(code)
	transcript, err := link.EnrollMessage(station, nonce, mac.id[:], rawCode, "main")
	if err != nil {
		t.Fatal(err)
	}
	send(t, replay, message{Type: "enroll", Code: code, PublicKey: base64.StdEncoding.EncodeToString(mac.spki), Name: "办公室", Kind: "mac", Purpose: "main", Sig: sign(t, mac.key, transcript)})
	if got := readJSON(t, replay); got.Type != "error" || got.Code != "rejected" {
		t.Fatalf("replay %#v", got)
	}

	send(t, macConn, message{Type: "invite", Kind: "phone"})
	invite := readJSON(t, macConn)
	if invite.Type != "invite" || len(invite.InvitationID) != 32 {
		t.Fatalf("invite %#v", invite)
	}
	inviteKey := bytes.Repeat([]byte{0xAB}, 16)
	proof, err := link.InvitationProof(inviteKey, phone.spki, "手机", "phone", "main")
	if err != nil {
		t.Fatal(err)
	}
	joined := make(chan *websocket.Conn, 1)
	joinErr := make(chan error, 1)
	go func() {
		conn, err := dialErr(client, wsURL(base))
		if err != nil {
			joinErr <- err
			return
		}
		hello, err := readJSONErr(conn)
		if err != nil {
			joinErr <- err
			return
		}
		peerStation, _ := hex.DecodeString(hello.StationID)
		peerNonce, _ := hex.DecodeString(hello.Nonce)
		invitationID, _ := hex.DecodeString(invite.InvitationID)
		body, err := link.JoinMessage(peerStation, peerNonce, phone.id[:], invitationID, "main")
		if err != nil {
			joinErr <- err
			return
		}
		sum := sha256.Sum256(body)
		sig, err := ecdsa.SignASN1(rand.Reader, phone.key, sum[:])
		if err != nil {
			joinErr <- err
			return
		}
		if err := sendErr(conn, message{
			Type: "join", InvitationID: invite.InvitationID, PublicKey: base64.StdEncoding.EncodeToString(phone.spki),
			Name: "手机", Kind: "phone", Purpose: "main", Proof: hex.EncodeToString(proof), Sig: hex.EncodeToString(sig),
		}); err != nil {
			joinErr <- err
			return
		}
		ready, err := readJSONErr(conn)
		if err != nil {
			joinErr <- err
			return
		}
		if ready.Type != "ready" {
			joinErr <- errors.New("ready " + ready.Type)
			return
		}
		found := false
		for _, peer := range ready.Peers {
			if peer.DeviceID == hex.EncodeToString(mac.id[:]) && peer.Purpose == "main" {
				found = true
			}
		}
		if !found {
			joinErr <- errors.New("missing mac peer")
			return
		}
		registry, err := readJSONErr(conn)
		if err != nil || registry.Type != "registry" {
			joinErr <- errors.New("registry")
			return
		}
		joined <- conn
	}()

	request := readJSON(t, macConn)
	if request.Type != "join_request" || request.DeviceID != hex.EncodeToString(phone.id[:]) {
		t.Fatalf("join_request %#v", request)
	}
	again, err := link.InvitationProof(inviteKey, phone.spki, "手机", "phone", "main")
	if err != nil || hex.EncodeToString(again) != request.Proof {
		t.Fatal("proof")
	}
	approveBody, err := link.ApproveMessage(server.station, mustHex(t, invite.InvitationID), phone.id[:])
	if err != nil {
		t.Fatal(err)
	}
	send(t, macConn, message{Type: "approve", InvitationID: invite.InvitationID, DeviceID: request.DeviceID, Sig: sign(t, mac.key, approveBody)})
	var phoneConn *websocket.Conn
	select {
	case phoneConn = <-joined:
		t.Cleanup(func() { phoneConn.CloseNow() })
	case err := <-joinErr:
		t.Fatal(err)
	case <-time.After(5 * time.Second):
		t.Fatal("join hung")
	}
	registry := readJSON(t, macConn)
	presence := readJSON(t, macConn)
	if registry.Type != "registry" || len(registry.Devices) != 2 || presence.Type != "presence" || presence.Online == nil || !*presence.Online {
		t.Fatalf("after join registry=%#v presence=%#v", registry, presence)
	}

	media := dial(t, client, wsURL(base))
	_, mediaStation, mediaNonce := helloParts(t, media)
	mediaAuth, err := link.RelayAuthMessage(mediaStation, mediaNonce, mac.id[:], "media")
	if err != nil {
		t.Fatal(err)
	}
	send(t, media, message{Type: "auth", DeviceID: hex.EncodeToString(mac.id[:]), Purpose: "media", App: "test", Sig: sign(t, mac.key, mediaAuth)})
	if got := readJSON(t, media); got.Type != "ready" {
		t.Fatalf("media %#v", got)
	}
	if got := readJSON(t, macConn); got.Type != "presence" || got.Purpose != "media" {
		t.Fatalf("media presence %#v", got)
	}
	if got := readJSON(t, phoneConn); got.Type != "presence" || got.Purpose != "media" {
		t.Fatalf("phone media presence %#v", got)
	}
	sendData(t, media, phone.id, []byte("media-only"))
	if got := readJSON(t, media); got.Type != "error" || got.Code != "undeliverable" {
		t.Fatalf("media route %#v", got)
	}
	sendData(t, macConn, phone.id, []byte("secret-payload"))
	frame := readData(t, phoneConn)
	if frame.Kind != link.KindMessage || frame.Peer != mac.id || string(frame.Payload) != "secret-payload" {
		t.Fatalf("routed %#v %q", frame.Peer, frame.Payload)
	}
	phoneConn.Close(websocket.StatusNormalClosure, "")
	offline := readJSON(t, macConn)
	if offline.Type != "presence" || offline.Online == nil || *offline.Online || offline.DeviceID != hex.EncodeToString(phone.id[:]) {
		t.Fatalf("offline %#v", offline)
	}
	sendData(t, macConn, phone.id, []byte("after-close"))
	if got := readJSON(t, macConn); got.Type != "error" || got.Code != "undeliverable" {
		t.Fatalf("offline route %#v", got)
	}

	send(t, macConn, message{Type: "revoke", DeviceID: hex.EncodeToString(phone.id[:])})
	revokedRegistry := readJSON(t, macConn)
	if revokedRegistry.Type != "registry" {
		t.Fatalf("revoke %#v", revokedRegistry)
	}
	revoked := false
	for _, device := range revokedRegistry.Devices {
		if device.DeviceID == hex.EncodeToString(phone.id[:]) && device.Revoked {
			revoked = true
		}
	}
	if !revoked {
		t.Fatal("phone still active")
	}
	againConn := dial(t, client, wsURL(base))
	_, againStation, againNonce := helloParts(t, againConn)
	revokedAuth, err := link.RelayAuthMessage(againStation, againNonce, phone.id[:], "main")
	if err != nil {
		t.Fatal(err)
	}
	send(t, againConn, message{Type: "auth", DeviceID: hex.EncodeToString(phone.id[:]), Purpose: "main", App: "test", Sig: sign(t, phone.key, revokedAuth)})
	if got := readJSON(t, againConn); got.Type != "error" || got.Code != "revoked" {
		t.Fatalf("revoked auth %#v", got)
	}

	bad := dial(t, client, wsURL(base))
	helloParts(t, bad)
	send(t, bad, message{Type: "auth", DeviceID: hex.EncodeToString(mac.id[:]), Purpose: "main", App: "test", Sig: "00112233445566778899aabbccddeeff"})
	if got := readJSON(t, bad); got.Type != "error" || got.Code != "bad_signature" {
		t.Fatalf("bad sig %#v", got)
	}
	for i := 0; i < 4; i++ {
		send(t, macConn, message{Type: "invite", Kind: "phone"})
		if got := readJSON(t, macConn); got.Type != "invite" {
			t.Fatalf("invite %d %#v", i, got)
		}
	}
	send(t, macConn, message{Type: "invite", Kind: "phone"})
	if got := readJSON(t, macConn); got.Type != "error" || got.Code != "rejected" {
		t.Fatalf("fifth invite %#v", got)
	}
	send(t, macConn, message{Type: "ping"})
	if got := readJSON(t, macConn); got.Type != "ping" {
		t.Fatalf("ping %#v", got)
	}

	logText := buf.String()
	file := mustRead(t, filepath.Join(server.reg.dir, "registry.json"))
	for _, secret := range []string{code, "secret-payload", hex.EncodeToString(inviteKey), request.Proof} {
		if strings.Contains(logText, secret) || bytes.Contains(file, []byte(secret)) {
			t.Fatalf("secret stored: %s", secret)
		}
	}
	if !strings.Contains(logText, "enroll") || !strings.Contains(logText, "undeliverable") {
		t.Fatalf("log %s", logText)
	}
	info, err := os.Stat(filepath.Join(server.reg.dir, "registry.json"))
	if err != nil || info.Mode().Perm() != 0600 {
		t.Fatalf("mode %v", info)
	}

	resp, err := client.Post(base+"/v2/link", "text/plain", strings.NewReader("x"))
	if err != nil || resp.StatusCode != http.StatusMethodNotAllowed {
		t.Fatalf("post %v %v", resp, err)
	}
	resp.Body.Close()
	resp, err = client.Get(base + "/v1/link")
	if err != nil || resp.StatusCode != http.StatusNotFound {
		t.Fatalf("path %v %v", resp, err)
	}
	resp.Body.Close()
	resp, err = client.Get(base + "/v2/link?token=1")
	if err != nil || resp.StatusCode != http.StatusNotFound {
		t.Fatalf("query %v %v", resp, err)
	}
	resp.Body.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_, originResp, originErr := websocket.Dial(ctx, wsURL(base), &websocket.DialOptions{HTTPClient: client, HTTPHeader: http.Header{"Origin": []string{"https://evil.example"}}})
	if originErr == nil || originResp == nil || originResp.StatusCode != http.StatusForbidden {
		t.Fatalf("origin %v %v", originResp, originErr)
	}
	badTLS := &http.Client{Transport: &http.Transport{TLSClientConfig: &tls.Config{RootCAs: client.Transport.(*http.Transport).TLSClientConfig.RootCAs, ServerName: "wrong.example", MinVersion: tls.VersionTLS12}}}
	if _, err := badTLS.Get(base + "/v2/link"); err == nil {
		t.Fatal("accepted wrong server name")
	}
}

func TestRelay6RateIdleAndCaps(t *testing.T) {
	server, client, base, _ := startTLS(t)
	server.mu.Lock()
	server.idle = time.Second
	now := time.Now()
	for i := 0; i < 8; i++ {
		server.failures["203.0.113.9"] = append(server.failures["203.0.113.9"], now)
	}
	server.failures["198.51.100.9"] = []time.Time{now.Add(-11 * time.Minute)}
	server.mu.Unlock()
	if server.allow("203.0.113.9") || !server.allow("198.51.100.9") {
		t.Fatal("window")
	}
	for i := 0; i < 8; i++ {
		server.note("127.0.0.1")
	}
	before := failureCount(server, "127.0.0.1")
	limited := dial(t, client, wsURL(base))
	helloParts(t, limited)
	send(t, limited, message{Type: "enroll", Code: "00112233445566778899aabbccddeeff", Name: "办公室", Kind: "mac", Purpose: "main"})
	if got := readJSON(t, limited); got.Code != "rate_limited" {
		t.Fatalf("enroll rate %#v", got)
	}
	joined := dial(t, client, wsURL(base))
	helloParts(t, joined)
	send(t, joined, message{Type: "join", Name: "手机", Kind: "phone", Purpose: "main"})
	if got := readJSON(t, joined); got.Code != "rate_limited" {
		t.Fatalf("join rate %#v", got)
	}
	unknown := dial(t, client, wsURL(base))
	helloParts(t, unknown)
	send(t, unknown, message{Type: "auth", DeviceID: "00112233445566778899aabbccddeeff", Purpose: "main", App: "test", Sig: "00112233445566778899aabbccddeeff"})
	if got := readJSON(t, unknown); got.Code != "unknown_device" {
		t.Fatalf("auth %#v", got)
	}
	if failureCount(server, "127.0.0.1") != before {
		t.Fatal("auth consumed an enroll attempt")
	}

	idleServer, idleClient, idleBase, _ := startTLS(t)
	idleServer.mu.Lock()
	idleServer.idle = time.Second
	idleServer.mu.Unlock()
	code, err := idleServer.IssueEnroll()
	if err != nil {
		t.Fatal(err)
	}
	conn := enrollMac(t, idleClient, idleBase, code, newIdentity(t))
	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
	defer cancel()
	if _, err := readFrame(ctx, conn, link.MainPayload); err == nil {
		t.Fatal("idle connection stayed open")
	}

	capServer, capClient, capBase, _ := startTLS(t)
	if err := capServer.reg.mutate(func(f *fileFormat) error {
		for i := 0; i < 32; i++ {
			id := newIdentity(t)
			f.Devices = append(f.Devices, deviceRec{ID: hex.EncodeToString(id.id[:]), PublicKey: encodeKey(id.spki), Name: "n", Kind: "mac"})
		}
		return nil
	}); err != nil {
		t.Fatal(err)
	}
	capCode, err := capServer.IssueEnroll()
	if err != nil {
		t.Fatal(err)
	}
	extra := newIdentity(t)
	extraConn := dial(t, capClient, wsURL(capBase))
	_, extraStation, extraNonce := helloParts(t, extraConn)
	raw, _ := hex.DecodeString(capCode)
	body, err := link.EnrollMessage(extraStation, extraNonce, extra.id[:], raw, "main")
	if err != nil {
		t.Fatal(err)
	}
	send(t, extraConn, message{Type: "enroll", Code: capCode, PublicKey: base64.StdEncoding.EncodeToString(extra.spki), Name: "办公室", Kind: "mac", Purpose: "main", Sig: sign(t, extra.key, body)})
	if got := readJSON(t, extraConn); got.Code != "rejected" {
		t.Fatalf("cap %#v", got)
	}
}

func TestRelay6RejectConsumesAttempt(t *testing.T) {
	server, client, base, _ := startTLS(t)
	code, err := server.IssueEnroll()
	if err != nil {
		t.Fatal(err)
	}
	mac := newIdentity(t)
	phone := newIdentity(t)
	macConn := enrollMac(t, client, base, code, mac)
	send(t, macConn, message{Type: "invite", Kind: "phone"})
	invite := readJSON(t, macConn)
	for attempt := 0; attempt < 5; attempt++ {
		done := make(chan error, 1)
		go func() {
			conn, err := dialErr(client, wsURL(base))
			if err != nil {
				done <- err
				return
			}
			defer conn.CloseNow()
			hello, err := readJSONErr(conn)
			if err != nil {
				done <- err
				return
			}
			station, _ := hex.DecodeString(hello.StationID)
			nonce, _ := hex.DecodeString(hello.Nonce)
			invitationID, _ := hex.DecodeString(invite.InvitationID)
			body, err := link.JoinMessage(station, nonce, phone.id[:], invitationID, "main")
			if err != nil {
				done <- err
				return
			}
			sum := sha256.Sum256(body)
			sig, err := ecdsa.SignASN1(rand.Reader, phone.key, sum[:])
			if err != nil {
				done <- err
				return
			}
			if err := sendErr(conn, message{
				Type: "join", InvitationID: invite.InvitationID, PublicKey: base64.StdEncoding.EncodeToString(phone.spki),
				Name: "手机", Kind: "phone", Purpose: "main", Proof: strings.Repeat("ab", 32), Sig: hex.EncodeToString(sig),
			}); err != nil {
				done <- err
				return
			}
			got, err := readJSONErr(conn)
			if err != nil {
				done <- err
				return
			}
			if got.Type != "error" || got.Code != "rejected" {
				done <- errors.New(got.Type + " " + got.Code)
				return
			}
			done <- nil
		}()
		request := readJSON(t, macConn)
		if request.Type != "join_request" {
			t.Fatalf("attempt %d %#v", attempt, request)
		}
		send(t, macConn, message{Type: "reject", InvitationID: invite.InvitationID, DeviceID: request.DeviceID})
		if err := <-done; err != nil {
			t.Fatal(err)
		}
	}
	snap := server.reg.snapshot()
	inv := findInvite(&snap, invite.InvitationID)
	if inv == nil || inv.AttemptsLeft != 0 {
		t.Fatalf("attempts %#v", inv)
	}
	exhausted := dial(t, client, wsURL(base))
	hello, station, nonce := helloParts(t, exhausted)
	_ = hello
	body, err := link.JoinMessage(station, nonce, phone.id[:], mustHex(t, invite.InvitationID), "main")
	if err != nil {
		t.Fatal(err)
	}
	send(t, exhausted, message{
		Type: "join", InvitationID: invite.InvitationID, PublicKey: base64.StdEncoding.EncodeToString(phone.spki),
		Name: "手机", Kind: "phone", Purpose: "main", Proof: strings.Repeat("ab", 32), Sig: sign(t, phone.key, body),
	})
	if got := readJSON(t, exhausted); got.Code != "rejected" {
		t.Fatalf("exhausted %#v", got)
	}
}

func TestRelay6WriteFailure(t *testing.T) {
	server, client, base, _ := startTLS(t)
	code, err := server.IssueEnroll()
	if err != nil {
		t.Fatal(err)
	}
	mac := newIdentity(t)
	conn := enrollMac(t, client, base, code, mac)
	before := mustRead(t, filepath.Join(server.reg.dir, "registry.json"))
	version := server.reg.snapshot().RegistryVersion
	server.reg.mu.Lock()
	server.reg.fault = func(string) error { return errors.New("disk") }
	server.reg.mu.Unlock()
	send(t, conn, message{Type: "invite", Kind: "phone"})
	if got := readJSON(t, conn); got.Code != "unavailable" {
		t.Fatalf("fault %#v", got)
	}
	if after := mustRead(t, filepath.Join(server.reg.dir, "registry.json")); !bytes.Equal(before, after) {
		t.Fatal("registry changed after a failed write")
	}
	if server.reg.snapshot().RegistryVersion != version {
		t.Fatal("memory registry changed after a failed write")
	}
	if _, err := server.IssueEnroll(); !errors.Is(err, errPersist) {
		t.Fatalf("enroll while disk is failing: %v", err)
	}
	authed, ready := authAs(t, client, base, mac, "main")
	if ready.Type != "ready" {
		t.Fatalf("auth after fault %#v", ready)
	}
	server.reg.mu.Lock()
	server.reg.fault = nil
	server.reg.mu.Unlock()
	if _, err := server.IssueEnroll(); err != nil {
		t.Fatal(err)
	}
	send(t, authed, message{Type: "invite", Kind: "phone"})
	if got := readJSON(t, authed); got.Type != "invite" {
		t.Fatalf("invite after disk recovered %#v", got)
	}
}

func TestRelay6RefundPersistFailure(t *testing.T) {
	server, client, base, _ := startTLS(t)
	code, err := server.IssueEnroll()
	if err != nil {
		t.Fatal(err)
	}
	mac := newIdentity(t)
	phone := newIdentity(t)
	macConn := enrollMac(t, client, base, code, mac)
	send(t, macConn, message{Type: "invite", Kind: "phone"})
	invite := readJSON(t, macConn)
	if invite.Type != "invite" {
		t.Fatalf("invite %#v", invite)
	}
	joined := make(chan error, 1)
	go func() {
		conn, err := dialErr(client, wsURL(base))
		if err != nil {
			joined <- err
			return
		}
		defer conn.CloseNow()
		hello, err := readJSONErr(conn)
		if err != nil {
			joined <- err
			return
		}
		station, _ := hex.DecodeString(hello.StationID)
		nonce, _ := hex.DecodeString(hello.Nonce)
		invitationID, err := hex.DecodeString(invite.InvitationID)
		if err != nil {
			joined <- err
			return
		}
		body, err := link.JoinMessage(station, nonce, phone.id[:], invitationID, "main")
		if err != nil {
			joined <- err
			return
		}
		sum := sha256.Sum256(body)
		sig, err := ecdsa.SignASN1(rand.Reader, phone.key, sum[:])
		if err != nil {
			joined <- err
			return
		}
		proof, err := link.InvitationProof(bytes.Repeat([]byte{0xAB}, 16), phone.spki, "手机", "phone", "main")
		if err != nil {
			joined <- err
			return
		}
		if err := sendErr(conn, message{
			Type: "join", InvitationID: invite.InvitationID, PublicKey: base64.StdEncoding.EncodeToString(phone.spki),
			Name: "手机", Kind: "phone", Purpose: "main", Proof: hex.EncodeToString(proof), Sig: hex.EncodeToString(sig),
		}); err != nil {
			joined <- err
			return
		}
		got, err := readJSONErr(conn)
		if err != nil {
			joined <- err
			return
		}
		if got.Code != "rejected" {
			joined <- errors.New(got.Type + " " + got.Code)
			return
		}
		joined <- nil
	}()
	request := readJSON(t, macConn)
	if request.Type != "join_request" {
		t.Fatalf("join_request %#v", request)
	}
	writes := 0
	server.reg.mu.Lock()
	server.reg.fault = func(stage string) error {
		if stage != "before-write" {
			return nil
		}
		writes++
		if writes >= 2 {
			return errors.New("disk")
		}
		return nil
	}
	server.reg.mu.Unlock()
	again := dial(t, client, wsURL(base))
	hello, station, nonce := helloParts(t, again)
	_ = hello
	body, err := link.JoinMessage(station, nonce, phone.id[:], mustHex(t, invite.InvitationID), "main")
	if err != nil {
		t.Fatal(err)
	}
	sum := sha256.Sum256(body)
	sig, err := ecdsa.SignASN1(rand.Reader, phone.key, sum[:])
	if err != nil {
		t.Fatal(err)
	}
	proof, err := link.InvitationProof(bytes.Repeat([]byte{0xAB}, 16), phone.spki, "手机", "phone", "main")
	if err != nil {
		t.Fatal(err)
	}
	send(t, again, message{
		Type: "join", InvitationID: invite.InvitationID, PublicKey: base64.StdEncoding.EncodeToString(phone.spki),
		Name: "手机", Kind: "phone", Purpose: "main", Proof: hex.EncodeToString(proof), Sig: hex.EncodeToString(sig),
	})
	if got := readJSON(t, again); got.Code != "unavailable" {
		t.Fatalf("refund fault %#v", got)
	}
	if failureCount(server, "127.0.0.1") != 0 {
		t.Fatal("disk failure counted as a join failure")
	}
	snap := server.reg.snapshot()
	inv := findInvite(&snap, invite.InvitationID)
	if inv == nil || inv.AttemptsLeft != 3 {
		t.Fatalf("attempts %#v", inv)
	}
	send(t, macConn, message{Type: "reject", InvitationID: invite.InvitationID, DeviceID: request.DeviceID})
	if err := <-joined; err != nil {
		t.Fatal(err)
	}
}

func TestRelay6Registry(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "registry.json"), []byte("{"), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := Open(dir); err == nil {
		t.Fatal("opened corrupt registry")
	}
	if body := mustRead(t, filepath.Join(dir, "registry.json")); string(body) != "{" {
		t.Fatalf("corrupt file replaced: %s", body)
	}
	versionDir := t.TempDir()
	if err := os.WriteFile(filepath.Join(versionDir, "registry.json"), []byte(`{"version":2}`), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := Open(versionDir); err == nil {
		t.Fatal("opened unknown version")
	}
	linkDir := t.TempDir()
	target := filepath.Join(linkDir, "other.json")
	if err := os.WriteFile(target, []byte("{}"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(target, filepath.Join(linkDir, "registry.json")); err != nil {
		t.Fatal(err)
	}
	if _, err := Open(linkDir); err == nil {
		t.Fatal("opened symlink")
	}
	busy := t.TempDir()
	if err := os.WriteFile(filepath.Join(busy, "state.json"), []byte("{}"), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := Open(busy); err == nil {
		t.Fatal("created a registry beside other state")
	}
	okDir := t.TempDir()
	first, err := Open(okDir)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := Open(okDir); err == nil {
		t.Fatal("second instance")
	}
	station := first.stationHex
	first.Close()
	info, err := os.Stat(filepath.Join(okDir, "registry.json"))
	if err != nil || info.Mode().Perm() != 0600 {
		t.Fatal(info, err)
	}
	if err := os.Chmod(filepath.Join(okDir, "registry.json"), 0644); err != nil {
		t.Fatal(err)
	}
	if _, err := Open(okDir); err == nil {
		t.Fatal("opened group-readable registry")
	}
	if err := os.Chmod(filepath.Join(okDir, "registry.json"), 0600); err != nil {
		t.Fatal(err)
	}
	second, err := Open(okDir)
	if err != nil || second.stationHex != station {
		t.Fatal(err, second)
	}
	second.Close()
}

func TestControlJSON(t *testing.T) {
	if _, err := parseControl([]byte(`{"type":"ping","type":"invite"}`)); err == nil {
		t.Fatal("duplicate")
	}
	if _, err := parseControl([]byte(`{"type":"ping","extra":1}`)); err == nil {
		t.Fatal("unknown")
	}
	msg, err := parseControl([]byte(`{"type":"ping"}`))
	if err != nil || msg.Type != "ping" {
		t.Fatal(err, msg)
	}
}

func TestRelay6RevokedDoesNotLockNeighbors(t *testing.T) {
	server, client, base, _ := startTLS(t)
	code, err := server.IssueEnroll()
	if err != nil {
		t.Fatal(err)
	}
	mac := newIdentity(t)
	phone := newIdentity(t)
	macConn := enrollMac(t, client, base, code, mac)
	joinDevice(t, server, client, base, macConn, mac, phone, "phone", "手机")
	send(t, macConn, message{Type: "revoke", DeviceID: hex.EncodeToString(phone.id[:])})
	revoked := readUntil(t, macConn, "registry")
	found := false
	for _, device := range revoked.Devices {
		if device.DeviceID == hex.EncodeToString(phone.id[:]) && device.Revoked {
			found = true
		}
	}
	if !found {
		t.Fatal("phone still active")
	}
	for i := 0; i < 8; i++ {
		_, got := authAs(t, client, base, phone, "main")
		if got.Type != "error" || got.Code != "revoked" {
			t.Fatalf("revoked auth %d %#v", i, got)
		}
	}
	if failureCount(server, "127.0.0.1") != 0 {
		t.Fatal("revoked auth counted against the address")
	}
	_, other := authAs(t, client, base, mac, "main")
	if other.Type != "ready" {
		t.Fatalf("neighbor %#v", other)
	}
	fresh := dial(t, client, wsURL(base))
	helloParts(t, fresh)
	send(t, fresh, message{Type: "enroll", Code: "00112233445566778899aabbccddeeff", Name: "办公室", Kind: "mac", Purpose: "main", Sig: "00112233445566778899aabbccddeeff"})
	if got := readJSON(t, fresh); got.Code == "rate_limited" {
		t.Fatalf("enroll locked %#v", got)
	}
}

func TestRelay6SlowReceiverDoesNotBlockSender(t *testing.T) {
	server, client, base, _ := startTLS(t)
	code, err := server.IssueEnroll()
	if err != nil {
		t.Fatal(err)
	}
	mac := newIdentity(t)
	slowID := newIdentity(t)
	fastID := newIdentity(t)
	macConn := enrollMac(t, client, base, code, mac)
	slow := joinDevice(t, server, client, base, macConn, mac, slowID, "phone", "手机甲")
	fast := joinDevice(t, server, client, base, macConn, mac, fastID, "phone", "手机乙")
	_, mediaReady := authAs(t, client, base, mac, "media")
	if mediaReady.Type != "ready" {
		t.Fatalf("media %#v", mediaReady)
	}
	server.mu.Lock()
	mainSess := server.links[hex.EncodeToString(mac.id[:])+"/main"]
	slowSess := server.links[hex.EncodeToString(slowID.id[:])+"/main"]
	mediaSess := server.links[hex.EncodeToString(mac.id[:])+"/media"]
	server.mu.Unlock()
	if mainSess == nil || mainSess.queueMax != mainQueueBytes || slowSess == nil || slowSess.queueMax != mainQueueBytes {
		t.Fatalf("main queue %#v %#v", mainSess, slowSess)
	}
	if mediaSess == nil || mediaSess.queueMax != mediaQueueBytes {
		t.Fatalf("media queue %#v", mediaSess)
	}
	hold := make(chan struct{})
	defer close(hold)
	slowSess.setWriteHold(hold)
	payload := bytes.Repeat([]byte{0xab}, link.MainPayload)
	start := time.Now()
	for i := 0; i < 140; i++ {
		sendData(t, macConn, slowID.id, payload)
	}
	if elapsed := time.Since(start); elapsed > 10*time.Second {
		t.Fatalf("sender blocked for %s", elapsed)
	}
	sendData(t, macConn, fastID.id, []byte("fast"))
	readPayload(t, fast, "fast", 5*time.Second)
	expectClosed(t, slow)
	if got := readUntil(t, macConn, "error"); got.Code != "undeliverable" {
		t.Fatalf("undeliverable %#v", got)
	}
}

func TestRelay6RejectsFormatNames(t *testing.T) {
	if !validName("办公室") || !validName("n") {
		t.Fatal("plain")
	}
	for _, name := range []string{"", strings.Repeat("a", 65), "a\u0000b", "a\u200bb", "a\u202eb", "a\u00adb"} {
		if validName(name) {
			t.Fatalf("accepted %q", name)
		}
	}
	server, client, base, _ := startTLS(t)
	code, err := server.IssueEnroll()
	if err != nil {
		t.Fatal(err)
	}
	mac := newIdentity(t)
	conn := dial(t, client, wsURL(base))
	_, station, nonce := helloParts(t, conn)
	rawCode := mustHex(t, code)
	transcript, err := link.EnrollMessage(station, nonce, mac.id[:], rawCode, "main")
	if err != nil {
		t.Fatal(err)
	}
	send(t, conn, message{
		Type: "enroll", Code: code, PublicKey: base64.StdEncoding.EncodeToString(mac.spki),
		Name: "办公\u200b室", Kind: "mac", Purpose: "main", Sig: sign(t, mac.key, transcript),
	})
	if got := readJSON(t, conn); got.Code != "rejected" {
		t.Fatalf("format name %#v", got)
	}
	if failureCount(server, "127.0.0.1") != 1 {
		t.Fatal("format name was not an enroll failure")
	}
	enrollMac(t, client, base, code, mac)
}

func TestRelay6UnauthenticatedCap(t *testing.T) {
	_, client, base, _ := startTLS(t)
	var wg sync.WaitGroup
	errCh := make(chan error, maxUnauthPerIP)
	connCh := make(chan *websocket.Conn, maxUnauthPerIP)
	for i := 0; i < maxUnauthPerIP; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			conn, err := dialErr(client, wsURL(base))
			if err != nil {
				errCh <- err
				return
			}
			if _, err := readJSONErr(conn); err != nil {
				conn.CloseNow()
				errCh <- err
				return
			}
			connCh <- conn
		}()
	}
	wg.Wait()
	close(errCh)
	close(connCh)
	for err := range errCh {
		t.Fatal(err)
	}
	var held []*websocket.Conn
	for conn := range connCh {
		conn := conn
		held = append(held, conn)
		t.Cleanup(func() { conn.CloseNow() })
	}
	if len(held) != maxUnauthPerIP {
		t.Fatalf("held %d", len(held))
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_, resp, err := websocket.Dial(ctx, wsURL(base), &websocket.DialOptions{HTTPClient: client})
	if err == nil || resp == nil || resp.StatusCode != http.StatusServiceUnavailable {
		t.Fatalf("cap %v %v", resp, err)
	}
	held[0].CloseNow()
	deadline := time.Now().Add(2 * time.Second)
	var last error
	for time.Now().Before(deadline) {
		conn, err := dialErr(client, wsURL(base))
		if err == nil {
			defer conn.CloseNow()
			if _, err := readJSONErr(conn); err != nil {
				t.Fatal(err)
			}
			return
		}
		last = err
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatal(last)
}

func TestRelay6TrustedProxyDefaultOff(t *testing.T) {
	server, client, base, _ := startTLS(t)
	for i := 0; i < 8; i++ {
		server.note("203.0.113.50")
	}
	ignored := dialHeader(t, client, wsURL(base), http.Header{"X-Forwarded-For": []string{"203.0.113.50"}})
	helloParts(t, ignored)
	send(t, ignored, message{Type: "enroll", Name: "办公室", Kind: "mac", Purpose: "main"})
	if got := readJSON(t, ignored); got.Code == "rate_limited" {
		t.Fatalf("header counted while proxies are off %#v", got)
	}
	server.SetTrustedProxies([]string{"127.0.0.1"})
	limited := dialHeader(t, client, wsURL(base), http.Header{"X-Forwarded-For": []string{"198.51.100.8, 203.0.113.50"}})
	helloParts(t, limited)
	send(t, limited, message{Type: "enroll", Name: "办公室", Kind: "mac", Purpose: "main"})
	if got := readJSON(t, limited); got.Code != "rate_limited" {
		t.Fatalf("rightmost client %#v", got)
	}
	other := dialHeader(t, client, wsURL(base), http.Header{"X-Forwarded-For": []string{"203.0.113.50, 198.51.100.20"}})
	helloParts(t, other)
	send(t, other, message{Type: "enroll", Name: "办公室", Kind: "mac", Purpose: "main"})
	if got := readJSON(t, other); got.Code == "rate_limited" {
		t.Fatalf("leftmost was used %#v", got)
	}
}

func TestParseTrustedProxies(t *testing.T) {
	got, err := ParseTrustedProxies("  ")
	if err != nil || got != nil {
		t.Fatalf("%v %v", got, err)
	}
	got, err = ParseTrustedProxies(" 127.0.0.1, ::1, ")
	if err != nil || len(got) != 2 || got[0] != "127.0.0.1" || got[1] != "::1" {
		t.Fatalf("%v %v", got, err)
	}
	if _, err := ParseTrustedProxies("127.0.0.1, nope"); err == nil {
		t.Fatal("accepted a non-IP")
	}
}

func TestForwardedClient(t *testing.T) {
	trusted := map[string]struct{}{"127.0.0.1": {}}
	if got := forwardedClient("198.51.100.8, 203.0.113.50", trusted); got != "203.0.113.50" {
		t.Fatal(got)
	}
	if got := forwardedClient("203.0.113.50, 127.0.0.1", trusted); got != "203.0.113.50" {
		t.Fatal(got)
	}
	if got := forwardedClient("not-an-ip", trusted); got != "" {
		t.Fatal(got)
	}
}

func authAs(t *testing.T, client *http.Client, base string, id identity, purpose string) (*websocket.Conn, message) {
	t.Helper()
	conn := dial(t, client, wsURL(base))
	_, station, nonce := helloParts(t, conn)
	body, err := link.RelayAuthMessage(station, nonce, id.id[:], purpose)
	if err != nil {
		t.Fatal(err)
	}
	send(t, conn, message{Type: "auth", DeviceID: hex.EncodeToString(id.id[:]), Purpose: purpose, App: "test", Sig: sign(t, id.key, body)})
	return conn, readJSON(t, conn)
}

func joinDevice(t *testing.T, server *Server, client *http.Client, base string, macConn *websocket.Conn, mac, peer identity, kind, name string) *websocket.Conn {
	t.Helper()
	send(t, macConn, message{Type: "invite", Kind: kind})
	invite := readJSON(t, macConn)
	if invite.Type != "invite" {
		t.Fatalf("invite %#v", invite)
	}
	inviteKey := bytes.Repeat([]byte{0xAB}, 16)
	proof, err := link.InvitationProof(inviteKey, peer.spki, name, kind, "main")
	if err != nil {
		t.Fatal(err)
	}
	joined := make(chan *websocket.Conn, 1)
	joinErr := make(chan error, 1)
	go func() {
		conn, err := dialErr(client, wsURL(base))
		if err != nil {
			joinErr <- err
			return
		}
		hello, err := readJSONErr(conn)
		if err != nil {
			conn.CloseNow()
			joinErr <- err
			return
		}
		peerStation, _ := hex.DecodeString(hello.StationID)
		peerNonce, _ := hex.DecodeString(hello.Nonce)
		invitationID, _ := hex.DecodeString(invite.InvitationID)
		body, err := link.JoinMessage(peerStation, peerNonce, peer.id[:], invitationID, "main")
		if err != nil {
			conn.CloseNow()
			joinErr <- err
			return
		}
		sum := sha256.Sum256(body)
		sig, err := ecdsa.SignASN1(rand.Reader, peer.key, sum[:])
		if err != nil {
			conn.CloseNow()
			joinErr <- err
			return
		}
		if err := sendErr(conn, message{
			Type: "join", InvitationID: invite.InvitationID, PublicKey: base64.StdEncoding.EncodeToString(peer.spki),
			Name: name, Kind: kind, Purpose: "main", Proof: hex.EncodeToString(proof), Sig: hex.EncodeToString(sig),
		}); err != nil {
			conn.CloseNow()
			joinErr <- err
			return
		}
		ready, err := readJSONErr(conn)
		if err != nil || ready.Type != "ready" {
			conn.CloseNow()
			joinErr <- errors.New("ready")
			return
		}
		registry, err := readJSONErr(conn)
		if err != nil || registry.Type != "registry" {
			conn.CloseNow()
			joinErr <- errors.New("registry")
			return
		}
		joined <- conn
	}()
	request := readJSON(t, macConn)
	if request.Type != "join_request" || request.DeviceID != hex.EncodeToString(peer.id[:]) {
		t.Fatalf("join_request %#v", request)
	}
	approveBody, err := link.ApproveMessage(server.station, mustHex(t, invite.InvitationID), peer.id[:])
	if err != nil {
		t.Fatal(err)
	}
	send(t, macConn, message{Type: "approve", InvitationID: invite.InvitationID, DeviceID: request.DeviceID, Sig: sign(t, mac.key, approveBody)})
	var peerConn *websocket.Conn
	select {
	case peerConn = <-joined:
		t.Cleanup(func() { peerConn.CloseNow() })
	case err := <-joinErr:
		t.Fatal(err)
	case <-time.After(5 * time.Second):
		t.Fatal("join hung")
	}
	if got := readUntil(t, macConn, "registry"); got.Type != "registry" {
		t.Fatalf("registry %#v", got)
	}
	if got := readUntil(t, macConn, "presence"); got.Type != "presence" {
		t.Fatalf("presence %#v", got)
	}
	return peerConn
}

func dialHeader(t *testing.T, client *http.Client, raw string, header http.Header) *websocket.Conn {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	conn, _, err := websocket.Dial(ctx, raw, &websocket.DialOptions{HTTPClient: client, HTTPHeader: header})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { conn.CloseNow() })
	return conn
}

func readUntil(t *testing.T, conn *websocket.Conn, kind string) message {
	t.Helper()
	for i := 0; i < 6; i++ {
		msg := readJSON(t, conn)
		if msg.Type == kind {
			return msg
		}
	}
	t.Fatalf("missing %s", kind)
	return message{}
}

func readPayload(t *testing.T, conn *websocket.Conn, payload string, timeout time.Duration) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		ctx, cancel := context.WithDeadline(context.Background(), deadline)
		frame, err := readFrame(ctx, conn, link.MainPayload)
		cancel()
		if err != nil {
			t.Fatal(err)
		}
		if frame.Kind == link.KindMessage && string(frame.Payload) == payload {
			return
		}
	}
	t.Fatalf("missing payload %q", payload)
}

func expectClosed(t *testing.T, conn *websocket.Conn) {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		ctx, cancel := context.WithDeadline(context.Background(), deadline)
		_, err := readFrame(ctx, conn, link.MainPayload)
		cancel()
		if err == nil {
			continue
		}
		if errors.Is(err, context.DeadlineExceeded) {
			t.Fatal("slow peer stayed open")
		}
		return
	}
	t.Fatal("slow peer stayed open")
}

func failureCount(s *Server, ip string) int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return len(s.failures[ip])
}

func TestReverseProxyPassesUpgradeAndMediaFrame(t *testing.T) {
	server, directClient, base, _ := startTLS(t)
	proxyBase, proxyClient, proxyIP := startReverseProxy(t, base)

	direct := dial(t, directClient, wsURL(base))
	helloParts(t, direct)
	if got := unauthKeys(server); len(got) != 1 || got[0] != "127.0.0.1" {
		t.Fatalf("direct address %v", got)
	}
	direct.CloseNow()
	waitUnauth(t, server, 0)

	proxied := dial(t, proxyClient, wsURL(proxyBase))
	helloParts(t, proxied)
	if got := unauthKeys(server); len(got) != 1 || got[0] != proxyIP {
		t.Fatalf("proxy address %v, want %s", got, proxyIP)
	}
	proxied.CloseNow()
	waitUnauth(t, server, 0)

	server.SetTrustedProxies([]string{proxyIP})
	trusted := dial(t, proxyClient, wsURL(proxyBase))
	helloParts(t, trusted)
	if got := unauthKeys(server); len(got) != 1 || got[0] != "127.0.0.1" {
		t.Fatalf("forwarded address %v", got)
	}
	trusted.CloseNow()
	waitUnauth(t, server, 0)
	server.SetTrustedProxies(nil)

	code, err := server.IssueEnroll()
	if err != nil {
		t.Fatal(err)
	}
	mac := newIdentity(t)
	enrollMac(t, proxyClient, proxyBase, code, mac)
	code, err = server.IssueEnroll()
	if err != nil {
		t.Fatal(err)
	}
	phone := newIdentity(t)
	enrollDevices(t, proxyClient, proxyBase, code, phone, 2)
	phoneMedia := authMedia(t, proxyClient, proxyBase, phone)
	macMedia := authMedia(t, proxyClient, proxyBase, mac)
	if got := readJSON(t, phoneMedia); got.Type != "presence" || got.Purpose != "media" {
		t.Fatalf("presence %#v", got)
	}
	payload := bytes.Repeat([]byte{0x5a}, link.MediaPayload)
	raw, err := link.Encode(link.Frame{Kind: link.KindMessage, Peer: phone.id, Payload: payload}, link.MediaPayload)
	if err != nil {
		t.Fatal(err)
	}
	if len(raw) != link.HeaderSize+link.MediaPayload {
		t.Fatalf("frame bytes %d", len(raw))
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := macMedia.Write(ctx, websocket.MessageBinary, raw); err != nil {
		t.Fatal(err)
	}
	phoneMedia.SetReadLimit(int64(link.HeaderSize + link.MediaPayload))
	frame, err := readFrame(ctx, phoneMedia, link.MediaPayload)
	if err != nil {
		t.Fatal(err)
	}
	if frame.Kind != link.KindMessage || frame.Peer != mac.id || !bytes.Equal(frame.Payload, payload) {
		t.Fatalf("delivered kind=%d peer=%x bytes=%d", frame.Kind, frame.Peer, len(frame.Payload))
	}
}

func startReverseProxy(t *testing.T, backend string) (string, *http.Client, string) {
	t.Helper()
	target, err := url.Parse(backend)
	if err != nil {
		t.Fatal(err)
	}
	proxyIP := outboundIPv4(t)
	proxy := &httputil.ReverseProxy{
		Rewrite: func(req *httputil.ProxyRequest) {
			req.SetURL(target)
			req.SetXForwarded()
		},
		Transport: &http.Transport{
			DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
				dialer := &net.Dialer{LocalAddr: &net.TCPAddr{IP: net.ParseIP(proxyIP)}}
				return dialer.DialContext(ctx, network, addr)
			},
			TLSClientConfig: &tls.Config{InsecureSkipVerify: true, MinVersion: tls.VersionTLS12},
		},
		ErrorLog: log.New(io.Discard, "", 0),
	}
	cert, pool := proxyCert(t)
	listener, err := tls.Listen("tcp", "127.0.0.1:0", &tls.Config{Certificates: []tls.Certificate{cert}, MinVersion: tls.VersionTLS12})
	if err != nil {
		t.Fatal(err)
	}
	httpServer := &http.Server{Handler: proxy, ReadHeaderTimeout: 5 * time.Second, ErrorLog: log.New(io.Discard, "", 0)}
	go httpServer.Serve(listener)
	t.Cleanup(func() { httpServer.Close() })
	client := &http.Client{Transport: &http.Transport{TLSClientConfig: &tls.Config{
		RootCAs: pool, ServerName: "127.0.0.1", MinVersion: tls.VersionTLS12,
	}}}
	return "https://" + listener.Addr().String(), client, proxyIP
}

func outboundIPv4(t *testing.T) string {
	t.Helper()
	conn, err := net.Dial("udp", "192.168.0.1:9")
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	ip, _, err := net.SplitHostPort(conn.LocalAddr().String())
	if err != nil || net.ParseIP(ip) == nil || net.ParseIP(ip).To4() == nil || ip == "127.0.0.1" {
		t.Fatalf("source %s", conn.LocalAddr())
	}
	return ip
}

func proxyCert(t *testing.T) (tls.Certificate, *x509.CertPool) {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	template := &x509.Certificate{
		SerialNumber: big.NewInt(2),
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(time.Hour),
		IPAddresses:  []net.IP{net.ParseIP("127.0.0.1")},
		KeyUsage:     x509.KeyUsageDigitalSignature,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
	}
	der, err := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	cert, err := tls.X509KeyPair(
		pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}),
		pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: mustPKCS8(t, key)}),
	)
	if err != nil {
		t.Fatal(err)
	}
	pool := x509.NewCertPool()
	if !pool.AppendCertsFromPEM(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})) {
		t.Fatal("pool")
	}
	return cert, pool
}

func enrollDevices(t *testing.T, client *http.Client, base, code string, who identity, devices int) *websocket.Conn {
	t.Helper()
	conn := dial(t, client, wsURL(base))
	_, station, nonce := helloParts(t, conn)
	rawCode, err := hex.DecodeString(code)
	if err != nil {
		t.Fatal(err)
	}
	transcript, err := link.EnrollMessage(station, nonce, who.id[:], rawCode, "main")
	if err != nil {
		t.Fatal(err)
	}
	send(t, conn, message{
		Type: "enroll", Code: code, PublicKey: base64.StdEncoding.EncodeToString(who.spki),
		Name: "办公室", Kind: "mac", Purpose: "main", Sig: sign(t, who.key, transcript),
	})
	ready := readJSON(t, conn)
	if ready.Type != "ready" || ready.RegistryVersion == 0 {
		t.Fatalf("ready %#v", ready)
	}
	registry := readJSON(t, conn)
	if registry.Type != "registry" || len(registry.Devices) != devices {
		t.Fatalf("registry %#v", registry)
	}
	return conn
}

func authMedia(t *testing.T, client *http.Client, base string, who identity) *websocket.Conn {
	t.Helper()
	conn := dial(t, client, wsURL(base))
	_, station, nonce := helloParts(t, conn)
	body, err := link.RelayAuthMessage(station, nonce, who.id[:], "media")
	if err != nil {
		t.Fatal(err)
	}
	send(t, conn, message{
		Type: "auth", DeviceID: hex.EncodeToString(who.id[:]), Purpose: "media", App: "test",
		Sig: sign(t, who.key, body),
	})
	if got := readJSON(t, conn); got.Type != "ready" {
		t.Fatalf("media %#v", got)
	}
	return conn
}

func unauthKeys(server *Server) []string {
	server.mu.Lock()
	defer server.mu.Unlock()
	keys := make([]string, 0, len(server.unauth))
	for key := range server.unauth {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	return keys
}

func TestMediaPurposeSealedFrameHidesBody(t *testing.T) {
	server, client, base, _ := startTLS(t)
	code, err := server.IssueEnroll()
	if err != nil {
		t.Fatal(err)
	}
	mac := newIdentity(t)
	phone := newIdentity(t)
	macMain := enrollMac(t, client, base, code, mac)
	phoneMain := joinDevice(t, server, client, base, macMain, mac, phone, "phone", "手机")
	t.Cleanup(func() {
		macMain.CloseNow()
		phoneMain.CloseNow()
	})
	macMedia, ready := authAs(t, client, base, mac, "media")
	if ready.Type != "ready" {
		t.Fatalf("mac media %#v", ready)
	}
	phoneMedia, phoneReady := authAs(t, client, base, phone, "media")
	if phoneReady.Type != "ready" {
		t.Fatalf("phone media %#v", phoneReady)
	}
	t.Cleanup(func() {
		macMedia.CloseNow()
		phoneMedia.CloseNow()
	})
	secret := []byte("media-secret-9f3a")
	if kept, err := link.AdmitApplication(true, link.Frame{Kind: link.KindMessage, Payload: secret}); err == nil || len(kept.Payload) != 0 {
		t.Fatal(err, kept.Payload)
	}
	nonce, err := link.Nonce("mac-to-phone", 1)
	if err != nil {
		t.Fatal(err)
	}
	aad := link.AAD(link.KindMessage, link.FlagEncrypted|link.FlagEnd, 0, 1)
	sealed, err := link.Seal(bytes.Repeat([]byte{7}, 32), nonce, secret, aad)
	if err != nil || bytes.Contains(sealed, secret) {
		t.Fatal(err)
	}
	raw, err := link.Encode(link.Frame{
		Kind: link.KindMessage, Flags: link.FlagEncrypted | link.FlagEnd, Peer: phone.id, Seq: 1, Payload: sealed,
	}, link.MediaPayload)
	if err != nil || bytes.Contains(raw, secret) {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := macMedia.Write(ctx, websocket.MessageBinary, raw); err != nil {
		t.Fatal(err)
	}
	deadline := time.Now().Add(5 * time.Second)
	for {
		if time.Now().After(deadline) {
			t.Fatal("frame")
		}
		readCtx, readCancel := context.WithTimeout(context.Background(), time.Until(deadline))
		frame, err := readFrame(readCtx, phoneMedia, link.MediaPayload)
		readCancel()
		if err != nil {
			t.Fatal(err)
		}
		if frame.Kind != link.KindMessage {
			continue
		}
		if bytes.Contains(frame.Payload, secret) || frame.Flags&link.FlagEncrypted == 0 {
			t.Fatalf("forwarded body %x", frame.Payload)
		}
		return
	}
}

func waitUnauth(t *testing.T, server *Server, want int) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for {
		if len(unauthKeys(server)) == want {
			return
		}
		if time.Now().After(deadline) {
			t.Fatalf("unauth %v", unauthKeys(server))
		}
		time.Sleep(20 * time.Millisecond)
	}
}

func mustHex(t *testing.T, value string) []byte {
	t.Helper()
	out, err := hex.DecodeString(value)
	if err != nil {
		t.Fatal(err)
	}
	return out
}

func mustRead(t *testing.T, name string) []byte {
	t.Helper()
	body, err := os.ReadFile(name)
	if err != nil {
		t.Fatal(err)
	}
	return body
}
