package link

import (
	"bytes"
	"testing"
	"time"
)

func TestRequireSealRefusesLocal(t *testing.T) {
	session := &LocalSession{engine: NewEndpoint(true, 0, 0, 0, time.Now())}
	err := session.RequireSeal(Identity{Sign: func([]byte) ([]byte, error) { return []byte{1}, nil }}, []byte{1})
	if err == nil || err.Error() != "local" {
		t.Fatal(err)
	}
}

func TestSealCodecHidesBodyAndRejectsPlaintext(t *testing.T) {
	macKey, macSPKI, err := newEphemeral()
	if err != nil {
		t.Fatal(err)
	}
	phoneKey, phoneSPKI, err := newEphemeral()
	if err != nil {
		t.Fatal(err)
	}
	var macID, phoneID [16]byte
	macID[0] = 1
	phoneID[0] = 2
	mac := newSealCodec(true, Identity{}, phoneSPKI, macID, phoneID)
	phone := newSealCodec(false, Identity{}, macSPKI, macID, phoneID)
	macNonce := bytes.Repeat([]byte{3}, 32)
	phoneNonce := bytes.Repeat([]byte{4}, 32)
	if err := mac.install(macKey, phoneSPKI, macNonce, phoneNonce); err != nil {
		t.Fatal(err)
	}
	if err := phone.install(phoneKey, macSPKI, macNonce, phoneNonce); err != nil {
		t.Fatal(err)
	}
	secret := []byte("seal-body-9f3a")
	sealed, err := mac.Seal(Frame{Kind: KindMessage, Flags: FlagEnd, Seq: 1, Payload: secret})
	if err != nil || bytes.Contains(sealed.Payload, secret) || sealed.Flags&FlagEncrypted == 0 {
		t.Fatal(err)
	}
	opened, err := phone.Open(sealed)
	if err != nil || !bytes.Equal(opened.Payload, secret) {
		t.Fatalf("%v %q", err, opened.Payload)
	}
	if _, err := phone.Open(sealed); err == nil || err.Error() != "replay" {
		t.Fatal(err)
	}
	tamper := newSealCodec(false, Identity{}, macSPKI, macID, phoneID)
	if err := tamper.install(phoneKey, macSPKI, macNonce, phoneNonce); err != nil {
		t.Fatal(err)
	}
	bad := sealed
	bad.Payload = append([]byte(nil), sealed.Payload...)
	bad.Payload[len(bad.Payload)-1] ^= 1
	if _, err := tamper.Open(bad); err == nil || err.Error() != "open" {
		t.Fatal(err)
	}
	if _, err := phone.Open(Frame{Kind: KindMessage, Flags: FlagEnd, Seq: 2, Payload: secret}); err == nil || err.Error() != "plaintext" {
		t.Fatal(err)
	}
	bare := newSealCodec(true, Identity{}, phoneSPKI, macID, phoneID)
	if _, err := bare.Seal(Frame{Kind: KindMessage, Flags: FlagEnd, Seq: 1, Payload: secret}); err == nil || err.Error() != "plaintext" {
		t.Fatal(err)
	}
}

func TestSealTranscriptPrefixes(t *testing.T) {
	macID := bytes.Repeat([]byte{1}, 16)
	phoneID := bytes.Repeat([]byte{2}, 16)
	nonce := bytes.Repeat([]byte{4}, 32)
	open, err := sealOpenMessage(macID, phoneID, []byte{3}, nonce)
	if err != nil || !bytes.HasPrefix(open, []byte(sealOpenLabel)) || len(open) != len(sealOpenLabel)+16+16+1+32 {
		t.Fatal(err, len(open))
	}
	accept, err := sealAcceptMessage(macID, phoneID, []byte{5}, nonce, []byte{6}, nonce)
	if err != nil || !bytes.HasPrefix(accept, []byte(sealAcceptLabel)) {
		t.Fatal(err)
	}
}
