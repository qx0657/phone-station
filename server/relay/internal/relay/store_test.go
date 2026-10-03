package relay

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
	"testing"
	"time"
)

func durableConfig(dir string) Config {
	return Config{DeviceID: "isolated", PhoneToken: "phone-test-only", DesktopToken: "desktop-test-only", StateDir: dir}
}
func openTest(t *testing.T, cfg Config) *Server {
	t.Helper()
	s, err := Open(cfg)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = s.Close() })
	return s
}
func rpc(s *Server, route, role string, value any) *httptest.ResponseRecorder {
	raw, _ := json.Marshal(value)
	r := httptest.NewRequest(http.MethodPost, route, strings.NewReader(string(raw)))
	r.Header.Set("Authorization", "Bearer "+role)
	w := httptest.NewRecorder()
	s.ServeHTTP(w, r)
	return w
}
func callBody(id string) map[string]any {
	return map[string]any{"operationId": id, "payload": map[string]any{"jsonrpc": "2.0", "id": 1, "method": "tools/call"}}
}
func newID(s *Server, suffix string) string { return s.store.epoch + "." + strings.Repeat(suffix, 32) }
func beginCall(t *testing.T, s *Server, id string) (<-chan *httptest.ResponseRecorder, context.CancelFunc) {
	t.Helper()
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan *httptest.ResponseRecorder, 1)
	go func() {
		raw, _ := json.Marshal(callBody(id))
		r := httptest.NewRequest(http.MethodPost, "/v1/desktop/call", strings.NewReader(string(raw))).WithContext(ctx)
		r.Header.Set("Authorization", "Bearer "+s.config.DesktopToken)
		w := httptest.NewRecorder()
		s.ServeHTTP(w, r)
		done <- w
	}()
	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		s.device.mu.Lock()
		ready := s.device.active != nil
		s.device.mu.Unlock()
		if ready {
			return done, cancel
		}
		time.Sleep(time.Millisecond)
	}
	cancel()
	t.Fatal("operation was not queued")
	return nil, cancel
}
func markOnline(s *Server) { s.device.mu.Lock(); s.device.lastSeen = time.Now(); s.device.mu.Unlock() }
func finishOperation(t *testing.T, s *Server, id string, body any) {
	t.Helper()
	poll := rpc(s, "/v1/phone/poll", s.config.PhoneToken, map[string]int{"waitMs": 250})
	var out pollReply
	if poll.Code != 200 || json.Unmarshal(poll.Body.Bytes(), &out) != nil || out.OperationID != id {
		t.Fatalf("dispatch: %d %s", poll.Code, poll.Body.String())
	}
	result := rpc(s, "/v1/phone/result", s.config.PhoneToken, map[string]any{"operationId": id, "response": map[string]any{"status": 200, "body": body}})
	if result.Code != 200 {
		t.Fatalf("result: %d %s", result.Code, result.Body.String())
	}
}

func TestDurableCompletedSurvivesRestartAndRejectsConflict(t *testing.T) {
	cfg := durableConfig(t.TempDir())
	s := openTest(t, cfg)
	markOnline(s)
	id := newID(s, "a")
	done, cancel := beginCall(t, s, id)
	defer cancel()
	body := map[string]any{"jsonrpc": "2.0", "id": 1, "result": map[string]any{"value": "kept"}}
	finishOperation(t, s, id, body)
	if w := <-done; w.Code != 200 {
		t.Fatal(w.Code)
	}
	_ = s.Close()
	s = openTest(t, cfg)
	if w := rpc(s, "/v1/desktop/call", cfg.DesktopToken, callBody(id)); w.Code != 200 || !strings.Contains(w.Body.String(), "kept") {
		t.Fatalf("cached across restart: %d %s", w.Code, w.Body.String())
	}
	changed := callBody(id)
	changed["payload"] = map[string]string{"method": "different"}
	if w := rpc(s, "/v1/desktop/call", cfg.DesktopToken, changed); w.Code != 409 {
		t.Fatal("payload conflict", w.Code)
	}
	result := map[string]any{"operationId": id, "response": map[string]any{"status": 200, "body": body}}
	if w := rpc(s, "/v1/phone/result", cfg.PhoneToken, result); w.Code != 200 {
		t.Fatal("result retransmission", w.Code)
	}
	result["response"] = map[string]any{"status": 500, "body": map[string]any{"error": "different"}}
	if w := rpc(s, "/v1/phone/result", cfg.PhoneToken, result); w.Code != 409 {
		t.Fatal("result conflict", w.Code)
	}
	if s.device.active != nil {
		t.Fatal("completed operation dispatched again")
	}
}

