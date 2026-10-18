package link

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"errors"
	"sync"
	"time"
)

const (
	sealOpenLabel   = "phone-station link-v2 seal-open"
	sealAcceptLabel = "phone-station link-v2 seal-accept"
)

// frameCodec seals outgoing message and data frames and opens incoming ones.
// Handshake frames stay outside it so the key exchange is not encrypted with itself.
type frameCodec interface {
	Seal(Frame) (Frame, error)
	Open(Frame) (Frame, error)
}

type sealMsg struct {
	Type  string `json:"type"`
	Eph   string `json:"eph"`
	Nonce string `json:"nonce"`
	Sig   string `json:"sig"`
}

// sealCodec is the remote-session cipher. Local sessions never install one.
// A required remote session rejects plaintext instead of falling back.
type sealCodec struct {
	fromMac  bool
	self     Identity
	peerSPKI []byte
	macID    [16]byte
	phoneID  [16]byte
	now      func() time.Time

	mu       sync.Mutex
	sendKey  []byte
	recvKey  []byte
	sendDir  string
	recvDir  string
	sendWin  ReplayWindow
	recvWin  ReplayWindow
	ephPKCS8 []byte
	ephSPKI  []byte
	nonce    []byte
}

func newSealCodec(fromMac bool, self Identity, peerSPKI []byte, macID, phoneID [16]byte) *sealCodec {
	return &sealCodec{
		fromMac:  fromMac,
		self:     self,
		peerSPKI: append([]byte(nil), peerSPKI...),
		macID:    macID,
		phoneID:  phoneID,
		now:      time.Now,
	}
}

func (c *sealCodec) ready() bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.sendKey != nil
}

func (c *sealCodec) Seal(frame Frame) (Frame, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.sendKey == nil {
		return Frame{}, errors.New("plaintext")
	}
	if err := c.sendWin.Accept(frame.Seq, c.now()); err != nil {
		return Frame{}, err
	}
	frame.Flags |= FlagEncrypted
	sealed, err := c.box(c.sendKey, c.sendDir, frame)
	if err != nil {
		return Frame{}, err
	}
	frame.Payload = sealed
	return frame, nil
}

func (c *sealCodec) Open(frame Frame) (Frame, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if err := (SealChoice{Required: true}).Admit(frame.Flags); err != nil {
		return Frame{}, err
	}
	if c.recvKey == nil {
		return Frame{}, errors.New("plaintext")
	}
	if err := c.recvWin.Accept(frame.Seq, c.now()); err != nil {
		return Frame{}, err
	}
	plain, err := c.unbox(c.recvKey, c.recvDir, frame)
	if err != nil {
		return Frame{}, errors.New("open")
	}
	frame.Payload = plain
	frame.Flags &^= FlagEncrypted
	return frame, nil
}

func (c *sealCodec) box(key []byte, direction string, frame Frame) ([]byte, error) {
	nonce, err := Nonce(direction, frame.Seq)
	if err != nil {
		return nil, err
	}
	aad := AAD(frame.Kind, frame.Flags, frame.Channel, frame.Seq)
	return Seal(key, nonce, frame.Payload, aad)
}

func (c *sealCodec) unbox(key []byte, direction string, frame Frame) ([]byte, error) {
	nonce, err := Nonce(direction, frame.Seq)
	if err != nil {
		return nil, err
	}
	aad := AAD(frame.Kind, frame.Flags, frame.Channel, frame.Seq)
	return Open(key, nonce, frame.Payload, aad)
}

func (c *sealCodec) begin() ([]byte, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if !c.fromMac || c.sendKey != nil || c.ephPKCS8 != nil {
		return nil, errors.New("plaintext")
	}
	pkcs8, spki, err := newEphemeral()
	if err != nil {
		return nil, err
	}
	nonce, err := randomNonce()
	if err != nil {
		return nil, err
	}
	message, err := sealOpenMessage(c.macID[:], c.phoneID[:], spki, nonce)
	if err != nil {
		return nil, err
	}
	sig, err := c.self.Sign(message)
	if err != nil {
		return nil, err
	}
	c.ephPKCS8 = pkcs8
	c.ephSPKI = spki
	c.nonce = nonce
	return marshalSeal("open", spki, nonce, sig)
}

