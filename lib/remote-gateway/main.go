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
	"net/url"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
	"unicode/utf8"
)

const (
	listenAddress = "127.0.0.1:18765"
	localAddress  = "http://127.0.0.1:18766/mcp"
	publicAddress = "http://127.0.0.1:18765/mcp"
	maxBody       = 18 << 20
	probeEvery    = time.Second
	remoteEvery   = 2 * time.Second
	localTimeout  = time.Second
	remoteFresh   = 6 * time.Second
)

var rpcIntegerID = regexp.MustCompile(`^-?(0|[1-9][0-9]*)$`)

type config struct {
	GatewayToken   string `json:"gatewayToken"`
	LocalToken     string `json:"localToken"`
	RemoteURL      string `json:"remoteUrl"`
	RemotePin      string `json:"remotePin"`
	RemoteRevision string `json:"remoteRevision,omitempty"`
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
	remoteForFailure bool
	remoteProfile    string
	remoteCheckedAt  time.Time
	remoteBusy       bool
	localRevision    uint64
	localRequests    map[uint64]context.CancelFunc
	nextRequest      uint64
}

type gateway struct {
	statusToken string
	state       health
	remoteMu    remoteGate
	// Overrides let transport tests use isolated servers without real credentials.
	localURL                   string
	readConfig                 func() (config, error)
	readClients                func() (clientRegistry, error)
	callRemote                 func(context.Context, config, []byte) (relayResponse, error)
	screenToken                func(config) (string, error)
	controlStatus              func(context.Context, config) (controlHealth, error)
	statusClient               *http.Client
	statusProfile              string
	probeProfile, probeSession string
	probeAt                    time.Time
}

// One bounded queue shared by App, CLI, and health probes. FIFO within each priority.
type remoteWaiter struct {
	ready    chan struct{}
	ctx      context.Context
	priority int
}
type remoteGate struct {
	mu             sync.Mutex
	busy           bool
	pending        []*remoteWaiter
	interactiveRun int
}

func (gate *remoteGate) acquire(ctx context.Context) error { return gate.acquirePriority(ctx, 1) }
func (gate *remoteGate) acquirePriority(ctx context.Context, priority int) error {
	gate.mu.Lock()
	if err := ctx.Err(); err != nil {
		gate.mu.Unlock()
		return err
	}
	if !gate.busy {
		gate.busy = true
		gate.mu.Unlock()
		return nil
	}
	if len(gate.pending) >= 64 {
		gate.mu.Unlock()
		return errors.New("远程请求队列已满，尚未执行")
	}
	waiter := &remoteWaiter{ready: make(chan struct{}), ctx: ctx, priority: priority}
	gate.pending = append(gate.pending, waiter)
	gate.mu.Unlock()
	select {
	case <-waiter.ready:
		if err := ctx.Err(); err != nil {
			gate.Unlock()
			return err
		}
		return nil
	case <-ctx.Done():
		gate.mu.Lock()
		found := false
		for i, pending := range gate.pending {
			if pending == waiter {
				gate.pending = append(gate.pending[:i], gate.pending[i+1:]...)
				found = true
				break
			}
		}
		if !found {
			gate.releaseLocked()
		} // Already granted; give its slot to the next request.
		gate.mu.Unlock()
		return ctx.Err()
	}
}
func (gate *remoteGate) TryLock() bool {
	gate.mu.Lock()
	defer gate.mu.Unlock()
	if gate.busy {
		return false
	}
	gate.busy = true
	return true
}
func (gate *remoteGate) Unlock() { gate.mu.Lock(); defer gate.mu.Unlock(); gate.releaseLocked() }
func (gate *remoteGate) releaseLocked() {
	for len(gate.pending) > 0 {
		next := 0
		if gate.interactiveRun < 3 {
			for i, waiter := range gate.pending {
				if waiter.priority > gate.pending[next].priority {
					next = i
				}
			}
		}
		if gate.pending[next].priority == 2 {
			gate.interactiveRun++
		} else {
			gate.interactiveRun = 0
		}
		waiter := gate.pending[next]
		gate.pending = append(gate.pending[:next], gate.pending[next+1:]...)
		// Canceled waiters still receive a grant, allowing their cancel path to release it.
		close(waiter.ready)
		return
	}
	gate.busy = false
}

func remotePriority(body []byte) int {
	var request struct {
		Method string
		Params struct{ Name string }
	}
	if json.Unmarshal(body, &request) != nil {
		return 1
	}
	switch request.Params.Name {
	case "station_clipboard_exchange", "station_clipboard_state", "station_notification_poll",
		"station_operation_status", "station_device_status", "station_controls_status", "station_terminal_read", "station_screen_status":
		return 2
	case "station_file_read_bytes", "station_file_write_bytes", "station_file_append_bytes":
		return 0
	}
	return 1
}

