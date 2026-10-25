package main

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"strings"
)

// AI tools advertised by this gateway. The installed phone still lists the old
// station_ names on meta.tools; clients of this process see only this set.
// Calls that still use station_ names are forwarded so the menu bar keeps working.

type catalogTool struct {
	Name        string          `json:"name"`
	Description string          `json:"description"`
	InputSchema json.RawMessage `json:"inputSchema"`
	Annotations struct {
		ReadOnly    bool `json:"readOnlyHint"`
		Destructive bool `json:"destructiveHint"`
	} `json:"annotations"`
	OutputSchema json.RawMessage `json:"outputSchema,omitempty"`
}

type catalogList struct {
	ResultType string        `json:"resultType"`
	Tools      []catalogTool `json:"tools"`
	TTLMs      int           `json:"ttlMs"`
	CacheScope string        `json:"cacheScope"`
}

func catalogTools() []byte {
	raw, err := json.Marshal(catalogList{
		ResultType: "complete",
		Tools:      aiTools(),
		TTLMs:      300000,
		CacheScope: "private",
	})
	if err != nil {
		panic(err)
	}
	return raw
}

func catalogDiscover() []byte {
	body := struct {
		ResultType string   `json:"resultType"`
		Versions   []string `json:"supportedVersions"`
		Caps       struct {
			Tools struct{} `json:"tools"`
		} `json:"capabilities"`
		Meta struct {
			Server struct {
				Name    string `json:"name"`
				Version string `json:"version"`
			} `json:"io.modelcontextprotocol/serverInfo"`
		} `json:"_meta"`
		TTLMs      int    `json:"ttlMs"`
		CacheScope string `json:"cacheScope"`
	}{
		ResultType: "complete",
		Versions:   []string{"2025-03-26", "2026-07-28"},
		TTLMs:      3600000,
		CacheScope: "public",
	}
	body.Meta.Server.Name = "手机工位"
	body.Meta.Server.Version = "gateway"
	raw, err := json.Marshal(body)
	if err != nil {
		panic(err)
	}
	return raw
}

func aiTools() []catalogTool {
	return []catalogTool{
		aiTool("device_list", "List paired phones.", true, false, schema(nil)),
		aiToolOutput("device_status", "Read battery, storage, and control state.", true, false, schema(nil),
			json.RawMessage(`{"type":"object","properties":{"model":{"type":"string"},"battery":{"type":"number"}}}`)),
		aiTool("files_list", "List a directory or search file names.", true, false, schema([]string{"path"},
			field("path", "string", "Directory path."),
			field("action", "string", "list or search."),
			field("query", "string", "Name query when action is search."))),
		aiTool("files_stat", "Read file metadata.", true, false, schema([]string{"path"},
			field("path", "string", "File path."))),
		aiTool("files_read", "Read a file as text or bytes.", true, false, schema([]string{"path"},
			field("path", "string", "File path."),
			field("action", "string", "text or bytes."))),
		aiTool("files_write", "Write or append text.", false, true, schema([]string{"path"},
			field("path", "string", "File path."),
			field("text", "string", "Text to write."),
			field("action", "string", "write or append."))),
		aiTool("files_edit", "Replace text in a file.", false, true, schema([]string{"path"},
			field("path", "string", "File path."),
			field("find", "string", "Text to find."),
			field("replace", "string", "Replacement."))),
		aiTool("files_manage", "Move, copy, or delete a file or directory.", false, true, schema([]string{"path", "action"},
			field("path", "string", "Source path."),
			field("action", "string", "delete, move, copy, mkdir, or rmdir."),
			field("to", "string", "Destination for move or copy."))),
		aiTool("files_grep", "Search text inside files.", true, false, schema([]string{"path", "query"},
			field("path", "string", "Directory path."),
			field("query", "string", "Text to find."))),
		aiTool("files_transfer", "Read or write file bytes.", false, true, schema([]string{"path"},
			field("path", "string", "File path."),
			field("action", "string", "pull or push."))),
		aiTool("app_install", "Install an APK that is already on the phone.", false, true, schema([]string{"path"},
			field("path", "string", "APK path."))),
		aiTool("screenshot", "Capture the current screen.", false, false, schema(nil,
			field("action", "string", "now or start."))),
		aiTool("shell_run", "Run a Shizuku shell command.", false, true, schema([]string{"command"},
			field("command", "string", "Command."),
			field("action", "string", "run or start."))),
		aiTool("clipboard_read", "Read the phone text clipboard.", true, false, schema(nil)),
		aiTool("clipboard_write", "Replace the phone text clipboard.", false, true, schema([]string{"text"},
			field("text", "string", "Clipboard text."))),
		aiTool("phone_alert", "Show a notification on the phone.", false, false, schema([]string{"text"},
			field("text", "string", "Notification text."),
			field("title", "string", "Title."),
			field("agent", "string", "Sender."),
			field("sound", "string", "Sound path."))),
		aiTool("device_control", "Change stay-awake, torch, find-phone ringing, or open a file.", false, false, schema([]string{"action"},
			field("action", "string", "awake, torch, find_phone, or open."),
			field("on", "boolean", "Desired state."),
			field("path", "string", "File path when action is open."))),
		aiTool("task_status", "Read one existing task receipt.", true, false, schema([]string{"id"},
			field("id", "string", "Receipt id."))),
	}
}

