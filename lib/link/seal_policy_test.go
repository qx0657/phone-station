package link

import (
	"bytes"
	"testing"
	"time"
)

func TestSealChoiceRejectsPlaintextWhenRequired(t *testing.T) {
	if err := (SealChoice{}).Admit(0); err != nil {
		t.Fatal(err)
	}
	if err := (SealChoice{Required: true}).Admit(0); err == nil || err.Error() != "plaintext" {
		t.Fatal(err)
	}
	if err := (SealChoice{Required: true}).Admit(FlagEncrypted); err != nil {
		t.Fatal(err)
	}
	if err := (SealChoice{Required: true, Local: true}).Admit(0); err != nil {
		t.Fatal("local downgrade")
	}
	if err := (SealChoice{Local: true}).Admit(FlagEncrypted); err == nil {
		t.Fatal("local encrypted")
	}
	secret := []byte("media-plain-secret")
	if kept, err := AdmitApplication(true, Frame{Kind: KindMessage, Payload: secret}); err == nil || err.Error() != "plaintext" || len(kept.Payload) != 0 {
		t.Fatal(err, kept.Payload)
	}
	sealed := Frame{Kind: KindData, Flags: FlagEncrypted, Payload: []byte("ciphertext")}
	got, err := AdmitApplication(true, sealed)
	if err != nil || !bytes.Equal(got.Payload, sealed.Payload) || got.Flags&FlagEncrypted == 0 {
		t.Fatal(err, got.Flags)
	}
}

func TestReplayAndRekey(t *testing.T) {
	var window ReplayWindow
	now := time.Unix(1_700_000_000, 0)
	if err := window.Accept(1, now); err != nil {
		t.Fatal(err)
	}
	if err := window.Accept(1, now); err == nil || err.Error() != "replay" {
		t.Fatal(err)
	}
	if err := window.Accept(2, now); err != nil {
		t.Fatal(err)
	}
	window.frames = 1 << 32
	if err := window.Accept(3, now); err == nil || err.Error() != "rekey" {
		t.Fatal(err)
	}
	window.frames = 1
	window.start = now.Add(-24 * time.Hour)
	if err := window.Accept(4, now); err == nil || err.Error() != "rekey" {
		t.Fatal(err)
	}
	key := bytes.Repeat([]byte{7}, 32)
	nonce := []byte{0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 9}
	aad := AAD(KindMessage, FlagEncrypted, 0, 9)
	sealed, err := Seal(key, nonce, []byte("hello link"), aad)
	if err != nil {
		t.Fatal(err)
	}
	sealed[len(sealed)-1] ^= 1
	if _, err := Open(key, nonce, sealed, aad); err == nil {
		t.Fatal("tamper")
	}
}
