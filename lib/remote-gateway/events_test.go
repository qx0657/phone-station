package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/qx0657/phone-station/server/relay"
)

func TestEventsThroughPinnedGatewayAndCredentialChange(t *testing.T) {
	backend, err := relay.Open(relay.Config{PhoneToken: "phone", DesktopToken: "desktop", StateDir: t.TempDir()})
	if err != nil {
		t.Fatal(err)
	}
	defer backend.Close()
	remote := httptest.NewTLSServer(backend)
	defer remote.Close()
	pin := sha256.Sum256(remote.Certificate().RawSubjectPublicKeyInfo)
	value := config{GatewayToken: "native", RemoteURL: remote.URL, RemotePin: hex.EncodeToString(pin[:])}
	var mu sync.Mutex
	g := &gateway{readConfig: func() (config, error) { mu.Lock(); defer mu.Unlock(); return value, nil }, screenToken: func(config) (string, error) { return "desktop", nil }}
	local := httptest.NewServer(g)
	defer local.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	for _, header := range []http.Header{
		{"Authorization": []string{"Bearer restricted"}},
		{"Authorization": []string{"Bearer native"}, "Origin": []string{local.URL}},
	} {
		c, response, err := websocket.Dial(ctx, local.URL+"/__subscription", &websocket.DialOptions{HTTPHeader: header})
		if c != nil {
			c.CloseNow()
		}
		if err == nil || response.StatusCode < 400 {
			t.Fatal("event permission boundary")
		}
	}
	c, _, err := websocket.Dial(ctx, local.URL+"/__subscription", &websocket.DialOptions{HTTPHeader: http.Header{"Authorization": []string{"Bearer native"}}})
	if err != nil {
		t.Fatal(err)
	}
	defer c.CloseNow()
	_, data, err := c.Read(ctx)
	if err != nil {
		t.Fatal(err)
	}
	var frame map[string]any
	json.Unmarshal(data, &frame)
	if frame["protocol"] != "events-v1" {
		t.Fatal("relay event handshake not forwarded", frame)
	}
	if err = c.Write(ctx, websocket.MessageText, []byte(`{"type":"subscribe","requestId":"one","clipboard":"client"}`)); err != nil {
		t.Fatal(err)
	}
	_, data, err = c.Read(ctx)
	if err != nil {
		t.Fatal(err)
	}
	json.Unmarshal(data, &frame)
	if frame["type"] != "subscribing" || frame["requestId"] != "one" {
		t.Fatal(frame)
	}
	mu.Lock()
	value.RemoteRevision = "changed"
	mu.Unlock()
	if _, _, err = c.Read(ctx); err == nil || ctx.Err() != nil {
		t.Fatal("configuration change did not revoke old event stream", err)
	}
}
