package main

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

const pingReply = `{"jsonrpc":"2.0","id":"local-probe","result":{}}`

func testGateway(address string, remote func(context.Context, config, []byte) (relayResponse, error)) *gateway {
	return &gateway{
		state:    health{mode: "local", localOnline: true, remoteOnline: true},
		localURL: address,
		readConfig: func() (config, error) {
			return config{GatewayToken: "test", LocalToken: "local", RemoteURL: "https://test", RemotePin: "pin"}, nil
		},
		callRemote: remote,
	}
}

func callGateway(g *gateway, body string) *httptest.ResponseRecorder {
	r := httptest.NewRequest(http.MethodPost, "/mcp", strings.NewReader(body))
	r.Host = "127.0.0.1:18765"
	r.Header.Set("Authorization", "Bearer test")
	w := httptest.NewRecorder()
	g.ServeHTTP(w, r)
	return w
}

func TestStatusStreamPublishesTransitionsAndRequiresAuth(t *testing.T) {
	g := testGateway("", nil)
	server := httptest.NewServer(g)
	defer server.Close()
	response, err := http.Get(server.URL + "/__events")
	if err != nil {
		t.Fatal(err)
	}
	response.Body.Close()
	if response.StatusCode != http.StatusUnauthorized {
		t.Fatal("status stream exposed without auth")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	r, _ := http.NewRequestWithContext(ctx, http.MethodGet, server.URL+"/__events", nil)
	r.Header.Set("Authorization", "Bearer test")
	response, err = http.DefaultClient.Do(r)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	decoder := json.NewDecoder(response.Body)
	var first, next statusReply
	if err := decoder.Decode(&first); err != nil {
		t.Fatal(err)
	}
	if !first.Ready || !first.LocalOnline || first.Token != "" {
		t.Fatalf("bad initial snapshot: %+v", first)
	}
	value, _ := g.config()
	g.noteRemote(value, probeResult{})
	if err := decoder.Decode(&next); err != nil {
		t.Fatal(err)
	}
	if next.RemoteOnline || !next.LocalOnline {
		t.Fatalf("transition not published: %+v", next)
	}
}

func TestBusyRemoteExpiresConfirmationWithoutCancellingOperation(t *testing.T) {
	g := testGateway("", nil)
	value, _ := g.config()
	g.state.mu.Lock()
	g.state.remoteBusy = true
	g.state.remoteCheckedAt = time.Now().Add(-remoteFresh - time.Second)
	g.state.mu.Unlock()
	status := g.snapshot(value)
	if status.RemoteVerified || !status.RemoteChecking || !status.RemoteOnline {
		t.Fatalf("stale busy route presented as confirmed: %+v", status)
	}
	g.noteRemote(value, probeResult{ok: true, rtt: time.Millisecond})
	status = g.snapshot(value)
	if !status.RemoteVerified || status.RemoteChecking {
		t.Fatalf("response did not restore confirmation: %+v", status)
	}
}

func TestDeadForwardSwitchesBeforeMutationDispatch(t *testing.T) {
	var localCalls, remoteCalls atomic.Int32
	local := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		localCalls.Add(1)
		_, _ = io.Copy(io.Discard, r.Body)
		<-r.Context().Done() // TCP accepts but adb never delivers the ping.
	}))
	defer local.Close()
	body := `{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"station_file_append_text"}}`
	g := testGateway(local.URL, func(ctx context.Context, c config, payload []byte) (relayResponse, error) {
		remoteCalls.Add(1)
		if string(payload) != body {
			t.Errorf("changed operation: %s", payload)
		}
		return relayResponse{Status: 200, Body: json.RawMessage(`{"jsonrpc":"2.0","id":9,"result":{}}`)}, nil
	})
	started := time.Now()
	w := callGateway(g, body)
	if w.Code != 200 || g.mode() != "remote" || localCalls.Load() != 1 || remoteCalls.Load() != 1 {
		t.Fatalf("status=%d mode=%s local=%d remote=%d", w.Code, g.mode(), localCalls.Load(), remoteCalls.Load())
	}
	if time.Since(started) > 2*time.Second {
		t.Fatal("dead forward blocked failover")
	}
}

