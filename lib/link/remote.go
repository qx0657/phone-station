package link

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"crypto/tls"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"
)

// RelayPath is the relay WebSocket path. The running relay 5 process does not serve it.
const RelayPath = "/v2/link"

// RelaySession is one authenticated relay link. Close ends it.
// The gateway calls DialRelayPinned after the relay 6 cutover. DialRelay stays loopback-only.
type RelaySession struct {
	ws      *wsConn
	ready   bool
	station []byte
}

// Ready reports that hello, auth, and ready have finished.
func (s *RelaySession) Ready() bool {
	return s != nil && s.ready
}

// Close ends the TLS connection. Bind transfers the socket, so Close after Bind does nothing.
func (s *RelaySession) Close() error {
	if s == nil || s.ws == nil {
		return nil
	}
	return s.ws.Close()
}

// Bind aims the authenticated socket at one peer and returns the link session.
// The relay delivers frames to that device and rewrites the peer to the sender.
// fromMac selects which side opens odd channels. The caller closes the returned session.
func (s *RelaySession) Bind(peer [16]byte, fromMac bool) *LocalSession {
	if s == nil || s.ws == nil || peer == ([16]byte{}) {
		return nil
	}
	ws := s.ws
	s.ws = nil
	s.ready = false
	session := newSession(ws, fromMac, peer)
	session.remote = true
	return session
}

// DialRelay is the device side of a relay 6 handshake. addr must be a loopback
// host:port. pin is the SHA-256 of the server certificate's SPKI. The client
// checks that name and pin before sending the upgrade request, then reads
// hello, sends auth, and waits for ready. purpose is main or media.
func DialRelay(addr string, pin []byte, self Identity, purpose, app string) (*RelaySession, error) {
	return dialRelay(addr, pin, self, purpose, app, true)
}

// DialRelayPinned is the same handshake for a literal IP. It still checks the
// certificate name and SPKI pin before sending the upgrade, and the connected
// address must be that IP. Names are rejected.
func DialRelayPinned(addr string, pin []byte, self Identity, purpose, app string) (*RelaySession, error) {
	return dialRelay(addr, pin, self, purpose, app, false)
}

// DialRelayEnroll registers this device with a one-time enroll code and leaves
// the connection as the authenticated main or media link. addr must be loopback.
// The code is not written to logs.
func DialRelayEnroll(addr string, pin []byte, self Identity, code []byte, name, purpose string) (*RelaySession, error) {
	return dialRelayEnroll(addr, pin, self, code, name, purpose, true)
}

// DialRelayEnrollPinned is DialRelayEnroll for a literal IP address.
func DialRelayEnrollPinned(addr string, pin []byte, self Identity, code []byte, name, purpose string) (*RelaySession, error) {
	return dialRelayEnroll(addr, pin, self, code, name, purpose, false)
}

// DialRelayJoin registers a phone with an invitation. It blocks until the
// inviting Mac approves. addr must be a loopback host:port. The invite key
// never leaves this process except inside the signed proof.
func DialRelayJoin(addr string, pin []byte, self Identity, invitation, inviteKey []byte, name, purpose string) (*RelaySession, error) {
	return dialRelayJoin(addr, pin, self, invitation, inviteKey, name, purpose, true)
}

// DialRelayJoinPinned is DialRelayJoin for a literal IP address.
func DialRelayJoinPinned(addr string, pin []byte, self Identity, invitation, inviteKey []byte, name, purpose string) (*RelaySession, error) {
	return dialRelayJoin(addr, pin, self, invitation, inviteKey, name, purpose, false)
}

func dialRelay(addr string, pin []byte, self Identity, purpose, app string, loopbackOnly bool) (*RelaySession, error) {
	if !purposeOK(purpose) {
		return nil, errors.New("purpose")
	}
	if app == "" || len(app) > 64 || !utf8.ValidString(app) {
		return nil, errors.New("app")
	}
	ws, err := openRelay(addr, pin, loopbackOnly)
	if err != nil {
		return nil, err
	}
	if self.Sign == nil {
		ws.Close()
		return nil, errors.New("sign")
	}
	session, err := relayHandshake(ws, self, purpose, app)
	if err != nil {
		ws.Close()
		return nil, err
	}
	ws.relax()
	return session, nil
}

