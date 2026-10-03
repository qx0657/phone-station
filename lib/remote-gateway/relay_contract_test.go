//go:build relaycontract

package main

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"sync"
	"testing"
	"time"
)

type contractPeer struct {
	endpoint, phone, desktop string
	client                   *http.Client
	restart                  func() error
}
type contractReply struct {
	status int
	body   []byte
	err    error
}

func (p contractPeer) post(ctx context.Context, route, token string, body any) contractReply {
	raw, _ := json.Marshal(body)
	r, err := http.NewRequestWithContext(ctx, "POST", p.endpoint+route, bytes.NewReader(raw))
	if err != nil {
		return contractReply{err: errors.New("请求构造失败")}
	}
	r.Header.Set("Authorization", "Bearer "+token)
	r.Header.Set("Content-Type", "application/json")
	response, err := p.client.Do(r)
	if err != nil {
		return contractReply{err: errors.New("中继连接未确认")}
	}
	defer response.Body.Close()
	result, err := readBody(response.Body, maxBody)
	return contractReply{response.StatusCode, result, err}
}
func (p contractPeer) async(ctx context.Context, id string, payload any) <-chan contractReply {
	done := make(chan contractReply, 1)
	go func() {
		done <- p.post(ctx, "/v1/desktop/call", p.desktop, map[string]any{"operationId": id, "payload": payload})
	}()
	return done
}
func contractEqual(a, b []byte) bool {
	var left, right any
	if json.Unmarshal(a, &left) != nil || json.Unmarshal(b, &right) != nil {
		return false
	}
	leftJSON, _ := json.Marshal(left)
	rightJSON, _ := json.Marshal(right)
	return bytes.Equal(leftJSON, rightJSON)
}
func runRelayContract(p contractPeer) error {
	ctx, cancel := context.WithTimeout(context.Background(), 55*time.Second)
	defer cancel()
	call := func(route, token string, body any) contractReply { return p.post(ctx, route, token, body) }
	poll := func() contractReply { return call("/v1/phone/poll", p.phone, map[string]int{"waitMs": 100}) }
	id := randomHex(16)
	payload := map[string]any{"jsonrpc": "2.0", "id": id, "method": "ping"}
	request := map[string]any{"operationId": id, "payload": payload}
	for _, wrong := range []struct {
		route, token string
		body         any
	}{
		{"/v1/phone/poll", p.desktop, map[string]int{"waitMs": 100}},
		{"/v1/phone/result", p.desktop, map[string]any{"operationId": id, "response": map[string]any{"status": 200}}},
		{"/v1/desktop/call", p.phone, request}, {"/v1/desktop/call", "invalid-contract-token", request},
	} {
		reply := call(wrong.route, wrong.token, wrong.body)
		if reply.err != nil || (reply.status != 401 && reply.status != 403) {
			return errors.New("角色或未授权请求没有被拒绝")
		}
	}
	empty := func() bool {
		reply := poll()
		var value struct {
			OperationID *string `json:"operationId"`
		}
		return reply.err == nil && reply.status == 200 && json.Unmarshal(reply.body, &value) == nil && value.OperationID == nil && bytes.Contains(reply.body, []byte("operationId"))
	}
	if !empty() {
		return errors.New("隔离中继不为空，停止测试；不要连接生产中继")
	}
	dispatch := func(want string) bool {
		deadline := time.Now().Add(6 * time.Second)
		for time.Now().Before(deadline) {
			reply := poll()
			var value struct {
				OperationID *string         `json:"operationId"`
				Payload     json.RawMessage `json:"payload"`
			}
			if reply.err != nil || reply.status != 200 || json.Unmarshal(reply.body, &value) != nil {
				return false
			}
			if value.OperationID != nil {
				expected, _ := json.Marshal(payload)
				return *value.OperationID == want && contractEqual(value.Payload, expected)
			}
		}
		return false
	}
	expected := map[string]any{"status": 200, "body": map[string]any{"jsonrpc": "2.0", "id": id, "result": map[string]any{}}}
	expectedJSON, _ := json.Marshal(expected)
	checkReply := func(done <-chan contractReply) bool {
		select {
		case reply := <-done:
			return reply.err == nil && reply.status == 200 && contractEqual(reply.body, expectedJSON)
		case <-ctx.Done():
			return false
		}
	}
	first := p.async(ctx, id, payload)
	if !dispatch(id) {
		return errors.New("请求未派发或负载变化")
	}
	duplicate := p.async(ctx, id, payload)
	if !empty() {
		return errors.New("正在执行的编号被重复派发")
	}
	result := map[string]any{"operationId": id, "response": expected}
	for i := 0; i < 2; i++ {
		reply := call("/v1/phone/result", p.phone, result)
		if reply.err != nil || reply.status != 200 || !json.Valid(reply.body) {
			return errors.New("相同结果重传未确认")
		}
	}
	if !checkReply(first) || !checkReply(duplicate) {
		return errors.New("相同编号没有共享结果")
	}
	if !checkReply(p.async(ctx, id, payload)) || !empty() {
		return errors.New("完成编号未使用已有结果")
	}
	conflict := call("/v1/desktop/call", p.desktop, map[string]any{"operationId": id, "payload": map[string]string{"method": "different"}})
	if conflict.err != nil || conflict.status != 409 || !empty() {
		return errors.New("同编号不同负载没有拒绝")
	}
	conflict = call("/v1/phone/result", p.phone, map[string]any{"operationId": id, "response": map[string]int{"status": 500}})
	if conflict.err != nil || conflict.status != 409 {
		return errors.New("冲突结果没有拒绝")
	}
	abandonedID := randomHex(16)
	abandonedContext, abandon := context.WithCancel(ctx)
	abandoned := p.async(abandonedContext, abandonedID, payload)
	if !dispatch(abandonedID) {
		abandon()
		return errors.New("未能建立重启前的执行中操作")
	}
	abandon()
	<-abandoned
	if err := p.restart(); err != nil {
		return errors.New("隔离中继重启命令失败")
	}
	// A restart hook must return only after the isolated instance is ready.
	if !empty() {
		return errors.New("重启后重新派发了结果未知的操作")
	}
	if !checkReply(p.async(ctx, id, payload)) {
		return errors.New("重启丢失已完成结果")
	}
	unknown := call("/v1/desktop/call", p.desktop, map[string]any{"operationId": abandonedID, "payload": payload})
	if unknown.err != nil || (unknown.status != 409 && unknown.status != 410) || !empty() {
		return errors.New("重启后未知结果没有明确终止，或重新执行")
	}
	return nil
}

