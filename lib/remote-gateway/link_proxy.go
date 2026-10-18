package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
	"time"

	"phone-station/link"
)

const linkForward = "127.0.0.1:18766"

var linkOpPattern = regexp.MustCompile(`^[0-9a-f]{32}$`)

func (g *gateway) proxyLink(parent context.Context, w http.ResponseWriter, body []byte) {
	ctx, cancel := context.WithTimeout(parent, 4*time.Minute)
	defer cancel()
	g.usage.request("local")
	trimmed := bytes.TrimSpace(body)
	if len(trimmed) > 0 && trimmed[0] == '[' {
		g.proxyLinkBatch(ctx, w, body)
		return
	}
	reply, status := g.oneLink(ctx, body)
	writeRaw(w, status, reply)
}

func (g *gateway) proxyLinkBatch(ctx context.Context, w http.ResponseWriter, body []byte) {
	var items []json.RawMessage
	if json.Unmarshal(body, &items) != nil || len(items) == 0 || len(items) > 64 {
		writeRPCError(w, nil, http.StatusOK, -32600, "批量请求需要 1 到 64 项")
		return
	}
	replies := make([]json.RawMessage, 0, len(items))
	for _, item := range items {
		reply, status := g.oneLink(ctx, item)
		if status == http.StatusBadGateway {
			writeMCPError(w, requestID(item), http.StatusBadGateway, "无法连接手机")
			return
		}
		if len(bytes.TrimSpace(reply)) > 0 {
			replies = append(replies, reply)
		}
	}
	if len(replies) == 0 {
		writeRaw(w, http.StatusAccepted, nil)
		return
	}
	encoded, err := json.Marshal(replies)
	if err != nil {
		writeMCPError(w, nil, http.StatusBadGateway, "无法连接手机")
		return
	}
	writeRaw(w, http.StatusOK, encoded)
}

func (g *gateway) oneLink(ctx context.Context, body []byte) ([]byte, int) {
	return g.oneCaller(ctx, body, g.callLink)
}

func (g *gateway) oneCaller(ctx context.Context, body []byte, call func(context.Context, string, string, []byte) (string, []byte, error)) ([]byte, int) {
	var request struct {
		JSONRPC string          `json:"jsonrpc"`
		ID      json.RawMessage `json:"id"`
		Method  string          `json:"method"`
		Params  json.RawMessage `json:"params"`
	}
	if json.Unmarshal(body, &request) != nil || request.JSONRPC != "2.0" {
		return rpcErrorBody(request.ID, -32600, "需要 JSON-RPC 2.0"), http.StatusOK
	}
	if len(request.ID) == 0 || !validRPCID(request.ID) {
		if len(request.ID) == 0 {
			return nil, http.StatusAccepted
		}
		return rpcErrorBody(nil, -32600, "请求 id 需要字符串或整数，不能为 null"), http.StatusOK
	}
	if body, ok := answerCatalog(request.ID, request.Method); ok {
		return body, http.StatusOK
	}
	if request.Method == "subscriptions/listen" {
		return listenBody(request.ID, request.Params, g.live), http.StatusOK
	}
	if request.Method == "tools/call" {
		if body, status, ok := answerAI(ctx, request.ID, request.Params, call); ok {
			return body, status
		}
	}
	method, params, err := linkCallFor(request.Method, request.Params)
	if err != nil {
		return rpcErrorBody(request.ID, -32601, err.Error()), http.StatusOK
	}
	return completeCall(ctx, request.ID, linkOperation(request.Method, request.Params), method, params, call)
}

