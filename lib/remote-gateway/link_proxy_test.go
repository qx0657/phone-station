package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"testing"
	"time"

	"phone-station/link"
)

// Model the phone's durable receipt key: a different method/body may never
// reuse open's operation. Exercise both the legacy and app-facing tool names.
func TestTerminalSessionOperationsHaveSeparateReceipts(t *testing.T) {
	const session = "0123456789abcdef0123456789abcdef"
	for _, prefix := range []string{"station_terminal_", "terminal."} {
		t.Run(prefix, func(t *testing.T) {
			g := testGateway("", nil)
			seen := map[string]string{}
			g.linkCall = func(_ context.Context, op, method string, body []byte) (string, []byte, error) {
				if method == "terminal.open" {
					if op != session {
						t.Fatalf("open must retain saved id: %s", op)
					}
				} else if op == session {
					t.Fatalf("%s reused session as operation", method)
				}
				if previous, exists := seen[op]; exists {
					t.Fatalf("receipt collision: %s and %s", previous, method)
				}
				seen[op] = method
				return "complete", toolText(`{"sessionId":"` + session + `","state":"running","nextInputSequence":1}`), nil
			}
			for _, action := range []string{"open", "read", "resize", "input", "read", "input", "close"} {
				request := fmt.Sprintf(`{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":%q,"arguments":{"sessionId":%q,"sequence":0,"hex":"61"}}}`, prefix+action, session)
				body, status := g.oneLink(context.Background(), []byte(request))
				if status != http.StatusOK || toolPayload(t, body)["sessionId"] != session {
					t.Fatalf("%s: %s", action, body)
				}
			}
			// Creation retries still resolve to the originally saved operation.
			params := json.RawMessage(fmt.Sprintf(`{"name":%q,"arguments":{"sessionId":%q}}`, prefix+"open", session))
			if linkOperation("tools/call", params) != session {
				t.Fatal("open lost durable identity")
			}
		})
	}
}

func TestProxyLinkUsesDeviceLinkWithoutPhoneToken(t *testing.T) {
	var ops, methods []string
	g := testGateway("ws://127.0.0.1:9/v2/link", nil)
	g.linkLocal = true
	g.readConfig = func() (config, error) {
		return config{GatewayToken: "test"}, nil
	}
	g.linkCall = func(ctx context.Context, op, method string, body []byte) (string, []byte, error) {
		ops = append(ops, op)
		methods = append(methods, method)
		if method == "meta.ping" {
			return "complete", []byte("{}"), nil
		}
		if method != "terminal.open" || !bytes.Contains(body, []byte(`"sessionId"`)) {
			t.Errorf("call %s %s", method, body)
		}
		return "complete", []byte(`{"content":[{"type":"text","text":"hi"}]}`), nil
	}
	response := callGateway(g, `{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"station_terminal_open","arguments":{"sessionId":"0123456789abcdef0123456789abcdef","columns":80,"rows":24}}}`)
	if response.Code != http.StatusOK || !bytes.Contains(response.Body.Bytes(), []byte("hi")) {
		t.Fatal(response.Code, response.Body.String())
	}
	if linkMethod, ok := linkMethodName("station_install_run"); !ok || linkMethod != "install.run" {
		t.Fatal(linkMethod, ok)
	}
	found := false
	for i, method := range methods {
		if method == "terminal.open" {
			found = true
			if ops[i] != "0123456789abcdef0123456789abcdef" {
				t.Fatal(ops[i])
			}
		}
	}
	if !found {
		t.Fatal(methods)
	}
}

func TestEventWaitUsesLinkEvents(t *testing.T) {
	g := testGateway("ws://127.0.0.1:9/v2/link", nil)
	g.linkLocal = true
	g.eventsReady = true
	g.live = newLiveEvents()
	g.linkCall = func(context.Context, string, string, []byte) (string, []byte, error) {
		t.Fatal("forwarded")
		return "", nil, nil
	}
	g.live.note("notifications")
	state, body, err := g.callLink(context.Background(), "0123456789abcdef0123456789abcdef", "event.wait", []byte(`{"clipboard":0,"notifications":0}`))
	if err != nil || state != "complete" || !bytes.Contains(body, []byte(`"notifications":1`)) {
		t.Fatalf("%s %s %v", state, body, err)
	}
	woken := make(chan struct{})
	go func() {
		state, body, err := g.callLink(context.Background(), "0123456789abcdef0123456789abcdef", "event.wait", []byte(`{"clipboard":0,"notifications":1}`))
		if err != nil || state != "complete" || !bytes.Contains(body, []byte(`"clipboard":1`)) {
			t.Errorf("%s %s %v", state, body, err)
		}
		close(woken)
	}()
	time.Sleep(20 * time.Millisecond)
	g.live.note("clipboard")
	select {
	case <-woken:
	case <-time.After(time.Second):
		t.Fatal("wait")
	}
}

