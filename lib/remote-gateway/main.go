package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"crypto/tls"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
	"time"
)

const (
	listenAddress = "127.0.0.1:18765"
	localAddress  = "http://127.0.0.1:18766/mcp"
	publicAddress = "http://127.0.0.1:18765/mcp"
	maxBody       = 18 << 20
	probeEvery    = 5 * time.Second
)

type config struct {
	GatewayToken string `json:"gatewayToken"`
	LocalToken   string `json:"localToken"`
	RemoteURL    string `json:"remoteUrl"`
	RemotePin    string `json:"remotePin"`
}

type health struct {
	mu               sync.RWMutex
	mode             string
	localOnline      bool
	remoteOnline     bool
	localRTT         time.Duration
	remoteRTT        time.Duration
	localSlowSamples int
	localGoodSamples int
	lastModeChange   time.Time
}

type gateway struct {
	state    health
	remoteMu sync.Mutex
}

func main() {
	if len(os.Args) < 2 {
		usage()
		os.Exit(2)
	}
	var err error
	switch os.Args[1] {
	case "configure":
		err = configureRemote()
	case "local-token":
		err = updateLocalToken()
	case "clear-local":
		err = updateLocalTokenValue("")
	case "unpair":
		err = clearRemote()
	case "start":
		err = startDaemon()
	case "serve":
		err = serve()
	case "status":
		err = printStatus()
	case "credentials":
		err = printCredentials()
	default:
		usage()
		os.Exit(2)
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func usage() {
	fmt.Fprintln(os.Stderr, "phone-relay-gateway configure|local-token|clear-local|unpair|start|serve|status|credentials")
}

func configPath() (string, error) {
	root, err := os.UserConfigDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(root, "Phone Station", "remote.json"), nil
}

func loadConfig() (config, error) {
	path, err := configPath()
	if err != nil {
		return config{}, err
	}
	body, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return config{}, nil
	}
	if err != nil {
		return config{}, err
	}
	var value config
	if err := json.Unmarshal(body, &value); err != nil {
		return config{}, fmt.Errorf("read remote config: %w", err)
	}
	return value, nil
}

func saveConfig(value config) error {
	path, err := configPath()
	if err != nil {
		return err
	}
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0700); err != nil {
		return err
	}
	if err := os.Chmod(dir, 0700); err != nil {
		return err
	}
	body, err := json.Marshal(value)
	if err != nil {
		return err
	}
	temp, err := os.CreateTemp(dir, ".remote-*.tmp")
	if err != nil {
		return err
	}
	tempName := temp.Name()
	defer os.Remove(tempName)
	if err := temp.Chmod(0600); err != nil {
		_ = temp.Close()
		return err
	}
	if _, err := temp.Write(body); err != nil {
		_ = temp.Close()
		return err
	}
	if err := temp.Sync(); err != nil {
		_ = temp.Close()
		return err
	}
	if err := temp.Close(); err != nil {
		return err
	}
	return os.Rename(tempName, path)
}

func configureRemote() error {
	var input struct {
		Endpoint string `json:"endpoint"`
		Pin      string `json:"pin"`
		Token    string `json:"token"`
	}
	if err := json.NewDecoder(io.LimitReader(os.Stdin, 4096)).Decode(&input); err != nil {
		return errors.New("invalid remote profile")
	}
	if !strings.HasPrefix(input.Endpoint, "https://") || len(input.Pin) != 64 || !isHex(input.Pin) ||
		len(input.Token) != 64 || !isHex(input.Token) {
		return errors.New("remote profile values are invalid")
	}
	value, err := loadConfig()
	if err != nil {
		return err
	}
	if value.GatewayToken == "" {
		value.GatewayToken = randomHex(32)
	}
	if _, err := runKeychain("set", []byte(strings.ToLower(input.Token))); err != nil {
		return err
	}
	value.RemoteURL = strings.TrimRight(input.Endpoint, "/")
	value.RemotePin = strings.ToLower(input.Pin)
	return saveConfig(value)
}

func updateLocalToken() error {
	token, err := io.ReadAll(io.LimitReader(os.Stdin, 256))
	if err != nil {
		return err
	}
	return updateLocalTokenValue(strings.TrimSpace(string(token)))
}

func updateLocalTokenValue(token string) error {
	if token != "" && (len(token) != 32 || !isHex(token)) {
		return errors.New("local MCP token must be 32 hexadecimal characters")
	}
	value, err := loadConfig()
	if err != nil {
		return err
	}
	if value.GatewayToken != "" && value.LocalToken == token {
		return nil
	}
	if value.GatewayToken == "" {
		value.GatewayToken = randomHex(32)
	}
	value.LocalToken = token
	return saveConfig(value)
}