func (c *sealCodec) answer(msg sealMsg) ([]byte, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.fromMac || c.sendKey != nil {
		return nil, errors.New("plaintext")
	}
	macEph, macNonce, err := c.verifyOpen(msg)
	if err != nil {
		return nil, err
	}
	pkcs8, spki, err := newEphemeral()
	if err != nil {
		return nil, err
	}
	nonce, err := randomNonce()
	if err != nil {
		return nil, err
	}
	message, err := sealAcceptMessage(c.macID[:], c.phoneID[:], macEph, macNonce, spki, nonce)
	if err != nil {
		return nil, err
	}
	sig, err := c.self.Sign(message)
	if err != nil {
		return nil, err
	}
	if err := c.install(pkcs8, macEph, macNonce, nonce); err != nil {
		return nil, err
	}
	c.ephPKCS8 = nil
	return marshalSeal("accept", spki, nonce, sig)
}

func (c *sealCodec) finish(msg sealMsg) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if !c.fromMac || c.sendKey != nil || c.ephPKCS8 == nil {
		return errors.New("plaintext")
	}
	phoneEph, phoneNonce, err := decodeSeal(msg)
	if err != nil {
		return err
	}
	message, err := sealAcceptMessage(c.macID[:], c.phoneID[:], c.ephSPKI, c.nonce, phoneEph, phoneNonce)
	if err != nil {
		return err
	}
	if err := VerifySignature(c.peerSPKI, message, mustSig(msg.Sig)); err != nil {
		return errors.New("bad_signature")
	}
	if err := c.install(c.ephPKCS8, phoneEph, c.nonce, phoneNonce); err != nil {
		return err
	}
	c.ephPKCS8 = nil
	return nil
}

func (c *sealCodec) verifyOpen(msg sealMsg) ([]byte, []byte, error) {
	eph, nonce, err := decodeSeal(msg)
	if err != nil {
		return nil, nil, err
	}
	message, err := sealOpenMessage(c.macID[:], c.phoneID[:], eph, nonce)
	if err != nil {
		return nil, nil, err
	}
	if err := VerifySignature(c.peerSPKI, message, mustSig(msg.Sig)); err != nil {
		return nil, nil, errors.New("bad_signature")
	}
	return eph, nonce, nil
}

func (c *sealCodec) install(ownPKCS8, peerEph, macNonce, phoneNonce []byte) error {
	shared, err := SharedSecret(ownPKCS8, peerEph)
	if err != nil {
		return err
	}
	salt := append(append([]byte{}, macNonce...), phoneNonce...)
	sendDir, recvDir := "mac-to-phone", "phone-to-mac"
	if !c.fromMac {
		sendDir, recvDir = recvDir, sendDir
	}
	sendInfo, err := DirectionInfo(sendDir, c.macID[:], c.phoneID[:])
	if err != nil {
		return err
	}
	recvInfo, err := DirectionInfo(recvDir, c.macID[:], c.phoneID[:])
	if err != nil {
		return err
	}
	sendKey, err := DeriveKey(shared, salt, sendInfo)
	if err != nil {
		return err
	}
	recvKey, err := DeriveKey(shared, salt, recvInfo)
	if err != nil {
		return err
	}
	c.sendKey = sendKey
	c.recvKey = recvKey
	c.sendDir = sendDir
	c.recvDir = recvDir
	return nil
}

func sealOpenMessage(macID, phoneID, spki, nonce []byte) ([]byte, error) {
	if len(macID) != 16 || len(phoneID) != 16 || len(spki) == 0 || len(nonce) != 32 {
		return nil, errors.New("length")
	}
	out := make([]byte, 0, len(sealOpenLabel)+64+len(spki))
	out = append(out, sealOpenLabel...)
	out = append(out, macID...)
	out = append(out, phoneID...)
	out = append(out, spki...)
	out = append(out, nonce...)
	return out, nil
}

func sealAcceptMessage(macID, phoneID, macEph, macNonce, phoneEph, phoneNonce []byte) ([]byte, error) {
	if len(macID) != 16 || len(phoneID) != 16 || len(macEph) == 0 || len(macNonce) != 32 || len(phoneEph) == 0 || len(phoneNonce) != 32 {
		return nil, errors.New("length")
	}
	out := make([]byte, 0, len(sealAcceptLabel)+64+len(macEph)+len(phoneEph)+64)
	out = append(out, sealAcceptLabel...)
	out = append(out, macID...)
	out = append(out, phoneID...)
	out = append(out, macEph...)
	out = append(out, macNonce...)
	out = append(out, phoneEph...)
	out = append(out, phoneNonce...)
	return out, nil
}

