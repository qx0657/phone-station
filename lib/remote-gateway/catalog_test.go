package main

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"strings"
	"testing"
)

func TestCatalogToolsListIsLocal(t *testing.T) {
	var methods []string
	g := testGateway("ws://127.0.0.1:9/v2/link", nil)
	g.linkLocal = true
	g.linkCall = func(ctx context.Context, op, method string, body []byte) (string, []byte, error) {
		methods = append(methods, method)
		if method != "meta.ping" {
			t.Errorf("phone call %s", method)
		}
		return "complete", []byte("{}"), nil
	}
	response := callGateway(g, `{"jsonrpc":"2.0","id":"list","method":"tools/list"}`)
	if response.Code != http.StatusOK {
		t.Fatal(response.Code, response.Body.String())
	}
	var reply struct {
		Result struct {
			ResultType string `json:"resultType"`
			TTLMs      int    `json:"ttlMs"`
			CacheScope string `json:"cacheScope"`
			Tools      []struct {
				Name string `json:"name"`
			} `json:"tools"`
		} `json:"result"`
	}
	if json.Unmarshal(response.Body.Bytes(), &reply) != nil {
		t.Fatal(response.Body.String())
	}
	if reply.Result.ResultType != "complete" || reply.Result.TTLMs != 300000 || reply.Result.CacheScope != "private" {
		t.Fatal(reply.Result.ResultType, reply.Result.TTLMs, reply.Result.CacheScope)
	}
	want := []string{
		"device_list", "device_status", "files_list", "files_stat", "files_read", "files_write",
		"files_edit", "files_manage", "files_grep", "files_transfer", "app_install", "screenshot",
		"shell_run", "clipboard_read", "clipboard_write", "phone_alert", "device_control", "task_status",
	}
	if len(reply.Result.Tools) != len(want) {
		t.Fatal(len(reply.Result.Tools))
	}
	for i, name := range want {
		if reply.Result.Tools[i].Name != name {
			t.Fatal(i, reply.Result.Tools[i].Name)
		}
	}
	if strings.Contains(response.Body.String(), "station_") {
		t.Fatal("old name listed")
	}
	for _, method := range methods {
		if method == "meta.tools" {
			t.Fatal("meta.tools")
		}
	}
	discover := callGateway(g, `{"jsonrpc":"2.0","id":"discover","method":"server/discover"}`)
	if discover.Code != http.StatusOK || !bytes.Contains(discover.Body.Bytes(), []byte("2026-07-28")) || !bytes.Contains(discover.Body.Bytes(), []byte("2025-03-26")) {
		t.Fatal(discover.Code, discover.Body.String())
	}
}

