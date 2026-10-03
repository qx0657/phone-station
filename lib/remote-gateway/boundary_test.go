package main

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestLoopbackHostAndOriginBoundary(t *testing.T) {
	for _, test := range []struct {
		host, origin string
		allowed      bool
	}{
		{"127.0.0.1:18765", "", true}, {"localhost:18766", "http://localhost:18766", true},
		{"[::1]:8765", "http://127.0.0.1:8765", true}, {"127.0.0.1", "http://localhost", true},
		{"evil.example:18765", "", false}, {"127.0.0.1.evil.example", "", false},
		{"127.0.0.1:18765", "https://evil.example", false}, {"localhost:18765", "null", false},
		{"localhost:18765", "http://localhost:1234", false}, {"localhost:18765", "http://localhost:18765/path", false},
		{"localhost:18765", "http://localhost:18765#", false}, {"localhost:18765", "http://localhost:18765?", false},
		{"localhost:18765", "https://localhost:18765", false}, {"user@localhost:18765", "", false},
		{"localhost:", "", false}, {"localhost:0", "", false}, {"localhost:65536", "", false},
	} {
		r := httptest.NewRequest(http.MethodGet, "/mcp", nil)
		r.Host = test.host
		if test.origin != "" {
			r.Header.Set("Origin", test.origin)
		}
		if allowedLoopbackRequest(r) != test.allowed {
			t.Errorf("host=%q origin=%q", test.host, test.origin)
		}
	}
}

func TestValidTokenCannotBypassOriginAndGETReturns405(t *testing.T) {
	calls := 0
	g := testGateway("", func(ctx context.Context, value config, body []byte) (relayResponse, error) {
		calls++
		return relayResponse{Status: 200, Body: json.RawMessage(pingReply)}, nil
	})
	g.state.mode = "remote"
	for _, method := range []string{http.MethodGet, http.MethodPost} {
		r := httptest.NewRequest(method, "/mcp", strings.NewReader(`{"jsonrpc":"2.0","id":1,"method":"ping"}`))
		r.Host = "127.0.0.1:18765"
		r.Header.Set("Authorization", "Bearer test")
		r.Header.Set("Origin", "https://evil.example")
		w := httptest.NewRecorder()
		g.ServeHTTP(w, r)
		if w.Code != 403 || calls != 0 {
			t.Fatal("foreign origin dispatched")
		}
	}
	r := httptest.NewRequest(http.MethodGet, "/mcp", nil)
	r.Host = "127.0.0.1:18765"
	r.Header.Set("Authorization", "Bearer test")
	w := httptest.NewRecorder()
	g.ServeHTTP(w, r)
	if w.Code != 405 || w.Header().Get("Allow") != "POST" {
		t.Fatal("GET did not advertise unsupported SSE")
	}
	r = httptest.NewRequest(http.MethodPost, "/mcp", strings.NewReader("bad JSON"))
	r.Host = "localhost:18765"
	r.Header.Set("Authorization", "Bearer test")
	w = httptest.NewRecorder()
	g.ServeHTTP(w, r)
	if !strings.Contains(w.Body.String(), "-32700") || calls != 0 {
		t.Fatal("malformed payload was dispatched")
	}
}

func TestBatchReplayRequiresEveryItemToBeReadOnly(t *testing.T) {
	read := `{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"station_file_stat"}}`
	write := `{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"station_file_delete"}}`
	if !safeToReplay([]byte("[" + read + "]")) {
		t.Fatal("read batch rejected")
	}
	if safeToReplay([]byte("[" + read + "," + write + "]")) {
		t.Fatal("mixed batch replayed")
	}
	for _, bad := range []string{"null", "true", "[]", "{}", "1.5", "1e2"} {
		if validRPCID(json.RawMessage(bad)) {
			t.Fatalf("invalid ID accepted: %s", bad)
		}
		if string(requestID([]byte(`{"id":`+bad+`}`))) != "null" {
			t.Fatal("unsafe error ID")
		}
	}
}
