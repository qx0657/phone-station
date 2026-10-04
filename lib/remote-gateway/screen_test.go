package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/qx0657/phone-station/server/relay"
)

func TestScreenThroughPinnedGateway(t *testing.T) {
	backend, err := relay.Open(relay.Config{DeviceID: "screen-device", PhoneToken: "phone", DesktopToken: "desktop", StateDir: t.TempDir()})
	if err != nil {
		t.Fatal(err)
	}
	defer backend.Close()
	relay := httptest.NewTLSServer(backend)
	defer relay.Close()
	fingerprint := sha256.Sum256(relay.Certificate().RawSubjectPublicKeyInfo)
	value := config{GatewayToken: "local", RemoteURL: relay.URL, RemotePin: hex.EncodeToString(fingerprint[:])}
	g := &gateway{readConfig: func() (config, error) { return value, nil }, screenToken: func(config) (string, error) { return "desktop", nil }}
	server := httptest.NewServer(g)
	defer server.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	id := strings.Repeat("c", 32)
	phone, _, err := websocket.Dial(ctx, strings.Replace(relay.URL, "https://", "wss://", 1)+"/v1/screen/phone/"+id, &websocket.DialOptions{HTTPClient: relay.Client(), HTTPHeader: http.Header{"Authorization": []string{"Bearer phone"}}})
	if err != nil {
		t.Fatal(err)
	}
	defer phone.CloseNow()
	url := strings.Replace(server.URL, "http://", "ws://", 1) + "/__screen/" + id
	_, response, err := websocket.Dial(ctx, url, &websocket.DialOptions{HTTPHeader: http.Header{"Authorization": []string{"Bearer status-client"}}})
	if err == nil || response.StatusCode != 401 {
		t.Fatal("restricted token was allowed")
	}
	bad := value
	bad.RemotePin = strings.Repeat("0", 64)
	badGateway := httptest.NewServer(&gateway{readConfig: func() (config, error) { return bad, nil }, screenToken: func(config) (string, error) { return "desktop", nil }})
	badURL := strings.Replace(badGateway.URL, "http://", "ws://", 1) + "/__screen/" + id
	_, badResponse, badErr := websocket.Dial(ctx, badURL, &websocket.DialOptions{HTTPHeader: http.Header{"Authorization": []string{"Bearer local"}}})
	badGateway.Close()
	if badErr == nil || badResponse.StatusCode != 502 {
		t.Fatal("wrong TLS pin accepted")
	}
	desktop, _, err := websocket.Dial(ctx, url, &websocket.DialOptions{HTTPHeader: http.Header{"Authorization": []string{"Bearer local"}}})
	if err != nil {
		t.Fatal(err)
	}
	defer desktop.CloseNow()
	_, _, err = phone.Read(ctx)
	if err != nil {
		t.Fatal("ready", err)
	}
	if err = phone.Write(ctx, websocket.MessageBinary, []byte{1, 2, 3}); err != nil {
		t.Fatal(err)
	}
	_, b, err := desktop.Read(ctx)
	if err != nil || string(b) != string([]byte{1, 2, 3}) {
		t.Fatal("pinned video", err)
	}
	if err = desktop.Write(ctx, websocket.MessageBinary, []byte{4, 0}); err != nil {
		t.Fatal(err)
	}
	_, b, err = phone.Read(ctx)
	if err != nil || string(b) != string([]byte{4, 0}) {
		t.Fatal("control", err)
	}
	desktop.CloseNow()
	phone.CloseNow()
}
