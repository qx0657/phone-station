package link

import (
	"bytes"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/hex"
	"encoding/json"
	"math/big"
	"os"
	"path/filepath"
	"runtime"
	"testing"

	"crypto/ecdh"
)

type vectorFile struct {
	Note         string         `json:"note"`
	MainPayload  int            `json:"mainPayload"`
	MediaPayload int            `json:"mediaPayload"`
	Frames       []frameVector  `json:"frames"`
	Auth         []authVector   `json:"auth"`
	Safety       []safetyVector `json:"safety"`
	Seal         []sealVector   `json:"seal"`
}

type frameVector struct {
	Name    string `json:"name"`
	Hex     string `json:"hex"`
	Limit   int    `json:"limit"`
	Reject  string `json:"reject,omitempty"`
	Kind    byte   `json:"kind,omitempty"`
	Flags   byte   `json:"flags,omitempty"`
	Peer    string `json:"peer,omitempty"`
	Channel uint32 `json:"channel,omitempty"`
	Seq     uint64 `json:"seq,omitempty"`
	Payload string `json:"payload,omitempty"`
}

type authVector struct {
	Name      string `json:"name"`
	Kind      string `json:"kind"`
	StationID string `json:"stationId,omitempty"`
	Nonce     string `json:"nonce"`
	DeviceID  string `json:"deviceId"`
	PeerID    string `json:"peerId,omitempty"`
	OwnID     string `json:"ownId,omitempty"`
	Purpose   string `json:"purpose,omitempty"`
	SPKI      string `json:"spki"`
	Sig       string `json:"sig"`
	OK        bool   `json:"ok"`
}

type safetyVector struct {
	Name         string `json:"name"`
	InviterSPKI  string `json:"inviterSpki"`
	DeviceSPKI   string `json:"deviceSpki"`
	InvitationID string `json:"invitationId"`
	RelaySPKI    string `json:"relaySpki"`
	Code         string `json:"code"`
}

type sealVector struct {
	Name       string `json:"name"`
	Direction  string `json:"direction"`
	MacPKCS8   string `json:"macPkcs8"`
	MacSPKI    string `json:"macSpki"`
	PhonePKCS8 string `json:"phonePkcs8"`
	PhoneSPKI  string `json:"phoneSpki"`
	MacID      string `json:"macId"`
	PhoneID    string `json:"phoneId"`
	MacNonce   string `json:"macNonce"`
	PhoneNonce string `json:"phoneNonce"`
	Shared     string `json:"shared"`
	Seq        uint64 `json:"seq"`
	Kind       byte   `json:"kind"`
	Flags      byte   `json:"flags"`
	Channel    uint32 `json:"channel"`
	Plaintext  string `json:"plaintext"`
	AAD        string `json:"aad"`
	Ciphertext string `json:"ciphertext"`
}

func TestVectors(t *testing.T) {
	path := vectorPath()
	if os.Getenv("LINK_WRITE_VECTORS") == "1" {
		if err := os.MkdirAll(filepath.Dir(path), 0755); err != nil {
			t.Fatal(err)
		}
		body, err := json.MarshalIndent(buildVectors(t), "", "  ")
		if err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(path, append(body, '\n'), 0644); err != nil {
			t.Fatal(err)
		}
	}
	body, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	var file vectorFile
	if err := json.Unmarshal(body, &file); err != nil {
		t.Fatal(err)
	}
	checkVectors(t, file)
}

