package link

import (
	"bytes"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"net"
	"strings"
	"sync"
	"time"
	"unicode/utf8"
)

// LocalPath is the phone loopback WebSocket path.
const LocalPath = "/v2/link"

// LocalAddress is the phone endpoint. Tests listen on an ephemeral port of 127.0.0.1
// and do not bind this port, so a running phone service is left alone.
const LocalAddress = "ws://127.0.0.1:8765" + LocalPath

// Failure is a handshake refusal. Code is unknown_device, bad_signature, or revoked.
type Failure struct {
	Code string
}

func (f *Failure) Error() string {
	if f == nil {
		return ""
	}
	return f.Code
}

// Identity is one endpoint of the local handshake. Sign produces a DER ECDSA-SHA256 signature.
// The phone service passes PhoneDeviceKey when it is wired up; tests pass an in-memory P-256 key.
type Identity struct {
	ID   [16]byte
	SPKI []byte
	Sign func([]byte) ([]byte, error)
}

// PeerKey is the other device's recorded public key.
// Seal is true only when this pair requires encryption on the relay.
// The local link ignores it.
type PeerKey struct {
	ID   [16]byte
	SPKI []byte
	Seal bool
}

// LocalSession is one authenticated local link. Closing it ends the session.
type LocalSession struct {
	peer   [16]byte
	engine *Endpoint
	mux    *Link
	io     *wsConn

	mu     sync.Mutex
	ready  bool
	ended  bool
	remote bool
	codec  *sealCodec

	engineMu     sync.Mutex
	inboxOnce    sync.Once
	inbox        sessionInbox
	shareChannel int
	shareDir     string
}

// Peer is the other device's id.
func (s *LocalSession) Peer() [16]byte {
	return s.peer
}

// Ready reports that hello, auth, and ready have finished.
func (s *LocalSession) Ready() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.ready
}

// Ended reports that the connection has closed.
func (s *LocalSession) Ended() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.ended
}

// Engine is the session engine bound to the peer. Local control frames stay outside it.
func (s *LocalSession) Engine() *Endpoint {
	return s.engine
}

// Mux pulls the next frame for this one session.
func (s *LocalSession) Mux() *Link {
	return s.mux
}

// Send queues one channel-0 message and writes the frames the mux pulls.
func (s *LocalSession) Send(payload []byte) error {
	if s.Ended() {
		return errors.New("ended")
	}
	s.engineMu.Lock()
	defer s.engineMu.Unlock()
	if err := s.engine.SendMessage(PriorityControl, 0, KindMessage, payload); err != nil {
		return err
	}
	return s.flush()
}

// Serve reads frames into the engine until the connection ends.
func (s *LocalSession) Serve() error {
	defer s.markEnded()
	for {
		frame, err := s.io.ReadFrame()
		if err != nil {
			return err
		}
		if err := s.engine.Accept(frame); err != nil && fatalLink(err) {
			return err
		}
		if err := s.flush(); err != nil {
			return err
		}
	}
}

// Close ends the session and the TCP connection.
func (s *LocalSession) Close() error {
	s.markEnded()
	return s.io.Close()
}

func (s *LocalSession) markEnded() {
	s.mu.Lock()
	s.ended = true
	s.mu.Unlock()
}

func (s *LocalSession) flush() error {
	for {
		frame, ok := s.mux.Pull()
		if !ok {
			if err := s.engine.Fail(); err != nil {
				s.markEnded()
				return err
			}
			return nil
		}
		if err := s.io.WriteFrame(frame); err != nil {
			s.markEnded()
			return err
		}
	}
}

// LocalListener is the phone side of the local link. It binds 127.0.0.1 only.
type LocalListener struct {
	ln    net.Listener
	self  Identity
	allow []PeerKey
}

// ListenLocal listens on 127.0.0.1 and an ephemeral port.
func ListenLocal(self Identity, allow []PeerKey) (*LocalListener, error) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return nil, err
	}
	return &LocalListener{ln: ln, self: self, allow: append([]PeerKey(nil), allow...)}, nil
}

// Addr is host:port.
func (l *LocalListener) Addr() string {
	return l.ln.Addr().String()
}