func (g *gateway) shareUpload(w http.ResponseWriter, r *http.Request, value config) {
	if r.Method != http.MethodPost {
		http.Error(w, "method", http.StatusMethodNotAllowed)
		return
	}
	if !authorized(r, value.GatewayToken) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	name := r.Header.Get("Phone-Station-Name")
	body, err := readBody(r.Body, 128<<10)
	if err != nil {
		http.Error(w, "share", http.StatusRequestEntityTooLarge)
		return
	}
	put := g.sharePut
	if put == nil {
		put = func(name string, body []byte) error {
			return g.putLinkFile(r.Context(), name, body)
		}
	}
	if err := put(name, body); err != nil {
		http.Error(w, "share", http.StatusBadGateway)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (g *gateway) putLinkFile(ctx context.Context, name string, body []byte) error {
	g.linkMu.Lock()
	defer g.linkMu.Unlock()
	session, err := g.ensureLinkLocked()
	if err != nil {
		return err
	}
	if err := session.PutFile(ctx, name, body); err != nil {
		_ = session.Close()
		g.linkSession = nil
		g.eventsReady = false
		return err
	}
	return nil
}

func (g *gateway) callLink(ctx context.Context, op, method string, params []byte) (string, []byte, error) {
	if method == "event.wait" {
		return g.waitLinkEvent(ctx, params)
	}
	if method == "event.states" {
		return g.stateSnapshot()
	}
	if g.linkCall != nil {
		return g.linkCall(ctx, op, method, params)
	}
	g.linkMu.Lock()
	defer g.linkMu.Unlock()
	session, err := g.ensureLinkLocked()
	if err != nil {
		return "", nil, err
	}
	state, body, err := session.Call(ctx, op, method, params)
	if err != nil {
		_ = session.Close()
		g.linkSession = nil
		g.eventsReady = false
	}
	return state, body, err
}

func (g *gateway) relayPayload(ctx context.Context, body []byte) (relayResponse, error) {
	trimmed := bytes.TrimSpace(body)
	if len(trimmed) > 0 && trimmed[0] == '[' {
		reply, status := g.relayBatch(ctx, body)
		if status == http.StatusBadGateway {
			return relayResponse{}, errors.New("link")
		}
		return relayResponse{Status: status, Body: reply}, nil
	}
	reply, status := g.oneCaller(ctx, body, g.callRelay)
	if status == http.StatusBadGateway {
		return relayResponse{}, errors.New("link")
	}
	return relayResponse{Status: status, Body: reply}, nil
}

func (g *gateway) relayBatch(ctx context.Context, body []byte) ([]byte, int) {
	var items []json.RawMessage
	if json.Unmarshal(body, &items) != nil || len(items) == 0 || len(items) > 64 {
		return rpcErrorBody(nil, -32600, "批量请求需要 1 到 64 项"), http.StatusOK
	}
	replies := make([]json.RawMessage, 0, len(items))
	for _, item := range items {
		reply, status := g.oneCaller(ctx, item, g.callRelay)
		if status == http.StatusBadGateway {
			return nil, status
		}
		if len(bytes.TrimSpace(reply)) > 0 {
			replies = append(replies, reply)
		}
	}
	if len(replies) == 0 {
		return nil, http.StatusAccepted
	}
	encoded, err := json.Marshal(replies)
	if err != nil {
		return nil, http.StatusBadGateway
	}
	return encoded, http.StatusOK
}

func (g *gateway) callRelay(ctx context.Context, op, method string, params []byte) (string, []byte, error) {
	if g.relayCall != nil {
		return g.relayCall(ctx, op, method, params)
	}
	value, err := g.config()
	if err != nil {
		return "", nil, err
	}
	g.relayMu.Lock()
	defer g.relayMu.Unlock()
	session, err := g.ensureRelayLocked(value)
	if err != nil {
		return "", nil, err
	}
	state, body, err := session.Call(ctx, op, method, params)
	if err != nil {
		_ = session.Close()
		g.relaySession = nil
	}
	return state, body, err
}

func invitePhone() error {
	value, err := loadConfig()
	if err != nil {
		return err
	}
	addr, err := relayDialAddr(value.RemoteURL)
	if err != nil {
		return errors.New("host")
	}
	pin, err := hex.DecodeString(strings.TrimSpace(value.RemotePin))
	if err != nil || len(pin) != 32 {
		return errors.New("host")
	}
	identity, err := macDeviceIdentity()
	if err != nil {
		return err
	}
	peer, err := loadPhonePeer()
	if err != nil {
		return err
	}
	opened, err := link.DialRelayPinned(addr, pin, identity, "main", "phone-station")
	if err != nil {
		return err
	}
	defer opened.Close()
	local, err := link.DialLocal(linkForward, identity, peer)
	if err != nil {
		return err
	}
	defer local.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 25*time.Second)
	defer cancel()
	return opened.OfferPhone(ctx, identity, peer, strings.TrimSpace(value.RemoteURL), hex.EncodeToString(pin), local.SendRelayInvite)
}

func (g *gateway) ensureRelayLocked(value config) (*link.LocalSession, error) {
	key := value.RemoteURL + "\n" + value.RemotePin
	if g.relaySession != nil && !g.relaySession.Ended() && g.relayKey == key {
		armShare(g.relaySession)
		return g.relaySession, nil
	}
	if g.relaySession != nil {
		_ = g.relaySession.Close()
		g.relaySession = nil
	}
	addr, err := relayDialAddr(value.RemoteURL)
	if err != nil {
		return nil, err
	}
	pin, err := hex.DecodeString(strings.TrimSpace(value.RemotePin))
	if err != nil || len(pin) != 32 {
		return nil, errors.New("host")
	}
	identity, err := macDeviceIdentity()
	if err != nil {
		return nil, err
	}
	peer, err := loadPhonePeer()
	if err != nil {
		return nil, err
	}
	opened, err := link.DialRelayPinned(addr, pin, identity, "main", "phone-station")
	if err != nil {
		return nil, err
	}
	session := opened.Bind(peer.ID, true)
	if session == nil {
		_ = opened.Close()
		return nil, errors.New("host")
	}
	if peer.Seal {
		if err := session.RequireSeal(identity, peer.SPKI); err != nil {
			_ = session.Close()
			return nil, err
		}
	}
	armShare(session)
	g.relaySession = session
	g.relayKey = key
	return session, nil
}

func relayDialAddr(endpoint string) (string, error) {
	parsed, err := url.Parse(strings.TrimSpace(endpoint))
	if err != nil || parsed.Hostname() == "" {
		return "", errors.New("host")
	}
	port := parsed.Port()
	if port == "" {
		port = "443"
	}
	addr := net.JoinHostPort(parsed.Hostname(), port)
	if _, err := link.RelayAddress(addr); err != nil {
		return "", err
	}
	return addr, nil
}

func (g *gateway) ensureLinkLocked() (*link.LocalSession, error) {
	if g.linkSession != nil && !g.linkSession.Ended() {
		armShare(g.linkSession)
		return g.linkSession, nil
	}
	identity, err := macDeviceIdentity()
	if err != nil {
		return nil, err
	}
	peer, err := loadPhonePeer()
	if err != nil {
		return nil, err
	}
	session, err := link.DialLocal(linkForward, identity, peer)
	if err != nil {
		return nil, err
	}
	g.linkSession = session
	g.eventsReady = false
	if err := g.subscribeLocked(session); err != nil {
		_ = session.Close()
		g.linkSession = nil
		g.eventsReady = false
		return nil, err
	}
	armShare(session)
	return session, nil
}

func armShare(session *link.LocalSession) {
	if session == nil {
		return
	}
	if dir := macShareDir(); dir != "" {
		session.SetShareDir(dir)
	}
}

func (g *gateway) keepShareSession() {
	g.armLocalShare()
	ticker := time.NewTicker(5 * time.Second)
	defer ticker.Stop()
	for range ticker.C {
		g.armLocalShare()
	}
}

func (g *gateway) armLocalShare() {
	g.linkMu.Lock()
	defer g.linkMu.Unlock()
	_, _ = g.ensureLinkLocked()
}

func macShareDir() string {
	home, err := os.UserHomeDir()
	if err != nil || home == "" {
		return ""
	}
	dir := filepath.Join(home, "Downloads", "手机工位")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return ""
	}
	return dir
}