// Live invocation is opt-in and never selected by scripts/check.sh.
func TestRelayContract(t *testing.T) {
	file := os.Getenv("PHONE_STATION_RELAY_CONTRACT_CONFIG")
	info, err := os.Lstat(file)
	if err != nil || !info.Mode().IsRegular() || info.Mode().Perm()&0077 != 0 {
		t.Fatal("需要仅本人可读写的普通 JSON 配置文件")
	}
	var cfg struct {
		Isolated                                bool
		Endpoint, Pin, PhoneToken, DesktopToken string
		RestartCommand                          []string
	}
	body, err := os.ReadFile(file)
	if err != nil || len(body) > 16384 || json.Unmarshal(body, &cfg) != nil || !cfg.Isolated || len(cfg.RestartCommand) == 0 || len(cfg.PhoneToken) < 16 || len(cfg.DesktopToken) < 16 || cfg.PhoneToken == cfg.DesktopToken {
		t.Fatal("配置需要 isolated=true、两枚独立令牌和重启隔离实例的参数数组")
	}
	endpoint, err := normalizeRemoteEndpoint(cfg.Endpoint)
	digest, pinErr := hex.DecodeString(cfg.Pin)
	if err != nil || pinErr != nil || len(digest) != 32 {
		t.Fatal("HTTPS 地址或 SPKI 指纹无效")
	}
	transport := pinnedTransport(cfg.Pin)
	defer transport.CloseIdleConnections()
	peer := contractPeer{endpoint: endpoint, phone: cfg.PhoneToken, desktop: cfg.DesktopToken, client: &http.Client{Timeout: 12 * time.Second, Transport: transport, CheckRedirect: rejectRelayRedirect}}
	peer.restart = func() error {
		ctx, cancel := context.WithTimeout(context.Background(), 25*time.Second)
		defer cancel()
		command := exec.CommandContext(ctx, cfg.RestartCommand[0], cfg.RestartCommand[1:]...)
		// Output might contain deployment secrets. Do not attach it to test logs.
		return command.Run()
	}
	if err := runRelayContract(peer); err != nil {
		t.Fatal(err)
	}
	t.Log("角色、去重、结果重传、负载冲突与重启边界通过（仅此隔离实例）")
}

