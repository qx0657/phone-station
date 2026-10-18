package v6

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/coder/websocket"
	"phone-station/link"
)

// Server is the relay 6 link endpoint. cmd/phone-relay serves it. The process that is running on the public host is still relay 5 until that host is switched.
type Server struct {
	reg        *registry
	station    []byte
	stationHex string
	log        io.Writer
	now        func() time.Time
	idle       time.Duration
	mu         sync.Mutex
	links      map[string]*session
	pending    map[string]*pendingJoin
	failures   map[string][]time.Time
	unauth     map[string]int
	trusted    map[string]struct{}
	closed     bool
	tap        func([]byte)
}

const (
	mainQueueBytes  = 4 << 20
	mediaQueueBytes = 8 << 20
	maxUnauthPerIP  = 16
)

var errQueueFull = errors.New("queue full")

type session struct {
	id        [16]byte
	hexID     string
	purpose   string
	conn      *websocket.Conn
	limit     int
	queueMax  int
	writeHold <-chan struct{}

	qmu       sync.Mutex
	pending   [][]byte
	queued    int
	dead      bool
	wake      chan struct{}
	stop      chan struct{}
	stopOnce  sync.Once
	closeOnce sync.Once
}

type pendingJoin struct {
	invitation string
	inviter    string
	deviceID   string
	spki       []byte
	name       string
	kind       string
	purpose    string
	result     chan error
}

func Open(dir string) (*Server, error) {
	reg, err := openRegistry(dir)
	if err != nil {
		return nil, err
	}
	raw, err := hex.DecodeString(reg.data.StationID)
	if err != nil {
		reg.close()
		return nil, err
	}
	return &Server{
		reg:        reg,
		station:    raw,
		stationHex: reg.data.StationID,
		links:      map[string]*session{},
		pending:    map[string]*pendingJoin{},
		failures:   map[string][]time.Time{},
		unauth:     map[string]int{},
	}, nil
}

func (s *Server) Close() error {
	s.mu.Lock()
	s.closed = true
	sessions := make([]*session, 0, len(s.links))
	for _, sess := range s.links {
		sessions = append(sessions, sess)
	}
	s.mu.Unlock()
	for _, sess := range sessions {
		sess.close()
	}
	s.reg.close()
	return nil
}

// SetTrustedProxies lists TCP peers whose X-Forwarded-For header supplies the client address.
// The default is empty: the header is ignored, so everyone behind one proxy shares that proxy's counters.
func (s *Server) SetTrustedProxies(addrs []string) {
	next := make(map[string]struct{}, len(addrs))
	for _, addr := range addrs {
		if net.ParseIP(addr) != nil {
			next[addr] = struct{}{}
		}
	}
	s.mu.Lock()
	s.trusted = next
	s.mu.Unlock()
}

// ParseTrustedProxies splits the comma-separated list intended for PHONE_RELAY_TRUSTED_PROXIES.
// Blank pieces are skipped. A piece that is not an IP is an error, so a typo does not silently drop that proxy.
// cmd/phone-relay reads PHONE_RELAY_TRUSTED_PROXIES. An empty value leaves the header ignored.
func ParseTrustedProxies(text string) ([]string, error) {
	if strings.TrimSpace(text) == "" {
		return nil, nil
	}
	var out []string
	for _, part := range strings.Split(text, ",") {
		part = strings.TrimSpace(part)
		if part == "" {
			continue
		}
		if net.ParseIP(part) == nil {
			return nil, fmt.Errorf("trusted proxy %q", part)
		}
		out = append(out, part)
	}
	return out, nil
}

func (s *Server) IssueEnroll() (string, error) {
	return s.reg.issueEnroll(s.clock())
}

func (s *Server) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path != "/v2/link" || r.URL.RawQuery != "" {
		http.NotFound(w, r)
		return
	}
	if r.Method != http.MethodGet {
		w.Header().Set("Allow", "GET")
		http.Error(w, "method", http.StatusMethodNotAllowed)
		return
	}
	if r.Header.Get("Origin") != "" {
		http.Error(w, "origin", http.StatusForbidden)
		return
	}
	s.mu.Lock()
	closed := s.closed
	s.mu.Unlock()
	if closed {
		http.Error(w, "closed", http.StatusServiceUnavailable)
		return
	}
	remote := s.clientIP(r)
	if !s.acquireUnauth(remote) {
		http.Error(w, "limited", http.StatusServiceUnavailable)
		return
	}
	conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{CompressionMode: websocket.CompressionDisabled})
	if err != nil {
		s.releaseUnauth(remote)
		return
	}
	go s.serve(conn, remote)
}

