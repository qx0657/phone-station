package main

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"
)

func queued(gate *remoteGate, count int, t *testing.T) {
	t.Helper()
	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		gate.mu.Lock()
		got := len(gate.pending)
		gate.mu.Unlock()
		if got == count {
			return
		}
		time.Sleep(time.Millisecond)
	}
	t.Fatal("queue did not settle")
}

func TestSharedQueuePrioritizesInteractiveCallsWithoutStarvingBulk(t *testing.T) {
	gate := &remoteGate{}
	_ = gate.acquire(context.Background())
	order := make(chan int, 5)
	for _, item := range []struct{ id, priority int }{{0, 0}, {1, 2}, {2, 2}, {3, 2}, {4, 2}} {
		item := item
		go func() {
			if err := gate.acquirePriority(context.Background(), item.priority); err != nil {
				t.Error(err)
				return
			}
			order <- item.id
			gate.Unlock()
		}()
		queued(gate, item.id+1, t)
	}
	gate.Unlock()
	for _, want := range []int{1, 2, 3, 0, 4} {
		select {
		case got := <-order:
			if got != want {
				t.Fatalf("want %d got %d", want, got)
			}
		case <-time.After(time.Second):
			t.Fatal("stalled")
		}
	}
}

func TestCLIAndAppShareTheSameRemotePermit(t *testing.T) {
	var active, maximum, calls atomic.Int32
	value := config{GatewayToken: "test", RemoteURL: "https://relay", RemotePin: "pin"}
	g := &gateway{readConfig: func() (config, error) { return value, nil }, callRemote: func(ctx context.Context, cfg config, body []byte) (relayResponse, error) {
		now := active.Add(1)
		if now > maximum.Load() {
			maximum.Store(now)
		}
		calls.Add(1)
		time.Sleep(20 * time.Millisecond)
		active.Add(-1)
		return relayResponse{Status: 200, Body: json.RawMessage(`{"jsonrpc":"2.0","id":1,"result":{}}`)}, nil
	}}
	g.state.mode = "remote"
	server := httptest.NewServer(g)
	defer server.Close()
	finished := make(chan struct{}, 8)
	for i := 0; i < 8; i++ {
		go func(i int) {
			defer func() { finished <- struct{}{} }()
			body := []byte(`{"jsonrpc":"2.0","id":1,"method":"ping"}`)
			if i%2 == 0 {
				reply, err := remoteViaHTTP(context.Background(), server.URL+"/mcp", value, body)
				if err != nil || reply.Status != 200 {
					t.Errorf("CLI failed: %v", err)
				}
			} else {
				w := httptest.NewRecorder()
				g.proxyRemote(context.Background(), w, body, value)
				if w.Code != http.StatusOK {
					t.Error("App failed")
				}
			}
		}(i)
	}
	for i := 0; i < 8; i++ {
		<-finished
	}
	if maximum.Load() != 1 || calls.Load() != 8 {
		t.Fatal("CLI bypassed App scheduler")
	}
}
