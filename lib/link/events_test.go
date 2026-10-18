package link

import (
	"context"
	"encoding/json"
	"testing"
	"time"
)

func TestSubscribeReceivesOpWhileCallRuns(t *testing.T) {
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
	release := make(chan struct{})
	go func() {
		for {
			messages, err := remote.readMessages()
			if err != nil {
				return
			}
			for _, raw := range messages {
				var msg wireMsg
				if json.Unmarshal(raw, &msg) != nil {
					continue
				}
				switch msg.Type {
				case "subscribe":
					_ = remote.Send([]byte(`{"type":"subscribed","topics":["ops","clipboard"],"rejected":[],"reasons":[]}`))
				case "call":
					_ = remote.Send([]byte(`{"type":"event","topic":"ops","op":"0123456789abcdef0123456789abcdef","state":"running"}`))
					<-release
					_ = remote.Send([]byte(`{"type":"result","op":"0123456789abcdef0123456789abcdef","state":"complete","result":"e30="}`))
				}
			}
		}
	}()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	subscribed, err := session.Subscribe(ctx, []string{"ops", "clipboard"}, nil)
	if err != nil || len(subscribed.Topics) != 2 || subscribed.Topics[0] != "ops" {
		t.Fatalf("subscribe %+v %v", subscribed, err)
	}
	callDone := make(chan struct{})
	go func() {
		state, body, err := session.Call(ctx, "0123456789abcdef0123456789abcdef", "terminal.open", []byte(`{}`))
		if err != nil || state != "complete" || string(body) != "{}" {
			t.Errorf("call %s %s %v", state, body, err)
		}
		close(callDone)
	}()
	ev, err := session.NextEvent(ctx)
	if err != nil || ev.Type != "event" || ev.Topic != "ops" || ev.State != "running" {
		t.Fatalf("event %+v %v", ev, err)
	}
	select {
	case <-callDone:
		t.Fatal("call finished before the op event")
	default:
	}
	close(release)
	select {
	case <-callDone:
	case <-ctx.Done():
		t.Fatal("call")
	}
}