func dialRelayEnroll(addr string, pin []byte, self Identity, code []byte, name, purpose string, loopbackOnly bool) (*RelaySession, error) {
	if self.Sign == nil {
		return nil, errors.New("sign")
	}
	if !purposeOK(purpose) {
		return nil, errors.New("purpose")
	}
	if len(code) != 16 || !enrollNameOK(name) || len(self.SPKI) == 0 || !bytes.Equal(DeviceID(self.SPKI), self.ID[:]) {
		return nil, errors.New("enroll")
	}
	ws, err := openRelay(addr, pin, loopbackOnly)
	if err != nil {
		return nil, err
	}
	session, err := relayEnroll(ws, self, code, name, purpose)
	if err != nil {
		ws.Close()
		return nil, err
	}
	ws.relax()
	return session, nil
}

func openRelay(addr string, pin []byte, loopbackOnly bool) (*wsConn, error) {
	var host string
	var err error
	if loopbackOnly {
		host, err = relayHost(addr)
	} else {
		host, err = RelayAddress(addr)
	}
	if err != nil {
		return nil, err
	}
	if len(pin) != sha256.Size {
		return nil, errors.New("pin")
	}
	dialer := &net.Dialer{Timeout: 5 * time.Second}
	// The SPKI pin replaces a CA pool. Nothing is sent until the name and pin match.
	conn, err := tls.DialWithDialer(dialer, "tcp", addr, &tls.Config{
		MinVersion:         tls.VersionTLS12,
		InsecureSkipVerify: true,
		ServerName:         host,
	})
	if err != nil {
		return nil, err
	}
	peer, _, peerErr := net.SplitHostPort(conn.RemoteAddr().String())
	if peerErr != nil || peer != host {
		conn.Close()
		return nil, errors.New("host")
	}
	if err := checkRelayCert(conn.ConnectionState(), host, pin); err != nil {
		conn.Close()
		return nil, err
	}
	ws, err := dialRelayWS(conn, addr)
	if err != nil {
		conn.Close()
		return nil, err
	}
	return ws, nil
}

func enrollNameOK(name string) bool {
	count := utf8.RuneCountInString(name)
	if count < 1 || count > 64 || !utf8.ValidString(name) {
		return false
	}
	for _, r := range name {
		if unicode.IsControl(r) || unicode.Is(unicode.Cf, r) {
			return false
		}
	}
	return true
}

func relayHost(addr string) (string, error) {
	host, err := RelayAddress(addr)
	if err != nil {
		return "", err
	}
	if ip := net.ParseIP(host); ip == nil || !ip.IsLoopback() {
		return "", errors.New("host")
	}
	return host, nil
}

// RelayAddress accepts a literal IP and port. Names, empty hosts, unspecified
// addresses and multicast addresses are rejected. It does not open a socket.
// DialRelay still requires a loopback address.
func RelayAddress(addr string) (string, error) {
	host, port, err := net.SplitHostPort(addr)
	if err != nil || !portOK(port) {
		return "", errors.New("host")
	}
	ip := net.ParseIP(host)
	if ip == nil || ip.IsUnspecified() || ip.IsMulticast() {
		return "", errors.New("host")
	}
	return ip.String(), nil
}

func checkRelayCert(state tls.ConnectionState, host string, pin []byte) error {
	if len(state.PeerCertificates) == 0 {
		return errors.New("pin")
	}
	leaf := state.PeerCertificates[0]
	if leaf.VerifyHostname(host) != nil {
		return errors.New("name")
	}
	sum := sha256.Sum256(leaf.RawSubjectPublicKeyInfo)
	if subtle.ConstantTimeCompare(sum[:], pin) != 1 {
		return errors.New("pin")
	}
	return nil
}

