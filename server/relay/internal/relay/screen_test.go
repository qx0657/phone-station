package relay

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"
)

func TestScreenIsolationAndRetirement(t *testing.T) {
	s := New(Config{DeviceID: "screen-test", PhoneToken: "phone", DesktopToken: "desktop"})
	defer s.Close()
	server := httptest.NewServer(s)
	defer server.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	endpoint := strings.Replace(server.URL, "http://", "ws://", 1) + "/v1/screen/"
	id := strings.Repeat("a", 32)
	dial := func(role, token, session string) (*websocket.Conn, *http.Response, error) {
		return websocket.Dial(ctx, endpoint+role+"/"+session, &websocket.DialOptions{HTTPHeader: http.Header{"Authorization": []string{"Bearer " + token}}})
	}
	if c, r, e := dial("phone", "desktop", id); e == nil || r.StatusCode != 401 {
		if c != nil {
			c.CloseNow()
		}
		t.Fatal("role separation")
	}
	desktop, _, err := dial("desktop", "desktop", id)
	if err != nil {
		t.Fatal(err)
	}
	defer desktop.CloseNow()
	if c, r, e := dial("desktop", "desktop", id); e == nil || r.StatusCode != 409 {
		if c != nil {
			c.CloseNow()
		}
		t.Fatal("duplicate role")
	}
	phone, _, err := dial("phone", "phone", id)
	if err != nil {
		t.Fatal(err)
	}
	defer phone.CloseNow()
	_, ack, err := phone.Read(ctx)
	if err != nil || len(ack) != 0 {
		t.Fatal("pair handshake", err)
	}
	frame := []byte{1, 2, 3}
	if err = phone.Write(ctx, websocket.MessageBinary, frame); err != nil {
		t.Fatal(err)
	}
	_, got, err := desktop.Read(ctx)
	if err != nil || string(got) != string(frame) {
		t.Fatal("video", err)
	}
	input := []byte{0, 1, 2}
	if err = desktop.Write(ctx, websocket.MessageBinary, input); err != nil {
		t.Fatal(err)
	}
	_, got, err = phone.Read(ctx)
	if err != nil || string(got) != string(input) {
		t.Fatal("input", err)
	}
	s.device.mu.Lock()
	active := s.device.active
	s.device.mu.Unlock()
	if active != nil {
		t.Fatal("video occupied durable MCP queue")
	}
	desktop.CloseNow()
	deadline := time.Now().Add(3 * time.Second)
	for {
		s.screens.mu.Lock()
		_, ended := s.screens.retired[id]
		s.screens.mu.Unlock()
		if ended {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("not retired")
		}
		time.Sleep(time.Millisecond)
	}
	if c, r, e := dial("phone", "phone", id); e == nil || r.StatusCode != 409 {
		if c != nil {
			c.CloseNow()
		}
		t.Fatal("session replay")
	}
	next := strings.Repeat("b", 32)
	replacement, _, err := dial("desktop", "desktop", next)
	if err != nil {
		t.Fatal(err)
	}
	defer replacement.CloseNow()
	s.Close()
	if _, _, err = replacement.Read(ctx); err == nil {
		t.Fatal("shutdown left stream open")
	}
}

func TestScreenRejectsBrowserAndInvalidID(t *testing.T) {
	s := New(Config{PhoneToken: "phone", DesktopToken: "desktop"})
	defer s.Close()
	server := httptest.NewServer(s)
	defer server.Close()
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	endpoint := strings.Replace(server.URL, "http://", "ws://", 1) + "/v1/screen/desktop/"
	_, r, err := websocket.Dial(ctx, endpoint+strings.Repeat("a", 32), &websocket.DialOptions{HTTPHeader: http.Header{"Authorization": []string{"Bearer desktop"}, "Origin": []string{"http://localhost"}}})
	if err == nil || r.StatusCode != 403 {
		t.Fatal("browser origin")
	}
	_, r, err = websocket.Dial(ctx, endpoint+"invalid", &websocket.DialOptions{HTTPHeader: http.Header{"Authorization": []string{"Bearer desktop"}}})
	if err == nil || r.StatusCode != 404 {
		t.Fatal("invalid id")
	}
}

func TestScreenRetiredReceiptsExpire(t *testing.T) {
	s := New(Config{PhoneToken: "phone", DesktopToken: "desktop"})
	defer s.Close()
	id := strings.Repeat("d", 32)
	s.screens.retired = map[string]time.Time{id: time.Now().Add(-25 * time.Hour)}
	server := httptest.NewServer(s)
	defer server.Close()
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	c, _, err := websocket.Dial(ctx, strings.Replace(server.URL, "http://", "ws://", 1)+"/v1/screen/desktop/"+id, &websocket.DialOptions{HTTPHeader: http.Header{"Authorization": []string{"Bearer desktop"}}})
	if err != nil {
		t.Fatal("expired transient receipts accumulated", err)
	}
	c.CloseNow()
	s.Close()
}