func (s *Server) serve(conn *websocket.Conn, remote string) {
	defer conn.CloseNow()
	var once sync.Once
	release := func() { once.Do(func() { s.releaseUnauth(remote) }) }
	defer release()
	conn.SetReadLimit(8192)
	nonce, err := s.writeHello(conn)
	if err != nil {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	frame, err := readFrame(ctx, conn, link.MainPayload)
	cancel()
	if err != nil || frame.Kind != link.KindLink {
		return
	}
	msg, err := parseControl(frame.Payload)
	if err != nil {
		s.fail(conn, "rejected")
		return
	}
	switch msg.Type {
	case "enroll":
		s.beginEnroll(conn, remote, nonce, msg, release)
	case "join":
		s.beginJoin(conn, remote, nonce, msg, release)
	case "auth":
		s.beginAuth(conn, nonce, msg, release)
	default:
		s.fail(conn, "rejected")
	}
}

func (s *Server) writeHello(conn *websocket.Conn) ([]byte, error) {
	var nonce [32]byte
	if _, err := rand.Read(nonce[:]); err != nil {
		return nil, err
	}
	body, err := marshal(message{Type: "hello", Protocol: 2, StationID: s.stationHex, Nonce: hex.EncodeToString(nonce[:])})
	if err != nil {
		return nil, err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := writeLink(ctx, conn, body); err != nil {
		return nil, err
	}
	out := make([]byte, 32)
	copy(out, nonce[:])
	return out, nil
}

func (s *Server) beginEnroll(conn *websocket.Conn, remote string, nonce []byte, msg message, release func()) {
	if !s.allow(remote) {
		s.fail(conn, "rate_limited")
		return
	}
	code, spki, sig, ok := s.enrollMaterial(remote, msg)
	if !ok {
		s.fail(conn, "rejected")
		return
	}
	id := deviceID(spki)
	transcript, err := link.EnrollMessage(s.station, nonce, id[:], code, msg.Purpose)
	if err != nil || link.VerifySignature(spki, transcript, sig) != nil {
		s.note(remote)
		s.fail(conn, "bad_signature")
		return
	}
	sum := sha256.Sum256(code)
	now := s.clock()
	err = s.reg.mutate(func(f *fileFormat) error {
		if f.Enroll == nil || !f.Enroll.Expires.After(now) {
			return errRejected
		}
		want, err := hex.DecodeString(f.Enroll.Hash)
		if err != nil || len(want) != sha256.Size || subtle.ConstantTimeCompare(sum[:], want) != 1 {
			return errRejected
		}
		hexID := hex.EncodeToString(id[:])
		if findDevice(f, hexID) != nil || len(f.Devices) >= 32 {
			return errRejected
		}
		f.Devices = append(f.Devices, deviceRec{ID: hexID, PublicKey: encodeKey(spki), Name: msg.Name, Kind: "mac"})
		f.Enroll = nil
		f.RegistryVersion++
		return nil
	})
	if err != nil {
		s.failPersist(conn, remote, err)
		return
	}
	release()
	sess := s.attachNew(conn, id, msg.Purpose)
	s.record(prefix(sess.hexID), "enroll")
	s.ready(sess)
	s.broadcastRegistry()
	s.broadcastPresence(sess, true)
	s.readLoop(sess)
}

func (s *Server) enrollMaterial(remote string, msg message) ([]byte, []byte, []byte, bool) {
	if msg.Kind != "mac" || !purposeOK(msg.Purpose) || !validName(msg.Name) {
		s.note(remote)
		return nil, nil, nil, false
	}
	code, err := decodeHex(msg.Code, 16)
	spki, err2 := decodeKey(msg.PublicKey)
	sig, err3 := decodeSig(msg.Sig)
	if err != nil || err2 != nil || err3 != nil {
		s.note(remote)
		return nil, nil, nil, false
	}
	return code, spki, sig, true
}

func (s *Server) beginAuth(conn *websocket.Conn, nonce []byte, msg message, release func()) {
	if !purposeOK(msg.Purpose) || !validName(msg.App) {
		s.fail(conn, "rejected")
		return
	}
	rawID, err := decodeHex(msg.DeviceID, 16)
	sig, err2 := decodeSig(msg.Sig)
	if err != nil || err2 != nil {
		s.fail(conn, "rejected")
		return
	}
	var id [16]byte
	copy(id[:], rawID)
	snap := s.reg.snapshot()
	device := findDevice(&snap, msg.DeviceID)
	if device == nil {
		s.fail(conn, "unknown_device")
		return
	}
	spki, err := decodeKey(device.PublicKey)
	transcript, err2 := link.RelayAuthMessage(s.station, nonce, id[:], msg.Purpose)
	if err != nil || err2 != nil || link.VerifySignature(spki, transcript, sig) != nil {
		s.fail(conn, "bad_signature")
		return
	}
	if device.Revoked {
		s.fail(conn, "revoked")
		return
	}
	release()
	sess := s.attachNew(conn, id, msg.Purpose)
	s.record(prefix(sess.hexID), "auth")
	s.ready(sess)
	s.broadcastPresence(sess, true)
	s.readLoop(sess)
}

func (s *Server) beginJoin(conn *websocket.Conn, remote string, nonce []byte, msg message, release func()) {
	if !s.allow(remote) {
		s.fail(conn, "rate_limited")
		return
	}
	spki, sig, invitation, ok := s.joinMaterial(msg)
	if !ok {
		s.note(remote)
		s.fail(conn, "rejected")
		return
	}
	id := deviceID(spki)
	hexID := hex.EncodeToString(id[:])
	transcript, err := link.JoinMessage(s.station, nonce, id[:], invitation, msg.Purpose)
	if err != nil || link.VerifySignature(spki, transcript, sig) != nil {
		s.note(remote)
		s.fail(conn, "bad_signature")
		return
	}
	snap := s.reg.snapshot()
	inv := findInvite(&snap, msg.InvitationID)
	now := s.clock()
	if inv == nil || !inv.Expires.After(now) || inv.AttemptsLeft < 1 || inv.Kind != msg.Kind {
		s.note(remote)
		s.fail(conn, "rejected")
		return
	}
	if device := findDevice(&snap, hexID); device != nil && !device.Revoked {
		s.note(remote)
		s.fail(conn, "rejected")
		return
	}
	s.mu.Lock()
	inviter := s.links[inv.Inviter+"/main"]
	s.mu.Unlock()
	if inviter == nil {
		s.fail(conn, "undeliverable")
		return
	}
	if err := s.reg.useAttempt(msg.InvitationID, now); err != nil {
		s.failPersist(conn, remote, err)
		return
	}
	pending := &pendingJoin{
		invitation: msg.InvitationID,
		inviter:    inv.Inviter,
		deviceID:   hexID,
		spki:       spki,
		name:       msg.Name,
		kind:       msg.Kind,
		purpose:    msg.Purpose,
		result:     make(chan error, 1),
	}
	s.mu.Lock()
	if _, exists := s.pending[msg.InvitationID]; exists {
		s.mu.Unlock()
		if err := s.refund(msg.InvitationID); err != nil {
			s.fail(conn, codeOf(err))
			return
		}
		s.note(remote)
		s.fail(conn, "rejected")
		return
	}
	s.pending[msg.InvitationID] = pending
	s.mu.Unlock()
	request := message{
		Type: "join_request", InvitationID: msg.InvitationID, DeviceID: hexID,
		PublicKey: encodeKey(spki), Name: msg.Name, Kind: msg.Kind, Purpose: msg.Purpose, Proof: msg.Proof,
	}
	if err := s.writeMsg(inviter, request); err != nil {
		if s.dropPending(msg.InvitationID, pending) {
			if err := s.refund(msg.InvitationID); err != nil {
				s.fail(conn, codeOf(err))
				return
			}
			s.fail(conn, "undeliverable")
			return
		}
	}
	s.record(prefix(hexID), "join")
	decision := s.waitJoin(msg.InvitationID, pending)
	if decision != nil {
		s.note(remote)
		s.fail(conn, codeOf(decision))
		return
	}
	release()
	sess := s.attachNew(conn, id, msg.Purpose)
	s.ready(sess)
	s.broadcastRegistry()
	s.broadcastPresence(sess, true)
	s.readLoop(sess)
}

func (s *Server) joinMaterial(msg message) ([]byte, []byte, []byte, bool) {
	if !purposeOK(msg.Purpose) || (msg.Kind != "mac" && msg.Kind != "phone") || !validName(msg.Name) {
		return nil, nil, nil, false
	}
	if _, err := decodeHex(msg.Proof, 32); err != nil {
		return nil, nil, nil, false
	}
	spki, err := decodeKey(msg.PublicKey)
	sig, err2 := decodeSig(msg.Sig)
	invitation, err3 := decodeHex(msg.InvitationID, 16)
	if err != nil || err2 != nil || err3 != nil {
		return nil, nil, nil, false
	}
	return spki, sig, invitation, true
}

func (s *Server) readLoop(sess *session) {
	defer func() {
		sess.close()
		s.detach(sess)
		s.record(prefix(sess.hexID), "disconnect")
	}()
	for {
		ctx, cancel := context.WithTimeout(context.Background(), s.idleLimit())
		frame, err := readFrame(ctx, sess.conn, sess.limit)
		cancel()
		if err != nil {
			return
		}
		if frame.Kind == link.KindLink {
			msg, err := parseControl(frame.Payload)
			if err != nil {
				s.writeMsg(sess, message{Type: "error", Code: "rejected"})
				continue
			}
			s.onControl(sess, msg)
			continue
		}
		s.forward(sess, frame)
	}
}

func (s *Server) onControl(sess *session, msg message) {
	switch msg.Type {
	case "ping":
		s.writeMsg(sess, message{Type: "ping"})
	case "invite":
		s.onInvite(sess, msg)
	case "approve":
		s.onApprove(sess, msg)
	case "reject":
		s.onReject(sess, msg)
	case "revoke":
		s.onRevoke(sess, msg)
	default:
		s.writeMsg(sess, message{Type: "error", Code: "rejected"})
	}
}

func (s *Server) onInvite(sess *session, msg message) {
	if msg.Kind != "mac" && msg.Kind != "phone" {
		s.writeMsg(sess, message{Type: "error", Code: "rejected"})
		return
	}
	var created inviteRec
	err := s.reg.mutate(func(f *fileFormat) error {
		self := findDevice(f, sess.hexID)
		if self == nil || self.Revoked || self.Kind != "mac" {
			return errRejected
		}
		kept := make([]inviteRec, 0, len(f.Invitations))
		now := s.clock()
		for _, inv := range f.Invitations {
			if inv.Expires.After(now) && inv.AttemptsLeft > 0 {
				kept = append(kept, inv)
			}
		}
		if len(kept) >= 4 {
			return errRejected
		}
		var raw [16]byte
		if _, err := rand.Read(raw[:]); err != nil {
			return err
		}
		created = inviteRec{ID: hex.EncodeToString(raw[:]), Inviter: sess.hexID, Kind: msg.Kind, Expires: now.UTC().Add(10 * time.Minute), AttemptsLeft: 5}
		f.Invitations = append(kept, created)
		return nil
	})
	if err != nil {
		s.writeMsg(sess, message{Type: "error", Code: codeOf(err)})
		return
	}
	s.writeMsg(sess, message{Type: "invite", InvitationID: created.ID, Kind: created.Kind, Expires: created.Expires.Format(time.RFC3339)})
}

func (s *Server) onApprove(sess *session, msg message) {
	invitation, err := decodeHex(msg.InvitationID, 16)
	device, err2 := decodeHex(msg.DeviceID, 16)
	sig, err3 := decodeSig(msg.Sig)
	if err != nil || err2 != nil || err3 != nil {
		s.writeMsg(sess, message{Type: "error", Code: "rejected"})
		return
	}
	snap := s.reg.snapshot()
	self := findDevice(&snap, sess.hexID)
	inv := findInvite(&snap, msg.InvitationID)
	if self == nil || self.Revoked || self.Kind != "mac" || inv == nil || inv.Inviter != sess.hexID {
		s.writeMsg(sess, message{Type: "error", Code: "rejected"})
		return
	}
	spki, err := decodeKey(self.PublicKey)
	transcript, err2 := link.ApproveMessage(s.station, invitation, device)
	if err != nil || err2 != nil || link.VerifySignature(spki, transcript, sig) != nil {
		s.writeMsg(sess, message{Type: "error", Code: "bad_signature"})
		return
	}
	pending := s.claim(msg.InvitationID, msg.DeviceID)
	if pending == nil {
		s.writeMsg(sess, message{Type: "error", Code: "rejected"})
		return
	}
	err = s.reg.approve(*pending)
	if err != nil {
		pending.result <- err
		s.writeMsg(sess, message{Type: "error", Code: codeOf(err)})
		return
	}
	pending.result <- nil
	s.record(prefix(sess.hexID), "approve")
}

func (s *Server) onReject(sess *session, msg message) {
	snap := s.reg.snapshot()
	inv := findInvite(&snap, msg.InvitationID)
	if inv == nil || inv.Inviter != sess.hexID {
		s.writeMsg(sess, message{Type: "error", Code: "rejected"})
		return
	}
	pending := s.claim(msg.InvitationID, msg.DeviceID)
	if pending == nil {
		s.writeMsg(sess, message{Type: "error", Code: "rejected"})
		return
	}
	pending.result <- errRejected
}

func (s *Server) onRevoke(sess *session, msg message) {
	if _, err := decodeHex(msg.DeviceID, 16); err != nil || msg.DeviceID == sess.hexID {
		s.writeMsg(sess, message{Type: "error", Code: "rejected"})
		return
	}
	if err := s.reg.revoke(msg.DeviceID); err != nil {
		s.writeMsg(sess, message{Type: "error", Code: codeOf(err)})
		return
	}
	for _, other := range s.sessions() {
		if other.hexID == msg.DeviceID {
			other.close()
		}
	}
	s.broadcastRegistry()
	s.record(prefix(sess.hexID), "revoke")
}

func (s *Server) watch(fn func([]byte)) {
	s.mu.Lock()
	s.tap = fn
	s.mu.Unlock()
}

func (s *Server) forward(from *session, frame link.Frame) {
	s.mu.Lock()
	tap := s.tap
	s.mu.Unlock()
	if tap != nil {
		tap(append([]byte(nil), frame.Payload...))
	}
	if frame.Peer == from.id {
		s.undeliverable(from)
		return
	}
	s.mu.Lock()
	dest := s.links[hex.EncodeToString(frame.Peer[:])+"/"+from.purpose]
	s.mu.Unlock()
	if dest == nil {
		s.undeliverable(from)
		return
	}
	out := frame
	out.Peer = from.id
	raw, err := link.Encode(out, dest.limit)
	if err != nil {
		s.undeliverable(from)
		return
	}
	if err := dest.enqueue(raw); err != nil {
		dest.close()
		s.undeliverable(from)
	}
}

func (s *Server) undeliverable(sess *session) {
	s.writeMsg(sess, message{Type: "error", Code: "undeliverable"})
	s.record(prefix(sess.hexID), "undeliverable")
}

func (s *Server) ready(sess *session) {
	snap := s.reg.snapshot()
	s.writeMsg(sess, message{Type: "ready", RegistryVersion: snap.RegistryVersion, Peers: s.peersExcept(sess)})
}

func (s *Server) broadcastRegistry() {
	snap := s.reg.snapshot()
	views := make([]deviceView, 0, len(snap.Devices))
	for _, device := range snap.Devices {
		views = append(views, deviceView{DeviceID: device.ID, PublicKey: device.PublicKey, Name: device.Name, Kind: device.Kind, Revoked: device.Revoked})
	}
	sort.Slice(views, func(i, j int) bool { return views[i].DeviceID < views[j].DeviceID })
	msg := message{Type: "registry", RegistryVersion: snap.RegistryVersion, Devices: views}
	for _, sess := range s.sessions() {
		s.writeMsg(sess, msg)
	}
}

func (s *Server) broadcastPresence(sess *session, online bool) {
	msg := message{Type: "presence", DeviceID: sess.hexID, Purpose: sess.purpose, Online: &online}
	for _, other := range s.sessions() {
		if other != sess {
			s.writeMsg(other, msg)
		}
	}
}

func (s *Server) peersExcept(self *session) []peerView {
	var out []peerView
	for _, other := range s.sessions() {
		if other != self {
			out = append(out, peerView{DeviceID: other.hexID, Purpose: other.purpose})
		}
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].DeviceID == out[j].DeviceID {
			return out[i].Purpose < out[j].Purpose
		}
		return out[i].DeviceID < out[j].DeviceID
	})
	return out
}

