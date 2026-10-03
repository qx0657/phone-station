package main

import (
	"bytes"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"syscall"
	"time"
)

// Explicit allowlist: neither tool annotations nor a station_file_ prefix grant access.
// Shell includes global job results because shell already has the phone shell identity.
var clientToolScopes = map[string]string{
	"station_device_status": "status", "station_controls_status": "status",
	"station_notification_status": "status", "station_shell_status": "status",
	"station_file_access_policy": "files.read", "station_storage_summary": "files.read",
	"station_file_list": "files.read", "station_file_stat": "files.read",
	"station_file_read_text": "files.read", "station_file_read_bytes": "files.read",
	"station_file_search": "files.read", "station_file_search_text": "files.read",
	"station_file_write_text": "files.write", "station_file_replace_text": "files.write",
	"station_file_append_text": "files.write", "station_file_write_bytes": "files.write",
	"station_file_append_bytes": "files.write", "station_file_patch_bytes": "files.write",
	"station_file_create_directory": "files.write",
	"station_file_delete_directory": "files.write", "station_file_truncate_bytes": "files.write", "station_file_move": "files.write",
	"station_file_copy": "files.write", "station_file_delete": "files.write",
	"station_shell_exec": "shell", "station_shell_start": "shell", "station_operation_status": "shell",
}
var clientScopes = map[string]bool{"status": true, "files.read": true, "files.write": true, "shell": true}

type clientGrant struct {
	ID        string   `json:"id"`
	Name      string   `json:"name"`
	Scopes    []string `json:"scopes"`
	CreatedAt string   `json:"createdAt"`
	TokenHash string   `json:"tokenHash"`
}
type clientRegistry struct {
	Version int           `json:"version"`
	Clients []clientGrant `json:"clients"`
}

