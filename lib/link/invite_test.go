package link

import (
	"bytes"
	"testing"
)

func TestRelayInviteMessageHidesNewlines(t *testing.T) {
	item := Invite{
		Endpoint: "https://127.0.0.1:24443",
		Pin:      "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff",
		ID:       "00112233445566778899aabbccddeeff",
		Key:      "ffeeddccbbaa99887766554433221100",
		SPKI:     "AQID",
		Kind:     "phone",
	}
	text, err := InviteText(item)
	if err != nil {
		t.Fatal(err)
	}
	payload, err := RelayInviteMessage(text)
	if err != nil || bytes.Contains(payload, []byte("\n")) || !bytes.Contains(payload, []byte(`"type":"relay-invite"`)) {
		t.Fatal("message")
	}
}

func TestInviteTextAndManualCode(t *testing.T) {
	item := Invite{
		Endpoint: "https://192.0.2.8:24443",
		Pin:      "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff",
		ID:       "11111111111111111111111111111111",
		Key:      "22222222222222222222222222222222",
		SPKI:     "AQID",
		Kind:     "phone",
	}
	text, err := InviteText(item)
	if err != nil {
		t.Fatal(err)
	}
	parsed, err := ParseInvite(text)
	if err != nil || parsed != item {
		t.Fatal(parsed, err)
	}
	if _, err := ParseInvite(text + "\n"); err == nil {
		t.Fatal("extra")
	}
	if _, err := ParseInvite("https://relay.example\n"); err == nil {
		t.Fatal("bare")
	}
	item.Endpoint = "http://192.0.2.8"
	if _, err := InviteText(item); err == nil {
		t.Fatal("http")
	}
	id := bytes.Repeat([]byte{0x11}, 16)
	key := bytes.Repeat([]byte{0x22}, 16)
	code, err := ManualCode(id, key)
	if err != nil || code != "111111222222" {
		t.Fatal(code, err)
	}
	idPrefix, keyPrefix, err := ParseManual(code)
	if err != nil || !bytes.Equal(idPrefix, id[:3]) || !bytes.Equal(keyPrefix, key[:3]) {
		t.Fatal(err)
	}
	if _, _, err := ParseManual("11111122222G"); err == nil {
		t.Fatal("letter")
	}
	phone := bytes.Repeat([]byte{0x77}, 32)
	mac := bytes.Repeat([]byte{0x33}, 32)
	local, err := LocalSafetyCode(phone, mac)
	if err != nil || len(local) != 6 {
		t.Fatal(local, err)
	}
	again, err := SafetyCode(phone, mac, make([]byte, 16), phone)
	if err != nil || again != local {
		t.Fatal(again, err)
	}
	nonce := bytes.Repeat([]byte{0x22}, 32)
	message, err := LocalPairMessage(phone, nonce)
	if err != nil || !bytes.HasPrefix(message, []byte("phone-station link-v2 local-pair")) || len(message) != len("phone-station link-v2 local-pair")+len(phone)+len(nonce) {
		t.Fatal(err, len(message))
	}
	if _, err := LocalPairMessage(nil, nonce); err == nil {
		t.Fatal("empty")
	}
}