func TestStateBodiesReachMacWaiter(t *testing.T) {
	g := testGateway("ws://127.0.0.1:9/v2/link", nil)
	g.linkLocal = true
	g.eventsReady = true
	g.live = newLiveEvents()
	g.linkCall = func(_ context.Context, _, method string, _ []byte) (string, []byte, error) {
		if method == "meta.ping" {
			return "complete", []byte("{}"), nil
		}
		t.Fatal(method)
		return "", nil, nil
	}
	issues := []byte(`{"batteryPercent":40,"issues":[{"id":"shizuku","title":"Shizuku 未启动","detail":"需要 Shizuku","destination":"permissions"}]}`)
	if !g.acceptLinkEvent(link.LinkEvent{Topic: "device", Body: issues}) {
		t.Fatal("device")
	}
	if g.acceptLinkEvent(link.LinkEvent{Topic: "device", Body: issues}) {
		t.Fatal("unchanged device woke")
	}
	if !g.acceptLinkEvent(link.LinkEvent{Topic: "grants", Body: []byte(`{"granted":true}`)}) {
		t.Fatal("grants")
	}
	if !g.acceptLinkEvent(link.LinkEvent{Topic: "capabilities", Body: []byte(`{"shizuku":true,"verified":true}`)}) {
		t.Fatal("capabilities")
	}
	response := callGateway(g, `{"jsonrpc":"2.0","id":"h","method":"tools/call","params":{"name":"station_event_states","arguments":{}}}`)
	body := response.Body.String()
	if response.Code != http.StatusOK || !bytes.Contains(response.Body.Bytes(), []byte(`"issues"`)) || !bytes.Contains(response.Body.Bytes(), []byte(`"granted"`)) || !bytes.Contains(response.Body.Bytes(), []byte(`"shizuku"`)) {
		t.Fatal(response.Code, body)
	}
	g.live.note("clipboard")
	state, waited, err := g.callLink(context.Background(), "0123456789abcdef0123456789abcdef", "event.wait", []byte(`{"clipboard":0,"notifications":0}`))
	if err != nil || state != "complete" || !bytes.Contains(waited, []byte(`"clipboard":1`)) {
		t.Fatal(state, waited, err)
	}
	g.eventsReady = false
	if _, _, err := g.callLink(context.Background(), "0123456789abcdef0123456789abcdef", "event.states", []byte(`{}`)); err == nil {
		t.Fatal("stream down")
	}
}

func TestProxyRemoteUsesRelayLink(t *testing.T) {
	g := testGateway("http://127.0.0.1:9", nil)
	g.relayLink = true
	g.callRemote = func(context.Context, config, []byte) (relayResponse, error) {
		t.Fatal("token")
		return relayResponse{}, nil
	}
	g.relayCall = func(ctx context.Context, op, method string, body []byte) (string, []byte, error) {
		if method != "clipboard.set" || op != "0123456789abcdef0123456789abcdef" || !bytes.Contains(body, []byte(`"text"`)) {
			t.Errorf("%s %s %s", method, op, body)
		}
		return "complete", []byte(`{"content":[{"type":"text","text":"remote"}]}`), nil
	}
	response, err := g.invokeRemote(context.Background(), config{}, []byte(`{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"station_clipboard_set","arguments":{"jobId":"0123456789abcdef0123456789abcdef","text":"hi"}}}`), "remote")
	if err != nil || response.Status != http.StatusOK || !bytes.Contains(response.Body, []byte("remote")) {
		t.Fatal(err, response.Status, string(response.Body))
	}
	addr, err := relayDialAddr("https://192.0.2.8:24443/station")
	if err != nil || addr != "192.0.2.8:24443" {
		t.Fatal(addr, err)
	}
	if _, err := relayDialAddr("https://relay.example/station"); err == nil {
		t.Fatal("name")
	}
	if _, err := relayDialAddr("https://0.0.0.0:443/station"); err == nil {
		t.Fatal("unspecified")
	}
	if _, err := g.ensureRelayLocked(config{RemoteURL: "https://relay.example/station", RemotePin: "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"}); err == nil {
		t.Fatal("name dialed")
	}
	if _, err := g.ensureRelayLocked(config{RemoteURL: "https://192.0.2.8:24443/station", RemotePin: "zz"}); err == nil {
		t.Fatal("pin dialed")
	}
}