func clearRemote() error {
	value, err := loadConfig()
	if err != nil {
		return err
	}
	value.RemoteURL = ""
	value.RemotePin = ""
	if _, err := runKeychain("delete", nil); err != nil {
		return err
	}
	return saveConfig(value)
}

func startDaemon() error {
	if _, err := ensureConfig(); err != nil {
		return err
	}
	if status, err := getStatus(); err == nil && status.Ready {
		return nil
	}
	executable, err := os.Executable()
	if err != nil {
		return err
	}
	cache, err := os.UserCacheDir()
	if err != nil {
		return err
	}
	logDir := filepath.Join(cache, "Phone Station")
	if err := os.MkdirAll(logDir, 0700); err != nil {
		return err
	}
	logFile, err := os.OpenFile(filepath.Join(logDir, "remote-gateway.log"), os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0600)
	if err != nil {
		return err
	}
	cmd := exec.Command(executable, "serve")
	cmd.Stdin = nil
	cmd.Stdout = logFile
	cmd.Stderr = logFile
	cmd.SysProcAttr = &syscall.SysProcAttr{Setsid: true}
	if err := cmd.Start(); err != nil {
		_ = logFile.Close()
		return err
	}
	_ = logFile.Close()
	cmd.Process.Release()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		if status, err := getStatus(); err == nil && status.Ready {
			return nil
		}
		time.Sleep(100 * time.Millisecond)
	}
	return errors.New("本机 MCP 网关没有启动")
}

func serve() error {
	if _, err := ensureConfig(); err != nil {
		return err
	}
	g := &gateway{}
	server := &http.Server{
		Addr:              listenAddress,
		Handler:           g,
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       2 * time.Minute,
		WriteTimeout:      4 * time.Minute,
		IdleTimeout:       45 * time.Second,
		MaxHeaderBytes:    16 << 10,
	}
	go g.monitor()
	listener, err := net.Listen("tcp4", listenAddress)
	if err != nil {
		return err
	}
	stopping := make(chan os.Signal, 1)
	signal.Notify(stopping, syscall.SIGINT, syscall.SIGTERM)
	go func() {
		<-stopping
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		_ = server.Shutdown(ctx)
	}()
	if err := server.Serve(listener); err != nil && !errors.Is(err, http.ErrServerClosed) {
		return err
	}
	return nil
}

func ensureConfig() (config, error) {
	value, err := loadConfig()
	if err != nil {
		return config{}, err
	}
	if value.GatewayToken == "" {
		value.GatewayToken = randomHex(32)
		if err := saveConfig(value); err != nil {
			return config{}, err
		}
	}
	return value, nil
}

func (g *gateway) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	value, err := loadConfig()
	if err != nil {
		writeMCPError(w, nil, http.StatusInternalServerError, "网关配置不可用")
		return
	}
	if r.URL.Path == "/__status" && r.Method == http.MethodGet {
		if !authorized(r, value.GatewayToken) {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
		g.state.mu.RLock()
		out := statusReply{
			Ready:        value.GatewayToken != "",
			Mode:         g.state.mode,
			LocalOnline:  g.state.localOnline,
			RemoteOnline: g.state.remoteOnline,
			LocalRTTMs:   durationMs(g.state.localRTT),
			RemoteRTTMs:  durationMs(g.state.remoteRTT),
		}
		g.state.mu.RUnlock()
		writeJSON(w, http.StatusOK, out)
		return
	}
	if r.Method != http.MethodPost || r.URL.Path != "/mcp" {
		writeMCPError(w, nil, http.StatusNotFound, "unknown endpoint")
		return
	}
	if !authorized(r, value.GatewayToken) {
		writeMCPError(w, nil, http.StatusUnauthorized, "需要本机网关令牌")
		return
	}
	body, err := readBody(r.Body, maxBody)
	if err != nil {
		writeMCPError(w, nil, http.StatusRequestEntityTooLarge, "请求过大或无法读取")
		return
	}
	g.state.mu.RLock()
	mode := g.state.mode
	g.state.mu.RUnlock()
	switch mode {
	case "local":
		g.proxyLocal(w, body, value.LocalToken)
	case "remote":
		g.proxyRemote(w, body, value)
	default:
		writeMCPError(w, requestID(body), http.StatusServiceUnavailable, "手机暂时没有可用通道")
	}
}