var linkTopics = []string{"device", "grants", "capabilities", "ops", "clipboard", "notifications"}

func (g *gateway) subscribeLocked(session *link.LocalSession) error {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if _, err := session.Subscribe(ctx, linkTopics, nil); err != nil {
		return err
	}
	if g.live == nil {
		g.live = newLiveEvents()
	}
	g.eventsReady = true
	go g.readLinkEvents(session)
	return nil
}

func (g *gateway) readLinkEvents(session *link.LocalSession) {
	for {
		ev, err := session.NextEvent(context.Background())
		if err != nil {
			g.linkMu.Lock()
			if g.linkSession == session {
				g.eventsReady = false
			}
			g.linkMu.Unlock()
			return
		}
		g.acceptLinkEvent(ev)
	}
}

// acceptLinkEvent keeps device, grants, and capabilities bodies for the Mac
// waiter. Clipboard and notification counts stay on the existing event.wait path.
func (g *gateway) acceptLinkEvent(ev link.LinkEvent) bool {
	if g.live == nil {
		return false
	}
	switch ev.Topic {
	case "clipboard", "notifications":
		g.live.note(ev.Topic)
		return true
	case "device", "grants", "capabilities":
		return g.live.keep(ev.Topic, ev.Body)
	default:
		return false
	}
}