func checkVectors(t *testing.T, file vectorFile) {
	t.Helper()
	if file.MainPayload != MainPayload || file.MediaPayload != MediaPayload {
		t.Fatal("payload limits")
	}
	for _, item := range file.Frames {
		raw, err := hex.DecodeString(item.Hex)
		if err != nil {
			t.Fatal(item.Name)
		}
		frame, err := Decode(raw, item.Limit)
		if item.Reject != "" {
			if err == nil || err.Error() != item.Reject {
				t.Fatalf("%s reject", item.Name)
			}
			continue
		}
		if err != nil {
			t.Fatalf("%s %v", item.Name, err)
		}
		peer, err := hex.DecodeString(item.Peer)
		payload, err2 := hex.DecodeString(item.Payload)
		if err != nil || err2 != nil || frame.Kind != item.Kind || frame.Flags != item.Flags || !bytes.Equal(frame.Peer[:], peer) || frame.Channel != item.Channel || frame.Seq != item.Seq || !bytes.Equal(frame.Payload, payload) {
			t.Fatalf("%s fields", item.Name)
		}
		again, err := Encode(frame, item.Limit)
		if err != nil || !bytes.Equal(again, raw) {
			t.Fatalf("%s encode", item.Name)
		}
	}
	for _, item := range file.Auth {
		spki := mustHex(t, item.SPKI)
		if !bytes.Equal(DeviceID(spki), mustHex(t, item.DeviceID)) && item.Kind == "relay" {
			t.Fatalf("%s device", item.Name)
		}
		var message []byte
		var err error
		if item.Kind == "relay" {
			message, err = RelayAuthMessage(mustHex(t, item.StationID), mustHex(t, item.Nonce), mustHex(t, item.DeviceID), item.Purpose)
		} else {
			message, err = LocalAuthMessage(mustHex(t, item.PeerID), mustHex(t, item.Nonce), mustHex(t, item.OwnID))
			if !bytes.Equal(DeviceID(spki), mustHex(t, item.OwnID)) {
				t.Fatalf("%s own device", item.Name)
			}
		}
		if err != nil {
			t.Fatal(item.Name)
		}
		err = VerifySignature(spki, message, mustHex(t, item.Sig))
		if item.OK != (err == nil) {
			t.Fatalf("%s signature", item.Name)
		}
	}
	for _, item := range file.Safety {
		code, err := SafetyCode(mustHex(t, item.InviterSPKI), mustHex(t, item.DeviceSPKI), mustHex(t, item.InvitationID), mustHex(t, item.RelaySPKI))
		if err != nil || code != item.Code || len(code) != 6 {
			t.Fatalf("%s safety", item.Name)
		}
	}
	for _, item := range file.Seal {
		shared, err := SharedSecret(mustHex(t, item.MacPKCS8), mustHex(t, item.PhoneSPKI))
		other, err2 := SharedSecret(mustHex(t, item.PhonePKCS8), mustHex(t, item.MacSPKI))
		if err != nil || err2 != nil || !bytes.Equal(shared, other) || !bytes.Equal(shared, mustHex(t, item.Shared)) {
			t.Fatalf("%s shared", item.Name)
		}
		info, err := DirectionInfo(item.Direction, mustHex(t, item.MacID), mustHex(t, item.PhoneID))
		salt := append(mustHex(t, item.MacNonce), mustHex(t, item.PhoneNonce)...)
		key, err2 := DeriveKey(shared, salt, info)
		nonce, err3 := Nonce(item.Direction, item.Seq)
		aad := AAD(item.Kind, item.Flags, item.Channel, item.Seq)
		if err != nil || err2 != nil || err3 != nil || !bytes.Equal(aad, mustHex(t, item.AAD)) {
			t.Fatalf("%s key", item.Name)
		}
		plain := mustHex(t, item.Plaintext)
		sealed, err := Seal(key, nonce, plain, aad)
		opened, err2 := Open(key, nonce, mustHex(t, item.Ciphertext), aad)
		if err != nil || err2 != nil || !bytes.Equal(sealed, mustHex(t, item.Ciphertext)) || !bytes.Equal(opened, plain) {
			t.Fatalf("%s seal", item.Name)
		}
		broken := append([]byte(nil), sealed...)
		broken[len(broken)-1] ^= 0x01
		if _, err := Open(key, nonce, broken, aad); err == nil {
			t.Fatalf("%s tamper", item.Name)
		}
	}
}