func clientsPath() (string, error) {
	file, err := configPath()
	return filepath.Join(filepath.Dir(file), "clients.json"), err
}
func readClients(file string) (clientRegistry, error) {
	f, err := os.Open(file)
	if errors.Is(err, os.ErrNotExist) {
		return clientRegistry{Version: 1, Clients: []clientGrant{}}, nil
	}
	if err != nil {
		return clientRegistry{}, errors.New("客户端授权文件不可读")
	}
	defer f.Close()
	body, err := readBody(f, 1<<20)
	var value clientRegistry
	if err != nil || json.Unmarshal(body, &value) != nil || value.Version != 1 || len(value.Clients) > 128 {
		return clientRegistry{}, errors.New("客户端授权文件无效")
	}
	ids, hashes := map[string]bool{}, map[string]bool{}
	for _, client := range value.Clients {
		digest, err := hex.DecodeString(client.TokenHash)
		if client.ID == "" || ids[client.ID] || hashes[client.TokenHash] || err != nil || len(digest) != 32 || len(client.Scopes) == 0 {
			return clientRegistry{}, errors.New("客户端授权记录无效")
		}
		ids[client.ID], hashes[client.TokenHash] = true, true
		for _, scope := range client.Scopes {
			if !clientScopes[scope] {
				return clientRegistry{}, errors.New("客户端权限无效")
			}
		}
	}
	return value, nil
}
func updateClients(file string, update func(*clientRegistry) error) error {
	dir := filepath.Dir(file)
	if err := os.MkdirAll(dir, 0700); err != nil {
		return err
	}
	if err := os.Chmod(dir, 0700); err != nil {
		return err
	}
	lock, err := os.OpenFile(file+".lock", os.O_CREATE|os.O_RDWR, 0600)
	if err != nil {
		return err
	}
	defer lock.Close()
	if err := syscall.Flock(int(lock.Fd()), syscall.LOCK_EX); err != nil {
		return err
	}
	defer syscall.Flock(int(lock.Fd()), syscall.LOCK_UN)
	value, err := readClients(file)
	if err != nil {
		return err
	}
	if err = update(&value); err != nil {
		return err
	}
	body, err := json.Marshal(value)
	if err != nil {
		return err
	}
	temp, err := os.CreateTemp(dir, ".clients-*.tmp")
	if err != nil {
		return err
	}
	defer os.Remove(temp.Name())
	defer temp.Close()
	if err = temp.Chmod(0600); err != nil {
		return err
	}
	if _, err = temp.Write(body); err != nil {
		return err
	}
	if err = temp.Sync(); err != nil {
		return err
	}
	if err = temp.Close(); err != nil {
		return err
	}
	if err = os.Rename(temp.Name(), file); err != nil {
		return err
	}
	directory, err := os.Open(dir)
	if err != nil {
		return err
	}
	defer directory.Close()
	return directory.Sync()
}
func tokenDigest(token string) string {
	sum := sha256.Sum256([]byte(token))
	return hex.EncodeToString(sum[:])
}
func clientsCLI(args []string, file string, output io.Writer) error {
	if len(args) == 1 && args[0] == "list" {
		value, err := readClients(file)
		if err != nil {
			return err
		}
		list := []map[string]any{}
		for _, c := range value.Clients {
			list = append(list, map[string]any{"id": c.ID, "name": c.Name, "scopes": c.Scopes, "createdAt": c.CreatedAt})
		}
		return json.NewEncoder(output).Encode(list)
	}
	if len(args) == 2 && args[0] == "revoke" {
		err := updateClients(file, func(value *clientRegistry) error {
			for i, c := range value.Clients {
				if c.ID == args[1] {
					value.Clients = append(value.Clients[:i], value.Clients[i+1:]...)
					return nil
				}
			}
			return errors.New("没有这个客户端编号")
		})
		if err != nil {
			return err
		}
		return json.NewEncoder(output).Encode(map[string]string{"revoked": args[1]})
	}
	if len(args) != 3 || args[0] != "create" {
		return errors.New("用法: clients create <名称> <逗号分隔权限> | list | revoke <编号>；权限: status,files.read,files.write,shell")
	}
	name := strings.TrimSpace(args[1])
	if name == "" || len(name) > 128 || strings.ContainsAny(name, "\r\n\x00") {
		return errors.New("客户端名称为空、过长或包含控制字符")
	}
	scopes, seen := []string{}, map[string]bool{}
	for _, scope := range strings.Split(args[2], ",") {
		if !clientScopes[scope] {
			return errors.New("未知权限；可用: status,files.read,files.write,shell")
		}
		if !seen[scope] {
			scopes = append(scopes, scope)
			seen[scope] = true
		}
	}
	sort.Strings(scopes)
	token := "ps_" + randomHex(32)
	client := clientGrant{ID: randomHex(16), Name: name, Scopes: scopes, CreatedAt: time.Now().UTC().Format(time.RFC3339), TokenHash: tokenDigest(token)}
	if err := updateClients(file, func(value *clientRegistry) error {
		if len(value.Clients) >= 128 {
			return errors.New("客户端数量已达 128，请先撤销不用的客户端")
		}
		for _, existing := range value.Clients {
			if existing.Name == name {
				return errors.New("客户端名称已存在，请先撤销旧令牌或使用不同名称")
			}
		}
		value.Clients = append(value.Clients, client)
		return nil
	}); err != nil {
		return err
	}
	// The only time plaintext is returned. Never stored or included by list/status.
	return json.NewEncoder(output).Encode(map[string]any{"id": client.ID, "name": name, "scopes": scopes, "endpoint": publicAddress, "token": token})
}
func (g *gateway) clientAuthorization(r *http.Request) (*clientGrant, error) {
	headers := r.Header.Values("Authorization")
	if len(headers) != 1 || !strings.HasPrefix(headers[0], "Bearer ps_") {
		return nil, nil
	}
	var value clientRegistry
	var err error
	if g.readClients != nil {
		value, err = g.readClients()
	} else {
		var file string
		file, err = clientsPath()
		if err == nil {
			value, err = readClients(file)
		}
	}
	if err != nil {
		return nil, err
	}
	digest := tokenDigest(strings.TrimPrefix(headers[0], "Bearer "))
	for _, client := range value.Clients {
		if subtle.ConstantTimeCompare([]byte(client.TokenHash), []byte(digest)) == 1 {
			return &client, nil
		}
	}
	return nil, nil
}
func (c clientGrant) allows(tool string) bool {
	scope, known := clientToolScopes[tool]
	if !known {
		return false
	}
	for _, granted := range c.Scopes {
		if scope == granted {
			return true
		}
	}
	return false
}