func (g *gateway) waitLinkEvent(ctx context.Context, params []byte) (string, []byte, error) {
	if !(g.eventsReady && g.live != nil) {
		if g.linkCall != nil {
			return "", nil, errors.New("events")
		}
		g.linkMu.Lock()
		_, err := g.ensureLinkLocked()
		g.linkMu.Unlock()
		if err != nil {
			return "", nil, err
		}
	}
	var args struct {
		Clipboard     int64 `json:"clipboard"`
		Notifications int64 `json:"notifications"`
	}
	if len(bytes.TrimSpace(params)) > 0 && json.Unmarshal(params, &args) != nil {
		return "", nil, errors.New("events")
	}
	clip, notes, err := g.live.await(ctx, args.Clipboard, args.Notifications)
	if err != nil {
		return "", nil, err
	}
	body, err := json.Marshal(struct {
		Clipboard     int64 `json:"clipboard"`
		Notifications int64 `json:"notifications"`
	}{clip, notes})
	if err != nil {
		return "", nil, err
	}
	return "complete", body, nil
}

func (g *gateway) stateSnapshot() (string, []byte, error) {
	if !(g.eventsReady && g.live != nil) {
		return "", nil, errors.New("events")
	}
	generation, device, grants, capabilities := g.live.snapshot()
	payload := map[string]any{"generation": generation}
	if object := jsonObject(device); object != nil {
		payload["device"] = object
	}
	if object := jsonObject(grants); object != nil {
		payload["grants"] = object
	}
	if object := jsonObject(capabilities); object != nil {
		payload["capabilities"] = object
	}
	body, err := json.Marshal(payload)
	if err != nil {
		return "", nil, err
	}
	return "complete", body, nil
}

func jsonObject(raw []byte) map[string]any {
	if len(bytes.TrimSpace(raw)) == 0 || !json.Valid(raw) {
		return nil
	}
	var object map[string]any
	if json.Unmarshal(raw, &object) != nil || object == nil {
		return nil
	}
	return object
}

func linkCallFor(method string, params json.RawMessage) (string, []byte, error) {
	switch method {
	case "ping":
		return "meta.ping", []byte("{}"), nil
	case "initialize":
		return "meta.initialize", []byte("{}"), nil
	case "tools/list":
		return "meta.tools", []byte("{}"), nil
	case "tools/call":
		var call struct {
			Name      string          `json:"name"`
			Arguments json.RawMessage `json:"arguments"`
		}
		if json.Unmarshal(params, &call) != nil || call.Name == "" {
			return "", nil, errors.New("需要工具名")
		}
		name, ok := linkMethodName(call.Name)
		if !ok {
			return "", nil, errors.New("没有这个方法")
		}
		args := bytes.TrimSpace(call.Arguments)
		if len(args) == 0 || bytes.Equal(args, []byte("null")) {
			args = []byte("{}")
		}
		return name, args, nil
	default:
		return "", nil, errors.New("没有这个方法")
	}
}