func (s *Server) attachNew(conn *websocket.Conn, id [16]byte, purpose string) *session {
	sess := newSession(conn, id, purpose)
	if old := s.attach(sess); old != nil {
		old.close()
	}
	return sess
}

func newSession(conn *websocket.Conn, id [16]byte, purpose string) *session {
	limit := link.MainPayload
	queueMax := mainQueueBytes
	if purpose == "media" {
		limit = link.MediaPayload
		queueMax = mediaQueueBytes
	}
	conn.SetReadLimit(int64(link.HeaderSize + limit))
	sess := &session{
		id: id, hexID: hex.EncodeToString(id[:]), purpose: purpose, conn: conn, limit: limit, queueMax: queueMax,
		wake: make(chan struct{}, 1), stop: make(chan struct{}),
	}
	go sess.writeLoop()
	return sess
}

func (c *session) setWriteHold(hold <-chan struct{}) {
	c.qmu.Lock()
	c.writeHold = hold
	c.qmu.Unlock()
}

func (c *session) takeHold() <-chan struct{} {
	c.qmu.Lock()
	defer c.qmu.Unlock()
	return c.writeHold
}

func (c *session) enqueue(raw []byte) error {
	c.qmu.Lock()
	defer c.qmu.Unlock()
	if c.dead || len(raw) > c.queueMax || c.queued+len(raw) > c.queueMax {
		c.dead = true
		return errQueueFull
	}
	c.pending = append(c.pending, raw)
	c.queued += len(raw)
	select {
	case c.wake <- struct{}{}:
	default:
	}
	return nil
}