func dialRelayWS(conn net.Conn, addr string) (*wsConn, error) {
	_ = conn.SetDeadline(time.Now().Add(5 * time.Second))
	key := make([]byte, 16)
	if _, err := rand.Read(key); err != nil {
		return nil, err
	}
	nonce := base64.StdEncoding.EncodeToString(key)
	req := "GET " + RelayPath + " HTTP/1.1\r\n" +
		"Host: " + addr + "\r\n" +
		"Upgrade: websocket\r\n" +
		"Connection: Upgrade\r\n" +
		"Sec-WebSocket-Key: " + nonce + "\r\n" +
		"Sec-WebSocket-Version: 13\r\n\r\n"
	if _, err := io.WriteString(conn, req); err != nil {
		return nil, err
	}
	ws := newWS(conn, true)
	status, headers, err := readHead(ws.r)
	if err != nil {
		return nil, err
	}
	if !strings.HasPrefix(status, "HTTP/1.1 101 ") {
		return nil, errors.New(statusCode(status))
	}
	if !headerHas(headers, "Upgrade", "websocket") || !headerHas(headers, "Connection", "upgrade") {
		return nil, errors.New("upgrade")
	}
	if headers["sec-websocket-accept"] != wsAccept(nonce) {
		return nil, errors.New("accept")
	}
	return ws, nil
}

func relayHandshake(ws *wsConn, self Identity, purpose, app string) (*RelaySession, error) {
	hello, err := readRelay(ws)
	if err != nil {
		return nil, err
	}
	if code, bad := hello.failure(); bad {
		return nil, &Failure{Code: code}
	}
	station, nonce, err := hello.hello()
	if err != nil {
		return nil, err
	}
	transcript, err := RelayAuthMessage(station, nonce, self.ID[:], purpose)
	if err != nil {
		return nil, err
	}
	der, err := self.Sign(transcript)
	if err != nil || len(der) == 0 {
		return nil, errors.New("sign")
	}
	body, err := json.Marshal(relayMessage{
		Type:     "auth",
		DeviceID: hex.EncodeToString(self.ID[:]),
		Purpose:  purpose,
		App:      app,
		Sig:      hex.EncodeToString(der),
	})
	if err != nil {
		return nil, err
	}
	if err := ws.WriteFrame(Frame{Kind: KindLink, Payload: body}); err != nil {
		return nil, err
	}
	reply, err := readRelay(ws)
	if err != nil {
		return nil, err
	}
	if code, bad := reply.failure(); bad {
		return nil, &Failure{Code: code}
	}
	if !reply.ready() {
		return nil, errors.New("ready")
	}
	return &RelaySession{ws: ws, ready: true, station: append([]byte(nil), station...)}, nil
}

func relayEnroll(ws *wsConn, self Identity, code []byte, name, purpose string) (*RelaySession, error) {
	hello, err := readRelay(ws)
	if err != nil {
		return nil, err
	}
	if codeName, bad := hello.failure(); bad {
		return nil, &Failure{Code: codeName}
	}
	station, nonce, err := hello.hello()
	if err != nil {
		return nil, err
	}
	transcript, err := EnrollMessage(station, nonce, self.ID[:], code, purpose)
	if err != nil {
		return nil, err
	}
	der, err := self.Sign(transcript)
	if err != nil || len(der) == 0 {
		return nil, errors.New("sign")
	}
	body, err := json.Marshal(relayMessage{
		Type:      "enroll",
		Code:      hex.EncodeToString(code),
		PublicKey: base64.StdEncoding.EncodeToString(self.SPKI),
		Name:      name,
		Kind:      "mac",
		Purpose:   purpose,
		Sig:       hex.EncodeToString(der),
	})
	if err != nil {
		return nil, err
	}
	if err := ws.WriteFrame(Frame{Kind: KindLink, Payload: body}); err != nil {
		return nil, err
	}
	reply, err := readRelay(ws)
	if err != nil {
		return nil, err
	}
	if codeName, bad := reply.enrollFailure(); bad {
		return nil, &Failure{Code: codeName}
	}
	if !reply.ready() {
		return nil, errors.New("ready")
	}
	return &RelaySession{ws: ws, ready: true, station: append([]byte(nil), station...)}, nil
}