func (g *gateway) monitor() {
	g.probe()
	ticker := time.NewTicker(probeEvery)
	defer ticker.Stop()
	for range ticker.C {
		g.probe()
	}
}

func (g *gateway) probe() {
	value, err := loadConfig()
	if err != nil {
		return
	}
	localDone := make(chan probeResult, 1)
	remoteDone := make(chan probeResult, 1)
	go func() { localDone <- g.probeLocal(value.LocalToken) }()
	go func() { remoteDone <- g.probeRemote(value) }()
	local := <-localDone
	remote := <-remoteDone
	state := &g.state
	state.mu.Lock()
	state.localOnline, state.remoteOnline = local.ok, remote.ok
	state.localRTT, state.remoteRTT = local.rtt, remote.rtt
	previous := state.mode
	if local.ok && !remote.ok {
		state.mode = "local"
	} else if remote.ok && !local.ok {
		state.mode = "remote"
	} else if local.ok && remote.ok {
		if state.mode == "" || state.mode == "offline" {
			state.mode = "local"
		}
		if state.mode == "local" {
			if local.rtt > 500*time.Millisecond && remote.rtt*10 < local.rtt*6 {
				state.localSlowSamples++
			} else {
				state.localSlowSamples = 0
			}
			if state.localSlowSamples >= 3 {
				state.mode = "remote"
				state.localGoodSamples = 0
			}
		} else {
			if local.rtt < 150*time.Millisecond {
				state.localGoodSamples++
			} else {
				state.localGoodSamples = 0
			}
			if state.localGoodSamples >= 6 && time.Since(state.lastModeChange) >= time.Minute {
				state.mode = "local"
				state.localSlowSamples = 0
			}
		}
	} else {
		state.mode = "offline"
	}
	if state.mode != previous {
		state.lastModeChange = time.Now()
	}
	state.mu.Unlock()
}

type probeResult struct {
	ok  bool
	rtt time.Duration
}

func (g *gateway) probeLocal(token string) probeResult {
	if token == "" {
		return probeResult{}
	}
	start := time.Now()
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	request, _ := http.NewRequestWithContext(ctx, http.MethodPost, localAddress, bytes.NewBufferString(`{"jsonrpc":"2.0","id":"local-probe","method":"ping"}`))
	request.Header.Set("Authorization", "Bearer "+token)
	request.Header.Set("Content-Type", "application/json")
	response, err := (&http.Client{Timeout: time.Second}).Do(request)
	if err != nil {
		return probeResult{}
	}
	defer response.Body.Close()
	body, err := readBody(response.Body, 1<<20)
	if err != nil || response.StatusCode != http.StatusOK || !validMCPReply(body) {
		return probeResult{}
	}
	return probeResult{ok: true, rtt: time.Since(start)}
}

func (g *gateway) probeRemote(value config) probeResult {
	// The relay dispatches one operation at a time. A health probe must not
	// compete with an MCP call or mark a busy, working channel as offline.
	if !g.remoteMu.TryLock() {
		g.state.mu.RLock()
		defer g.state.mu.RUnlock()
		return probeResult{ok: g.state.remoteOnline, rtt: g.state.remoteRTT}
	}
	defer g.remoteMu.Unlock()
	if value.RemoteURL == "" || value.RemotePin == "" {
		return probeResult{}
	}
	start := time.Now()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	response, err := remoteCall(ctx, value, []byte(`{"jsonrpc":"2.0","id":"remote-probe","method":"ping"}`))
	if err != nil || response.Status != http.StatusOK || !validMCPReply(response.Body) {
		return probeResult{}
	}
	return probeResult{ok: true, rtt: time.Since(start)}
}

func (g *gateway) proxyLocal(w http.ResponseWriter, body []byte, token string) {
	if token == "" {
		writeMCPError(w, requestID(body), http.StatusServiceUnavailable, "adb 通道还没就绪")
		return
	}
	request, err := http.NewRequest(http.MethodPost, localAddress, bytes.NewReader(body))
	if err != nil {
		writeMCPError(w, requestID(body), http.StatusBadGateway, "无法连接手机")
		return
	}
	request.Header.Set("Authorization", "Bearer "+token)
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("Accept", "application/json, text/event-stream")
	response, err := (&http.Client{Timeout: 4 * time.Minute}).Do(request)
	if err != nil {
		writeMCPError(w, requestID(body), http.StatusBadGateway, "本次请求中断；结果可能需要核实")
		return
	}
	defer response.Body.Close()
	result, err := readBody(response.Body, maxBody)
	if err != nil {
		writeMCPError(w, requestID(body), http.StatusBadGateway, "手机响应无法读取；结果可能需要核实")
		return
	}
	w.Header().Set("Content-Type", response.Header.Get("Content-Type"))
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(response.StatusCode)
	_, _ = w.Write(result)
}

