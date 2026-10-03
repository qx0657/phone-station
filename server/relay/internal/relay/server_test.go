package relay

import (
	"context"
	"crypto/sha256"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func waitPollGeneration(t *testing.T, s *Server, want uint64) {
	t.Helper()
	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		s.device.mu.Lock()
		generation := s.device.pollGeneration
		s.device.mu.Unlock()
		if generation == want {
			return
		}
		time.Sleep(time.Millisecond)
	}
	t.Fatalf("poll %d did not start", want)
}

func TestNewNetworkTakesOverVanishedWifiPoll(t *testing.T) {
	s := New(Config{PhoneToken: "phone"})
	first := httptest.NewRecorder()
	oldDone := make(chan struct{})
	go func() {
		r := httptest.NewRequest(http.MethodPost, "/v1/phone/poll", strings.NewReader(`{}`))
		r.Header.Set("Authorization", "Bearer phone")
		s.ServeHTTP(first, r) // Simulates a vanished socket whose context never cancels.
		close(oldDone)
	}()
	waitPollGeneration(t, s, 1)
	// A different role must not be allowed to replace the phone's poll.
	bad := httptest.NewRequest(http.MethodPost, "/v1/phone/poll", strings.NewReader(`{}`))
	bad.Header.Set("Authorization", "Bearer desktop")
	w := httptest.NewRecorder()
	s.ServeHTTP(w, bad)
	if w.Code != 401 {
		t.Fatal("unauthorized poll accepted")
	}

	second := httptest.NewRecorder()
	newDone := make(chan struct{})
	go func() {
		r := httptest.NewRequest(http.MethodPost, "/v1/phone/poll", strings.NewReader(`{}`))
		r.Header.Set("Authorization", "Bearer phone")
		s.ServeHTTP(second, r)
		close(newDone)
	}()
	waitPollGeneration(t, s, 2)
	select {
	case <-oldDone:
	case <-time.After(time.Second):
		t.Fatal("old Wi-Fi poll kept new connection blocked")
	}
	s.device.mu.Lock()
	if !s.device.polling {
		t.Fatal("old poll cleared the new poll's state")
	}
	op := &operation{id: "once", state: "queued", payload: []byte(`{"method":"ping"}`), createdAt: time.Now(), done: make(chan struct{})}
	s.device.active = op
	s.device.operations[op.id] = op
	signal(s.device)
	s.device.mu.Unlock()
	select {
	case <-newDone:
	case <-time.After(time.Second):
		t.Fatal("new network did not receive operation")
	}
	var reply pollReply
	if json.Unmarshal(second.Body.Bytes(), &reply) != nil || reply.OperationID != "once" || first.Body.Len() != 0 {
		t.Fatalf("operation not dispatched exclusively to new network: %s / %s", first.Body.String(), second.Body.String())
	}
}

func TestPollTakeoverDoesNotRedispatchRunningMutation(t *testing.T) {
	s := New(Config{PhoneToken: "phone"})
	op := &operation{id: "mutation", state: "running", startedAt: time.Now(), payload: []byte(`{"method":"tools/call"}`), done: make(chan struct{})}
	s.device.active = op
	s.device.operations[op.id] = op
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	w := httptest.NewRecorder()
	go func() {
		s.poll(w, httptest.NewRequest(http.MethodPost, "/v1/phone/poll", nil).WithContext(ctx))
		close(done)
	}()
	waitPollGeneration(t, s, 1)
	cancel()
	<-done
	if op.state != "running" || s.device.active != op || w.Body.Len() != 0 {
		t.Fatal("reconnected poll changed or redispatched a running mutation")
	}
}