// Close stops accepting.
func (l *LocalListener) Close() error {
	return l.ln.Close()
}

// Accept completes one Mac handshake. The running MCP service does not call this.
func (l *LocalListener) Accept() (*LocalSession, error) {
	conn, err := l.ln.Accept()
	if err != nil {
		return nil, err
	}
	ws, err := acceptWS(conn)
	if err != nil {
		conn.Close()
		return nil, err
	}
	session, err := phoneHandshake(ws, l.self, l.allow)
	if err != nil {
		ws.Close()
		return nil, err
	}
	ws.relax()
	return session, nil
}

// DialLocal is the Mac side. host:port must be the phone listener.
func DialLocal(addr string, self Identity, phone PeerKey) (*LocalSession, error) {
	ws, err := dialWS(addr)
	if err != nil {
		return nil, err
	}
	session, err := macHandshake(ws, self, phone)
	if err != nil {
		ws.Close()
		return nil, err
	}
	ws.relax()
	return session, nil
}

// AdmitLocal rejects anything except GET /v2/link from a loopback Host without Origin.
func AdmitLocal(method, target, host string, origin bool) error {
	if method != httpGET {
		return &Failure{Code: "method"}
	}
	path, query, _ := strings.Cut(target, "?")
	if path != LocalPath || query != "" {
		return &Failure{Code: "path"}
	}
	if !loopbackHost(host) {
		return &Failure{Code: "host"}
	}
	if origin {
		return &Failure{Code: "origin"}
	}
	return nil
}

const httpGET = "GET"

func loopbackHost(host string) bool {
	host = strings.TrimSpace(host)
	if host == "" {
		return false
	}
	if strings.HasPrefix(host, "[") {
		end := strings.IndexByte(host, ']')
		if end <= 1 {
			return false
		}
		name := host[1:end]
		rest := host[end+1:]
		if rest != "" && !portSuffix(rest) {
			return false
		}
		ip := net.ParseIP(name)
		return ip != nil && ip.IsLoopback()
	}
	name := host
	if h, port, err := net.SplitHostPort(host); err == nil {
		if !portOK(port) {
			return false
		}
		name = h
	}
	if strings.EqualFold(name, "localhost") {
		return true
	}
	ip := net.ParseIP(name)
	return ip != nil && ip.IsLoopback()
}

func portSuffix(rest string) bool {
	if !strings.HasPrefix(rest, ":") {
		return false
	}
	return portOK(rest[1:])
}

func portOK(port string) bool {
	if port == "" || len(port) > 5 {
		return false
	}
	n := 0
	for _, c := range port {
		if c < '0' || c > '9' {
			return false
		}
		n = n*10 + int(c-'0')
	}
	return n > 0 && n <= 65535
}

type localMessage struct {
	Type     string `json:"type"`
	Protocol int    `json:"protocol,omitempty"`
	DeviceID string `json:"deviceId,omitempty"`
	Nonce    string `json:"nonce,omitempty"`
	Sig      string `json:"sig,omitempty"`
	Code     string `json:"code,omitempty"`
}

func phoneHandshake(ws *wsConn, self Identity, allow []PeerKey) (*LocalSession, error) {
	if self.Sign == nil {
		return nil, errors.New("sign")
	}
	msg, err := readControl(ws)
	if err != nil {
		return nil, err
	}
	macID, macNonce, err := msg.macHello()
	if err != nil {
		return nil, err
	}
	peer, ok := findPeer(allow, macID)
	if !ok {
		_ = writeControl(ws, localMessage{Type: "error", Code: "unknown_device"})
		return nil, &Failure{Code: "unknown_device"}
	}
	nonce, err := randomNonce()
	if err != nil {
		return nil, err
	}
	sig, err := signLocal(self.Sign, macID[:], macNonce, self.ID[:])
	if err != nil {
		return nil, err
	}
	if err := writeControl(ws, localMessage{
		Type: "hello", Protocol: 2, DeviceID: hex.EncodeToString(self.ID[:]), Nonce: hex.EncodeToString(nonce), Sig: sig,
	}); err != nil {
		return nil, err
	}
	auth, err := readControl(ws)
	if err != nil {
		return nil, err
	}
	if code, bad := auth.failure(); bad {
		return nil, &Failure{Code: code}
	}
	der, err := auth.authSig()
	if err != nil {
		return nil, err
	}
	if err := verifyLocal(peer.SPKI, self.ID[:], nonce, macID[:], der); err != nil {
		_ = writeControl(ws, localMessage{Type: "error", Code: "bad_signature"})
		return nil, err
	}
	if err := writeControl(ws, localMessage{Type: "ready"}); err != nil {
		return nil, err
	}
	return newSession(ws, false, macID), nil
}

