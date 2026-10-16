package link

import (
	"crypto/ecdsa"
	"crypto/sha256"
	"crypto/x509"
	"errors"
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
