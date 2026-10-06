package relay

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"
)

type controlFixture struct {
	t    *testing.T
	s    *Server
	http *httptest.Server
	ctx  context.Context
}

func newControlFixture(t *testing.T) *controlFixture {
	t.Helper()
	s := openTest(t, durableConfig(t.TempDir()))
	server := httptest.NewTLSServer(s)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	t.Cleanup(func() { cancel(); s.Close(); server.Close() })
	return &controlFixture{t, s, server, ctx}
}
func (f *controlFixture) dial(path, role string) *websocket.Conn {
	f.t.Helper()
	c, _, err := websocket.Dial(f.ctx, f.http.URL+path, &websocket.DialOptions{HTTPClient: f.http.Client(), HTTPHeader: http.Header{"Authorization": []string{"Bearer " + role}}})
	if err != nil {
		f.t.Fatal(err)
	}
	f.t.Cleanup(func() { c.CloseNow() })
	return c
}
func (f *controlFixture) read(c *websocket.Conn, kind string) map[string]any {
	f.t.Helper()
	for {
		_, data, err := c.Read(f.ctx)
		if err != nil {
			f.t.Fatal(err)
		}
		var frame map[string]any
		if json.Unmarshal(data, &frame) != nil {
			f.t.Fatal("JSON", string(data))
		}
		if frame["type"] == kind {
			return frame
		}
		if frame["type"] == "heartbeat" {
			f.write(c, map[string]string{"type": "heartbeat"})
			continue
		}
		f.t.Fatalf("wanted %s, got %s", kind, data)
	}
}
func (f *controlFixture) write(c *websocket.Conn, frame any) {
	f.t.Helper()
	data, _ := json.Marshal(frame)
	if err := c.Write(f.ctx, websocket.MessageText, data); err != nil {
		f.t.Fatal(err)
	}
}
func TestControlTLSReceiptRecoveryAndTakeover(t *testing.T) {
	f := newControlFixture(t)
	c := f.dial("/v1/phone/control", f.s.config.PhoneToken)
	f.read(c, "ready")
	id := newID(f.s, "a")
	done, cancel := beginCall(t, f.s, id)
	defer cancel()
	if got := f.read(c, "operation"); got["operationId"] != id {
		t.Fatal(got)
	}
	replacement := f.dial("/v1/phone/control", f.s.config.PhoneToken)
	f.read(replacement, "ready")
	if _, _, err := c.Read(f.ctx); err == nil {
		t.Fatal("obsolete network remained open")
	}
	// Same original result may be sent on the new connection; no operation is redispatched.
	result := map[string]any{"type": "result", "operationId": id, "response": map[string]any{"status": 200, "body": map[string]any{"result": "once"}}}
	f.write(replacement, result)
	if ack := f.read(replacement, "result"); ack["status"] != float64(200) {
		t.Fatal(ack)
	}
	if (<-done).Code != 200 {
		t.Fatal("desktop reply")
	}
	f.write(replacement, result)
	if ack := f.read(replacement, "result"); ack["status"] != float64(200) {
		t.Fatal("lost acknowledgement not recoverable", ack)
	}
	result["response"] = map[string]any{"status": 200, "body": map[string]any{"result": "changed"}}
	f.write(replacement, result)
	if ack := f.read(replacement, "result"); ack["status"] != float64(409) {
		t.Fatal("conflict", ack)
	}
	f.s.Close()
	restored := openTest(t, f.s.config)
	if response := rpc(restored, "/v1/desktop/call", restored.config.DesktopToken, callBody(id)); response.Code != 200 || !strings.Contains(response.Body.String(), "once") {
		t.Fatal("receipt not durable", response.Body.String())
	}
}
func TestControlSecurityAndStorageFailure(t *testing.T) {
	f := newControlFixture(t)
	for _, tc := range []struct {
		path, token, origin string
		code                int
	}{
		{"/v1/phone/control", f.s.config.DesktopToken, "", 401},
		{"/v1/desktop/events", f.s.config.PhoneToken, "", 401},
		{"/v1/phone/control", f.s.config.PhoneToken, "https://example.com", 403},
		{"/v1/phone/control?token=x", f.s.config.PhoneToken, "", 400},
	} {
		headers := http.Header{"Authorization": []string{"Bearer " + tc.token}}
		if tc.origin != "" {
			headers.Set("Origin", tc.origin)
		}
		c, r, err := websocket.Dial(f.ctx, f.http.URL+tc.path, &websocket.DialOptions{HTTPClient: f.http.Client(), HTTPHeader: headers})
		if c != nil {
			c.CloseNow()
		}
		if err == nil || r == nil || r.StatusCode != tc.code {
			t.Fatal("boundary", tc, r, err)
		}
	}
	c := f.dial("/v1/phone/control", f.s.config.PhoneToken)
	f.read(c, "ready")
	f.s.device.mu.Lock()
	f.s.store.err = errors.New("test disk failed")
	signal(f.s.device)
	f.s.device.mu.Unlock()
	if _, _, err := c.Read(f.ctx); err == nil {
		t.Fatal("storage failure kept dispatcher online")
	}
}
func TestControlHeartbeatDetectsSilentPeer(t *testing.T) {
	s := New(Config{PhoneToken: "phone"})
	defer s.Close()
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		s.controlWithTiming(w, r, 20*time.Millisecond, 80*time.Millisecond)
	}))
	defer server.Close()
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	c, _, err := websocket.Dial(ctx, server.URL, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer c.CloseNow()
	heartbeats := 0
	for {
		_, data, err := c.Read(ctx)
		if err != nil {
			break
		}
		if strings.Contains(string(data), "heartbeat") {
			heartbeats++
		}
	}
	if heartbeats == 0 || ctx.Err() != nil {
		t.Fatal("silent peer was not evicted")
	}
	deadline := time.Now().Add(time.Second)
	for {
		s.device.mu.Lock()
		offline := !s.device.control && s.device.lastSeen.IsZero()
		s.device.mu.Unlock()
		if offline {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("dead connection reported online")
		}
		time.Sleep(time.Millisecond)
	}
}
func TestEventSubscriptionMetadataAndDisconnect(t *testing.T) {
	f := newControlFixture(t)
	phone := f.dial("/v1/phone/control", f.s.config.PhoneToken)
	f.read(phone, "ready")
	desktop := f.dial("/v1/desktop/events", f.s.config.DesktopToken)
	f.read(desktop, "ready")
	f.write(desktop, map[string]string{"type": "subscribe", "requestId": "request", "clipboard": "clip-client", "notifications": "note-client"})
	pending := f.read(desktop, "subscribing")
	sub := f.read(phone, "subscribe")
	if sub["clipboard"] != "clip-client" || sub["subscriptionId"] != pending["subscriptionId"] {
		t.Fatal(sub, pending)
	}
	f.write(phone, map[string]any{"type": "subscribed", "subscriptionId": sub["subscriptionId"], "clipboard": true, "notifications": true})
	if ack := f.read(desktop, "subscribed"); ack["clipboard"] != true {
		t.Fatal(ack)
	}
	f.write(phone, map[string]any{"type": "event", "subscriptionId": sub["subscriptionId"], "topic": "notifications", "text": "must not forward"})
	event := f.read(desktop, "event")
	if len(event) != 3 || event["topic"] != "notifications" {
		t.Fatal("content crossed invalidation stream", event)
	}
	desktop.CloseNow()
	cleared := f.read(phone, "subscribe")
	if cleared["subscriptionId"] != "" || cleared["clipboard"] != "" || cleared["notifications"] != "" {
		t.Fatal("lease not revoked", cleared)
	}
}