func TestInflightFailoverReplaysOnlyReadOperations(t *testing.T) {
	for _, tool := range []string{"station_device_status", "station_file_append_text", "station_notify", "station_clipboard_set", "station_shell_exec", "unknown_tool"} {
		t.Run(tool, func(t *testing.T) {
			dispatched := make(chan struct{})
			var broken atomic.Bool
			var remoteCalls atomic.Int32
			local := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				var req struct {
					Method string `json:"method"`
				}
				_ = json.NewDecoder(r.Body).Decode(&req)
				if req.Method == "ping" && !broken.Load() {
					_, _ = w.Write([]byte(pingReply))
					return
				}
				if req.Method != "ping" {
					close(dispatched)
				}
				<-r.Context().Done()
			}))
			defer local.Close()
			g := testGateway(local.URL, func(ctx context.Context, c config, payload []byte) (relayResponse, error) {
				remoteCalls.Add(1)
				return relayResponse{Status: 200, Body: []byte(pingReply)}, nil
			})
			finished := make(chan *httptest.ResponseRecorder, 1)
			go func() {
				finished <- callGateway(g, `{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"`+tool+`"}}`)
			}()
			select {
			case <-dispatched:
			case <-time.After(2 * time.Second):
				t.Fatal("operation not dispatched")
			}
			broken.Store(true)
			started := time.Now()
			g.checkLocal("local", true)
			select {
			case w := <-finished:
				if tool == "station_device_status" {
					if w.Code != 200 || remoteCalls.Load() != 1 {
						t.Fatal("read did not transparently recover")
					}
				} else if w.Code != 502 || remoteCalls.Load() != 0 {
					t.Fatal("mutation or unknown operation replayed")
				}
			case <-time.After(2 * time.Second):
				t.Fatal("in-flight request was left hanging")
			}
			if time.Since(started) > 2*time.Second {
				t.Fatal("failover exceeded detection budget")
			}
		})
	}
}

func TestBusyRelayCannotDelayLocalFailureOrOverwriteHealth(t *testing.T) {
	g := testGateway("", nil)
	if err := g.remoteMu.acquire(context.Background()); err != nil {
		t.Fatal(err)
	}
	defer g.remoteMu.Unlock()
	if _, sampled := g.probeRemote(config{}); sampled {
		t.Fatal("busy relay produced stale health sample")
	}
	g.checkLocal("", true)
	if g.mode() != "remote" {
		t.Fatal("local failure waited for busy relay")
	}
	r := httptest.NewRequest(http.MethodGet, "/__status", nil)
	r.Host = "127.0.0.1:18765"
	r.Header.Set("Authorization", "Bearer test")
	w := httptest.NewRecorder()
	g.ServeHTTP(w, r)
	var reply statusReply
	_ = json.Unmarshal(w.Body.Bytes(), &reply)
	if reply.Mode != "remote" || reply.LocalOnline || !reply.RemoteOnline {
		t.Fatal("status did not publish failover")
	}
}

func TestRecoveryRequiresConsecutiveSamples(t *testing.T) {
	s := health{mode: "remote", remoteForFailure: true, localOnline: true, remoteOnline: true,
		localRTT: 20 * time.Millisecond, remoteRTT: 100 * time.Millisecond, lastModeChange: time.Now().Add(-4 * time.Second)}
	s.selectMode(true)
	s.selectMode(true)
	if s.mode != "remote" {
		t.Fatal("recovered before stable samples")
	}
	s.localOnline = false
	s.localGoodSamples = 0
	s.selectMode(true)
	s.localOnline = true
	s.selectMode(true)
	s.selectMode(true)
	if s.mode != "remote" {
		t.Fatal("failure did not reset recovery samples")
	}
	s.selectMode(true)
	if s.mode != "local" {
		t.Fatal("healthy local route did not recover")
	}
	s.mode, s.remoteForFailure, s.localGoodSamples = "remote", false, 0
	s.lastModeChange = time.Now().Add(-4 * time.Second)
	for i := 0; i < 6; i++ {
		s.selectMode(true)
	}
	if s.mode != "remote" {
		t.Fatal("latency selection lost its cooldown")
	}
}