func TestDurableInterruptedNeverRedispatchesAndExpiredEpochNeverReappears(t *testing.T) {
	for _, dispatch := range []bool{false, true} {
		t.Run(map[bool]string{false: "queued", true: "running"}[dispatch], func(t *testing.T) {
			cfg := durableConfig(t.TempDir())
			s := openTest(t, cfg)
			markOnline(s)
			id := newID(s, "b")
			done, cancel := beginCall(t, s, id)
			if dispatch {
				if w := rpc(s, "/v1/phone/poll", cfg.PhoneToken, map[string]int{"waitMs": 250}); w.Code != 200 {
					t.Fatal(w.Code)
				}
			}
			_ = s.Close()
			cancel()
			<-done
			s = openTest(t, cfg)
			if w := rpc(s, "/v1/desktop/call", cfg.DesktopToken, callBody(id)); w.Code != 410 {
				t.Fatalf("restart replay: %d", w.Code)
			}
			s.device.mu.Lock()
			old := s.device.operations[id]
			old.completedAt = time.Now().Add(-2 * resultKeep)
			if !s.persist(old) {
				t.Fatal("cannot age receipt")
			}
			future := time.Now().Add(2 * resultKeep)
			if err := s.store.rotate(cfg, future); err != nil {
				t.Fatal(err)
			}
			if err := s.prune(future, nil); err != nil {
				t.Fatal(err)
			}
			s.device.mu.Unlock()
			if _, err := os.Stat(filepath.Join(cfg.StateDir, s.store.name(id)+".json")); !os.IsNotExist(err) {
				t.Fatal("receipt did not expire")
			}
			_ = s.Close()
			s = openTest(t, cfg)
			if w := rpc(s, "/v1/desktop/call", cfg.DesktopToken, callBody(id)); w.Code != 410 {
				t.Fatal("expired ID became new", w.Code)
			}
		})
	}
}

func TestPersistenceFailureCannotAcknowledgeOrDispatch(t *testing.T) {
	for _, stage := range []string{"before-write", "before-fsync", "before-rename", "after-rename"} {
		t.Run(stage, func(t *testing.T) {
			cfg := durableConfig(t.TempDir())
			s := openTest(t, cfg)
			markOnline(s)
			id := newID(s, "c")
			s.store.fault = func(got string) error {
				if got == stage {
					return syscall.ENOSPC
				}
				return nil
			}
			if w := rpc(s, "/v1/desktop/call", cfg.DesktopToken, callBody(id)); w.Code != 503 {
				t.Fatal("storage failure accepted", w.Code)
			}
			if s.device.active != nil {
				t.Fatal("request published before durable acceptance")
			}
			_ = s.Close()
			s = openTest(t, cfg)
			if stage == "after-rename" {
				if w := rpc(s, "/v1/desktop/call", cfg.DesktopToken, callBody(id)); w.Code != 410 {
					t.Fatal("ambiguous receipt replayed", w.Code)
				}
			}
		})
	}
	cfg := durableConfig(t.TempDir())
	s := openTest(t, cfg)
	markOnline(s)
	id := newID(s, "d")
	done, cancel := beginCall(t, s, id)
	defer cancel()
	s.device.mu.Lock()
	s.store.fault = func(string) error { return syscall.ENOSPC }
	s.device.mu.Unlock()
	if w := rpc(s, "/v1/phone/poll", cfg.PhoneToken, map[string]int{"waitMs": 250}); w.Code != 503 {
		t.Fatal("dispatch acknowledged without durable state", w.Code)
	}
	_ = s.Close()
	<-done
	s = openTest(t, cfg)
	if w := rpc(s, "/v1/desktop/call", cfg.DesktopToken, callBody(id)); w.Code != 410 {
		t.Fatal("failed dispatch replayed", w.Code)
	}
}