func main() {
	if len(os.Args) < 2 {
		usage()
		os.Exit(2)
	}
	var err error
	switch os.Args[1] {
	case "clients":
		var file string
		file, err = clientsPath()
		if err == nil {
			err = clientsCLI(os.Args[2:], file, os.Stdout)
		}
	case "configure":
		err = configureRemote()
	case "profile":
		err = printRemoteProfile()
	case "validate-endpoint":
		if len(os.Args) != 3 {
			err = errors.New("validate-endpoint requires an HTTPS address")
		} else {
			var endpoint string
			endpoint, err = normalizeRemoteEndpoint(os.Args[2])
			if err == nil {
				fmt.Println(endpoint)
			}
		}
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
	case "status-json":
		err = printJSONStatus()
	case "status-safe":
		var state statusReply
		state, err = getStatus()
		if err == nil {
			state.Token = ""
			state.Endpoint = publicAddress
			err = json.NewEncoder(os.Stdout).Encode(state)
		}
	case "watch-status":
		err = watchJSONStatus()
	case "credentials":
		err = printCredentials()
	case "call":
		var value config
		value, err = loadConfig()
		if err == nil {
			err = runRPCCLI(value, os.Stdin, os.Stdout, func(ctx context.Context, value config, body []byte) (relayResponse, error) {
				return gatewayViaHTTP(ctx, publicAddress, value, body, false)
			})
		}
	case "remote-call":
		var value config
		value, err = loadConfig()
		if err == nil {
			err = runRemoteCLI(value, os.Stdin, os.Stdout, remoteViaGateway)
		}
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
	fmt.Fprintln(os.Stderr, "phone-relay-gateway clients <create|list|revoke> | configure|profile|validate-endpoint <https-address>|local-token|clear-local|unpair|start|serve|status|status-json|status-safe|credentials|call|remote-call")
}

// Uses the daemon's remote-only route, without opening adb or printing credentials.
// Retries require the relay to explicitly confirm that it has not queued the
// operation. An unknown outcome is never resubmitted.
func runRemoteCLI(value config, input io.Reader, output io.Writer,
	call func(context.Context, config, []byte) (relayResponse, error)) error {
	if value.RemoteURL == "" || value.RemotePin == "" {
		return errors.New("尚未配置远程中继")
	}
	return runRPCCLI(value, input, output, call)
}

func runRPCCLI(value config, input io.Reader, output io.Writer,
	call func(context.Context, config, []byte) (relayResponse, error)) error {
	body, err := readBody(input, maxBody)
	if err != nil {
		return errors.New("无法读取 MCP 请求或请求过大")
	}
	var request struct {
		JSONRPC string          `json:"jsonrpc"`
		Method  string          `json:"method"`
		ID      json.RawMessage `json:"id"`
	}
	if json.Unmarshal(body, &request) != nil || request.JSONRPC != "2.0" ||
		request.Method == "" || !validRPCID(request.ID) {
		return errors.New("需要带 id 的 JSON-RPC 2.0 请求")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Minute)
	defer cancel()
	response, err := call(ctx, value, body)
	if err != nil {
		return fmt.Errorf("MCP 请求未确认；请核实结果，不要自动重做 (%v)", err)
	}
	var reply struct {
		JSONRPC string          `json:"jsonrpc"`
		ID      json.RawMessage `json:"id"`
		Result  json.RawMessage `json:"result"`
		Error   json.RawMessage `json:"error"`
	}
	if response.Status != http.StatusOK || json.Unmarshal(response.Body, &reply) != nil ||
		reply.JSONRPC != "2.0" || !sameRPCID(reply.ID, request.ID) || (len(reply.Result) == 0) == (len(reply.Error) == 0) {
		return errors.New("手机返回无效或编号不匹配的 MCP 响应；请核实结果")
	}
	_, err = output.Write(append(response.Body, '\n'))
	return err
}

func sameRPCID(left, right json.RawMessage) bool {
	if !validRPCID(left) || !validRPCID(right) {
		return false
	}
	var a, b string
	if json.Unmarshal(left, &a) == nil && json.Unmarshal(right, &b) == nil {
		return a == b
	}
	return bytes.Equal(bytes.TrimSpace(left), bytes.TrimSpace(right))
}

// All CLI processes use the daemon's queue; this never opens or starts adb.
func remoteViaGateway(ctx context.Context, value config, body []byte) (relayResponse, error) {
	if err := startDaemon(); err != nil {
		return relayResponse{}, err
	}
	current, err := loadConfig()
	if err != nil {
		return relayResponse{}, err
	}
	if current.remoteProfileKey() != value.remoteProfileKey() {
		return relayResponse{}, errors.New("远程配置已变化，尚未提交请求")
	}
	return remoteViaHTTP(ctx, publicAddress, current, body)
}
func profileFingerprint(value config) string {
	digest := sha256.Sum256([]byte(value.remoteProfileKey()))
	return hex.EncodeToString(digest[:])
}
func remoteViaHTTP(ctx context.Context, address string, value config, body []byte) (relayResponse, error) {
	return gatewayViaHTTP(ctx, address, value, body, true)
}

func gatewayViaHTTP(ctx context.Context, address string, value config, body []byte, remoteOnly bool) (relayResponse, error) {
	if remoteOnly {
		address = strings.TrimSuffix(address, "/mcp") + "/__remote-call"
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, address, bytes.NewReader(body))
	if err != nil {
		return relayResponse{}, err
	}
	request.Header.Set("Authorization", "Bearer "+value.GatewayToken)
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("Accept", "application/json, text/event-stream")
	if remoteOnly {
		request.Header.Set("X-Phone-Station-Remote-Profile", profileFingerprint(value))
	}
	// A redirect could replay a mutation or send the token to another endpoint.
	client := &http.Client{Timeout: 4 * time.Minute, CheckRedirect: func(*http.Request, []*http.Request) error {
		return http.ErrUseLastResponse
	}}
	response, err := client.Do(request)
	if err != nil {
		return relayResponse{}, err
	}
	defer response.Body.Close()
	result, err := readBody(response.Body, maxBody)
	return relayResponse{Status: response.StatusCode, Body: result}, err
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

// Pairing and the background adb-token refresh run in different processes.
// Serialize their read/modify/write transactions so neither loses the other.
func updateConfig(update func(*config) error) error {
	path, err := configPath()
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
		return err
	}
	lock, err := os.OpenFile(path+".lock", os.O_CREATE|os.O_RDWR, 0600)
	if err != nil {
		return err
	}
	defer lock.Close()
	if err := syscall.Flock(int(lock.Fd()), syscall.LOCK_EX); err != nil {
		return err
	}
	defer syscall.Flock(int(lock.Fd()), syscall.LOCK_UN)
	value, err := loadConfig()
	if err != nil {
		return err
	}
	previous := value
	if err := update(&value); err != nil {
		return err
	}
	if value == previous {
		return nil
	}
	return saveConfig(value)
}

// Restrict profiles to HTTPS hosts and optional base paths. This also keeps an
// endpoint safe to pass as a single argument to the Android pairing broadcast.
var remoteEndpointPattern = regexp.MustCompile(`^https://(?:[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?|\[[0-9A-Fa-f:.]+\])(?::[0-9]{1,5})?(?:/[A-Za-z0-9._~/-]*)?$`)

func normalizeRemoteEndpoint(endpoint string) (string, error) {
	endpoint = strings.TrimSpace(endpoint)
	invalid := errors.New("中继地址必须是 HTTPS 主机和可选端口、路径；不能包含账号、查询参数或片段。")
	if !remoteEndpointPattern.MatchString(endpoint) {
		return "", invalid
	}
	parsed, err := url.Parse(endpoint)
	if err != nil || parsed.Hostname() == "" {
		return "", invalid
	}
	if strings.HasPrefix(parsed.Host, "[") && net.ParseIP(parsed.Hostname()) == nil {
		return "", invalid
	}
	if !strings.HasPrefix(parsed.Host, "[") {
		for _, label := range strings.Split(parsed.Hostname(), ".") {
			if len(label) == 0 || len(label) > 63 || strings.HasPrefix(label, "-") || strings.HasSuffix(label, "-") {
				return "", invalid
			}
		}
	}
	if port := parsed.Port(); port != "" {
		number, err := strconv.Atoi(port)
		if err != nil || number < 1 || number > 65535 {
			return "", invalid
		}
	}
	for _, segment := range strings.Split(parsed.Path, "/") {
		if segment == "." || segment == ".." {
			return "", invalid
		}
	}
	return strings.TrimRight(endpoint, "/"), nil
}

type remoteProfile struct {
	Endpoint   string `json:"endpoint"`
	Pin        string `json:"pin"`
	Configured bool   `json:"configured"`
}

func publicRemoteProfile(value config) remoteProfile {
	return remoteProfile{Endpoint: value.RemoteURL, Pin: value.RemotePin,
		Configured: value.RemoteURL != "" && value.RemotePin != ""}
}

// Reading a profile never starts the gateway or reads keychain credentials.
func printRemoteProfile() error {
	value, err := loadConfig()
	if err != nil {
		return err
	}
	return json.NewEncoder(os.Stdout).Encode(publicRemoteProfile(value))
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
	endpoint, err := normalizeRemoteEndpoint(input.Endpoint)
	if err != nil {
		return err
	}
	if len(input.Pin) != 64 || !isHex(input.Pin) ||
		len(input.Token) != 64 || !isHex(input.Token) {
		return errors.New("remote profile values are invalid")
	}
	revision := randomHex(8)
	var previousRevision string
	err = updateConfig(func(value *config) error {
		previousRevision = value.RemoteRevision
		if _, err := runKeychain("set", []byte(strings.ToLower(input.Token)), revision); err != nil {
			return err
		}
		if value.GatewayToken == "" {
			value.GatewayToken = randomHex(32)
		}
		value.RemoteURL, value.RemotePin, value.RemoteRevision = endpoint, strings.ToLower(input.Pin), revision
		return nil
	})
	if err != nil {
		_, _ = runKeychain("delete", nil, revision)
		return err
	}
	// Requests already holding the previous token can finish. A stale request
	// that has not loaded it yet fails rather than using the new server's token.
	_, _ = runKeychain("delete", nil, previousRevision)
	return nil
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
	return updateConfig(func(value *config) error {
		if value.GatewayToken == "" {
			value.GatewayToken = randomHex(32)
		}
		value.LocalToken = token
		return nil
	})
}

func clearRemote() error {
	return updateConfig(func(value *config) error {
		if _, err := runKeychain("delete", nil, value.RemoteRevision); err != nil {
			return err
		}
		value.RemoteURL, value.RemotePin = "", ""
		value.RemoteRevision = randomHex(8)
		return nil
	})
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
	err := updateConfig(func(value *config) error {
		if value.GatewayToken == "" {
			value.GatewayToken = randomHex(32)
		}
		return nil
	})
	if err != nil {
		return config{}, err
	}
	return loadConfig()
}

func (g *gateway) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if !allowedLoopbackRequest(r) {
		writeMCPError(w, nil, http.StatusForbidden, "Host 或 Origin 不允许")
		return
	}
	value, err := g.config()
	if err != nil {
		writeMCPError(w, nil, http.StatusInternalServerError, "网关配置不可用")
		return
	}
	if r.URL.Path == "/__subscription" {
		g.subscriptions(w, r, value)
		return
	}
	if strings.HasPrefix(r.URL.Path, "/__screen/") {
		g.screen(w, r, value)
		return
	}
	if (r.URL.Path == "/__status" || r.URL.Path == "/__events") && r.Method == http.MethodGet {
		if !authorized(r, value.GatewayToken) {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
		if r.URL.Path == "/__events" {
			g.streamStatus(w, r, value)
		} else {
			writeJSON(w, http.StatusOK, g.snapshot(value))
		}
		return
	}
	if r.URL.Path == "/mcp" && r.Method != http.MethodPost {
		if !authorized(r, value.GatewayToken) {
			client, err := g.clientAuthorization(r)
			if err != nil {
				writeMCPError(w, nil, http.StatusServiceUnavailable, "客户端授权不可用")
				return
			}
			if client == nil {
				writeMCPError(w, nil, http.StatusUnauthorized, "需要有效的网关令牌")
				return
			}
		}
		w.Header().Set("Allow", "POST")
		writeMCPError(w, nil, http.StatusMethodNotAllowed, "本服务不提供 MCP SSE 流，请使用 POST")
		return
	}
	if r.Method != http.MethodPost || (r.URL.Path != "/mcp" && r.URL.Path != "/__remote-call") {
		writeMCPError(w, nil, http.StatusNotFound, "unknown endpoint")
		return
	}
	var client *clientGrant
	if !authorized(r, value.GatewayToken) {
		if r.URL.Path == "/mcp" {
			client, err = g.clientAuthorization(r)
		}
		if err != nil {
			writeMCPError(w, nil, http.StatusServiceUnavailable, "客户端授权不可用")
			return
		}
		if client == nil {
			writeMCPError(w, nil, http.StatusUnauthorized, "需要有效的网关令牌")
			return
		}
	}
	body, err := readBody(r.Body, maxBody)
	if err != nil {
		writeMCPError(w, nil, http.StatusRequestEntityTooLarge, "请求过大或无法读取")
		return
	}
	if !utf8.Valid(body) || !json.Valid(body) {
		writeRPCError(w, nil, http.StatusOK, -32700, "请求必须是 UTF-8 JSON")
		return
	}
	if client != nil {
		lists, denied := client.preflight(body)
		if denied != nil {
			writeMCPError(w, requestID(body), http.StatusForbidden, denied.Error())
			return
		}
		if len(lists) > 0 {
			captured := &clientResponse{header: make(http.Header)}
			defer client.finish(w, captured, lists)
			w = captured
		}
	}
	if r.URL.Path == "/__remote-call" {
		if r.Header.Get("X-Phone-Station-Remote-Profile") != profileFingerprint(value) {
			writeMCPError(w, requestID(body), http.StatusConflict, "远程配置已变化，尚未执行")
			return
		}
		g.proxyRemote(r.Context(), w, body, value)
		return
	}
	mode := g.mode()
	// Check the local route before dispatch: a dead adb TCP forward can still
	// accept a socket. The actual operation has not run and can safely go remote.
	if mode == "local" {
		g.checkLocal(value.LocalToken, false)
		mode = g.mode()
	}
	switch mode {
	case "local":
		g.proxyLocal(r.Context(), w, body, value)
	case "remote":
		g.proxyRemote(r.Context(), w, body, value)
	default:
		writeMCPError(w, requestID(body), http.StatusServiceUnavailable, "手机暂时没有可用通道")
	}
}

func loopbackAuthority(authority string) (*url.URL, bool) {
	if authority == "" || strings.ContainsAny(authority, "/?#@\\") || strings.HasSuffix(authority, ":") {
		return nil, false
	}
	parsed, err := url.Parse("http://" + authority)
	if err != nil || parsed.User != nil || parsed.Path != "" || parsed.RawQuery != "" || parsed.Fragment != "" {
		return nil, false
	}
	switch strings.ToLower(parsed.Hostname()) {
	case "127.0.0.1", "localhost", "::1":
	default:
		return nil, false
	}
	if parsed.Port() != "" {
		port, err := strconv.Atoi(parsed.Port())
		if err != nil || port < 1 || port > 65535 {
			return nil, false
		}
	}
	return parsed, true
}
func boundaryPort(address *url.URL) string {
	if address.Port() == "" {
		return "80"
	}
	value, _ := strconv.Atoi(address.Port())
	return strconv.Itoa(value)
}
func allowedLoopbackRequest(r *http.Request) bool {
	target, valid := loopbackAuthority(r.Host)
	if !valid {
		return false
	}
	origins := r.Header.Values("Origin")
	if len(origins) == 0 {
		return true
	}
	if len(origins) != 1 || strings.ContainsAny(origins[0], "?#") {
		return false
	}
	source, err := url.Parse(origins[0])
	if err != nil || source.Scheme != "http" || source.User != nil || source.Path != "" || source.RawQuery != "" || source.Fragment != "" {
		return false
	}
	checked, valid := loopbackAuthority(source.Host)
	return valid && boundaryPort(checked) == boundaryPort(target)
}
func validRPCID(id json.RawMessage) bool {
	if len(id) == 0 {
		return false
	}
	if id[0] == '"' {
		var text string
		return json.Unmarshal(id, &text) == nil
	}
	return rpcIntegerID.Match(id)
}

func (g *gateway) monitor() {
	// A stalled relay probe must never delay noticing a failed local route.
	go func() {
		ticker := time.NewTicker(remoteEvery)
		defer ticker.Stop()
		for {
			if value, err := g.config(); err == nil {
				g.probeRemote(value)
			}
			<-ticker.C
		}
	}()
	ticker := time.NewTicker(probeEvery)
	defer ticker.Stop()
	for {
		if value, err := g.config(); err == nil {
			g.checkLocal(value.LocalToken, true)
		}
		<-ticker.C
	}
}

func (g *gateway) config() (config, error) {
	var value config
	var err error
	if g.readConfig != nil {
		value, err = g.readConfig()
	} else {
		value, err = loadConfig()
	}
	if err == nil {
		key := value.remoteProfileKey()
		g.state.mu.Lock()
		if g.state.remoteProfile != "" && g.state.remoteProfile != key {
			g.state.remoteOnline, g.state.remoteRTT = false, 0
			g.state.remoteCheckedAt = time.Time{}
			g.state.localSlowSamples, g.state.localGoodSamples = 0, 0
			g.state.selectMode(false)
		}
		g.state.remoteProfile = key
		g.state.mu.Unlock()
	}
	return value, err
}

func (value config) remoteProfileKey() string {
	return value.RemoteURL + "\n" + value.RemotePin + "\n" + value.RemoteRevision
}

func (g *gateway) mode() string {
	g.state.mu.RLock()
	defer g.state.mu.RUnlock()
	return g.state.mode
}

func (g *gateway) checkLocal(token string, sample bool) {
	g.state.mu.RLock()
	revision := g.state.localRevision
	g.state.mu.RUnlock()
	result := g.probeLocal(token)
	g.state.mu.Lock()
	defer g.state.mu.Unlock()
	if revision != g.state.localRevision {
		return
	}
	g.noteLocalLocked(result, sample)
}

func (g *gateway) noteLocalLocked(result probeResult, sample bool) {
	state := &g.state
	state.localOnline, state.localRTT = result.ok, result.rtt
	if !result.ok {
		state.localRevision++
		state.localSlowSamples, state.localGoodSamples = 0, 0
		for _, cancel := range state.localRequests {
			cancel()
		}
	}
	state.selectMode(sample)
}

func (g *gateway) noteRemote(value config, result probeResult) {
	current, err := g.config()
	if err != nil || current.remoteProfileKey() != value.remoteProfileKey() {
		return
	}
	g.state.mu.Lock()
	defer g.state.mu.Unlock()
	if g.state.remoteProfile != value.remoteProfileKey() {
		return
	}
	g.state.remoteOnline, g.state.remoteRTT = result.ok, result.rtt
	g.state.remoteCheckedAt = time.Now()
	g.state.selectMode(false)
}

// Called with the state lock held. Recovery from a broken route is faster than
// switching back after choosing the relay for sustained local latency.
func (state *health) selectMode(localSample bool) {
	previous := state.mode
	if state.localOnline && !state.remoteOnline {
		state.mode = "local"
	} else if state.remoteOnline && !state.localOnline {
		state.mode = "remote"
		state.remoteForFailure = true
	} else if state.localOnline && state.remoteOnline {
		if state.mode == "" || state.mode == "offline" {
			state.mode = "local"
		}
		if state.mode == "local" && localSample {
			if state.localRTT > 500*time.Millisecond && state.remoteRTT*10 < state.localRTT*6 {
				state.localSlowSamples++
			} else {
				state.localSlowSamples = 0
			}
			if state.localSlowSamples >= 3 {
				state.mode = "remote"
				state.remoteForFailure = false
				state.localGoodSamples = 0
			}
		} else if state.mode == "remote" && localSample {
			if state.localRTT < 150*time.Millisecond {
				state.localGoodSamples++
			} else {
				state.localGoodSamples = 0
			}
			needed, cooldown := 6, time.Minute
			if state.remoteForFailure {
				needed, cooldown = 3, 3*time.Second
			}
			if state.localGoodSamples >= needed && time.Since(state.lastModeChange) >= cooldown {
				state.mode = "local"
				state.localSlowSamples = 0
			}
		}
	} else {
		state.mode = "offline"
	}
	if state.mode != previous {
		state.lastModeChange = time.Now()
		state.localSlowSamples, state.localGoodSamples = 0, 0
	}
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
	ctx, cancel := context.WithTimeout(context.Background(), localTimeout)
	defer cancel()
	address := g.localURL
	if address == "" {
		address = localAddress
	}
	request, _ := http.NewRequestWithContext(ctx, http.MethodPost, address, bytes.NewBufferString(`{"jsonrpc":"2.0","id":"local-probe","method":"ping"}`))
	request.Header.Set("Authorization", "Bearer "+token)
	request.Header.Set("Content-Type", "application/json")
	response, err := (&http.Client{Timeout: time.Second, CheckRedirect: rejectRelayRedirect}).Do(request)
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

func (g *gateway) probeRemote(value config) (result probeResult, sampled bool) {
	// The relay dispatches one operation at a time. A health probe must not
	// compete with an MCP call or mark a busy, working channel as offline.
	if !g.remoteMu.TryLock() {
		return probeResult{}, false
	}
	defer g.remoteMu.Unlock()
	g.setRemoteBusy(true)
	defer g.setRemoteBusy(false)
	// Publish before releasing the relay lock, so a late probe cannot overwrite
	// the health of a business call that has already completed successfully.
	defer func() {
		if sampled {
			g.noteRemote(value, result)
		}
	}()
	if value.RemoteURL == "" || value.RemotePin == "" {
		return probeResult{}, true
	}
	start := time.Now()
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	var presence controlHealth
	// Test overrides without a status transport retain the legacy probe behavior.
	if g.callRemote == nil || g.controlStatus != nil {
		var err error
		presence, err = g.readControlHealth(ctx, value)
		if err != nil || !presence.Online {
			g.probeAt = time.Time{}
			return probeResult{}, true
		}
		if presence.Protocol == "control-v1" && presence.Connected && presence.Session != "" &&
			g.probeProfile == value.remoteProfileKey() && g.probeSession == presence.Session && time.Since(g.probeAt) < time.Minute {
			g.state.mu.RLock()
			rtt, healthy := g.state.remoteRTT, g.state.remoteOnline
			g.state.mu.RUnlock()
			if healthy {
				return probeResult{ok: true, rtt: rtt}, true
			}
		}
	}
	response, err := g.remoteCall(ctx, value, []byte(`{"jsonrpc":"2.0","id":"remote-probe","method":"ping"}`))
	if err != nil || response.Status != http.StatusOK || !validMCPReply(response.Body) {
		return probeResult{}, true
	}
	g.probeAt = time.Now()
	g.probeProfile, g.probeSession = value.remoteProfileKey(), presence.Session
	return probeResult{ok: true, rtt: time.Since(start)}, true
}

func (g *gateway) proxyLocal(parent context.Context, w http.ResponseWriter, body []byte, value config) {
	token := value.LocalToken
	if token == "" {
		writeMCPError(w, requestID(body), http.StatusServiceUnavailable, "adb 通道还没就绪")
		return
	}
	ctx, cancel := context.WithTimeout(parent, 4*time.Minute)
	defer cancel()
	g.state.mu.Lock()
	g.state.nextRequest++
	id := g.state.nextRequest
	revision := g.state.localRevision
	if g.state.localRequests == nil {
		g.state.localRequests = make(map[uint64]context.CancelFunc)
	}
	g.state.localRequests[id] = cancel
	if !g.state.localOnline {
		cancel()
	}
	g.state.mu.Unlock()
	defer func() {
		g.state.mu.Lock()
		delete(g.state.localRequests, id)
		g.state.mu.Unlock()
	}()
	address := g.localURL
	if address == "" {
		address = localAddress
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, address, bytes.NewReader(body))
	if err != nil {
		writeMCPError(w, requestID(body), http.StatusBadGateway, "无法连接手机")
		return
	}
	request.Header.Set("Authorization", "Bearer "+token)
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("Accept", "application/json, text/event-stream")
	response, err := (&http.Client{Timeout: 4 * time.Minute, CheckRedirect: rejectRelayRedirect}).Do(request)
	if err != nil {
		g.localRequestFailed(parent, w, body, value, revision)
		return
	}
	defer response.Body.Close()
	result, err := readBody(response.Body, maxBody)
	if err != nil {
		g.localRequestFailed(parent, w, body, value, revision)
		return
	}
	w.Header().Set("Content-Type", response.Header.Get("Content-Type"))
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(response.StatusCode)
	_, _ = w.Write(result)
}

func (g *gateway) localRequestFailed(parent context.Context, w http.ResponseWriter, body []byte, value config, revision uint64) {
	if parent.Err() != nil {
		return
	}
	g.state.mu.Lock()
	if revision == g.state.localRevision {
		g.noteLocalLocked(probeResult{}, false)
	}
	remoteOnline := g.state.remoteOnline
	g.state.mu.Unlock()
	if remoteOnline && safeToReplay(body) {
		g.proxyRemote(parent, w, body, value)
	} else {
		writeMCPError(w, requestID(body), http.StatusBadGateway, "本次请求中断；结果可能需要核实")
	}
}

func safeToReplay(body []byte) bool {
	trimmed := bytes.TrimSpace(body)
	if len(trimmed) > 0 && trimmed[0] == '[' {
		var batch []json.RawMessage
		if json.Unmarshal(body, &batch) != nil || len(batch) == 0 || len(batch) > 64 {
			return false
		}
		for _, item := range batch {
			value := bytes.TrimSpace(item)
			if len(value) == 0 || value[0] == '[' || !safeToReplay(item) {
				return false
			}
		}
		return true
	}
	var request struct {
		JSONRPC string          `json:"jsonrpc"`
		ID      json.RawMessage `json:"id"`
		Method  string          `json:"method"`
		Params  struct {
			Name string `json:"name"`
		} `json:"params"`
	}
	if json.Unmarshal(body, &request) != nil || request.JSONRPC != "2.0" || !validRPCID(request.ID) {
		return false
	}
	switch request.Method {
	case "ping", "initialize", "tools/list":
		return true
	case "tools/call":
		switch request.Params.Name {
		case "station_operation_status", "station_controls_status", "station_screen_capture_status", "station_terminal_read", "station_screen_status",
			"station_file_access_policy", "station_storage_summary", "station_device_status", "station_shell_status", "station_notification_status", "station_notification_icon",
			"station_file_list", "station_file_stat", "station_file_read_text", "station_file_read_bytes",
			"station_file_search", "station_file_search_text":
			return true
		}
	}
	return false
}

func (g *gateway) proxyRemote(parent context.Context, w http.ResponseWriter, body []byte, value config) {
	ctx, cancel := context.WithTimeout(parent, 4*time.Minute)
	defer cancel()
	if err := g.remoteMu.acquirePriority(ctx, remotePriority(body)); err != nil {
		writeMCPError(w, requestID(body), http.StatusServiceUnavailable, "排队取消或队列已满，本次尚未执行")
		return
	}
	defer g.remoteMu.Unlock()
	g.setRemoteBusy(true)
	defer g.setRemoteBusy(false)
	current, err := g.config()
	if err != nil || current.remoteProfileKey() != value.remoteProfileKey() {
		writeMCPError(w, requestID(body), http.StatusConflict, "远程配置已变化，本次尚未执行")
		return
	}
	if value.RemoteURL == "" || value.RemotePin == "" {
		writeMCPError(w, requestID(body), http.StatusServiceUnavailable, "尚未配置远程中继")
		return
	}
	start := time.Now()
	response, err := g.remoteCall(ctx, value, body)
	if err != nil {
		if parent.Err() == nil {
			g.noteRemote(value, probeResult{})
		}
		writeMCPError(w, requestID(body), http.StatusBadGateway, "远程请求中断；请用相同操作核实结果")
		return
	}
	g.noteRemote(value, probeResult{ok: true, rtt: time.Since(start)})
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(response.Status)
	_, _ = w.Write(response.Body)
}

func (g *gateway) remoteCall(ctx context.Context, value config, payload []byte) (relayResponse, error) {
	if g.callRemote != nil {
		return g.callRemote(ctx, value, payload)
	}
	return remoteCall(ctx, value, payload)
}

func remoteCall(ctx context.Context, value config, payload []byte) (relayResponse, error) {
	token, err := loadRemoteToken(value)
	if err != nil {
		return relayResponse{}, err
	}
	transport := pinnedTransport(value.RemotePin)
	defer transport.CloseIdleConnections()
	client := &http.Client{Timeout: 4 * time.Minute, Transport: transport, CheckRedirect: rejectRelayRedirect}
	endpoint := strings.TrimRight(value.RemoteURL, "/")
	operationID, err := relayOperationID(ctx, client, endpoint, token)
	if err != nil {
		return relayResponse{}, err
	}
	requestBody, err := json.Marshal(struct {
		OperationID string          `json:"operationId"`
		Payload     json.RawMessage `json:"payload"`
	}{OperationID: operationID, Payload: payload})
	if err != nil {
		return relayResponse{}, err
	}
	response, err := postRelayCall(ctx, client, endpoint+"/v1/desktop/call", token, requestBody)
	if err != nil {
		return relayResponse{}, fmt.Errorf("relay operation %s outcome unknown: %w", operationID, err)
	}
	defer response.Body.Close()
	result, err := readBody(response.Body, maxBody)
	if err != nil {
		return relayResponse{}, err
	}
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		return relayResponse{}, fmt.Errorf("relay operation %s returned status %d; do not replay", operationID, response.StatusCode)
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

// This authenticated, pinned handshake happens before any operation is submitted.
// Legacy relays have no epoch; keeping their IDs supports client-first upgrades.
func relayOperationID(ctx context.Context, client *http.Client, endpoint, token string) (string, error) {
	ctx, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint+"/v1/desktop/status", nil)
	if err != nil {
		return "", err
	}
	request.Header.Set("Authorization", "Bearer "+token)
	response, err := client.Do(request)
	if err != nil {
		return "", errors.New("relay handshake failed before submission")
	}
	defer response.Body.Close()
	body, err := readBody(response.Body, 16384)
	if err != nil || response.StatusCode != 200 {
		return "", errors.New("relay handshake rejected before submission")
	}
	var info struct {
		Epoch   string `json:"operationEpoch"`
		Durable bool   `json:"durable"`
	}
	body = bytes.TrimSpace(body)
	if json.Unmarshal(body, &info) != nil || len(body) == 0 || body[0] != '{' {
		return "", errors.New("invalid relay handshake")
	}
	id := randomHex(16)
	if info.Epoch == "" && !info.Durable {
		return id, nil
	}
	digest, err := hex.DecodeString(info.Epoch)
	if err != nil || len(digest) != 16 || info.Epoch != strings.ToLower(info.Epoch) || !info.Durable {
		return "", errors.New("invalid relay operation epoch")
	}
	return info.Epoch + "." + id, nil
}

func postRelayCall(ctx context.Context, client *http.Client, endpoint, token string, requestBody []byte) (*http.Response, error) {
	for attempt := 0; ; attempt++ {
		request, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, bytes.NewReader(requestBody))
		if err != nil {
			return nil, err
		}
		request.Header.Set("Authorization", "Bearer "+token)
		request.Header.Set("Content-Type", "application/json")
		response, err := client.Do(request)
		if err != nil {
			// A lost reply may follow a completed side effect. External relay
			// durability alone cannot prove whether the phone already executed it.
			return nil, err
		}
		if response.StatusCode != http.StatusTooManyRequests || response.Header.Get("X-Phone-Station-Not-Queued") != "1" {
			return response, nil
		}
		// Only this explicit pre-acceptance rejection is retryable, with the same ID.
		_, _ = io.Copy(io.Discard, io.LimitReader(response.Body, 4096))
		_ = response.Body.Close()
		timer := time.NewTimer(time.Duration(min(attempt+1, 8)) * 250 * time.Millisecond)
		select {
		case <-ctx.Done():
			timer.Stop()
			return nil, ctx.Err()
		case <-timer.C:
		}
	}
}

func rejectRelayRedirect(_ *http.Request, _ []*http.Request) error { return http.ErrUseLastResponse }

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
	Ready            bool   `json:"ready"`
	Mode             string `json:"mode"`
	LocalOnline      bool   `json:"localOnline"`
	RemoteOnline     bool   `json:"remoteOnline"`
	RemoteConfigured bool   `json:"remoteConfigured"`
	RemoteVerified   bool   `json:"remoteVerified"`
	RemoteChecking   bool   `json:"remoteChecking"`
	LocalRTTMs       int64  `json:"localRttMs"`
	RemoteRTTMs      int64  `json:"remoteRttMs"`
	Endpoint         string `json:"endpoint,omitempty"`
	Token            string `json:"token,omitempty"`
}