func (c *session) pop() ([]byte, bool) {
	for {
		c.qmu.Lock()
		if len(c.pending) > 0 && !c.dead {
			raw := c.pending[0]
			c.pending[0] = nil
			c.pending = c.pending[1:]
			c.queued -= len(raw)
			c.qmu.Unlock()
			return raw, true
		}
		dead := c.dead
		c.qmu.Unlock()
		if dead {
			return nil, false
		}
		select {
		case <-c.wake:
		case <-c.stop:
			return nil, false
		}
	}
}

func (c *session) writeLoop() {
	for {
		raw, ok := c.pop()
		if !ok {
			return
		}
		if hold := c.takeHold(); hold != nil {
			select {
			case <-hold:
			case <-c.stop:
				return
			}
		}
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		err := c.conn.Write(ctx, websocket.MessageBinary, raw)
		cancel()
		if err != nil {
			c.close()
			return
		}
	}
}

func (c *session) close() {
	c.qmu.Lock()
	c.dead = true
	c.pending = nil
	c.queued = 0
	c.qmu.Unlock()
	c.stopOnce.Do(func() { close(c.stop) })
	// Close waits for the peer while this connection's read holds the read lock.
	// CloseNow unblocks that read without the handshake.
	c.closeOnce.Do(func() { _ = c.conn.CloseNow() })
}

