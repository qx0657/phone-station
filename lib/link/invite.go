package link

import (
	"encoding/base64"
	"encoding/hex"
	"errors"
	"strings"
)

// Invite is the text a QR code or a local-link message carries.
// The relay never sees the key. The manual code is only the first three
// bytes of the id and of the key, so the two sides still compare the safety code.
type Invite struct {
	Endpoint string
	Pin      string
	ID       string
	Key      string
	SPKI     string
	Kind     string
}

func InviteText(item Invite) (string, error) {
	if err := item.valid(); err != nil {
		return "", err
	}
	return "ps-invite-v1\n" + item.Endpoint + "\n" + item.Pin + "\n" + item.ID + "\n" + item.Key + "\n" + item.SPKI + "\n" + item.Kind + "\n", nil
}

// RelayInviteMessage carries an invitation on the local link.
// The text is base64 so the link JSON parser can keep rejecting raw newlines.
func RelayInviteMessage(text string) ([]byte, error) {
	if _, err := ParseInvite(text); err != nil {
		return nil, err
	}
	encoded := base64.StdEncoding.EncodeToString([]byte(text))
	return []byte(`{"type":"relay-invite","body":"` + encoded + `"}`), nil
}

// SendRelayInvite writes the invitation on an open local session.
func (s *LocalSession) SendRelayInvite(text string) error {
	payload, err := RelayInviteMessage(text)
	if err != nil {
		return err
	}
	return s.Send(payload)
}

func ParseInvite(text string) (Invite, error) {
	if len(text) > 1200 || !strings.HasSuffix(text, "\n") {
		return Invite{}, errors.New("invite")
	}
	lines := strings.Split(text[:len(text)-1], "\n")
	if len(lines) != 7 || lines[0] != "ps-invite-v1" {
		return Invite{}, errors.New("invite")
	}
	item := Invite{Endpoint: lines[1], Pin: lines[2], ID: lines[3], Key: lines[4], SPKI: lines[5], Kind: lines[6]}
	if err := item.valid(); err != nil {
		return Invite{}, err
	}
	return item, nil
}

func (item Invite) valid() error {
	if !validEndpoint(item.Endpoint) || !hex32(item.Pin) || !hex16(item.ID) || !hex16(item.Key) {
		return errors.New("invite")
	}
	raw, err := base64.StdEncoding.DecodeString(item.SPKI)
	if err != nil || len(raw) == 0 || len(raw) > 400 || item.SPKI != base64.StdEncoding.EncodeToString(raw) {
		return errors.New("invite")
	}
	if item.Kind != "phone" && item.Kind != "mac" {
		return errors.New("invite")
	}
	return nil
}

func validEndpoint(value string) bool {
	if len(value) < len("https://a") || len(value) > 200 || strings.ContainsAny(value, " \t\r\n") {
		return false
	}
	return strings.HasPrefix(value, "https://")
}

func hex16(value string) bool {
	raw, err := hex.DecodeString(value)
	return err == nil && len(raw) == 16 && value == hex.EncodeToString(raw)
}

func hex32(value string) bool {
	raw, err := hex.DecodeString(value)
	return err == nil && len(raw) == 32 && value == hex.EncodeToString(raw)
}

func ManualCode(id, key []byte) (string, error) {
	if len(id) != 16 || len(key) != 16 {
		return "", errors.New("length")
	}
	return hex.EncodeToString(id[:3]) + hex.EncodeToString(key[:3]), nil
}

func ParseManual(code string) (idPrefix, keyPrefix []byte, err error) {
	if len(code) != 12 {
		return nil, nil, errors.New("code")
	}
	raw, err := hex.DecodeString(code)
	if err != nil || len(raw) != 6 || code != hex.EncodeToString(raw) {
		return nil, nil, errors.New("code")
	}
	return append([]byte(nil), raw[:3]...), append([]byte(nil), raw[3:]...), nil
}