func (g *gateway) setRemoteBusy(busy bool) {
	g.state.mu.Lock()
	g.state.remoteBusy = busy
	g.state.mu.Unlock()
}

func (g *gateway) snapshot(value config) statusReply {
	g.state.mu.RLock()
	defer g.state.mu.RUnlock()
	s := &g.state
	configured := value.RemoteURL != "" && value.RemotePin != ""
	verified := s.remoteOnline && (s.remoteCheckedAt.IsZero() || time.Since(s.remoteCheckedAt) <= remoteFresh)
	return statusReply{
		Ready: value.GatewayToken != "", Mode: s.mode,
		LocalOnline: s.localOnline, RemoteOnline: s.remoteOnline,
		RemoteConfigured: configured, RemoteVerified: configured && verified,
		RemoteChecking: configured && (s.remoteCheckedAt.IsZero() || (s.remoteBusy && !verified)),
		LocalRTTMs:     durationMs(s.localRTT), RemoteRTTMs: durationMs(s.remoteRTT),
	}
}

// Publish transitions through one authenticated connection, including freshness
// expiry while a long operation prevents another ping. No credentials on the wire.
func (g *gateway) streamStatus(w http.ResponseWriter, r *http.Request, value config) {
	flusher, ok := w.(http.Flusher)
	if !ok {
		http.Error(w, "stream unavailable", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/x-ndjson")
	w.Header().Set("Cache-Control", "no-store")
	ticker := time.NewTicker(100 * time.Millisecond)
	defer ticker.Stop()
	previous := statusReply{}
	first := true
	for {
		current, err := g.config()
		if err != nil || current.GatewayToken != value.GatewayToken {
			return
		}
		next := g.snapshot(current)
		if first || next != previous {
			if json.NewEncoder(w).Encode(next) != nil {
				return
			}
			flusher.Flush()
			previous, first = next, false
		}
		select {
		case <-r.Context().Done():
			return
		case <-ticker.C:
		}
	}
}

func watchJSONStatus() error {
	value, err := loadConfig()
	if err != nil {
		return err
	}
	var lifetime io.Reader
	if os.Getenv("PHONE_STATION_WATCH_STDIN") == "1" {
		lifetime = os.Stdin
	}
	return streamJSONStatus("http://"+listenAddress+"/__events", value.GatewayToken, lifetime, os.Stdout)
}

// The app keeps stdin open for the lifetime of this watcher. EOF also arrives
// when it crashes or is killed, cancelling an idle HTTP read without polling.
// Plain CLI watchers keep their existing stdin-independent behavior.
func streamJSONStatus(endpoint, token string, lifetime io.Reader, output io.Writer) error {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	if lifetime != nil {
		go func() {
			_, _ = io.Copy(io.Discard, lifetime)
			cancel()
		}()
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		return err
	}
	request.Header.Set("Authorization", "Bearer "+token)
	client := &http.Client{Transport: &http.Transport{ResponseHeaderTimeout: time.Second}, CheckRedirect: rejectRelayRedirect}
	defer client.CloseIdleConnections()
	response, err := client.Do(request)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return errors.New("status stream unavailable")
	}
	decoder, encoder := json.NewDecoder(response.Body), json.NewEncoder(output)
	for {
		var status statusReply
		if err := decoder.Decode(&status); err != nil {
			return err
		}
		status.Endpoint, status.Token = publicAddress, token
		if err := encoder.Encode(status); err != nil {
			return err
		}
	}
}

func printJSONStatus() error {
	status, err := getStatus()
	if err != nil {
		return json.NewEncoder(os.Stdout).Encode(statusReply{})
	}
	value, err := loadConfig()
	if err != nil {
		return err
	}
	status.Endpoint, status.Token = publicAddress, value.GatewayToken
	return json.NewEncoder(os.Stdout).Encode(status)
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
	response, err := (&http.Client{Timeout: time.Second, CheckRedirect: rejectRelayRedirect}).Do(request)
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

func loadRemoteToken(value config) (string, error) {
	output, err := runKeychain("get", nil, value.RemoteRevision)
	if err != nil {
		return "", err
	}
	token := strings.TrimSpace(string(output))
	if len(token) != 64 || !isHex(token) {
		return "", errors.New("remote credential is invalid")
	}
	return token, nil
}

func runKeychain(action string, input []byte, revision string) ([]byte, error) {
	executable, err := os.Executable()
	if err != nil {
		return nil, err
	}
	helper := filepath.Join(filepath.Dir(executable), "phone-relay-keychain")
	arguments := []string{action}
	if revision != "" {
		arguments = append(arguments, revision)
	}
	cmd := exec.Command(helper, arguments...)
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
	headers := r.Header.Values("Authorization")
	if len(headers) != 1 || want == "" {
		return false
	}
	got := headers[0]
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
	if json.Unmarshal(body, &value) != nil || value.JSONRPC != "2.0" || !validRPCID(value.ID) {
		return false
	}
	return (len(value.Result) > 0) != (len(value.Error) > 0)
}

func requestID(body []byte) json.RawMessage {
	var value struct {
		ID json.RawMessage `json:"id"`
	}
	if json.Unmarshal(body, &value) != nil || !validRPCID(value.ID) {
		return json.RawMessage("null")
	}
	return value.ID
}

func writeMCPError(w http.ResponseWriter, id json.RawMessage, status int, message string) {
	writeRPCError(w, id, status, -32001, message)
}
func writeRPCError(w http.ResponseWriter, id json.RawMessage, status, code int, message string) {
	if len(id) == 0 {
		id = json.RawMessage("null")
	}
	body, _ := json.Marshal(map[string]any{
		"jsonrpc": "2.0", "id": id,
		"error": map[string]any{"code": code, "message": message},
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
