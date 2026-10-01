package relay

import (
	"context"
	"crypto/sha256"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

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