func linkMethodName(tool string) (string, bool) {
	rest, ok := strings.CutPrefix(tool, "station_")
	if !ok || rest == "" {
		return "", false
	}
	group, action, ok := strings.Cut(rest, "_")
	if !ok {
		if !linkName.MatchString(rest) {
			return "", false
		}
		return rest + ".call", true
	}
	if group == "" || action == "" {
		return "", false
	}
	action = strings.ReplaceAll(action, "_", "")
	if !linkName.MatchString(group) || !linkName.MatchString(action) {
		return "", false
	}
	return group + "." + action, true
}

var linkName = regexp.MustCompile(`^[a-z]+$`)

func linkOperation(method string, params json.RawMessage) string {
	if method == "tools/call" {
		var call struct {
			Arguments json.RawMessage `json:"arguments"`
		}
		if json.Unmarshal(params, &call) == nil {
			if op := operationField(call.Arguments); op != "" {
				return op
			}
		}
	}
	return newLinkOp()
}

func operationField(raw json.RawMessage) string {
	var fields struct {
		JobID     string `json:"jobId"`
		SessionID string `json:"sessionId"`
		ID        string `json:"id"`
	}
	if len(raw) == 0 || json.Unmarshal(raw, &fields) != nil {
		return ""
	}
	if linkOpPattern.MatchString(fields.JobID) {
		return fields.JobID
	}
	if linkOpPattern.MatchString(fields.SessionID) {
		return fields.SessionID
	}
	if linkOpPattern.MatchString(fields.ID) {
		return fields.ID
	}
	return ""
}

func newLinkOp() string {
	var raw [16]byte
	_, _ = rand.Read(raw[:])
	return hex.EncodeToString(raw[:])
}

func rpcResult(id, result json.RawMessage) []byte {
	if len(result) == 0 || !json.Valid(result) {
		result = []byte("{}")
	}
	encoded, err := json.Marshal(struct {
		JSONRPC string          `json:"jsonrpc"`
		ID      json.RawMessage `json:"id"`
		Result  json.RawMessage `json:"result"`
	}{JSONRPC: "2.0", ID: id, Result: result})
	if err != nil {
		return rpcErrorBody(id, -32603, "内部错误")
	}
	return encoded
}

func rpcErrorBody(id json.RawMessage, code int, message string) []byte {
	if len(id) == 0 {
		id = json.RawMessage("null")
	}
	body, _ := json.Marshal(map[string]any{
		"jsonrpc": "2.0", "id": id,
		"error": map[string]any{"code": code, "message": message},
	})
	return body
}

func writeRaw(w http.ResponseWriter, status int, body []byte) {
	if status == 0 {
		status = http.StatusOK
	}
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	if len(body) > 0 {
		_, _ = w.Write(body)
	}
}

func macDeviceIdentity() (link.Identity, error) {
	output, err := runDeviceKey("public", nil)
	if err != nil {
		return link.Identity{}, err
	}
	lines := bytes.Split(bytes.TrimSpace(output), []byte{'\n'})
	if len(lines) != 2 {
		return link.Identity{}, errors.New("device key unavailable")
	}
	id, err := hex.DecodeString(string(bytes.TrimSpace(lines[0])))
	spki, errSPKI := base64.StdEncoding.DecodeString(string(bytes.TrimSpace(lines[1])))
	if err != nil || errSPKI != nil || len(id) != 16 || len(spki) == 0 {
		return link.Identity{}, errors.New("device key unavailable")
	}
	identity := link.Identity{SPKI: spki, Sign: func(message []byte) ([]byte, error) {
		return runDeviceKey("sign", message)
	}}
	copy(identity.ID[:], id)
	return identity, nil
}

