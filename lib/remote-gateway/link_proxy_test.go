package main

import (
	"bytes"
	"context"
	"net/http"
	"testing"
	"time"

	"phone-station/link"
)

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