func TestNegotiatedPollWaitBoundsIdleHandover(t *testing.T) {
	s := New(Config{PhoneToken: "phone"})
	r := httptest.NewRequest(http.MethodPost, "/v1/phone/poll", strings.NewReader(`{"waitMs":250}`))
	r.Header.Set("Authorization", "Bearer phone")
	w := httptest.NewRecorder()
	started := time.Now()
	s.ServeHTTP(w, r)
	var reply pollReply
	if w.Code != 200 || json.Unmarshal(w.Body.Bytes(), &reply) != nil || !reply.Idle {
		t.Fatalf("idle reply: %d %s", w.Code, w.Body.String())
	}
	if duration := time.Since(started); duration < 200*time.Millisecond || duration > time.Second {
		t.Fatalf("negotiated wait not honored: %s", duration)
	}
	for _, body := range []string{`{"waitMs":1}`, `{"waitMs":25001}`, `{"waitMs":-1}`} {
		r = httptest.NewRequest(http.MethodPost, "/v1/phone/poll", strings.NewReader(body))
		r.Header.Set("Authorization", "Bearer phone")
		w = httptest.NewRecorder()
		s.ServeHTTP(w, r)
		if w.Code != 400 {
			t.Fatal("invalid poll wait accepted")
		}
	}
}

func TestCanceledOperationPreservesMutationOutcome(t *testing.T) {
	for _, tc := range []struct {
		name, state, want string
		probe             bool
		keepActive        bool
	}{
		{"undelivered", "queued", "expired", false, false},
		{"dispatched-ping", "running", "unknown", true, false},
		{"dispatched-mutation", "running", "running", false, true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			s := New(Config{DesktopToken: "desktop"})
			op := &operation{id: "op", state: tc.state, probe: tc.probe, done: make(chan struct{})}
			s.device.active = op
			s.device.operations[op.id] = op
			ctx, cancel := context.WithCancel(context.Background())
			cancel()
			r := httptest.NewRequest(http.MethodPost, "/v1/desktop/call", nil).WithContext(ctx)
			s.await(httptest.NewRecorder(), r, op, op.done)
			if op.state != tc.want || (s.device.active == op) != tc.keepActive {
				t.Fatalf("state=%s active=%v", op.state, s.device.active == op)
			}
			if !tc.keepActive && !op.doneClosed {
				t.Fatal("canceled operation did not notify waiters")
			}
		})
	}
}

func TestCompletedOperationCannotBeReplayedOrChanged(t *testing.T) {
	s := New(Config{PhoneToken: "phone", DesktopToken: "desktop"})
	payload := []byte(`{"jsonrpc":"2.0","id":1,"method":"tools/call"}`)
	op := &operation{
		id: "once", state: "complete", digest: sha256.Sum256(payload),
		response: responseEnvelope{Status: http.StatusOK, Body: []byte(`{"result":{}}`)},
		done:     make(chan struct{}), completedAt: time.Now(),
	}
	s.device.operations[op.id] = op
	s.device.lastSeen = time.Now()
	request := func(token, body string) *httptest.ResponseRecorder {
		r := httptest.NewRequest(http.MethodPost, "/v1/desktop/call", strings.NewReader(body))
		r.Header.Set("Authorization", "Bearer "+token)
		w := httptest.NewRecorder()
		s.ServeHTTP(w, r)
		return w
	}
	body := `{"operationId":"once","payload":` + string(payload) + `}`
	if w := request("phone", body); w.Code != http.StatusUnauthorized {
		t.Fatalf("phone token accepted desktop role: %d", w.Code)
	}
	if w := request("desktop", body); w.Code != http.StatusOK {
		t.Fatalf("cached result: %d %s", w.Code, w.Body.String())
	}
	if w := request("desktop", `{"operationId":"once","payload":{"method":"ping"}}`); w.Code != http.StatusConflict {
		t.Fatalf("changed payload accepted: %d", w.Code)
	}
	op.response.Body = nil
	if w := request("desktop", body); w.Code != http.StatusGone {
		t.Fatalf("evicted response replayed: %d", w.Code)
	}
	if s.device.active != nil || len(s.device.operations) != 1 {
		t.Fatal("duplicate request created another execution")
	}
}