func TestWaitingPollStopsWhenClosedOrStorageFails(t *testing.T) {
	for _, storageFailure := range []bool{false, true} {
		t.Run(map[bool]string{false: "closed", true: "storage-failure"}[storageFailure], func(t *testing.T) {
			s := openTest(t, durableConfig(t.TempDir()))
			done := make(chan *httptest.ResponseRecorder, 1)
			go func() { done <- rpc(s, "/v1/phone/poll", s.config.PhoneToken, map[string]int{"waitMs": 25000}) }()
			deadline := time.Now().Add(time.Second)
			for {
				s.device.mu.Lock()
				polling := s.device.polling
				s.device.mu.Unlock()
				if polling {
					break
				}
				if time.Now().After(deadline) {
					t.Fatal("poll did not start")
				}
				time.Sleep(time.Millisecond)
			}
			if storageFailure {
				s.device.mu.Lock()
				s.store.fault = func(string) error { return syscall.ENOSPC }
				s.device.mu.Unlock()
				if w := rpc(s, "/v1/desktop/call", s.config.DesktopToken, callBody(newID(s, "a"))); w.Code != 503 {
					t.Fatal("failed storage accepted a call", w.Code)
				}
			} else {
				_ = s.Close()
			}
			select {
			case w := <-done:
				if w.Code != 503 || strings.Contains(w.Body.String(), "idle") {
					t.Fatalf("unavailable poll response: %d %s", w.Code, w.Body.String())
				}
			case <-time.After(time.Second):
				t.Fatal("waiting poll was not woken")
			}
		})
	}
}

func TestResultPersistenceFailureDoesNotAcknowledgeOrReexecute(t *testing.T) {
	for _, fileNumber := range []int{1, 2} { // result body, then complete receipt
		for _, stage := range []string{"before-write", "before-fsync", "before-rename", "after-rename"} {
			t.Run(fmt.Sprintf("file-%d/%s", fileNumber, stage), func(t *testing.T) {
				cfg := durableConfig(t.TempDir())
				s := openTest(t, cfg)
				markOnline(s)
				id := newID(s, "a")
				done, cancel := beginCall(t, s, id)
				defer cancel()
				if w := rpc(s, "/v1/phone/poll", cfg.PhoneToken, map[string]int{"waitMs": 250}); w.Code != 200 {
					t.Fatal(w.Code)
				}
				s.device.mu.Lock()
				writes := 0
				s.store.fault = func(got string) error {
					if got == "before-write" {
						writes++
					}
					if writes == fileNumber && got == stage {
						return syscall.ENOSPC
					}
					return nil
				}
				s.device.mu.Unlock()
				input := map[string]any{"operationId": id, "response": map[string]any{"status": 200, "body": map[string]string{"result": "done"}}}
				if w := rpc(s, "/v1/phone/result", cfg.PhoneToken, input); w.Code != 503 {
					t.Fatal("result acknowledged before durable completion", w.Code)
				}
				_ = s.Close()
				<-done
				s = openTest(t, cfg)
				w := rpc(s, "/v1/desktop/call", cfg.DesktopToken, callBody(id))
				if w.Code != 410 && w.Code != 200 {
					t.Fatal("unexpected recovery", w.Code)
				}
				if s.device.active != nil {
					t.Fatal("failed result caused redispatch")
				}
			})
		}
	}
}