func loadPhonePeer() (link.PeerKey, error) {
	path, err := phonePeerPath()
	if err != nil {
		return link.PeerKey{}, err
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return link.PeerKey{}, errors.New("phone link is not paired")
	}
	return phonePeerFrom(data)
}

func phonePeerFrom(data []byte) (link.PeerKey, error) {
	var stored struct {
		DeviceID string `json:"deviceId"`
		SPKI     string `json:"spki"`
		Seal     string `json:"seal"`
	}
	if json.Unmarshal(data, &stored) != nil {
		return link.PeerKey{}, errors.New("phone link is not paired")
	}
	id, err := hex.DecodeString(stored.DeviceID)
	spki, errSPKI := base64.StdEncoding.DecodeString(stored.SPKI)
	if err != nil || errSPKI != nil || len(id) != 16 || len(spki) == 0 {
		return link.PeerKey{}, errors.New("phone link is not paired")
	}
	peer := link.PeerKey{SPKI: spki, Seal: stored.Seal == "required"}
	copy(peer.ID[:], id)
	return peer, nil
}

func phonePeerPath() (string, error) {
	config, err := configPath()
	if err != nil {
		return "", err
	}
	return filepath.Join(filepath.Dir(config), "link-phone.json"), nil
}

func runDeviceKey(action string, input []byte) ([]byte, error) {
	executable, err := os.Executable()
	if err != nil {
		return nil, err
	}
	helper := filepath.Join(filepath.Dir(executable), "phone-device-key")
	cmd := exec.Command(helper, action)
	cmd.Stdin = bytes.NewReader(input)
	var stderr bytes.Buffer
	cmd.Stderr = &stderr
	output, err := cmd.Output()
	if err != nil {
		if stderr.Len() > 0 {
			return nil, errors.New(strings.TrimSpace(stderr.String()))
		}
		return nil, fmt.Errorf("device key helper failed: %w", err)
	}
	return output, nil
}

func linkProbeCall() error {
	method := "probe.mark"
	params := []byte("{}")
	if len(os.Args) == 4 {
		method = os.Args[2]
		if !regexp.MustCompile(`^[a-z]+\.[a-z]+$`).MatchString(method) || len(os.Args[3]) > 4096 || !json.Valid([]byte(os.Args[3])) {
			return errors.New("method")
		}
		params = []byte(os.Args[3])
	} else if len(os.Args) != 2 {
		return errors.New("method")
	}
	session, err := dialLocalProbe()
	if err != nil {
		return clipLink(err)
	}
	defer session.Close()
	var raw [16]byte
	if _, err := rand.Read(raw[:]); err != nil {
		return errors.New("link")
	}
	op := hex.EncodeToString(raw[:])
	fmt.Printf("op %s\n", op)
	os.Stdout.Sync()
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	state, _, err := session.Call(ctx, op, method, params)
	if err != nil {
		return clipLink(err)
	}
	if state == "" || strings.ContainsAny(state, " \n\r") || len(state) > 32 {
		return errors.New("link")
	}
	fmt.Printf("op %s\nstate %s\n", op, state)
	return nil
}

func linkProbeQuery(op string) error {
	if !linkOpPattern.MatchString(op) {
		return errors.New("query")
	}
	session, err := dialRemoteProbe()
	if err != nil {
		return stageLink("dial", err)
	}
	defer session.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	subscribed, err := session.Subscribe(ctx, []string{"device", "capabilities", "grants"}, nil)
	if err != nil {
		return stageLink("subscribe", err)
	}
	event := "none"
	evCtx, evCancel := context.WithTimeout(context.Background(), 3*time.Second)
	ev, evErr := session.NextEvent(evCtx)
	evCancel()
	if evErr == nil && ev.Topic != "" && !strings.ContainsAny(ev.Topic, " \n\r") && len(ev.Topic) <= 32 {
		event = ev.Topic
		if ev.State != "" && !strings.ContainsAny(ev.State, " \n\r") && len(ev.State) <= 32 {
			event = ev.Topic + " " + ev.State
		}
	}
	replies, err := session.Query(ctx, []string{op})
	if err != nil {
		return stageLink("query", err)
	}
	if len(replies) != 1 || replies[0].State == "" || strings.ContainsAny(replies[0].State, " \n\r") || len(replies[0].State) > 32 {
		return errors.New("query")
	}
	fmt.Printf("subscribed %d\nevent %s\nstate %s\n", len(subscribed.Topics), event, replies[0].State)
	return nil
}

