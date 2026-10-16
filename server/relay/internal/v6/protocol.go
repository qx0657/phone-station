package v6

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"strings"
	"unicode"
	"unicode/utf8"

	"github.com/coder/websocket"
	"phone-station/link"
)

var (
	errRejected = errors.New("rejected")
	errPersist  = errors.New("persist")
)

type message struct {
	Type            string       `json:"type"`
	Protocol        int          `json:"protocol,omitempty"`
	StationID       string       `json:"stationId,omitempty"`
	Nonce           string       `json:"nonce,omitempty"`
	DeviceID        string       `json:"deviceId,omitempty"`
	Purpose         string       `json:"purpose,omitempty"`
	App             string       `json:"app,omitempty"`
	Sig             string       `json:"sig,omitempty"`
	Code            string       `json:"code,omitempty"`
	PublicKey       string       `json:"publicKey,omitempty"`
	Name            string       `json:"name,omitempty"`
	Kind            string       `json:"kind,omitempty"`
	InvitationID    string       `json:"invitationId,omitempty"`
	Proof           string       `json:"proof,omitempty"`
	RegistryVersion uint64       `json:"registryVersion,omitempty"`
	Expires         string       `json:"expires,omitempty"`
	Online          *bool        `json:"online,omitempty"`
	Peers           []peerView   `json:"peers,omitempty"`
	Devices         []deviceView `json:"devices,omitempty"`
}

type peerView struct {
	DeviceID string `json:"deviceId"`
	Purpose  string `json:"purpose"`
}

type deviceView struct {
	DeviceID  string `json:"deviceId"`
	PublicKey string `json:"publicKey"`
	Name      string `json:"name"`
	Kind      string `json:"kind"`
	Revoked   bool   `json:"revoked"`
}

func marshal(v any) ([]byte, error) {
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	if err := enc.Encode(v); err != nil {
		return nil, err
	}
	return bytes.TrimRight(buf.Bytes(), "\n"), nil
}

func parseControl(payload []byte) (message, error) {
	if len(payload) == 0 || len(payload) > 4096 || !utf8.Valid(payload) || rejectDuplicate(payload) != nil {
		return message{}, errRejected
	}
	dec := json.NewDecoder(bytes.NewReader(payload))
	dec.DisallowUnknownFields()
	var msg message
	if err := dec.Decode(&msg); err != nil || dec.More() {
		return message{}, errRejected
	}
	return msg, nil
}

func rejectDuplicate(payload []byte) error {
	dec := json.NewDecoder(bytes.NewReader(payload))
	token, err := dec.Token()
	if err != nil || token != json.Delim('{') {
		return errRejected
	}
	seen := map[string]bool{}
	for dec.More() {
		keyToken, err := dec.Token()
		key, ok := keyToken.(string)
		if err != nil || !ok || seen[key] {
			return errRejected
		}
		seen[key] = true
		var skip json.RawMessage
		if err := dec.Decode(&skip); err != nil {
			return errRejected
		}
	}
	token, err = dec.Token()
	if err != nil || token != json.Delim('}') {
		return errRejected
	}
	return nil
}

func writeLink(ctx context.Context, conn *websocket.Conn, payload []byte) error {
	raw, err := link.Encode(link.Frame{Kind: link.KindLink, Payload: payload}, link.MainPayload)
	if err != nil {
		return err
	}
	return conn.Write(ctx, websocket.MessageBinary, raw)
}

func readFrame(ctx context.Context, conn *websocket.Conn, limit int) (link.Frame, error) {
	kind, data, err := conn.Read(ctx)
	if err != nil {
		return link.Frame{}, err
	}
	if kind != websocket.MessageBinary {
		return link.Frame{}, errors.New("binary")
	}
	return link.Decode(data, limit)
}

func validHex(value string) bool {
	if len(value) != 32 || value != strings.ToLower(value) {
		return false
	}
	_, err := hex.DecodeString(value)
	return err == nil
}

func decodeHex(value string, size int) ([]byte, error) {
	if size > 0 && len(value) != size*2 {
		return nil, errRejected
	}
	if len(value) == 0 || len(value)%2 != 0 || value != strings.ToLower(value) {
		return nil, errRejected
	}
	out, err := hex.DecodeString(value)
	if err != nil {
		return nil, errRejected
	}
	return out, nil
}

func decodeSig(value string) ([]byte, error) {
	if len(value) < 16 || len(value) > 160 {
		return nil, errRejected
	}
	return decodeHex(value, 0)
}

func decodeKey(value string) ([]byte, error) {
	if value == "" || len(value) > 400 {
		return nil, errRejected
	}
	spki, err := base64.StdEncoding.DecodeString(value)
	if err != nil || !validKey(spki) {
		return nil, errRejected
	}
	return spki, nil
}

func encodeKey(spki []byte) string {
	return base64.StdEncoding.EncodeToString(spki)
}

func validKey(spki []byte) bool {
	pub, err := x509.ParsePKIXPublicKey(spki)
	if err != nil {
		return false
	}
	key, ok := pub.(*ecdsa.PublicKey)
	return ok && key.Curve == elliptic.P256()
}

func deviceID(spki []byte) (id [16]byte) {
	copy(id[:], link.DeviceID(spki))
	return id
}

func purposeOK(purpose string) bool {
	return purpose == "main" || purpose == "media"
}

func validName(name string) bool {
	count := utf8.RuneCountInString(name)
	if count < 1 || count > 64 || !utf8.ValidString(name) {
		return false
	}
	for _, r := range name {
		if unicode.IsControl(r) {
			return false
		}
	}
	return true
}

func prefix(id string) string {
	if len(id) < 8 {
		return "-"
	}
	return id[:8]
}

func codeOf(err error) string {
	if errors.Is(err, errPersist) {
		return "unavailable"
	}
	return "rejected"
}