func dialRelayJoin(addr string, pin []byte, self Identity, invitation, inviteKey []byte, name, purpose string, loopbackOnly bool) (*RelaySession, error) {
	if self.Sign == nil || len(invitation) != 16 || len(inviteKey) != 16 || len(self.SPKI) == 0 || !bytes.Equal(DeviceID(self.SPKI), self.ID[:]) {
		return nil, errors.New("join")
	}
	if !purposeOK(purpose) || !proofNameOK(name) {
		return nil, errors.New("join")
	}
	ws, err := openRelay(addr, pin, loopbackOnly)
	if err != nil {
		return nil, err
	}
	session, err := relayJoin(ws, self, invitation, inviteKey, name, purpose)
	if err != nil {
		ws.Close()
		return nil, err
	}
	ws.relax()
	return session, nil
}

func relayJoin(ws *wsConn, self Identity, invitation, inviteKey []byte, name, purpose string) (*RelaySession, error) {
	hello, err := readRelay(ws)
	if err != nil {
		return nil, err
	}
	if codeName, bad := hello.joinFailure(); bad {
		return nil, &Failure{Code: codeName}
	}
	station, nonce, err := hello.hello()
	if err != nil {
		return nil, err
	}
	transcript, err := JoinMessage(station, nonce, self.ID[:], invitation, purpose)
	if err != nil {
		return nil, err
	}
	der, err := self.Sign(transcript)
	if err != nil || len(der) == 0 {
		return nil, errors.New("sign")
	}
	proof, err := InvitationProof(inviteKey, self.SPKI, name, "phone", purpose)
	if err != nil {
		return nil, err
	}
	body, err := json.Marshal(relayMessage{
		Type:         "join",
		InvitationID: hex.EncodeToString(invitation),
		PublicKey:    base64.StdEncoding.EncodeToString(self.SPKI),
		Name:         name,
		Kind:         "phone",
		Purpose:      purpose,
		Proof:        hex.EncodeToString(proof),
		Sig:          hex.EncodeToString(der),
	})
	if err != nil {
		return nil, err
	}
	if err := ws.WriteFrame(Frame{Kind: KindLink, Payload: body}); err != nil {
		return nil, err
	}
	_ = ws.c.SetReadDeadline(time.Now().Add(25 * time.Second))
	reply, err := readRelay(ws)
	_ = ws.c.SetReadDeadline(time.Time{})
	if err != nil {
		return nil, err
	}
	if codeName, bad := reply.joinFailure(); bad {
		return nil, &Failure{Code: codeName}
	}
	if !reply.ready() {
		return nil, errors.New("ready")
	}
	return &RelaySession{ws: ws, ready: true, station: append([]byte(nil), station...)}, nil
}

// Invite asks the relay for one invitation id. The relay does not learn the key.
func (s *RelaySession) Invite(kind string) (string, error) {
	if s == nil || s.ws == nil || (kind != "phone" && kind != "mac") {
		return "", errors.New("invite")
	}
	body, err := json.Marshal(relayMessage{Type: "invite", Kind: kind})
	if err != nil {
		return "", err
	}
	if err := s.ws.WriteFrame(Frame{Kind: KindLink, Payload: body}); err != nil {
		return "", err
	}
	deadline := time.Now().Add(5 * time.Second)
	for {
		msg, err := s.readControl(deadline)
		if err != nil {
			return "", err
		}
		if msg.Type == "invite" && len(msg.InvitationID) == 32 && msg.Kind == kind {
			if _, err := hex.DecodeString(msg.InvitationID); err != nil {
				return "", errors.New("invite")
			}
			return msg.InvitationID, nil
		}
		if codeName, bad := msg.joinFailure(); bad {
			return "", &Failure{Code: codeName}
		}
	}
}