func TestRewriteAICallMapsLinkMethods(t *testing.T) {
	cases := []struct {
		name, args, method, contains string
	}{
		{"files_list", `{"path":"/storage/emulated/0/Download"}`, "file.list", `"path"`},
		{"files_list", `{"path":"/storage/emulated/0","action":"search","query":"a"}`, "file.search", `"name":"a"`},
		{"files_read", `{"path":"a"}`, "file.readtext", ""},
		{"files_read", `{"path":"a","action":"bytes"}`, "file.readbytes", ""},
		{"files_write", `{"path":"a","action":"append"}`, "file.appendtext", ""},
		{"files_write", `{"path":"a","action":"bytes"}`, "file.writebytes", ""},
		{"files_write", `{"path":"a","action":"append-bytes"}`, "file.appendbytes", ""},
		{"files_write", `{"path":"a","action":"truncate"}`, "file.truncatebytes", ""},
		{"files_write", `{"path":"a"}`, "file.writetext", ""},
		{"files_edit", `{"path":"a","action":"patch"}`, "file.patchbytes", ""},
		{"files_edit", `{"path":"a"}`, "file.replacetext", ""},
		{"files_manage", `{"path":"a","action":"move","to":"b"}`, "file.move", `"from":"a"`},
		{"files_manage", `{"path":"a","action":"copy","to":"b"}`, "file.copy", `"from":"a"`},
		{"files_manage", `{"path":"a","action":"mkdir"}`, "file.createdirectory", ""},
		{"files_manage", `{"path":"a","action":"rmdir"}`, "file.deletedirectory", ""},
		{"files_manage", `{"path":"a","action":"delete"}`, "file.delete", ""},
		{"files_grep", `{"path":"a","query":"q"}`, "file.searchtext", ""},
		{"files_transfer", `{"path":"a","action":"push"}`, "file.writebytes", ""},
		{"files_transfer", `{"path":"a"}`, "file.readbytes", ""},
		{"app_install", `{"path":"/storage/emulated/0/Download/a.apk"}`, "install.run", ""},
		{"screenshot", `{}`, "screen.capture", ""},
		{"screenshot", `{"action":"start"}`, "screen.capturestart", ""},
		{"screenshot", `{"action":"status"}`, "screen.capturestatus", ""},
		{"screenshot", `{"action":"release"}`, "screen.capturerelease", ""},
		{"shell_run", `{"command":"id"}`, "shell.exec", ""},
		{"shell_run", `{"action":"start","command":"id"}`, "shell.start", ""},
		{"shell_run", `{"action":"status"}`, "shell.status", ""},
		{"clipboard_read", `{}`, "clipboard.get", ""},
		{"clipboard_write", `{"text":"a"}`, "clipboard.set", ""},
		{"phone_alert", `{"text":"a"}`, "notify.call", ""},
		{"device_control", `{"action":"torch","on":true}`, "torch.call", ""},
		{"device_control", `{"action":"open","path":"a"}`, "file.open", ""},
		{"device_control", `{"action":"awake","on":true}`, "stay.awake", ""},
		{"task_status", `{"id":"0123456789abcdef0123456789abcdef"}`, "operation.status", `"jobId":"0123456789abcdef0123456789abcdef"`},
		{"task_status", `{"jobId":"0123456789abcdef0123456789abcdef"}`, "operation.status", `"jobId":"0123456789abcdef0123456789abcdef"`},
	}
	for _, item := range cases {
		routed, err := rewriteAICall(item.name, json.RawMessage(item.args))
		if err != nil || routed.method != item.method || (item.contains != "" && !bytes.Contains(routed.params, []byte(item.contains))) {
			t.Fatal(item.name, item.method, routed.method, string(routed.params), err)
		}
	}
	if _, err := rewriteAICall("missing_tool", []byte(`{}`)); err == nil || err.Error() != "没有这个工具" {
		t.Fatal(err)
	}
	if method, ok := linkMethodName("station_notify"); !ok || method != "notify.call" {
		t.Fatal(method, ok)
	}
	if method, ok := linkMethodName("station_torch"); !ok || method != "torch.call" {
		t.Fatal(method, ok)
	}
}