func (g *gateway) proxyRemote(w http.ResponseWriter, body []byte, value config) {
	g.remoteMu.Lock()
	defer g.remoteMu.Unlock()
	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Minute)
	defer cancel()
	response, err := remoteCall(ctx, value, body)
	if err != nil {
		writeMCPError(w, requestID(body), http.StatusBadGateway, "远程请求中断；请用相同操作核实结果")
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(response.Status)
	_, _ = w.Write(response.Body)
}

func remoteCall(ctx context.Context, value config, payload []byte) (relayResponse, error) {
	operationID := randomHex(16)
	requestBody, err := json.Marshal(struct {
		OperationID string          `json:"operationId"`
		Payload     json.RawMessage `json:"payload"`
	}{OperationID: operationID, Payload: payload})
	if err != nil {
		return relayResponse{}, err
	}
	token, err := loadRemoteToken()
	if err != nil {
		return relayResponse{}, err
	}
	client := &http.Client{Timeout: 4 * time.Minute, Transport: pinnedTransport(value.RemotePin)}
	var response *http.Response
	for attempt := 0; attempt < 3; attempt++ {
		request, requestErr := http.NewRequestWithContext(ctx, http.MethodPost,
			strings.TrimRight(value.RemoteURL, "/")+"/v1/desktop/call", bytes.NewReader(requestBody))
		if requestErr != nil {
			return relayResponse{}, requestErr
		}
		request.Header.Set("Authorization", "Bearer "+token)
		request.Header.Set("Content-Type", "application/json")
		response, err = client.Do(request)
		if err == nil {
			break
		}
		if attempt == 2 || ctx.Err() != nil {
			return relayResponse{}, err
		}
		timer := time.NewTimer(time.Duration(attempt+1) * 250 * time.Millisecond)
		select {
		case <-ctx.Done():
			timer.Stop()
			return relayResponse{}, ctx.Err()
		case <-timer.C:
		}
	}
	defer response.Body.Close()
	result, err := readBody(response.Body, maxBody)
	if err != nil {
		return relayResponse{}, err
	}
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		return relayResponse{}, fmt.Errorf("relay returned status %d", response.StatusCode)
	}
	var wrapped relayResponse
	if err := json.Unmarshal(result, &wrapped); err != nil || wrapped.Status < 200 || wrapped.Status > 599 {
		return relayResponse{}, errors.New("invalid relay response")
	}
	if wrapped.Status != http.StatusAccepted && len(wrapped.Body) == 0 {
		return relayResponse{}, errors.New("relay response body is empty")
	}
	return wrapped, nil
}

func pinnedTransport(pin string) *http.Transport {
	expected, _ := hex.DecodeString(pin)
	return &http.Transport{TLSClientConfig: &tls.Config{
		MinVersion:         tls.VersionTLS12,
		InsecureSkipVerify: true,
		VerifyConnection: func(state tls.ConnectionState) error {
			if len(state.PeerCertificates) == 0 || len(expected) != sha256.Size {
				return errors.New("relay TLS certificate is missing")
			}
			actual := sha256.Sum256(state.PeerCertificates[0].RawSubjectPublicKeyInfo)
			if subtle.ConstantTimeCompare(actual[:], expected) != 1 {
				return errors.New("relay certificate pin mismatch")
			}
			return nil
		},
	}}
}

type statusReply struct {
	Ready        bool   `json:"ready"`
	Mode         string `json:"mode"`
	LocalOnline  bool   `json:"localOnline"`
	RemoteOnline bool   `json:"remoteOnline"`
	LocalRTTMs   int64  `json:"localRttMs"`
	RemoteRTTMs  int64  `json:"remoteRttMs"`
}

type relayResponse struct {
	Status int             `json:"status"`
	Body   json.RawMessage `json:"body"`
}

func getStatus() (statusReply, error) {
	value, err := loadConfig()
	if err != nil {
		return statusReply{}, err
	}
	request, _ := http.NewRequest(http.MethodGet, "http://"+listenAddress+"/__status", nil)
	request.Header.Set("Authorization", "Bearer "+value.GatewayToken)
	response, err := (&http.Client{Timeout: time.Second}).Do(request)
	if err != nil {
		return statusReply{}, err
	}
	defer response.Body.Close()
	var status statusReply
	if err := json.NewDecoder(io.LimitReader(response.Body, 4096)).Decode(&status); err != nil {
		return statusReply{}, err
	}
	if response.StatusCode != http.StatusOK || !status.Ready {
		return statusReply{}, errors.New("gateway not ready")
	}
	return status, nil
}