func aiTool(name, description string, readOnly, destructive bool, input json.RawMessage) catalogTool {
	return aiToolOutput(name, description, readOnly, destructive, input, nil)
}

func aiToolOutput(name, description string, readOnly, destructive bool, input, output json.RawMessage) catalogTool {
	tool := catalogTool{Name: name, Description: description, InputSchema: input, OutputSchema: output}
	tool.Annotations.ReadOnly = readOnly
	tool.Annotations.Destructive = destructive
	return tool
}

func schema(required []string, fields ...string) json.RawMessage {
	props := "{" + strings.Join(fields, ",") + "}"
	if len(required) == 0 {
		return json.RawMessage(`{"type":"object","properties":` + props + `}`)
	}
	names, err := json.Marshal(required)
	if err != nil {
		panic(err)
	}
	return json.RawMessage(`{"type":"object","properties":` + props + `,"required":` + string(names) + `}`)
}

func field(name, kind, description string) string {
	raw, err := json.Marshal(struct {
		Type        string `json:"type"`
		Description string `json:"description"`
	}{kind, description})
	if err != nil {
		panic(err)
	}
	return `"` + name + `":` + string(raw)
}

type aiRoute struct {
	method string
	params []byte
	kind   string
}

var errToolArgs = errors.New("params 需要对象")