func TestAICallUsesMappedMethods(t *testing.T) {
	g := testGateway("ws://127.0.0.1:9/v2/link", nil)
	g.linkLocal = true
	g.linkCall = func(ctx context.Context, op, method string, body []byte) (string, []byte, error) {
		switch method {
		case "meta.ping":
			return "complete", []byte("{}"), nil
		case "file.search":
			if !bytes.Contains(body, []byte(`"name":"a"`)) {
				t.Errorf("search %s", body)
			}
			return "complete", toolText(`{"entries":[]}`), nil
		case "device.status":
			return "complete", toolText(`{"battery":1}`), nil
		case "controls.status":
			return "complete", toolText(`{"battery":9,"verified":true,"model":"PGT-AN20","features":{"shell":true}}`), nil
		case "terminal.open":
			return "complete", toolText(`{"state":"running"}`), nil
		default:
			t.Errorf("unexpected %s %s", method, body)
			return "complete", []byte("{}"), nil
		}
	}
	unknown := callGateway(g, `{"jsonrpc":"2.0","id":"no","method":"tools/call","params":{"name":"not_a_tool"}}`)
	if unknown.Code != http.StatusOK || !bytes.Contains(unknown.Body.Bytes(), []byte("没有这个工具")) {
		t.Fatal(unknown.Code, unknown.Body.String())
	}
	search := callGateway(g, `{"jsonrpc":"2.0","id":"s","method":"tools/call","params":{"name":"files_list","arguments":{"path":"/storage/emulated/0","action":"search","query":"a"}}}`)
	if search.Code != http.StatusOK || !bytes.Contains(search.Body.Bytes(), []byte("entries")) {
		t.Fatal(search.Code, search.Body.String())
	}
	status := callGateway(g, `{"jsonrpc":"2.0","id":"d","method":"tools/call","params":{"name":"device_status","arguments":{}}}`)
	statusPayload := toolPayload(t, status.Body.Bytes())
	if status.Code != http.StatusOK || statusPayload["battery"] != 1.0 || statusPayload["verified"] != true || statusPayload["model"] != "PGT-AN20" || statusPayload["features"] == nil {
		t.Fatal(status.Code, statusPayload)
	}
	list := callGateway(g, `{"jsonrpc":"2.0","id":"l","method":"tools/call","params":{"name":"device_list"}}`)
	listPayload := toolPayload(t, list.Body.Bytes())
	devices, _ := listPayload["devices"].([]any)
	if list.Code != http.StatusOK || len(devices) != 1 {
		t.Fatal(list.Code, listPayload)
	}
	one, _ := devices[0].(map[string]any)
	if one["online"] != true || one["model"] != "PGT-AN20" || one["verified"] != true {
		t.Fatal(one)
	}
	kept := callGateway(g, `{"jsonrpc":"2.0","id":"k","method":"tools/call","params":{"name":"station_terminal_open","arguments":{"sessionId":"0123456789abcdef0123456789abcdef"}}}`)
	if kept.Code != http.StatusOK {
		t.Fatal(kept.Code, kept.Body.String())
	}
	if !bytes.Contains(status.Body.Bytes(), []byte("structuredContent")) {
		t.Fatal(status.Body.String())
	}
}

func TestP4ListenStructuredTaskAndAppNames(t *testing.T) {
	var ops, methods []string
	g := testGateway("ws://127.0.0.1:9/v2/link", nil)
	g.linkLocal = true
	g.live = newLiveEvents()
	g.live.note("notifications")
	png := []byte{0x89, 'P', 'N', 'G', 1, 2, 3, 4}
	encoded := base64.StdEncoding.EncodeToString(png)
	taskID := "0123456789abcdef0123456789abcdef"
	g.linkCall = func(ctx context.Context, op, method string, body []byte) (string, []byte, error) {
		ops = append(ops, op)
		methods = append(methods, method)
		switch method {
		case "meta.ping":
			return "complete", []byte("{}"), nil
		case "clipboard.exchange":
			return "complete", toolText(`{"ok":true}`), nil
		case "screen.capture":
			return "complete", toolText(encoded), nil
		case "operation.status":
			if !bytes.Contains(body, []byte(taskID)) {
				t.Errorf("task body %s", body)
			}
			return "complete", toolText(`{"state":"complete"}`), nil
		default:
			t.Errorf("unexpected %s", method)
			return "complete", []byte("{}"), nil
		}
	}
	list := callGateway(g, `{"jsonrpc":"2.0","id":"list","method":"tools/list"}`)
	if !bytes.Contains(list.Body.Bytes(), []byte("outputSchema")) {
		t.Fatal(list.Body.String())
	}
	oldName := callGateway(g, `{"jsonrpc":"2.0","id":"old","method":"tools/call","params":{"name":"clipboard_exchange","arguments":{}}}`)
	if oldName.Code != http.StatusOK || !bytes.Contains(oldName.Body.Bytes(), []byte("没有这个工具")) {
		t.Fatal(oldName.Body.String())
	}
	forwarded := callGateway(g, `{"jsonrpc":"2.0","id":"pre","method":"tools/call","params":{"name":"station_clipboard_exchange","arguments":{}}}`)
	if forwarded.Code != http.StatusOK || !bytes.Contains(forwarded.Body.Bytes(), []byte("ok")) {
		t.Fatal(forwarded.Body.String())
	}
	appName := callGateway(g, `{"jsonrpc":"2.0","id":"app","method":"tools/call","params":{"name":"clipboard.exchange","arguments":{}}}`)
	if appName.Code != http.StatusOK || !bytes.Contains(appName.Body.Bytes(), []byte("ok")) {
		t.Fatal(appName.Body.String())
	}
	shot := callGateway(g, `{"jsonrpc":"2.0","id":"shot","method":"tools/call","params":{"name":"screenshot","arguments":{}}}`)
	if !bytes.Contains(shot.Body.Bytes(), []byte(`"type":"image"`)) || !bytes.Contains(shot.Body.Bytes(), []byte("image/png")) {
		t.Fatal(shot.Body.String())
	}
	huge := presentLarge(strings.Repeat("secret-body-", 20), 8)
	if bytes.Contains(huge, []byte("secret-body-")) || !bytes.Contains(huge, []byte("resource_link")) {
		t.Fatal(string(huge))
	}
	task := callGateway(g, `{"jsonrpc":"2.0","id":"task","method":"tools/call","params":{"name":"task_status","arguments":{"id":"`+taskID+`"}}}`)
	if !bytes.Contains(task.Body.Bytes(), []byte(taskID)) || !bytes.Contains(task.Body.Bytes(), []byte("io.modelcontextprotocol/tasks")) {
		t.Fatal(task.Body.String())
	}
	taskOps := 0
	for i, method := range methods {
		if method == "operation.status" {
			taskOps++
			if ops[i] != taskID {
				t.Fatal(ops[i])
			}
		}
	}
	if taskOps != 1 {
		t.Fatal(methods)
	}
	listen := callGateway(g, `{"jsonrpc":"2.0","id":"sub","method":"subscriptions/listen","params":{"protocolVersion":"2026-07-28"}}`)
	if !bytes.Contains(listen.Body.Bytes(), []byte(`"notifications"`)) || bytes.Contains(listen.Body.Bytes(), []byte("meta.ping")) {
		t.Fatal(listen.Body.String())
	}
	for _, method := range methods {
		if method == "subscriptions.listen" || method == "event.wait" {
			t.Fatal(method)
		}
	}
}