func TestLegacyPollCanReplaceControlWithoutReplaying(t *testing.T) {
	f := newControlFixture(t)
	c := f.dial("/v1/phone/control", f.s.config.PhoneToken)
	f.read(c, "ready")
	id := newID(f.s, "b")
	done, cancel := beginCall(t, f.s, id)
	defer cancel()
	f.read(c, "operation")
	// A rolling downgrade can reclaim the receiver but cannot receive this running mutation again.
	response := rpc(f.s, "/v1/phone/poll", f.s.config.PhoneToken, map[string]int{"waitMs": 250})
	if response.Code != 200 || !strings.Contains(response.Body.String(), `"idle":true`) {
		t.Fatal(response.Body.String())
	}
	if _, _, err := c.Read(f.ctx); err == nil {
		t.Fatal("legacy takeover did not close old socket")
	}
	result := rpc(f.s, "/v1/phone/result", f.s.config.PhoneToken, map[string]any{"operationId": id, "response": map[string]any{"status": 200, "body": map[string]any{"result": true}}})
	if result.Code != 200 || (<-done).Code != 200 {
		t.Fatal("original result no longer recoverable")
	}
}
func TestControlRejectsAmbiguousJSON(t *testing.T) {
	for _, body := range []string{
		`{"type":"heartbeat","type":"result"}`,
		`{"type":"heartbeat","\u0074ype":"result"}`,
		`{"type":"heartbeat"} {}`,
		`{"type":"not-a-control-frame"}`,
	} {
		t.Run(body, func(t *testing.T) {
			f := newControlFixture(t)
			c := f.dial("/v1/phone/control", f.s.config.PhoneToken)
			f.read(c, "ready")
			if err := c.Write(f.ctx, websocket.MessageText, []byte(body)); err != nil {
				t.Fatal(err)
			}
			if _, _, err := c.Read(f.ctx); err == nil || f.ctx.Err() != nil {
				t.Fatal("invalid message remained connected")
			}
		})
	}
}