func (s *Server) writeMsg(sess *session, msg message) error {
	body, err := marshal(msg)
	if err != nil {
		return err
	}
	raw, err := link.Encode(link.Frame{Kind: link.KindLink, Payload: body}, sess.limit)
	if err != nil {
		return err
	}
	if err := sess.enqueue(raw); err != nil {
		sess.close()
		return err
	}
	return nil
}

func (s *Server) attach(sess *session) *session {
	s.mu.Lock()
	defer s.mu.Unlock()
	key := sess.hexID + "/" + sess.purpose
	old := s.links[key]
	s.links[key] = sess
	return old
}

func (s *Server) detach(sess *session) {
	s.mu.Lock()
	key := sess.hexID + "/" + sess.purpose
	current := s.links[key] == sess
	if current {
		delete(s.links, key)
	}
	s.mu.Unlock()
	if current {
		s.broadcastPresence(sess, false)
	}
}

func (s *Server) sessions() []*session {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make([]*session, 0, len(s.links))
	for _, sess := range s.links {
		out = append(out, sess)
	}
	return out
}

func (s *Server) claim(invitation, device string) *pendingJoin {
	s.mu.Lock()
	defer s.mu.Unlock()
	pending := s.pending[invitation]
	if pending == nil || pending.deviceID != device {
		return nil
	}
	delete(s.pending, invitation)
	return pending
}