// OfferPhone sends an invitation over deliver, then approves the paired phone.
// deliver must return without waiting for the phone to finish joining.
// A different device, or a proof that does not match, is rejected.
func (s *RelaySession) OfferPhone(ctx context.Context, self Identity, peer PeerKey, endpoint, pin string, deliver func(string) error) error {
	if s == nil || s.ws == nil || self.Sign == nil || deliver == nil || len(s.station) != 16 {
		return errors.New("invite")
	}
	if len(peer.SPKI) == 0 || !bytes.Equal(DeviceID(peer.SPKI), peer.ID[:]) {
		return errors.New("invite")
	}
	key := make([]byte, 16)
	if _, err := rand.Read(key); err != nil {
		return err
	}
	id, err := s.Invite("phone")
	if err != nil {
		return err
	}
	text, err := InviteText(Invite{
		Endpoint: endpoint,
		Pin:      strings.ToLower(pin),
		ID:       id,
		Key:      hex.EncodeToString(key),
		SPKI:     base64.StdEncoding.EncodeToString(self.SPKI),
		Kind:     "phone",
	})
	if err != nil {
		return err
	}
	if err := deliver(text); err != nil {
		return err
	}
	deadline := time.Now().Add(25 * time.Second)
	if ctxDeadline, ok := ctx.Deadline(); ok && ctxDeadline.Before(deadline) {
		deadline = ctxDeadline
	}
	for {
		if err := ctx.Err(); err != nil {
			return err
		}
		msg, err := s.readControl(deadline)
		if err != nil {
			return err
		}
		if msg.Type != "join_request" || msg.InvitationID != id {
			continue
		}
		if err := joinMatches(key, peer, msg); err != nil {
			_ = s.reject(msg.InvitationID, msg.DeviceID)
			return err
		}
		return s.approve(self, id, msg.DeviceID)
	}
}

func (s *RelaySession) readControl(deadline time.Time) (relayMessage, error) {
	if err := s.ws.c.SetReadDeadline(deadline); err != nil {
		return relayMessage{}, err
	}
	defer s.ws.c.SetReadDeadline(time.Time{})
	msg, err := readRelay(s.ws)
	if err != nil {
		var netErr net.Error
		if errors.As(err, &netErr) && netErr.Timeout() {
			return relayMessage{}, errors.New("timeout")
		}
		return relayMessage{}, err
	}
	return msg, nil
}

func (s *RelaySession) approve(self Identity, invitationID, deviceID string) error {
	invitation, err := hex.DecodeString(invitationID)
	device, err2 := hex.DecodeString(deviceID)
	if err != nil || err2 != nil || len(invitation) != 16 || len(device) != 16 {
		return errors.New("rejected")
	}
	transcript, err := ApproveMessage(s.station, invitation, device)
	if err != nil {
		return err
	}
	der, err := self.Sign(transcript)
	if err != nil || len(der) == 0 {
		return errors.New("sign")
	}
	body, err := json.Marshal(relayMessage{
		Type:         "approve",
		InvitationID: invitationID,
		DeviceID:     deviceID,
		Sig:          hex.EncodeToString(der),
	})
	if err != nil {
		return err
	}
	return s.ws.WriteFrame(Frame{Kind: KindLink, Payload: body})
}

func (s *RelaySession) reject(invitationID, deviceID string) error {
	body, err := json.Marshal(relayMessage{Type: "reject", InvitationID: invitationID, DeviceID: deviceID})
	if err != nil {
		return err
	}
	return s.ws.WriteFrame(Frame{Kind: KindLink, Payload: body})
}

func joinMatches(key []byte, peer PeerKey, msg relayMessage) error {
	if msg.Kind != "phone" || msg.Purpose != "main" || !proofNameOK(msg.Name) {
		return errors.New("rejected")
	}
	spki, err := base64.StdEncoding.DecodeString(msg.PublicKey)
	got, err2 := hex.DecodeString(msg.Proof)
	want, err3 := InvitationProof(key, peer.SPKI, msg.Name, msg.Kind, msg.Purpose)
	id, err4 := hex.DecodeString(msg.DeviceID)
	if err != nil || err2 != nil || err3 != nil || err4 != nil || len(id) != 16 {
		return errors.New("rejected")
	}
	if !bytes.Equal(spki, peer.SPKI) || !bytes.Equal(id, peer.ID[:]) || subtle.ConstantTimeCompare(want, got) != 1 {
		return errors.New("rejected")
	}
	return nil
}