func printStatus() error {
	status, err := getStatus()
	if err != nil {
		fmt.Println("off")
		return nil
	}
	if status.Mode == "" || status.Mode == "offline" {
		fmt.Println("off")
		return nil
	}
	relayState := "offline"
	if status.RemoteOnline {
		relayState = "online"
	}
	fmt.Printf("%s\nchannel=%s local=%dms remote=%dms relay=%s\n", publicAddress, status.Mode, status.LocalRTTMs, status.RemoteRTTMs, relayState)
	value, err := loadConfig()
	if err != nil {
		return err
	}
	fmt.Printf("Authorization: Bearer %s\n", value.GatewayToken)
	return nil
}

func printCredentials() error {
	value, err := ensureConfig()
	if err != nil {
		return err
	}
	fmt.Printf("%s\nAuthorization: Bearer %s\n", publicAddress, value.GatewayToken)
	return nil
}

func loadRemoteToken() (string, error) {
	output, err := runKeychain("get", nil)
	if err != nil {
		return "", err
	}
	token := strings.TrimSpace(string(output))
	if len(token) != 64 || !isHex(token) {
		return "", errors.New("remote credential is invalid")
	}
	return token, nil
}

func runKeychain(action string, input []byte) ([]byte, error) {
	executable, err := os.Executable()
	if err != nil {
		return nil, err
	}
	helper := filepath.Join(filepath.Dir(executable), "phone-relay-keychain")
	cmd := exec.Command(helper, action)
	cmd.Stdin = bytes.NewReader(input)
	var stderr bytes.Buffer
	cmd.Stderr = &stderr
	output, err := cmd.Output()
	if err != nil {
		if stderr.Len() > 0 {
			return nil, errors.New(strings.TrimSpace(stderr.String()))
		}
		return nil, fmt.Errorf("keychain helper failed: %w", err)
	}
	return output, nil
}

func durationMs(value time.Duration) int64 {
	if value <= 0 {
		return 0
	}
	return value.Milliseconds()
}

func authorized(r *http.Request, want string) bool {
	const prefix = "Bearer "
	got := r.Header.Get("Authorization")
	if !strings.HasPrefix(got, prefix) {
		return false
	}
	got = strings.TrimPrefix(got, prefix)
	if len(got) != len(want) {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(got), []byte(want)) == 1
}

func readBody(input io.Reader, limit int64) ([]byte, error) {
	body, err := io.ReadAll(io.LimitReader(input, limit+1))
	if err != nil {
		return nil, err
	}
	if int64(len(body)) > limit {
		return nil, errors.New("request body is too large")
	}
	return body, nil
}

func validMCPReply(body []byte) bool {
	var value struct {
		JSONRPC string          `json:"jsonrpc"`
		ID      json.RawMessage `json:"id"`
		Result  json.RawMessage `json:"result"`
		Error   json.RawMessage `json:"error"`
	}
	if json.Unmarshal(body, &value) != nil || value.JSONRPC != "2.0" || len(value.ID) == 0 {
		return false
	}
	return len(value.Result) > 0 || len(value.Error) > 0
}

func requestID(body []byte) json.RawMessage {
	var value struct {
		ID json.RawMessage `json:"id"`
	}
	if json.Unmarshal(body, &value) != nil || len(value.ID) == 0 {
		return json.RawMessage("null")
	}
	return value.ID
}

func writeMCPError(w http.ResponseWriter, id json.RawMessage, status int, message string) {
	if len(id) == 0 {
		id = json.RawMessage("null")
	}
	body, _ := json.Marshal(map[string]any{
		"jsonrpc": "2.0", "id": id,
		"error": map[string]any{"code": -32001, "message": message},
	})
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_, _ = w.Write(body)
}

func writeJSON(w http.ResponseWriter, status int, value any) {
	body, err := json.Marshal(value)
	if err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_, _ = w.Write(body)
}

func randomHex(length int) string {
	bytes := make([]byte, length)
	if _, err := rand.Read(bytes); err != nil {
		panic("crypto/rand unavailable")
	}
	return hex.EncodeToString(bytes)
}

func isHex(value string) bool {
	_, err := hex.DecodeString(value)
	return err == nil
}