func (s *Server) dropPending(invitation string, pending *pendingJoin) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.pending[invitation] != pending {
		return false
	}
	delete(s.pending, invitation)
	return true
}

func (s *Server) waitJoin(invitation string, pending *pendingJoin) error {
	timer := time.NewTimer(30 * time.Second)
	defer timer.Stop()
	select {
	case err := <-pending.result:
		return err
	case <-timer.C:
		if s.dropPending(invitation, pending) {
			return errRejected
		}
		return <-pending.result
	}
}

func (s *Server) refund(id string) error {
	return s.reg.refund(id)
}

func (s *Server) allow(ip string) bool {
	now := s.clock()
	s.mu.Lock()
	defer s.mu.Unlock()
	s.prune(ip, now)
	return len(s.failures[ip]) < 8
}

func (s *Server) note(ip string) {
	now := s.clock()
	s.mu.Lock()
	s.failures[ip] = append(s.failures[ip], now)
	s.mu.Unlock()
}

func (s *Server) prune(ip string, now time.Time) {
	cut := now.Add(-10 * time.Minute)
	kept := s.failures[ip][:0]
	for _, at := range s.failures[ip] {
		if at.After(cut) {
			kept = append(kept, at)
		}
	}
	s.failures[ip] = kept
}

func (s *Server) clock() time.Time {
	s.mu.Lock()
	now := s.now
	s.mu.Unlock()
	if now == nil {
		return time.Now()
	}
	return now()
}