func macHandshake(ws *wsConn, self Identity, phone PeerKey) (*LocalSession, error) {
	if self.Sign == nil {
		return nil, errors.New("sign")
	}
	nonce, err := randomNonce()
	if err != nil {
		return nil, err
	}
	if err := writeControl(ws, localMessage{
		Type: "hello", Protocol: 2, DeviceID: hex.EncodeToString(self.ID[:]), Nonce: hex.EncodeToString(nonce),
	}); err != nil {
		return nil, err
	}
	msg, err := readControl(ws)
	if err != nil {
		return nil, err
	}
	if code, bad := msg.failure(); bad {
		return nil, &Failure{Code: code}
	}
	phoneID, phoneNonce, sig, err := msg.phoneHello()
	if err != nil {
		return nil, err
	}
	if phoneID != phone.ID {
		return nil, &Failure{Code: "bad_signature"}
	}
	if err := verifyLocal(phone.SPKI, self.ID[:], nonce, phoneID[:], sig); err != nil {
		return nil, err
	}
	auth, err := signLocal(self.Sign, phoneID[:], phoneNonce, self.ID[:])
	if err != nil {
		return nil, err
	}
	if err := writeControl(ws, localMessage{Type: "auth", Sig: auth}); err != nil {
		return nil, err
	}
	ready, err := readControl(ws)
	if err != nil {
		return nil, err
	}
	if code, bad := ready.failure(); bad {
		return nil, &Failure{Code: code}
	}
	if !ready.ready() {
		return nil, errors.New("ready")
	}
	return newSession(ws, true, phoneID), nil
}

func newSession(ws *wsConn, fromMac bool, peer [16]byte) *LocalSession {
	now := time.Now()
	engine := NewEndpoint(fromMac, 0, 0, 0, now)
	engine.SetPeer(peer)
	return &LocalSession{
		peer:   peer,
		engine: engine,
		mux:    NewLink(now, engine),
		io:     ws,
		ready:  true,
	}
}

func findPeer(allow []PeerKey, id [16]byte) (PeerKey, bool) {
	for _, peer := range allow {
		if peer.ID == id {
			return peer, true
		}
	}
	return PeerKey{}, false
}

func randomNonce() ([]byte, error) {
	nonce := make([]byte, 32)
	_, err := rand.Read(nonce)
	return nonce, err
}

func signLocal(sign func([]byte) ([]byte, error), peerID, nonce, ownID []byte) (string, error) {
	message, err := LocalAuthMessage(peerID, nonce, ownID)
	if err != nil {
		return "", err
	}
	der, err := sign(message)
	if err != nil || len(der) == 0 {
		return "", errors.New("sign")
	}
	return hex.EncodeToString(der), nil
}

func verifyLocal(spki, peerID, nonce, ownID, sig []byte) error {
	message, err := LocalAuthMessage(peerID, nonce, ownID)
	if err != nil {
		return err
	}
	if VerifySignature(spki, message, sig) != nil {
		return &Failure{Code: "bad_signature"}
	}
	return nil
}

func readControl(ws *wsConn) (localMessage, error) {
	frame, err := ws.ReadFrame()
	if err != nil {
		return localMessage{}, err
	}
	if frame.Kind != KindLink || len(frame.Payload) == 0 || len(frame.Payload) > 4096 {
		return localMessage{}, errors.New("rejected")
	}
	return parseLocal(frame.Payload)
}