func rewriteAICall(name string, raw json.RawMessage) (aiRoute, error) {
	args, err := objectArgs(raw)
	if err != nil {
		return aiRoute{}, err
	}
	action, _ := argString(args, "action")
	delete(args, "action")
	switch name {
	case "device_list":
		return aiRoute{kind: "device_list"}, nil
	case "device_status":
		return aiRoute{kind: "device_status"}, nil
	case "files_list":
		if action == "search" {
			copyArg(args, "query", "name")
			return aiRoute{method: "file.search", params: mustArgs(args)}, nil
		}
		return aiRoute{method: "file.list", params: mustArgs(args)}, nil
	case "files_stat":
		return aiRoute{method: "file.stat", params: mustArgs(args)}, nil
	case "files_read":
		method := "file.readtext"
		if action == "bytes" {
			method = "file.readbytes"
		}
		return aiRoute{method: method, params: mustArgs(args)}, nil
	case "files_write":
		method := "file.writetext"
		switch action {
		case "append":
			method = "file.appendtext"
		case "bytes":
			method = "file.writebytes"
		case "append-bytes":
			method = "file.appendbytes"
		case "truncate":
			method = "file.truncatebytes"
		}
		return aiRoute{method: method, params: mustArgs(args)}, nil
	case "files_edit":
		method := "file.replacetext"
		if action == "patch" {
			method = "file.patchbytes"
		}
		return aiRoute{method: method, params: mustArgs(args)}, nil
	case "files_manage":
		method := "file.delete"
		switch action {
		case "move":
			method = "file.move"
			copyArg(args, "path", "from")
		case "copy":
			method = "file.copy"
			copyArg(args, "path", "from")
		case "mkdir":
			method = "file.createdirectory"
		case "rmdir":
			method = "file.deletedirectory"
		}
		return aiRoute{method: method, params: mustArgs(args)}, nil
	case "files_grep":
		return aiRoute{method: "file.searchtext", params: mustArgs(args)}, nil
	case "files_transfer":
		method := "file.readbytes"
		if action == "push" {
			method = "file.writebytes"
		}
		return aiRoute{method: method, params: mustArgs(args)}, nil
	case "app_install":
		return aiRoute{method: "install.run", params: mustArgs(args)}, nil
	case "screenshot":
		method := "screen.capture"
		switch action {
		case "start":
			method = "screen.capturestart"
		case "status":
			method = "screen.capturestatus"
		case "release":
			method = "screen.capturerelease"
		}
		return aiRoute{method: method, params: mustArgs(args)}, nil
	case "shell_run":
		method := "shell.exec"
		switch action {
		case "start":
			method = "shell.start"
		case "status":
			method = "shell.status"
		}
		return aiRoute{method: method, params: mustArgs(args)}, nil
	case "clipboard_read":
		return aiRoute{method: "clipboard.get", params: mustArgs(args)}, nil
	case "clipboard_write":
		return aiRoute{method: "clipboard.set", params: mustArgs(args)}, nil
	case "phone_alert":
		return aiRoute{method: "notify.call", params: mustArgs(args)}, nil
	case "device_control":
		method := "stay.awake"
		switch action {
		case "find_phone":
			method = "find.phone"
		case "torch":
			method = "torch.call"
		case "open":
			method = "file.open"
		}
		return aiRoute{method: method, params: mustArgs(args)}, nil
	case "task_status":
		copyArg(args, "id", "jobId")
		return aiRoute{method: "operation.status", params: mustArgs(args)}, nil
	default:
		return aiRoute{}, errors.New("没有这个工具")
	}
}

func objectArgs(raw json.RawMessage) (map[string]json.RawMessage, error) {
	raw = bytes.TrimSpace(raw)
	if len(raw) == 0 || bytes.Equal(raw, []byte("null")) {
		return map[string]json.RawMessage{}, nil
	}
	var args map[string]json.RawMessage
	if json.Unmarshal(raw, &args) != nil || args == nil {
		return nil, errToolArgs
	}
	return args, nil
}

func argString(args map[string]json.RawMessage, key string) (string, bool) {
	raw, ok := args[key]
	if !ok {
		return "", false
	}
	var text string
	if json.Unmarshal(raw, &text) != nil {
		return "", false
	}
	return text, true
}

func copyArg(args map[string]json.RawMessage, from, to string) {
	if _, ok := args[to]; ok {
		return
	}
	raw, ok := args[from]
	if !ok {
		return
	}
	args[to] = append(json.RawMessage(nil), raw...)
}

func mustArgs(args map[string]json.RawMessage) []byte {
	if args == nil {
		return []byte("{}")
	}
	raw, err := json.Marshal(args)
	if err != nil {
		return []byte("{}")
	}
	return raw
}

func answerCatalog(id json.RawMessage, method string) ([]byte, bool) {
	switch method {
	case "tools/list":
		return rpcResult(id, catalogTools()), true
	case "server/discover":
		return rpcResult(id, catalogDiscover()), true
	default:
		return nil, false
	}
}

