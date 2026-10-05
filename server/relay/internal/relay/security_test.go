package relay

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestRequestBoundaryRejectsAmbiguousCredentials(t *testing.T) {
	for _, tc := range []struct {
		name, path string
		headers    http.Header
		status     int
	}{
		{"duplicate bearer", "/v1/desktop/status", http.Header{"Authorization": {"Bearer desktop", "Bearer phone"}}, 401},
		{"wrong role", "/v1/desktop/status", http.Header{"Authorization": {"Bearer phone"}}, 401},
		{"missing bearer", "/v1/desktop/status", nil, 401},
		{"browser", "/v1/desktop/status", http.Header{"Authorization": {"Bearer desktop"}, "Origin": {"https://evil.example"}}, 403},
		{"empty origin", "/v1/desktop/status", http.Header{"Authorization": {"Bearer desktop"}, "Origin": {""}}, 403},
		{"duplicate origin", "/v1/desktop/status", http.Header{"Authorization": {"Bearer desktop"}, "Origin": {"", "https://evil.example"}}, 403},
		{"URL credential", "/v1/desktop/status?token=desktop", http.Header{"Authorization": {"Bearer desktop"}}, 400},
		{"duplicate ID", "/v1/desktop/operation?id=a&id=b", http.Header{"Authorization": {"Bearer desktop"}}, 400},
		{"extra parameter", "/v1/desktop/operation?id=a&token=desktop", http.Header{"Authorization": {"Bearer desktop"}}, 400},
		{"valid native status", "/v1/desktop/status", http.Header{"Authorization": {"Bearer desktop"}}, 200},
		{"valid operation query", "/v1/desktop/operation?id=a", http.Header{"Authorization": {"Bearer desktop"}}, 200},
	} {
		t.Run(tc.name, func(t *testing.T) {
			s := New(Config{PhoneToken: "phone", DesktopToken: "desktop"})
			defer s.Close()
			r := httptest.NewRequest(http.MethodGet, tc.path, nil)
			r.Header = tc.headers
			w := httptest.NewRecorder()
			s.ServeHTTP(w, r)
			if w.Code != tc.status || w.Header().Get("Cache-Control") != "no-store" {
				t.Fatalf("status %d, headers %v", w.Code, w.Header())
			}
		})
	}
}

func TestInvalidJSONCannotQueueAnOperation(t *testing.T) {
	for _, body := range []string{
		`{"operationId":"a","operationId":"b","payload":{"method":"ping"}}`,
		`{"operationId":"a","payload":{"method":"ping","method":"tools/call"}}`,
		`{"operationId":"a","payload":{"method":"ping","meth\u006fd":"tools/call"}}`,
		`{"operationId":"a","payload":{"args":{"on":false,"on":true}}}`,
		`{"operationId":"a","payload":` + strings.Repeat(`[`, 42) + `0` + strings.Repeat(`]`, 42) + `}`,
		"{\"operationId\":\"a\",\"payload\":\"\xff\"}",
		`{"operationId":"a","payload":{}} {}`,
	} {
		s := New(Config{PhoneToken: "phone", DesktopToken: "desktop"})
		r := httptest.NewRequest(http.MethodPost, "/v1/desktop/call", strings.NewReader(body))
		r.Header.Set("Authorization", "Bearer desktop")
		w := httptest.NewRecorder()
		s.ServeHTTP(w, r)
		if w.Code != 400 || len(s.device.operations) != 0 || s.device.active != nil {
			t.Fatalf("ambiguous request accepted: status %d, %q", w.Code, body)
		}
		s.Close()
	}
	for _, valid := range []string{`{}`, `{"a":[1,true,null,{"b":"手机"}]}`, `[{},{}]`} {
		if !unambiguousJSON([]byte(valid)) {
			t.Fatalf("valid JSON rejected: %s", valid)
		}
	}
}

func TestRejectedPhoneResultDoesNotCompleteReceipt(t *testing.T) {
	s := New(Config{PhoneToken: "phone", DesktopToken: "desktop"})
	defer s.Close()
	op := &operation{id: "a", state: "running", done: make(chan struct{})}
	s.device.active, s.device.operations[op.id] = op, op
	r := httptest.NewRequest(http.MethodPost, "/v1/phone/result", strings.NewReader(`{"operationId":"a","response":{"status":200,"body":{"result":{},"result":{"unsafe":true}}}}`))
	r.Header.Set("Authorization", "Bearer phone")
	w := httptest.NewRecorder()
	s.ServeHTTP(w, r)
	if w.Code != 400 || op.state != "running" || s.device.active != op {
		t.Fatal("ambiguous result changed receipt")
	}
}
