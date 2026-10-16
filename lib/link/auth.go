package link

import (
	"crypto/ecdsa"
	"crypto/hmac"
	"crypto/sha256"
	"crypto/x509"
	"errors"
	"unicode"
	"unicode/utf8"
)

func DeviceID(spki []byte) []byte {
	sum := sha256.Sum256(spki)
	return append([]byte(nil), sum[:16]...)
}

func RelayAuthMessage(stationID, nonce, deviceID []byte, purpose string) ([]byte, error) {
	if len(stationID) != 16 || len(nonce) != 32 || len(deviceID) != 16 || (purpose != "main" && purpose != "media") {
		return nil, errors.New("length")
	}
	out := make([]byte, 0, len("phone-station link-v2 auth")+16+32+16+len(purpose))
	out = append(out, "phone-station link-v2 auth"...)
	out = append(out, stationID...)
	out = append(out, nonce...)
	out = append(out, deviceID...)
	out = append(out, purpose...)
	return out, nil
}

func LocalAuthMessage(peerID, peerNonce, ownID []byte) ([]byte, error) {
	if len(peerID) != 16 || len(peerNonce) != 32 || len(ownID) != 16 {
		return nil, errors.New("length")
	}
	out := make([]byte, 0, len("phone-station link-v2 local-auth")+16+32+16)
	out = append(out, "phone-station link-v2 local-auth"...)
	out = append(out, peerID...)
	out = append(out, peerNonce...)
	out = append(out, ownID...)
	return out, nil
}

func VerifySignature(spki, message, der []byte) error {
	parsed, err := x509.ParsePKIXPublicKey(spki)
	if err != nil {
		return err
	}
	public, ok := parsed.(*ecdsa.PublicKey)
	if !ok {
		return errors.New("key")
	}
	sum := sha256.Sum256(message)
	if !ecdsa.VerifyASN1(public, sum[:], der) {
		return errors.New("signature")
	}
	return nil
}

func purposeOK(purpose string) bool {
	return purpose == "main" || purpose == "media"
}

func proofNameOK(name string) bool {
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

func concat(label string, parts ...[]byte) []byte {
	n := len(label)
	for _, part := range parts {
		n += len(part)
	}
	out := make([]byte, 0, n)
	out = append(out, label...)
	for _, part := range parts {
		out = append(out, part...)
	}
	return out
}

// EnrollMessage is the first-device transcript: label, station, nonce, device, enroll code, purpose.
func EnrollMessage(stationID, nonce, deviceID, code []byte, purpose string) ([]byte, error) {
	if len(stationID) != 16 || len(nonce) != 32 || len(deviceID) != 16 || len(code) != 16 || !purposeOK(purpose) {
		return nil, errors.New("length")
	}
	return concat("phone-station link-v2 enroll", stationID, nonce, deviceID, code, []byte(purpose)), nil
}

// JoinMessage binds the relay nonce to an invitation and the link purpose.
func JoinMessage(stationID, nonce, deviceID, invitationID []byte, purpose string) ([]byte, error) {
	if len(stationID) != 16 || len(nonce) != 32 || len(deviceID) != 16 || len(invitationID) != 16 || !purposeOK(purpose) {
		return nil, errors.New("length")
	}
	return concat("phone-station link-v2 join", stationID, nonce, deviceID, invitationID, []byte(purpose)), nil
}

// ApproveMessage is the inviter's confirmation. It has no purpose byte; the pending join carries that.
func ApproveMessage(stationID, invitationID, deviceID []byte) ([]byte, error) {
	if len(stationID) != 16 || len(invitationID) != 16 || len(deviceID) != 16 {
		return nil, errors.New("length")
	}
	return concat("phone-station link-v2 approve", stationID, invitationID, deviceID), nil
}

// InvitationProof is HMAC-SHA256(key, SPKI || name || 0x00 || kind || 0x00 || purpose).
// The relay forwards it and does not check it.
func InvitationProof(key, spki []byte, name, kind, purpose string) ([]byte, error) {
	if len(key) != 16 || len(spki) == 0 || !proofNameOK(name) || (kind != "mac" && kind != "phone") || !purposeOK(purpose) {
		return nil, errors.New("length")
	}
	mac := hmac.New(sha256.New, key)
	_, _ = mac.Write(spki)
	_, _ = mac.Write([]byte(name))
	_, _ = mac.Write([]byte{0})
	_, _ = mac.Write([]byte(kind))
	_, _ = mac.Write([]byte{0})
	_, _ = mac.Write([]byte(purpose))
	return mac.Sum(nil), nil
}
