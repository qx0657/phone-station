package link

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"testing"
)

func TestTranscriptBytes(t *testing.T) {
	station := bytes.Repeat([]byte{0x11}, 16)
	nonce := bytes.Repeat([]byte{0x22}, 32)
	device := bytes.Repeat([]byte{0x33}, 16)
	code := bytes.Repeat([]byte{0x44}, 16)
	invitation := bytes.Repeat([]byte{0x55}, 16)
	enroll, err := EnrollMessage(station, nonce, device, code, "main")
	join, err2 := JoinMessage(station, nonce, device, invitation, "main")
	approve, err3 := ApproveMessage(station, invitation, device)
	proof, err4 := InvitationProof(bytes.Repeat([]byte{0x66}, 16), bytes.Repeat([]byte{0x77}, 4), "n", "phone", "main")
	if err != nil || err2 != nil || err3 != nil || err4 != nil {
		t.Fatal(err, err2, err3, err4)
	}
	if got := sha256.Sum256(enroll); hex.EncodeToString(got[:]) != "28b265654ea39c8988ce7515423255e3295b4e86ebff878c48473487de36002f" {
		t.Fatalf("enroll %x", got)
	}
	if got := sha256.Sum256(join); hex.EncodeToString(got[:]) != "e47093674462494c7f377d111faf45bd8a25e6b7be7dda25d84402d6812fced3" {
		t.Fatalf("join %x", got)
	}
	if got := sha256.Sum256(approve); hex.EncodeToString(got[:]) != "26e8fd0aaf218a1e8063941858db263430729067dd2ddfd727176484f0ce1e97" {
		t.Fatalf("approve %x", got)
	}
	if hex.EncodeToString(proof) != "fbe5926156331eed23633774e48e7b2b213f4618762151325811bbca517cb5d3" {
		t.Fatalf("proof %x", proof)
	}
	local, err5 := LocalAuthMessage(bytes.Repeat([]byte{0x11}, 16), bytes.Repeat([]byte{0x22}, 32), bytes.Repeat([]byte{0x33}, 16))
	if err5 != nil {
		t.Fatal(err5)
	}
	if got := sha256.Sum256(local); hex.EncodeToString(got[:]) != "43d6e3b24dbca9a048816e036a08a793a0705f7d97cdaaa4172aedfc1b4c0b40" {
		t.Fatalf("local %x", got)
	}
	if _, err := EnrollMessage(station, nonce, device, code, "side"); err == nil {
		t.Fatal("purpose")
	}
	if _, err := InvitationProof(bytes.Repeat([]byte{0x66}, 16), []byte{1}, "", "phone", "main"); err == nil {
		t.Fatal("name")
	}
}