func buildVectors(t *testing.T) vectorFile {
	t.Helper()
	mac := testKey(1)
	phone := testKey(2)
	macEphemeral := testKey(3)
	phoneEphemeral := testKey(4)
	relay := testKey(5)
	macSPKI := mustSPKI(t, &mac.PublicKey)
	phoneSPKI := mustSPKI(t, &phone.PublicKey)
	relaySPKI := mustSPKI(t, &relay.PublicKey)
	macID := DeviceID(macSPKI)
	phoneID := DeviceID(phoneSPKI)
	station := bytes.Repeat([]byte{0x11}, 16)
	nonce := bytes.Repeat([]byte{0x22}, 32)
	phoneNonce := bytes.Repeat([]byte{0x33}, 32)
	relayMessage, err := RelayAuthMessage(station, nonce, macID, "main")
	if err != nil {
		t.Fatal(err)
	}
	relaySig := mustSign(t, mac, relayMessage)
	badSig := append([]byte(nil), relaySig...)
	badSig[len(badSig)-1] ^= 0x01
	localMessage, err := LocalAuthMessage(macID, nonce, phoneID)
	if err != nil {
		t.Fatal(err)
	}
	localSig := mustSign(t, phone, localMessage)
	invitation := bytes.Repeat([]byte{0x44}, 16)
	code, err := SafetyCode(macSPKI, phoneSPKI, invitation, relaySPKI)
	if err != nil {
		t.Fatal(err)
	}
	otherInvitation := bytes.Repeat([]byte{0x45}, 16)
	otherCode, err := SafetyCode(macSPKI, phoneSPKI, otherInvitation, relaySPKI)
	if err != nil || otherCode == code {
		t.Fatal("safety collision")
	}
	ping := Frame{Kind: KindLink, Payload: []byte("ping")}
	message := Frame{Kind: KindMessage, Flags: FlagMore, Peer: idArray(phoneID), Channel: 0x01020304, Seq: 0x0102030405060708, Payload: []byte(`{"type":"call"}`)}
	credit := Frame{Kind: KindCredit, Peer: idArray(phoneID), Channel: 1, Seq: 7, Payload: []byte{0x00, 0x02, 0x00, 0x00}}
	file := vectorFile{
		Note:         "固定测试密钥，私钥标量是 1 到 5。不能当作设备密钥。",
		MainPayload:  MainPayload,
		MediaPayload: MediaPayload,
		Frames: []frameVector{
			frameOK("link-ping", ping, MainPayload),
			frameOK("message", message, MainPayload),
			frameOK("credit", credit, MainPayload),
			frameBad("bad-version", mutate(raw(ping), func(b []byte) { b[0] = 1 }), MainPayload, "version"),
			frameBad("bad-reserved", mutate(raw(ping), func(b []byte) { b[3] = 1 }), MainPayload, "reserved"),
			frameBad("bad-kind", mutate(raw(ping), func(b []byte) { b[1] = 0 }), MainPayload, "kind"),
			frameBad("bad-flags", mutate(raw(ping), func(b []byte) { b[2] = 0x08 }), MainPayload, "flags"),
			frameBad("bad-peer", mutate(raw(ping), func(b []byte) { b[4] = 1 }), MainPayload, "peer"),
			frameBad("bad-channel", mutate(raw(ping), func(b []byte) { b[23] = 1 }), MainPayload, "channel"),
			frameBad("bad-seq", mutate(raw(ping), func(b []byte) { b[31] = 1 }), MainPayload, "seq"),
			frameBad("bad-credit", raw(Frame{Kind: KindCredit, Peer: idArray(phoneID), Payload: []byte{1, 2, 3}}), MainPayload, "credit"),
			frameBad("truncated", []byte{2, 1}, MainPayload, "truncated"),
			frameBad("over-limit", raw(ping), 3, "limit"),
		},
		Auth: []authVector{
			{Name: "relay-main", Kind: "relay", StationID: hex.EncodeToString(station), Nonce: hex.EncodeToString(nonce), DeviceID: hex.EncodeToString(macID), Purpose: "main", SPKI: hex.EncodeToString(macSPKI), Sig: hex.EncodeToString(relaySig), OK: true},
			{Name: "relay-bad-sig", Kind: "relay", StationID: hex.EncodeToString(station), Nonce: hex.EncodeToString(nonce), DeviceID: hex.EncodeToString(macID), Purpose: "main", SPKI: hex.EncodeToString(macSPKI), Sig: hex.EncodeToString(badSig), OK: false},
			{Name: "local-phone", Kind: "local", Nonce: hex.EncodeToString(nonce), DeviceID: hex.EncodeToString(phoneID), PeerID: hex.EncodeToString(macID), OwnID: hex.EncodeToString(phoneID), SPKI: hex.EncodeToString(phoneSPKI), Sig: hex.EncodeToString(localSig), OK: true},
		},
		Safety: []safetyVector{
			{Name: "pair", InviterSPKI: hex.EncodeToString(macSPKI), DeviceSPKI: hex.EncodeToString(phoneSPKI), InvitationID: hex.EncodeToString(invitation), RelaySPKI: hex.EncodeToString(relaySPKI), Code: code},
			{Name: "other-invitation", InviterSPKI: hex.EncodeToString(macSPKI), DeviceSPKI: hex.EncodeToString(phoneSPKI), InvitationID: hex.EncodeToString(otherInvitation), RelaySPKI: hex.EncodeToString(relaySPKI), Code: otherCode},
		},
	}
	for _, direction := range []string{"mac-to-phone", "phone-to-mac"} {
		file.Seal = append(file.Seal, sealSample(t, direction, macEphemeral, phoneEphemeral, macID, phoneID, nonce, phoneNonce))
	}
	return file
}