func TestPhonePeerSealDefaultsOff(t *testing.T) {
	body := []byte(`{"deviceId":"00112233445566778899aabbccddeeff","spki":"AQ=="}`)
	off, err := phonePeerFrom(body)
	if err != nil || off.Seal {
		t.Fatal(err, off.Seal)
	}
	on, err := phonePeerFrom([]byte(`{"deviceId":"00112233445566778899aabbccddeeff","spki":"AQ==","seal":"required"}`))
	if err != nil || !on.Seal {
		t.Fatal(err, on.Seal)
	}
	other, err := phonePeerFrom([]byte(`{"deviceId":"00112233445566778899aabbccddeeff","spki":"AQ==","seal":"optional"}`))
	if err != nil || other.Seal {
		t.Fatal(err, other.Seal)
	}
}

// A slow business call must not consume the health deadline or close its session.
func TestBusyLocalLinkProbeDoesNotChangeHealth(t *testing.T) {
	g := testGateway("", nil)
	g.linkLocal = true
	entered, release, finished := make(chan struct{}), make(chan struct{}), make(chan struct{})
	g.linkCall = func(ctx context.Context, _, method string, _ []byte) (string, []byte, error) {
		if method == "meta.ping" {
			t.Error("probe competed with business call")
		}
		close(entered)
		<-release
		return "complete", []byte("{}"), nil
	}
	go func() {
		defer close(finished)
		_, _, err := g.callLink(context.Background(), "0123456789abcdef0123456789abcdef", "device.status", []byte("{}"))
		if err != nil {
			t.Error(err)
		}
	}()
	<-entered
	defer func() { close(release); <-finished }()
	checked := make(chan struct{})
	go func() { g.checkLocal("", true); close(checked) }()
	select {
	case <-checked:
	case <-time.After(time.Second):
		t.Fatal("probe queued behind business call")
	}
	if !g.state.localOnline || g.mode() != "local" || g.state.localRevision != 0 {
		t.Fatal("busy route was marked offline")
	}
	g.state.localOnline = false
	g.state.mode = "remote"
	g.checkLocal("", true)
	if g.state.localOnline || g.mode() != "remote" {
		t.Fatal("skipped probe revived offline route")
	}
}

func TestLocalLinkProbeAllowsSlowReplyAndStillDetectsTimeout(t *testing.T) {
	g := testGateway("", nil)
	g.linkLocal = true
	g.linkCall = func(ctx context.Context, _, method string, _ []byte) (string, []byte, error) {
		if method != "meta.ping" {
			t.Fatal(method)
		}
		select {
		case <-time.After(1100 * time.Millisecond):
			return "complete", []byte("{}"), nil
		case <-ctx.Done():
			return "", nil, ctx.Err()
		}
	}
	g.checkLocal("", true)
	if !g.state.localOnline || g.state.localRTT < time.Second || g.mode() != "local" {
		t.Fatal("slow successful link was marked offline")
	}
	g.linkCall = func(ctx context.Context, _, _ string, _ []byte) (string, []byte, error) {
		<-ctx.Done()
		return "", nil, ctx.Err()
	}
	started := time.Now()
	g.checkLocal("", true)
	if g.state.localOnline || g.mode() != "remote" || time.Since(started) > 4*time.Second {
		t.Fatal("dead link did not fail over within its bounded deadline")
	}
}

func TestExpiredQueuedLinkCallDoesNotTouchSession(t *testing.T) {
	g := testGateway("", nil)
	g.eventsReady = true
	g.linkCall = func(context.Context, string, string, []byte) (string, []byte, error) {
		t.Error("expired request reached session")
		return "", nil, nil
	}
	g.linkMu.Lock()
	ctx, cancel := context.WithCancel(context.Background())
	finished := make(chan error, 1)
	go func() {
		_, _, err := g.callLink(ctx, "0123456789abcdef0123456789abcdef", "device.status", []byte("{}"))
		finished <- err
	}()
	cancel()
	g.linkMu.Unlock()
	if err := <-finished; err != context.Canceled || !g.eventsReady {
		t.Fatal("cancelled queue entry changed session", err)
	}
}