func marshalSeal(kind string, spki, nonce, sig []byte) ([]byte, error) {
	return json.Marshal(sealMsg{
		Type:  kind,
		Eph:   base64.StdEncoding.EncodeToString(spki),
		Nonce: base64.StdEncoding.EncodeToString(nonce),
		Sig:   base64.StdEncoding.EncodeToString(sig),
	})
}

func decodeSeal(msg sealMsg) ([]byte, []byte, error) {
	if msg.Type != "open" && msg.Type != "accept" {
		return nil, nil, errors.New("plaintext")
	}
	eph, err := base64.StdEncoding.DecodeString(msg.Eph)
	if err != nil || len(eph) == 0 {
		return nil, nil, errors.New("bad_signature")
	}
	nonce, err := base64.StdEncoding.DecodeString(msg.Nonce)
	if err != nil || len(nonce) != 32 {
		return nil, nil, errors.New("bad_signature")
	}
	if _, err := base64.StdEncoding.DecodeString(msg.Sig); err != nil {
		return nil, nil, errors.New("bad_signature")
	}
	return eph, nonce, nil
}

func mustSig(text string) []byte {
	sig, err := base64.StdEncoding.DecodeString(text)
	if err != nil {
		return nil
	}
	return sig
}

func newEphemeral() ([]byte, []byte, error) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return nil, nil, err
	}
	pkcs8, err := x509.MarshalPKCS8PrivateKey(key)
	if err != nil {
		return nil, nil, err
	}
	spki, err := x509.MarshalPKIXPublicKey(&key.PublicKey)
	if err != nil {
		return nil, nil, err
	}
	return pkcs8, spki, nil
}

// RequireSeal arms a remote session. The local link refuses, and a required
// session does not send application data until the seal handshake finishes.
func (s *LocalSession) RequireSeal(self Identity, peerSPKI []byte) error {
	if s == nil || !s.remote {
		return errors.New("local")
	}
	if self.Sign == nil || len(peerSPKI) == 0 || s.engine == nil {
		return errors.New("device")
	}
	macID, phoneID := self.ID, s.peer
	if !s.engine.fromMac {
		macID, phoneID = s.peer, self.ID
	}
	s.codec = newSealCodec(s.engine.fromMac, self, peerSPKI, macID, phoneID)
	s.engine.useCodec(s.codec)
	return nil
}

func (s *LocalSession) ensureSeal() error {
	if s.codec == nil || s.codec.ready() {
		return nil
	}
	if !s.engine.fromMac {
		return errors.New("plaintext")
	}
	payload, err := s.codec.begin()
	if err != nil {
		return err
	}
	if err := s.sendKind(KindHandshake, payload); err != nil {
		return err
	}
	for !s.codec.ready() {
		if s.Ended() {
			return errors.New("plaintext")
		}
		if _, err := s.readMessages(); err != nil {
			return err
		}
	}
	return nil
}

func (s *LocalSession) sendKind(kind byte, payload []byte) error {
	if s.Ended() {
		return errors.New("ended")
	}
	if err := s.engine.SendMessage(PriorityControl, 0, kind, payload); err != nil {
		return err
	}
	return s.flush()
}

func (s *LocalSession) takeSeal(raw []byte) (bool, error) {
	if s.codec == nil || len(raw) == 0 || raw[0] != '{' {
		return false, nil
	}
	var msg sealMsg
	if json.Unmarshal(raw, &msg) != nil {
		return false, nil
	}
	switch msg.Type {
	case "open":
		if s.engine.fromMac || s.codec.ready() {
			return true, errors.New("plaintext")
		}
		reply, err := s.codec.answer(msg)
		if err != nil {
			return true, err
		}
		if err := s.sendKind(KindHandshake, reply); err != nil {
			return true, err
		}
		return true, nil
	case "accept":
		if !s.engine.fromMac || s.codec.ready() {
			return true, errors.New("plaintext")
		}
		if err := s.codec.finish(msg); err != nil {
			return true, err
		}
		return true, nil
	default:
		return false, nil
	}
}