func sealSample(t *testing.T, direction string, macKey, phoneKey *ecdsa.PrivateKey, macID, phoneID, macNonce, phoneNonce []byte) sealVector {
	t.Helper()
	macPKCS8 := mustPKCS8(t, macKey)
	phonePKCS8 := mustPKCS8(t, phoneKey)
	macSPKI := mustSPKI(t, &macKey.PublicKey)
	phoneSPKI := mustSPKI(t, &phoneKey.PublicKey)
	shared, err := SharedSecret(macPKCS8, phoneSPKI)
	if err != nil {
		t.Fatal(err)
	}
	info, err := DirectionInfo(direction, macID, phoneID)
	if err != nil {
		t.Fatal(err)
	}
	key, err := DeriveKey(shared, append(append([]byte{}, macNonce...), phoneNonce...), info)
	if err != nil {
		t.Fatal(err)
	}
	const seq = 7
	kind := byte(KindMessage)
	flags := byte(FlagEncrypted)
	const channel = 0
	nonce, err := Nonce(direction, seq)
	if err != nil {
		t.Fatal(err)
	}
	aad := AAD(kind, flags, channel, seq)
	plain := []byte("hello link")
	sealed, err := Seal(key, nonce, plain, aad)
	if err != nil {
		t.Fatal(err)
	}
	return sealVector{
		Name: direction, Direction: direction,
		MacPKCS8: hex.EncodeToString(macPKCS8), MacSPKI: hex.EncodeToString(macSPKI),
		PhonePKCS8: hex.EncodeToString(phonePKCS8), PhoneSPKI: hex.EncodeToString(phoneSPKI),
		MacID: hex.EncodeToString(macID), PhoneID: hex.EncodeToString(phoneID),
		MacNonce: hex.EncodeToString(macNonce), PhoneNonce: hex.EncodeToString(phoneNonce),
		Shared: hex.EncodeToString(shared), Seq: seq, Kind: kind, Flags: flags, Channel: channel,
		Plaintext: hex.EncodeToString(plain), AAD: hex.EncodeToString(aad), Ciphertext: hex.EncodeToString(sealed),
	}
}

func frameOK(name string, frame Frame, limit int) frameVector {
	return frameVector{
		Name: name, Hex: hex.EncodeToString(raw(frame)), Limit: limit,
		Kind: frame.Kind, Flags: frame.Flags, Peer: hex.EncodeToString(frame.Peer[:]),
		Channel: frame.Channel, Seq: frame.Seq, Payload: hex.EncodeToString(frame.Payload),
	}
}

func frameBad(name string, body []byte, limit int, reject string) frameVector {
	return frameVector{Name: name, Hex: hex.EncodeToString(body), Limit: limit, Reject: reject}
}

func mutate(body []byte, change func([]byte)) []byte {
	out := append([]byte(nil), body...)
	change(out)
	return out
}

func idArray(id []byte) (out [16]byte) {
	copy(out[:], id)
	return out
}

func testKey(scalar byte) *ecdsa.PrivateKey {
	seed := make([]byte, 32)
	seed[31] = scalar
	priv, err := ecdh.P256().NewPrivateKey(seed)
	if err != nil {
		panic(err)
	}
	key := new(ecdsa.PrivateKey)
	key.PublicKey.Curve = elliptic.P256()
	key.D = new(big.Int).SetBytes(priv.Bytes())
	x, y := elliptic.Unmarshal(elliptic.P256(), priv.PublicKey().Bytes())
	key.X, key.Y = x, y
	return key
}

func mustSPKI(t *testing.T, key *ecdsa.PublicKey) []byte {
	t.Helper()
	body, err := x509.MarshalPKIXPublicKey(key)
	if err != nil {
		t.Fatal(err)
	}
	return body
}

func mustPKCS8(t *testing.T, key *ecdsa.PrivateKey) []byte {
	t.Helper()
	body, err := x509.MarshalPKCS8PrivateKey(key)
	if err != nil {
		t.Fatal(err)
	}
	return body
}

func mustSign(t *testing.T, key *ecdsa.PrivateKey, message []byte) []byte {
	t.Helper()
	sum := sha256.Sum256(message)
	sig, err := ecdsa.SignASN1(rand.Reader, key, sum[:])
	if err != nil {
		t.Fatal(err)
	}
	return sig
}

func mustHex(t *testing.T, value string) []byte {
	t.Helper()
	body, err := hex.DecodeString(value)
	if err != nil {
		t.Fatal(err)
	}
	return body
}

func vectorPath() string {
	_, file, _, _ := runtime.Caller(0)
	return filepath.Join(filepath.Dir(file), "testdata", "vectors.json")
}