type relayPeer struct {
	DeviceID string `json:"deviceId"`
	Purpose  string `json:"purpose"`
}

type relayMessage struct {
	Type            string        `json:"type"`
	Protocol        int           `json:"protocol,omitempty"`
	StationID       string        `json:"stationId,omitempty"`
	Nonce           string        `json:"nonce,omitempty"`
	DeviceID        string        `json:"deviceId,omitempty"`
	Purpose         string        `json:"purpose,omitempty"`
	App             string        `json:"app,omitempty"`
	Sig             string        `json:"sig,omitempty"`
	Code            string        `json:"code,omitempty"`
	PublicKey       string        `json:"publicKey,omitempty"`
	Name            string        `json:"name,omitempty"`
	Kind            string        `json:"kind,omitempty"`
	InvitationID    string        `json:"invitationId,omitempty"`
	Proof           string        `json:"proof,omitempty"`
	Expires         string        `json:"expires,omitempty"`
	Online          *bool         `json:"online,omitempty"`
	RegistryVersion uint64        `json:"registryVersion,omitempty"`
	Peers           []relayPeer   `json:"peers,omitempty"`
	Devices         []relayDevice `json:"devices,omitempty"`
}

type relayDevice struct {
	DeviceID  string `json:"deviceId"`
	PublicKey string `json:"publicKey"`
	Name      string `json:"name"`
	Kind      string `json:"kind"`
	Revoked   bool   `json:"revoked"`
}

func readRelay(ws *wsConn) (relayMessage, error) {
	frame, err := ws.ReadFrame()
	if err != nil {
		return relayMessage{}, err
	}
	if frame.Kind != KindLink || len(frame.Payload) == 0 || len(frame.Payload) > 4096 || !utf8.Valid(frame.Payload) {
		return relayMessage{}, errors.New("rejected")
	}
	if rejectLocalDuplicate(frame.Payload) != nil {
		return relayMessage{}, errors.New("rejected")
	}
	dec := json.NewDecoder(bytes.NewReader(frame.Payload))
	dec.DisallowUnknownFields()
	var msg relayMessage
	if err := dec.Decode(&msg); err != nil || dec.More() {
		return relayMessage{}, errors.New("rejected")
	}
	return msg, nil
}

func (m relayMessage) hello() ([]byte, []byte, error) {
	if m.Type != "hello" || m.Protocol != 2 || m.Code != "" || m.Sig != "" || m.DeviceID != "" {
		return nil, nil, errors.New("rejected")
	}
	station, err := parseHex(m.StationID, 16)
	if err != nil {
		return nil, nil, err
	}
	nonce, err := parseNonce(m.Nonce)
	if err != nil {
		return nil, nil, err
	}
	return station, nonce, nil
}

func (m relayMessage) ready() bool {
	return m.Type == "ready" && m.Protocol == 0 && m.Code == "" && m.Sig == "" && m.DeviceID == ""
}

func (m relayMessage) failure() (string, bool) {
	if m.Type != "error" || m.Protocol != 0 || m.DeviceID != "" || m.Sig != "" {
		return "", false
	}
	switch m.Code {
	case "unknown_device", "bad_signature", "revoked":
		return m.Code, true
	default:
		return "", false
	}
}

func (m relayMessage) enrollFailure() (string, bool) {
	return m.joinFailure()
}

func (m relayMessage) joinFailure() (string, bool) {
	if code, bad := m.failure(); bad {
		return code, true
	}
	if m.Type != "error" || m.Protocol != 0 || m.DeviceID != "" || m.Sig != "" {
		return "", false
	}
	switch m.Code {
	case "rejected", "rate_limited", "unavailable", "undeliverable":
		return m.Code, true
	default:
		return "", false
	}
}