func TestScopedLinkToolsListFilters(t *testing.T) {
	token := "ps_test"
	g := testGateway("ws://127.0.0.1:9/v2/link", nil)
	g.linkLocal = true
	g.linkCall = func(ctx context.Context, op, method string, body []byte) (string, []byte, error) {
		if method != "meta.ping" {
			t.Errorf("phone call %s", method)
		}
		return "complete", []byte("{}"), nil
	}
	g.readClients = func() (clientRegistry, error) {
		return clientRegistry{Clients: []clientGrant{{TokenHash: tokenDigest(token), Scopes: []string{"files.read"}}}}, nil
	}
	got := scopedCall(g, token, "/mcp", `{"jsonrpc":"2.0","id":"list","method":"tools/list"}`)
	body := got.Body.String()
	if got.Code != 200 || !strings.Contains(body, "files_list") || strings.Contains(body, "shell_run") || strings.Contains(body, "files_write") || strings.Contains(body, "station_") {
		t.Fatal(got.Code, body)
	}
}

func toolPayload(t *testing.T, body []byte) map[string]any {
	t.Helper()
	var reply struct {
		Result struct {
			Content []struct {
				Text string `json:"text"`
			} `json:"content"`
		} `json:"result"`
	}
	if json.Unmarshal(body, &reply) != nil || len(reply.Result.Content) == 0 {
		t.Fatalf("payload %s", body)
	}
	var payload map[string]any
	if json.Unmarshal([]byte(reply.Result.Content[0].Text), &payload) != nil {
		t.Fatal(reply.Result.Content[0].Text)
	}
	return payload
}

func toolText(payload string) []byte {
	raw, err := json.Marshal(struct {
		Content []struct {
			Type string `json:"type"`
			Text string `json:"text"`
		} `json:"content"`
	}{Content: []struct {
		Type string `json:"type"`
		Text string `json:"text"`
	}{{Type: "text", Text: payload}}})
	if err != nil {
		panic(err)
	}
	return raw
}