func TestDurableCacheHasByteBudgetAndEvictionRetainsIdentity(t *testing.T) {
	cfg := durableConfig(t.TempDir())
	s := openTest(t, cfg)
	markOnline(s)
	first := newID(s, "e")
	for _, id := range []string{first, newID(s, "f")} {
		done, cancel := beginCall(t, s, id)
		finishOperation(t, s, id, map[string]string{"large": strings.Repeat("x", 17<<20)})
		if w := <-done; w.Code != 200 {
			t.Fatal(w.Code)
		}
		cancel()
	}
	total := 0
	for _, op := range s.device.operations {
		total += len(op.response.Body)
	}
	if total > maxCachedBytes {
		t.Fatal("unbounded response cache", total)
	}
	_ = s.Close()
	s = openTest(t, cfg)
	if w := rpc(s, "/v1/desktop/call", cfg.DesktopToken, callBody(first)); w.Code != 410 {
		t.Fatal("evicted result became new operation", w.Code)
	}
}

func TestStateLockCorruptionAndPermissions(t *testing.T) {
	cfg := durableConfig(t.TempDir())
	s := openTest(t, cfg)
	if other, err := Open(cfg); err == nil {
		other.Close()
		t.Fatal("shared state accepted")
	}
	for _, name := range []string{cfg.StateDir, filepath.Join(cfg.StateDir, "state.json"), filepath.Join(cfg.StateDir, "lock")} {
		info, err := os.Stat(name)
		if err != nil || info.Mode().Perm()&0077 != 0 {
			t.Fatal("state readable by others", name)
		}
	}
	_ = s.Close()
	if err := os.WriteFile(filepath.Join(cfg.StateDir, "state.json"), []byte("corrupt"), 0600); err != nil {
		t.Fatal(err)
	}
	if other, err := Open(cfg); err == nil {
		other.Close()
		t.Fatal("corruption reset namespace")
	}
}

func TestCrashHelper(t *testing.T) {
	stage := os.Getenv("PHONE_RELAY_TEST_CRASH")
	if stage == "" {
		return
	}
	cfg := durableConfig(os.Getenv("PHONE_RELAY_TEST_STATE"))
	s, err := Open(cfg)
	if err != nil {
		t.Fatal(err)
	}
	op := &operation{id: newID(s, "a"), digest: [32]byte{1}, state: "queued", createdAt: time.Now(), done: make(chan struct{})}
	if !s.persist(op) {
		t.Fatal("seed failed")
	}
	s.store.fault = func(got string) error {
		if got == stage {
			_ = syscall.Kill(os.Getpid(), syscall.SIGKILL)
		}
		return nil
	}
	op.state = "running"
	op.startedAt = time.Now()
	_ = s.persist(op)
	t.Fatal("crash boundary not reached")
}

func TestKilledAtPersistenceBoundariesNeverReplays(t *testing.T) {
	for _, stage := range []string{"before-fsync", "before-rename", "after-rename"} {
		t.Run(stage, func(t *testing.T) {
			cfg := durableConfig(t.TempDir())
			s := openTest(t, cfg)
			id := newID(s, "a")
			_ = s.Close()
			cmd := exec.Command(os.Args[0], "-test.run=^TestCrashHelper$")
			cmd.Env = append(os.Environ(), "PHONE_RELAY_TEST_CRASH="+stage, "PHONE_RELAY_TEST_STATE="+cfg.StateDir)
			if err := cmd.Run(); err == nil {
				t.Fatal("helper was not killed")
			}
			s = openTest(t, cfg)
			if s.device.active != nil || s.device.operations[id] == nil || s.device.operations[id].state != "unknown" {
				t.Fatal("crashed operation redispatched")
			}
		})
	}
}