func (s *Server) idleLimit() time.Duration {
	s.mu.Lock()
	idle := s.idle
	s.mu.Unlock()
	if idle > 0 {
		return idle
	}
	return 45 * time.Second
}

func (s *Server) acquireUnauth(ip string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.unauth[ip] >= maxUnauthPerIP {
		return false
	}
	s.unauth[ip]++
	return true
}

func (s *Server) releaseUnauth(ip string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	n := s.unauth[ip]
	if n <= 1 {
		delete(s.unauth, ip)
		return
	}
	s.unauth[ip] = n - 1
}

func (s *Server) clientIP(r *http.Request) string {
	host := hostOnly(r.RemoteAddr)
	s.mu.Lock()
	trusted := s.trusted
	_, ok := trusted[host]
	s.mu.Unlock()
	if !ok {
		return host
	}
	if ip := forwardedClient(r.Header.Get("X-Forwarded-For"), trusted); ip != "" {
		return ip
	}
	return host
}

func forwardedClient(header string, trusted map[string]struct{}) string {
	parts := strings.Split(header, ",")
	for i := len(parts) - 1; i >= 0; i-- {
		ip := strings.TrimSpace(parts[i])
		if net.ParseIP(ip) == nil {
			continue
		}
		if _, skip := trusted[ip]; skip {
			continue
		}
		return ip
	}
	return ""
}

func (s *Server) record(id, reason string) {
	s.mu.Lock()
	writer := s.log
	s.mu.Unlock()
	if writer == nil {
		return
	}
	fmt.Fprintf(writer, "link %s %s\n", id, reason)
}

func (s *Server) failPersist(conn *websocket.Conn, remote string, err error) {
	if errors.Is(err, errPersist) {
		s.fail(conn, "unavailable")
		return
	}
	s.note(remote)
	s.fail(conn, codeOf(err))
}

func (s *Server) fail(conn *websocket.Conn, code string) {
	s.record("-", code)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if body, err := marshal(message{Type: "error", Code: code}); err == nil {
		_ = writeLink(ctx, conn, body)
	}
	_ = conn.Close(websocket.StatusPolicyViolation, "")
}

func hostOnly(remote string) string {
	host, _, err := net.SplitHostPort(remote)
	if err != nil {
		return remote
	}
	return host
}