func answerAI(ctx context.Context, id, params json.RawMessage, call func(context.Context, string, string, []byte) (string, []byte, error)) ([]byte, int, bool) {
	var request struct {
		Name      string          `json:"name"`
		Arguments json.RawMessage `json:"arguments"`
	}
	if json.Unmarshal(params, &request) != nil || request.Name == "" || strings.HasPrefix(request.Name, "station_") {
		return nil, 0, false
	}
	if method, ok := appLinkMethod(request.Name); ok {
		args := bytes.TrimSpace(request.Arguments)
		if len(args) == 0 || bytes.Equal(args, []byte("null")) {
			args = []byte("{}")
		}
		body, status := completeCall(ctx, id, linkOperation("tools/call", params), method, args, call)
		return body, status, true
	}
	if request.Name == "task_status" {
		return answerTask(ctx, id, params, request.Arguments, call)
	}
	routed, err := rewriteAICall(request.Name, request.Arguments)
	if err != nil {
		code := -32001
		if errors.Is(err, errToolArgs) {
			code = -32602
		}
		return rpcErrorBody(id, code, err.Error()), httpOK, true
	}
	switch routed.kind {
	case "device_status":
		body, status := compositeDevice(ctx, id, params, call)
		return body, status, true
	case "device_list":
		body, status := compositeList(ctx, id, params, call)
		return body, status, true
	}
	op := linkOperation("tools/call", params)
	body, status := completeCall(ctx, id, op, routed.method, routed.params, call)
	if request.Name == "screenshot" {
		body = decorateScreenshot(body)
	}
	return body, status, true
}

func appLinkMethod(name string) (string, bool) {
	switch name {
	case "clipboard.exchange", "clipboard.configure", "clipboard.state",
		"notification.poll", "notification.status", "notification.configure", "notification.icon",
		"screen.open", "screen.close", "screen.status", "screen.capture", "screen.capturestart",
		"terminal.open", "terminal.close", "terminal.input", "terminal.read", "terminal.resize":
		return name, true
	default:
		return "", false
	}
}

func answerTask(ctx context.Context, id, params, arguments json.RawMessage, call func(context.Context, string, string, []byte) (string, []byte, error)) ([]byte, int, bool) {
	routed, err := rewriteAICall("task_status", arguments)
	if err != nil {
		return rpcErrorBody(id, -32602, err.Error()), httpOK, true
	}
	op := linkOperation("tools/call", params)
	body, status := completeCall(ctx, id, op, routed.method, routed.params, call)
	return attachTask(body, op), status, true
}

const httpOK = 200

func compositeDevice(ctx context.Context, id, params json.RawMessage, call func(context.Context, string, string, []byte) (string, []byte, error)) ([]byte, int) {
	device, fail, status := rawCall(ctx, linkOperation("tools/call", params), "device.status", []byte("{}"), call)
	if fail != nil {
		return rewriteErrorID(fail, id), status
	}
	controls, fail, status := rawCall(ctx, linkOperation("tools/call", params), "controls.status", []byte("{}"), call)
	if fail != nil {
		return rewriteErrorID(fail, id), status
	}
	return rpcResult(id, attachStructured(mergeStatus(device, controls))), httpOK
}

func compositeList(ctx context.Context, id, params json.RawMessage, call func(context.Context, string, string, []byte) (string, []byte, error)) ([]byte, int) {
	controls, fail, status := rawCall(ctx, linkOperation("tools/call", params), "controls.status", []byte("{}"), call)
	if fail != nil {
		return rewriteErrorID(fail, id), status
	}
	return rpcResult(id, deviceListResult(controls)), httpOK
}

func rawCall(ctx context.Context, op, method string, params []byte, call func(context.Context, string, string, []byte) (string, []byte, error)) ([]byte, []byte, int) {
	state, result, err := call(ctx, op, method, params)
	if err != nil {
		return nil, rpcErrorBody(nil, -32001, "无法连接手机"), 502
	}
	if state != "complete" {
		return nil, rpcErrorBody(nil, -32001, state), httpOK
	}
	if len(result) == 0 || !json.Valid(result) {
		result = []byte("{}")
	}
	return result, nil, httpOK
}

