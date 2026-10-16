package link

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/ecdsa"
	"crypto/hmac"
	"crypto/sha256"
	"crypto/x509"
	"encoding/binary"
	"errors"
)

func SharedSecret(privatePKCS8, peerSPKI []byte) ([]byte, error) {
	parsed, err := x509.ParsePKCS8PrivateKey(privatePKCS8)
	if err != nil {
		return nil, err
	}
	private, ok := parsed.(*ecdsa.PrivateKey)
	if !ok {
		return nil, errors.New("key")
	}
	publicAny, err := x509.ParsePKIXPublicKey(peerSPKI)
	if err != nil {
		return nil, err
	}
	public, ok := publicAny.(*ecdsa.PublicKey)
	if !ok {
		return nil, errors.New("key")
	}
	ecdhPrivate, err := private.ECDH()
	if err != nil {
		return nil, err
	}
	ecdhPublic, err := public.ECDH()
	if err != nil {
		return nil, err
	}
	return ecdhPrivate.ECDH(ecdhPublic)
}

func DeriveKey(secret, salt, info []byte) ([]byte, error) {
	if len(secret) != 32 || len(salt) == 0 {
		return nil, errors.New("length")
	}
	prk := hmacSHA256(salt, secret)
	block := append(append([]byte{}, info...), 0x01)
	return hmacSHA256(prk, block), nil
}

func DirectionInfo(direction string, macID, phoneID []byte) ([]byte, error) {
	if len(macID) != 16 || len(phoneID) != 16 {
		return nil, errors.New("length")
	}
	var label string
	switch direction {
	case "mac-to-phone":
		label = "phone-station link-v2 mac-to-phone"
	case "phone-to-mac":
		label = "phone-station link-v2 phone-to-mac"
	default:
		return nil, errors.New("direction")
	}
	out := make([]byte, 0, len(label)+32)
	out = append(out, label...)
	out = append(out, macID...)
	out = append(out, phoneID...)
	return out, nil
}

func Nonce(direction string, seq uint64) ([]byte, error) {
	var constant uint32
	switch direction {
	case "mac-to-phone":
		constant = 1
	case "phone-to-mac":
		constant = 2
	default:
		return nil, errors.New("direction")
	}
	out := make([]byte, 12)
	binary.BigEndian.PutUint32(out[:4], constant)
	binary.BigEndian.PutUint64(out[4:], seq)
	return out, nil
}

func AAD(kind, flags byte, channel uint32, seq uint64) []byte {
	out := make([]byte, 16)
	out[0] = Version
	out[1] = kind
	out[2] = flags
	binary.BigEndian.PutUint32(out[4:8], channel)
	binary.BigEndian.PutUint64(out[8:], seq)
	return out
}

func Seal(key, nonce, plaintext, aad []byte) ([]byte, error) {
	gcm, err := gcmCipher(key, nonce)
	if err != nil {
		return nil, err
	}
	return gcm.Seal(nil, nonce, plaintext, aad), nil
}

func Open(key, nonce, ciphertext, aad []byte) ([]byte, error) {
	gcm, err := gcmCipher(key, nonce)
	if err != nil {
		return nil, err
	}
	return gcm.Open(nil, nonce, ciphertext, aad)
}

func gcmCipher(key, nonce []byte) (cipher.AEAD, error) {
	if len(key) != 32 || len(nonce) != 12 {
		return nil, errors.New("length")
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	return cipher.NewGCM(block)
}

func hmacSHA256(key, data []byte) []byte {
	mac := hmac.New(sha256.New, key)
	_, _ = mac.Write(data)
	return mac.Sum(nil)
}
