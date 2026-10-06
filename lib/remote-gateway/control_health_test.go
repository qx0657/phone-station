package main

import (
	"context"
	"testing"
	"time"
)

func TestControlPresenceSkipsRepeatedMCPProbesButRechecksNewSessions(t *testing.T) {
	value := config{RemoteURL: "https://relay.invalid", RemotePin: "pin", RemoteRevision: "profile"}
	state := controlHealth{Online: true, Protocol: "control-v1", Connected: true, Session: "one"}
	calls := 0
	g := &gateway{readConfig: func() (config, error) { return value, nil }, controlStatus: func(context.Context, config) (controlHealth, error) { return state, nil },
		callRemote: func(context.Context, config, []byte) (relayResponse, error) {
			calls++
			return relayResponse{Status: 200, Body: []byte(`{"jsonrpc":"2.0","id":"remote-probe","result":{}}`)}, nil
		}}
	g.config()
	for i := 0; i < 4; i++ {
		if result, sampled := g.probeRemote(value); !sampled || !result.ok {
			t.Fatal(result, sampled)
		}
	}
	if calls != 1 {
		t.Fatal("idle presence entered MCP queue", calls)
	}
	state.Session = "two"
	g.probeRemote(value)
	if calls != 2 {
		t.Fatal("new phone connection was not verified")
	}
	g.probeAt = time.Now().Add(-time.Minute)
	g.probeRemote(value)
	if calls != 3 {
		t.Fatal("periodic MCP verification missing")
	}
	g.state.mu.Lock()
	g.state.remoteOnline = false
	g.state.mu.Unlock()
	g.probeRemote(value)
	if calls != 4 {
		t.Fatal("failed business call was masked by cached presence")
	}
	state.Connected = false
	g.probeRemote(value)
	g.probeRemote(value)
	if calls != 6 {
		t.Fatal("legacy phone did not retain end-to-end checks")
	}
	state.Online = false
	if result, _ := g.probeRemote(value); result.ok {
		t.Fatal("offline relay phone reported online")
	}
	if calls != 6 {
		t.Fatal("offline check queued work")
	}
}