func completeCall(ctx context.Context, id json.RawMessage, op, method string, params []byte, call func(context.Context, string, string, []byte) (string, []byte, error)) ([]byte, int) {
	result, fail, status := rawCall(ctx, op, method, params, call)
	if fail != nil {
		patched := rewriteErrorID(fail, id)
		return patched, status
	}
	return rpcResult(id, result), httpOK
}

func attachStructured(envelope []byte) []byte {
	fields, ok := toolObject(envelope)
	if !ok {
		return envelope
	}
	var env map[string]any
	if json.Unmarshal(envelope, &env) != nil {
		return envelope
	}
	structured := map[string]any{}
	var summary strings.Builder
	for key, raw := range fields {
		var value any
		if json.Unmarshal(raw, &value) != nil {
			continue
		}
		structured[key] = value
		if summary.Len() > 0 {
			summary.WriteString(", ")
		}
		summary.WriteString(key)
	}
	env["structuredContent"] = structured
	content, _ := env["content"].([]any)
	content = append(content, map[string]any{"type": "text", "text": summary.String()})
	env["content"] = content
	raw, err := json.Marshal(env)
	if err != nil {
		return envelope
	}
	return raw
}

func decorateScreenshot(rpcBody []byte) []byte {
	var msg map[string]json.RawMessage
	if json.Unmarshal(rpcBody, &msg) != nil || len(msg["result"]) == 0 {
		return rpcBody
	}
	var env map[string]any
	if json.Unmarshal(msg["result"], &env) != nil {
		return rpcBody
	}
	text := ""
	if content, _ := env["content"].([]any); len(content) > 0 {
		if item, _ := content[0].(map[string]any); item != nil {
			text, _ = item["text"].(string)
		}
	}
	const large = 1 << 20
	if len(text) > large {
		msg["result"] = presentLarge(text, large)
	} else if _, ok := toolObject(msg["result"]); ok {
		msg["result"] = attachStructured(msg["result"])
	}
	if png, err := base64.StdEncoding.DecodeString(text); err == nil && len(png) >= 4 && bytes.Equal(png[:4], []byte{0x89, 'P', 'N', 'G'}) && len(text) <= large {
		if json.Unmarshal(msg["result"], &env) == nil {
			content, _ := env["content"].([]any)
			content = append(content, map[string]any{"type": "image", "data": text, "mimeType": "image/png"})
			env["content"] = content
			if raw, err := json.Marshal(env); err == nil {
				msg["result"] = raw
			}
		}
	}
	out, err := json.Marshal(msg)
	if err != nil {
		return rpcBody
	}
	return out
}

func presentLarge(text string, limit int) []byte {
	envelope := wrapToolObject(map[string]json.RawMessage{"text": mustJSONString(text)})
	if len(text) <= limit {
		return envelope
	}
	var env map[string]any
	if json.Unmarshal(envelope, &env) != nil {
		return envelope
	}
	env["content"] = []any{map[string]any{"type": "resource_link", "uri": "station://result/body"}}
	env["structuredContent"] = map[string]any{"truncated": true, "bytes": len(text)}
	raw, err := json.Marshal(env)
	if err != nil {
		return envelope
	}
	return raw
}

func mustJSONString(text string) json.RawMessage {
	raw, err := json.Marshal(text)
	if err != nil {
		return []byte(`""`)
	}
	return raw
}

func attachTask(rpcBody []byte, op string) []byte {
	var msg map[string]any
	if json.Unmarshal(rpcBody, &msg) != nil {
		return rpcBody
	}
	result, _ := msg["result"].(map[string]any)
	if result == nil {
		result = map[string]any{}
	}
	result["task"] = map[string]any{"id": op, "status": "complete"}
	meta, _ := result["_meta"].(map[string]any)
	if meta == nil {
		meta = map[string]any{}
	}
	meta["io.modelcontextprotocol/tasks"] = map[string]any{"taskId": op}
	result["_meta"] = meta
	msg["result"] = result
	raw, err := json.Marshal(msg)
	if err != nil {
		return rpcBody
	}
	return raw
}