// Reject ambiguous objects throughout params too; Android rejects duplicate fields.
func uniqueJSON(body []byte) bool {
	decoder := json.NewDecoder(bytes.NewReader(body))
	decoder.UseNumber()
	var value func(int) bool
	value = func(depth int) bool {
		if depth > 128 {
			return false
		}
		token, err := decoder.Token()
		if err != nil {
			return false
		}
		delimiter, compound := token.(json.Delim)
		if !compound {
			return true
		}
		if delimiter != '{' && delimiter != '[' {
			return false
		}
		keys := map[string]bool{}
		for decoder.More() {
			if delimiter == '{' {
				token, err = decoder.Token()
				key, ok := token.(string)
				if err != nil || !ok || keys[key] {
					return false
				}
				keys[key] = true
			}
			if !value(depth + 1) {
				return false
			}
		}
		_, err = decoder.Token()
		return err == nil
	}
	if !value(0) {
		return false
	}
	_, err := decoder.Token()
	return err == io.EOF
}
func rpcMessages(body []byte) ([]json.RawMessage, bool) {
	trimmed := bytes.TrimSpace(body)
	if len(trimmed) == 0 {
		return nil, false
	}
	if trimmed[0] != '[' {
		return []json.RawMessage{trimmed}, false
	}
	var messages []json.RawMessage
	if json.Unmarshal(trimmed, &messages) != nil {
		return nil, true
	}
	return messages, true
}
func (c clientGrant) preflight(body []byte) ([]json.RawMessage, error) {
	denied := errors.New("客户端无此权限或请求格式无效；整批尚未执行")
	if !uniqueJSON(body) {
		return nil, denied
	}
	messages, _ := rpcMessages(body)
	if len(messages) == 0 || len(messages) > 64 {
		return nil, denied
	}
	var lists []json.RawMessage
	var ids []json.RawMessage
	for _, message := range messages {
		var request map[string]json.RawMessage
		if json.Unmarshal(message, &request) != nil || request == nil {
			return nil, denied
		}
		var method, version string
		if json.Unmarshal(request["method"], &method) != nil || json.Unmarshal(request["jsonrpc"], &version) != nil || version != "2.0" {
			return nil, denied
		}
		if method == "notifications/initialized" {
			if _, hasID := request["id"]; hasID {
				return nil, denied
			}
			continue
		}
		id := request["id"]
		if !validRPCID(id) {
			return nil, denied
		}
		for _, previous := range ids {
			if sameRPCID(previous, id) {
				return nil, denied
			}
		}
		ids = append(ids, id)
		switch method {
		case "initialize", "ping":
		case "tools/list":
			lists = append(lists, id)
		case "tools/call":
			var params map[string]json.RawMessage
			var name string
			if json.Unmarshal(request["params"], &params) != nil || json.Unmarshal(params["name"], &name) != nil || !c.allows(name) {
				return nil, denied
			}
		default:
			return nil, denied
		}
	}
	return lists, nil
}

// Only tools/list needs response filtering; normal operation results pass through.
type clientResponse struct {
	header   http.Header
	status   int
	body     bytes.Buffer
	overflow bool
}

func (w *clientResponse) Header() http.Header { return w.header }
func (w *clientResponse) WriteHeader(code int) {
	if w.status == 0 {
		w.status = code
	}
}
func (w *clientResponse) Write(body []byte) (int, error) {
	if w.status == 0 {
		w.status = 200
	}
	if w.body.Len()+len(body) > maxBody {
		w.overflow = true
		return 0, errors.New("response exceeds limit")
	}
	return w.body.Write(body)
}
func (c clientGrant) filtered(body []byte, listIDs []json.RawMessage) ([]byte, error) {
	messages, batch := rpcMessages(body)
	if !uniqueJSON(body) {
		return nil, errors.New("工具列表响应无效")
	}
	for i, raw := range messages {
		var message map[string]json.RawMessage
		if json.Unmarshal(raw, &message) != nil || message == nil {
			return nil, errors.New("工具列表响应无效")
		}
		isList := false
		for _, id := range listIDs {
			if sameRPCID(id, message["id"]) {
				isList = true
			}
		}
		if _, failed := message["error"]; failed {
			continue
		}
		var result map[string]json.RawMessage
		var tools []map[string]json.RawMessage
		if json.Unmarshal(message["result"], &result) != nil {
			return nil, errors.New("工具列表响应无效")
		}
		if !isList {
			// Unexpected IDs must never bypass filtering of an inventory.
			if _, inventory := result["tools"]; inventory {
				return nil, errors.New("工具列表编号不匹配")
			}
			continue
		}
		if json.Unmarshal(result["tools"], &tools) != nil {
			return nil, errors.New("工具列表响应无效")
		}
		allowed := []map[string]json.RawMessage{}
		for _, tool := range tools {
			var name string
			if json.Unmarshal(tool["name"], &name) == nil && c.allows(name) {
				allowed = append(allowed, tool)
			}
		}
		result["tools"], _ = json.Marshal(allowed)
		message["result"], _ = json.Marshal(result)
		messages[i], _ = json.Marshal(message)
	}
	if batch {
		return json.Marshal(messages)
	}
	if len(messages) != 1 {
		return nil, errors.New("工具列表响应无效")
	}
	return messages[0], nil
}
func (c clientGrant) finish(w http.ResponseWriter, captured *clientResponse, lists []json.RawMessage) {
	body := captured.body.Bytes()
	if captured.overflow {
		writeMCPError(w, nil, http.StatusBadGateway, "手机响应过大；未重试")
		return
	}
	// Error responses contain no inventory; don't replace useful transport diagnostics.
	if captured.status == http.StatusOK {
		var err error
		body, err = c.filtered(body, lists)
		if err != nil {
			writeMCPError(w, nil, http.StatusBadGateway, fmt.Sprint(err))
			return
		}
	}
	for key, values := range captured.header {
		w.Header()[key] = values
	}
	w.Header().Del("Content-Length")
	if captured.status == 0 {
		captured.status = http.StatusBadGateway
	}
	w.WriteHeader(captured.status)
	_, _ = w.Write(body)
}