func writeControl(ws *wsConn, msg localMessage) error {
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	if err := enc.Encode(msg); err != nil {
		return err
	}
	payload := bytes.TrimRight(buf.Bytes(), "\n")
	return ws.WriteFrame(Frame{Kind: KindLink, Payload: payload})
}

func parseLocal(payload []byte) (localMessage, error) {
	if !utf8.Valid(payload) || rejectLocalDuplicate(payload) != nil {
		return localMessage{}, errors.New("rejected")
	}
	dec := json.NewDecoder(bytes.NewReader(payload))
	dec.DisallowUnknownFields()
	var msg localMessage
	if err := dec.Decode(&msg); err != nil || dec.More() {
		return localMessage{}, errors.New("rejected")
	}
	return msg, nil
}

func rejectLocalDuplicate(payload []byte) error {
	dec := json.NewDecoder(bytes.NewReader(payload))
	token, err := dec.Token()
	if err != nil || token != json.Delim('{') {
		return errors.New("rejected")
	}
	seen := map[string]bool{}
	for dec.More() {
		keyToken, err := dec.Token()
		key, ok := keyToken.(string)
		if err != nil || !ok || seen[key] {
			return errors.New("rejected")
		}
		seen[key] = true
		var skip json.RawMessage
		if err := dec.Decode(&skip); err != nil {
			return errors.New("rejected")
		}
	}
	token, err = dec.Token()
	if err != nil || token != json.Delim('}') {
		return errors.New("rejected")
	}
	return nil
}

func (m localMessage) macHello() ([16]byte, []byte, error) {
	if m.Type != "hello" || m.Protocol != 2 || m.Sig != "" || m.Code != "" {
		return [16]byte{}, nil, errors.New("rejected")
	}
	id, err := parseID(m.DeviceID)
	if err != nil {
		return id, nil, err
	}
	nonce, err := parseNonce(m.Nonce)
	return id, nonce, err
}

func (m localMessage) phoneHello() ([16]byte, []byte, []byte, error) {
	if m.Type != "hello" || m.Protocol != 2 || m.Code != "" {
		return [16]byte{}, nil, nil, errors.New("rejected")
	}
	id, err := parseID(m.DeviceID)
	if err != nil {
		return id, nil, nil, err
	}
	nonce, err := parseNonce(m.Nonce)
	if err != nil {
		return id, nil, nil, err
	}
	sig, err := parseSig(m.Sig)
	return id, nonce, sig, err
}

func (m localMessage) authSig() ([]byte, error) {
	if m.Type != "auth" || m.Protocol != 0 || m.DeviceID != "" || m.Nonce != "" || m.Code != "" {
		return nil, errors.New("rejected")
	}
	return parseSig(m.Sig)
}

func (m localMessage) ready() bool {
	return m.Type == "ready" && m.Protocol == 0 && m.DeviceID == "" && m.Nonce == "" && m.Sig == "" && m.Code == ""
}

func (m localMessage) failure() (string, bool) {
	if m.Type != "error" || m.Protocol != 0 || m.DeviceID != "" || m.Nonce != "" || m.Sig != "" {
		return "", false
	}
	if m.Code != "unknown_device" && m.Code != "bad_signature" {
		return "", false
	}
	return m.Code, true
}

func parseID(text string) ([16]byte, error) {
	var id [16]byte
	raw, err := parseHex(text, 16)
	if err != nil {
		return id, err
	}
	copy(id[:], raw)
	return id, nil
}

func parseNonce(text string) ([]byte, error) {
	return parseHex(text, 32)
}

func parseSig(text string) ([]byte, error) {
	if len(text) < 16 || len(text) > 160 {
		return nil, errors.New("rejected")
	}
	return parseHex(text, 0)
}

func parseHex(text string, size int) ([]byte, error) {
	if text == "" || len(text)%2 != 0 || text != strings.ToLower(text) {
		return nil, errors.New("rejected")
	}
	if size > 0 && len(text) != size*2 {
		return nil, errors.New("rejected")
	}
	raw, err := hex.DecodeString(text)
	if err != nil {
		return nil, errors.New("rejected")
	}
	return raw, nil
}