func listenBody(id, params json.RawMessage, live *liveEvents) []byte {
	var args struct {
		ProtocolVersion string `json:"protocolVersion"`
	}
	if len(bytes.TrimSpace(params)) > 0 && json.Unmarshal(params, &args) != nil {
		return rpcErrorBody(id, -32602, "params")
	}
	if args.ProtocolVersion != "" && args.ProtocolVersion != "2025-03-26" && args.ProtocolVersion != "2026-07-28" {
		return rpcErrorBody(id, -32602, "protocol")
	}
	clip, notes := int64(0), int64(0)
	if live != nil {
		clip, notes = live.counts()
	}
	raw, err := json.Marshal(map[string]any{
		"protocolVersion": "2026-07-28",
		"events": []map[string]any{
			{"topic": "clipboard", "cursor": clip},
			{"topic": "notifications", "cursor": notes},
		},
	})
	if err != nil {
		return rpcErrorBody(id, -32603, "内部错误")
	}
	return rpcResult(id, raw)
}

func rewriteErrorID(body, id []byte) []byte {
	if len(id) == 0 {
		return body
	}
	var msg struct {
		JSONRPC string          `json:"jsonrpc"`
		ID      json.RawMessage `json:"id"`
		Error   json.RawMessage `json:"error"`
	}
	if json.Unmarshal(body, &msg) != nil || len(msg.Error) == 0 {
		return body
	}
	msg.ID = id
	raw, err := json.Marshal(msg)
	if err != nil {
		return body
	}
	return raw
}

type toolEnvelope struct {
	IsError bool `json:"isError"`
	Content []struct {
		Type string `json:"type"`
		Text string `json:"text"`
	} `json:"content"`
}

func toolObject(result []byte) (map[string]json.RawMessage, bool) {
	var env toolEnvelope
	if json.Unmarshal(result, &env) != nil || env.IsError {
		return nil, false
	}
	for _, item := range env.Content {
		if item.Type != "text" {
			continue
		}
		var obj map[string]json.RawMessage
		if json.Unmarshal([]byte(item.Text), &obj) != nil || obj == nil {
			return nil, false
		}
		return obj, true
	}
	return nil, false
}

func wrapToolObject(obj map[string]json.RawMessage) []byte {
	text, err := json.Marshal(obj)
	if err != nil {
		return []byte(`{"content":[{"type":"text","text":"{}"}]}`)
	}
	raw, err := json.Marshal(struct {
		Content []struct {
			Type string `json:"type"`
			Text string `json:"text"`
		} `json:"content"`
	}{Content: []struct {
		Type string `json:"type"`
		Text string `json:"text"`
	}{{Type: "text", Text: string(text)}}})
	if err != nil {
		return []byte(`{"content":[{"type":"text","text":"{}"}]}`)
	}
	return raw
}

func mergeStatus(device, controls []byte) []byte {
	left, ok := toolObject(device)
	if !ok {
		return device
	}
	right, ok := toolObject(controls)
	if !ok {
		return controls
	}
	for key, value := range right {
		if _, exists := left[key]; !exists {
			left[key] = value
		}
	}
	return wrapToolObject(left)
}

func deviceListResult(controls []byte) []byte {
	fields, ok := toolObject(controls)
	if !ok {
		return controls
	}
	one := map[string]json.RawMessage{"online": []byte("true")}
	if model, exists := fields["model"]; exists {
		one["model"] = model
	}
	if verified, exists := fields["verified"]; exists {
		one["verified"] = verified
	}
	encoded, err := json.Marshal(one)
	if err != nil {
		return controls
	}
	return wrapToolObject(map[string]json.RawMessage{
		"devices": json.RawMessage("[" + string(encoded) + "]"),
	})
}
