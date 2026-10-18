package link

import (
	"bytes"
	"context"
	"encoding/json"
	"testing"
	"time"
)

func TestLocalCallRoundTrip(t *testing.T) {
	phone, mac := newPair(t)
	listener := listenPair(t, phone, mac)
	defer listener.Close()
	accepted := acceptAsync(listener)
	session, err := DialLocal(listener.Addr(), mac.identity, phone.peer)
	if err != nil {
		t.Fatal(err)
	}
	defer session.Close()
	remote := awaitSession(t, accepted)
	defer remote.Close()
	done := make(chan struct{})
	go func() {
		_ = remote.Respond(func(op, method string, params []byte) (string, []byte) {
			if method != "terminal.open" || !bytes.Equal(params, []byte(`{"sessionId":"abc"}`)) {
				return "failed", nil
			}
			return "complete", []byte(`{"state":"running"}`)
		})
		close(done)
	}()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	state, body, err := session.Call(ctx, "0123456789abcdef0123456789abcdef", "terminal.open", []byte(`{"sessionId":"abc"}`))
	if err != nil || state != "complete" || !bytes.Contains(body, []byte("running")) {
		t.Fatalf("call %s %s %v", state, body, err)
	}
	if err := session.Close(); err != nil {
		t.Fatal(err)
	}
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("respond")
	}
}

func TestLocalQueryRoundTrip(t *testing.T) {
	phone, mac := newPair(t)
	listener := listenPair(t, phone, mac)
	defer listener.Close()
	accepted := acceptAsync(listener)
	session, err := DialLocal(listener.Addr(), mac.identity, phone.peer)
	if err != nil {
		t.Fatal(err)
	}
	defer session.Close()
	remote := awaitSession(t, accepted)
	defer remote.Close()
	const op = "0123456789abcdef0123456789abcdef"
	done := make(chan struct{})
	go func() {
		defer close(done)
		for {
			messages, err := remote.readMessages()
			if err != nil {
				return
			}
			for _, raw := range messages {
				var msg wireMsg
				if json.Unmarshal(raw, &msg) != nil || msg.Type != "query" || len(msg.Ops) != 1 || msg.Ops[0] != op {
					continue
				}
				reply, err := json.Marshal(wireMsg{Type: "status", Op: op, State: "complete", Result: "eyJvayI6dHJ1ZX0="})
				if err != nil {
					return
				}
				_ = remote.Send(reply)
				return
			}
		}
	}()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	replies, err := session.Query(ctx, []string{op})
	if err != nil || len(replies) != 1 || replies[0].State != "complete" || !bytes.Contains(replies[0].Body, []byte("ok")) {
		t.Fatalf("query %v %v", replies, err)
	}
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("status")
	}
}