func linkExchange() error {
	session, err := dialLocalProbe()
	if err != nil {
		return clipLink(err)
	}
	defer session.Close()
	dir := macShareDir()
	if dir == "" {
		return errors.New("share")
	}
	session.SetShareDir(dir)
	drop := bytes.Repeat([]byte{0xA5}, 4096)
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	if err := session.PutFile(ctx, "ps-drop.bin", drop); err != nil {
		return clipLink(err)
	}
	sum := sha256.Sum256(drop)
	fmt.Printf("put %d %x\n", len(drop), sum)
	os.Stdout.Sync()
	started := time.Now()
	deadline := started.Add(25 * time.Second)
	for time.Now().Before(deadline) {
		entries, err := os.ReadDir(dir)
		if err == nil {
			for _, entry := range entries {
				name := entry.Name()
				if entry.IsDir() || name == "ps-drop.bin" || !shareResultName(name) {
					continue
				}
				info, err := entry.Info()
				if err != nil || info.Size() <= 0 || info.Size() > 128*1024 || info.ModTime().Before(started) {
					continue
				}
				body, err := os.ReadFile(filepath.Join(dir, name))
				if err != nil {
					continue
				}
				got := sha256.Sum256(body)
				fmt.Printf("got %d %x\n", len(body), got)
				os.Stdout.Sync()
				return nil
			}
		}
		time.Sleep(200 * time.Millisecond)
	}
	fmt.Println("got none")
	os.Stdout.Sync()
	return nil
}

func shareResultName(name string) bool {
	if name == "ps-to-mac.bin" {
		return true
	}
	if name == "" {
		return false
	}
	for _, c := range name {
		if c < '0' || c > '9' {
			return false
		}
	}
	return true
}

func dialLocalProbe() (*link.LocalSession, error) {
	_, identity, peer, err := probeMaterial()
	if err != nil {
		return nil, err
	}
	return link.DialLocal(linkForward, identity, peer)
}

func dialRemoteProbe() (*link.LocalSession, error) {
	value, identity, peer, err := probeMaterial()
	if err != nil {
		return nil, err
	}
	addr, err := relayDialAddr(value.RemoteURL)
	if err != nil {
		return nil, err
	}
	pin, err := hex.DecodeString(strings.TrimSpace(value.RemotePin))
	if err != nil || len(pin) != 32 {
		return nil, errors.New("host")
	}
	opened, err := link.DialRelayPinned(addr, pin, identity, "main", "phone-station")
	if err != nil {
		return nil, err
	}
	session := opened.Bind(peer.ID, true)
	if session == nil {
		_ = opened.Close()
		return nil, errors.New("link")
	}
	return session, nil
}

func probeMaterial() (config, link.Identity, link.PeerKey, error) {
	value, err := loadConfig()
	if err != nil {
		return config{}, link.Identity{}, link.PeerKey{}, err
	}
	identity, err := macDeviceIdentity()
	if err != nil {
		return config{}, link.Identity{}, link.PeerKey{}, err
	}
	peer, err := loadPhonePeer()
	if err != nil {
		return config{}, link.Identity{}, link.PeerKey{}, err
	}
	return value, identity, peer, nil
}

func clipLink(err error) error {
	return stageLink("link", err)
}

func stageLink(stage string, err error) error {
	if err == nil {
		return nil
	}
	msg := err.Error()
	if msg == "EOF" || msg == "timeout" || msg == "ended" {
		return errors.New(stage + "-" + msg)
	}
	if msg == "" || len(msg) > 32 || strings.ContainsAny(msg, " \n\r") {
		return errors.New(stage)
	}
	return errors.New(stage + "-" + msg)
}