// Fixture only: proves the runner detects a relay that loses receipts on restart.
func TestRelayContractHarness(t *testing.T) {
	for _, loseOnRestart := range []bool{false, true} {
		t.Run(fmt.Sprint("lose-receipts-", loseOnRestart), func(t *testing.T) {
			type operation struct {
				payload             json.RawMessage
				result              json.RawMessage
				dispatched, unknown bool
				done                chan struct{}
			}
			var mu sync.Mutex
			operations := map[string]*operation{}
			server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				token := "phone-test-token-000"
				if r.URL.Path == "/v1/desktop/call" {
					token = "desktop-test-token-000"
				}
				if !authorized(r, token) {
					w.WriteHeader(401)
					return
				}
				var request struct {
					OperationID       string `json:"operationId"`
					Payload, Response json.RawMessage
				}
				if json.NewDecoder(r.Body).Decode(&request) != nil {
					w.WriteHeader(400)
					return
				}
				mu.Lock()
				switch r.URL.Path {
				case "/v1/phone/poll":
					for id, op := range operations {
						if !op.dispatched {
							op.dispatched = true
							writeJSON(w, 200, map[string]any{"operationId": id, "payload": op.payload})
							mu.Unlock()
							return
						}
					}
					mu.Unlock()
					time.Sleep(10 * time.Millisecond)
					writeJSON(w, 200, map[string]any{"operationId": nil})
				case "/v1/phone/result":
					op := operations[request.OperationID]
					if op == nil {
						mu.Unlock()
						w.WriteHeader(404)
						return
					}
					if op.result != nil && !contractEqual(op.result, request.Response) {
						mu.Unlock()
						w.WriteHeader(409)
						return
					}
					if op.result == nil {
						op.result = request.Response
						close(op.done)
					}
					mu.Unlock()
					writeJSON(w, 200, map[string]any{})
				case "/v1/desktop/call":
					op := operations[request.OperationID]
					if op == nil {
						op = &operation{payload: request.Payload, done: make(chan struct{})}
						operations[request.OperationID] = op
					}
					if !contractEqual(op.payload, request.Payload) || op.unknown {
						mu.Unlock()
						w.WriteHeader(409)
						return
					}
					done := op.done
					mu.Unlock()
					select {
					case <-done:
						mu.Lock()
						result := op.result
						mu.Unlock()
						w.Write(result)
					case <-r.Context().Done():
					}
				default:
					mu.Unlock()
					w.WriteHeader(404)
				}
			}))
			defer server.Close()
			digest := sha256.Sum256(server.Certificate().RawSubjectPublicKeyInfo)
			transport := pinnedTransport(hex.EncodeToString(digest[:]))
			defer transport.CloseIdleConnections()
			peer := contractPeer{endpoint: server.URL, phone: "phone-test-token-000", desktop: "desktop-test-token-000", client: &http.Client{Timeout: time.Second, Transport: transport, CheckRedirect: rejectRelayRedirect}}
			peer.restart = func() error {
				mu.Lock()
				defer mu.Unlock()
				if loseOnRestart {
					operations = map[string]*operation{}
				} else {
					for _, op := range operations {
						if op.result == nil {
							op.unknown = true
						}
					}
				}
				return nil
			}
			err := runRelayContract(peer)
			if (err != nil) != loseOnRestart {
				t.Fatal("runner failed to distinguish restart durability", err)
			}
		})
	}
}