func TestUnsafeMethodsNeverReplay(t *testing.T) {
	for _, body := range []string{`bad json`, `{"method":"tools/call"}`, `{"method":"notifications/initialized"}`, `{"method":"tools/call","params":{"name":"station_file_delete"}}`, `{"method":"tools/call","params":{"name":"station_shell_exec","arguments":{"command":"id"}}}`} {
		if safeToReplay([]byte(body)) {
			t.Fatalf("unsafe replay: %s", body)
		}
	}
}

func TestShellStatusCanReplay(t *testing.T) {
	if !safeToReplay([]byte(`{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"station_shell_status"}}`)) {
		t.Fatal("read-only Shizuku status should be safe to replay")
	}
}

func TestNotificationReplayPolicy(t *testing.T) {
	if !safeToReplay([]byte(`{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"station_notification_status"}}`)) {
		t.Fatal("notification status should be safe to replay")
	}
	if !safeToReplay([]byte(`{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"station_notification_icon"}}`)) {
		t.Fatal("read-only app icon should be safe to replay")
	}
	for _, name := range []string{"station_notification_poll", "station_notification_configure"} {
		body := []byte(`{"method":"tools/call","params":{"name":"` + name + `"}}`)
		if safeToReplay(body) {
			t.Fatalf("notification session must not replay %s", name)
		}
	}
}

func TestLateLocalProbeCannotReviveDisconnectedRoute(t *testing.T) {
	entered, release, finished := make(chan struct{}), make(chan struct{}), make(chan struct{})
	local := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		close(entered)
		<-release
		_, _ = w.Write([]byte(pingReply))
	}))
	defer local.Close()
	g := testGateway(local.URL, nil)
	go func() { g.checkLocal("local", true); close(finished) }()
	<-entered
	g.state.mu.Lock()
	g.noteLocalLocked(probeResult{}, false)
	g.state.mu.Unlock()
	close(release)
	<-finished
	if g.mode() != "remote" || g.state.localOnline {
		t.Fatal("late success revived a failed route")
	}
}

func TestCancelledRelayQueueDoesNotDispatchLater(t *testing.T) {
	var calls atomic.Int32
	g := testGateway("", func(ctx context.Context, value config, body []byte) (relayResponse, error) {
		calls.Add(1)
		return relayResponse{Status: http.StatusOK, Body: []byte(pingReply)}, nil
	})
	if err := g.remoteMu.acquire(context.Background()); err != nil {
		t.Fatal(err)
	}
	value, _ := g.config()
	ctx, cancel := context.WithTimeout(context.Background(), 50*time.Millisecond)
	defer cancel()
	finished := make(chan struct{})
	go func() {
		g.proxyRemote(ctx, httptest.NewRecorder(), []byte(`{"method":"tools/call","params":{"name":"station_clipboard_exchange"}}`), value)
		close(finished)
	}()
	select {
	case <-finished:
	case <-time.After(time.Second):
		g.remoteMu.Unlock()
		t.Fatal("cancelled request remained in the relay queue")
	}
	g.remoteMu.Unlock()
	if calls.Load() != 0 {
		t.Fatal("expired mutation dispatched after queue cancellation")
	}
	// Cancellation must not consume the permit or mark a working route offline.
	if err := g.remoteMu.acquire(context.Background()); err != nil {
		t.Fatal(err)
	}
	g.remoteMu.Unlock()
	if !g.state.remoteOnline {
		t.Fatal("queue cancellation changed remote health")
	}
}
