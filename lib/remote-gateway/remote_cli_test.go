package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestRemoteCLIUsesRelayOnceAndReturnsRPCBody(t *testing.T) {
	var out bytes.Buffer
	const body = `{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"station_shell_exec"}}`
	calls := 0
	err := runRemoteCLI(config{RemoteURL: "https://relay", RemotePin: "pin", LocalToken: "unused"},
		strings.NewReader(body), &out, func(ctx context.Context, value config, got []byte) (relayResponse, error) {
			calls++
			if string(got) != body || value.RemoteURL != "https://relay" {
				t.Fatal("remote operation changed")
			}
			return relayResponse{Status: 200, Body: json.RawMessage(`{"jsonrpc":"2.0","id":4,"result":{}}`)}, nil
		})
	if err != nil || calls != 1 || out.String() != "{\"jsonrpc\":\"2.0\",\"id\":4,\"result\":{}}\n" {
		t.Fatalf("err=%v calls=%d out=%s", err, calls, out.String())
	}
}

func TestBusyRelayRetainsOperationID(t *testing.T) {
	const body = `{"operationId":"fixed-id","payload":{"method":"tools/call"}}`
	calls := 0
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		payload, _ := io.ReadAll(r.Body)
		if string(payload) != body || r.Header.Get("Authorization") != "Bearer test" {
			t.Error("operation changed while waiting")
		}
		calls++
		if calls == 1 {
			w.WriteHeader(http.StatusTooManyRequests)
			return
		}
		_, _ = w.Write([]byte(`{"status":200,"body":{}}`))
	}))
	defer server.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	response, err := postRelayCall(ctx, server.Client(), server.URL, "test", []byte(body))
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	if calls != 2 || response.StatusCode != 200 {
		t.Fatalf("calls=%d status=%d", calls, response.StatusCode)
	}
}

func TestRemoteCLIRejectsInvalidInputWithoutDispatch(t *testing.T) {
	for _, body := range []string{"null", "[]", "{}", `{"jsonrpc":"2.0","method":"ping"}`, "bad"} {
		err := runRemoteCLI(config{RemoteURL: "https://relay", RemotePin: "pin"}, strings.NewReader(body),
			&bytes.Buffer{}, func(context.Context, config, []byte) (relayResponse, error) {
				t.Fatal("invalid input dispatched")
				return relayResponse{}, nil
			})
		if err == nil {
			t.Fatalf("accepted %s", body)
		}
	}
}

func TestRemoteCLIUnknownOutcomeIsNotResubmitted(t *testing.T) {
	calls := 0
	err := runRemoteCLI(config{RemoteURL: "https://relay", RemotePin: "pin"},
		strings.NewReader(`{"jsonrpc":"2.0","id":1,"method":"ping"}`), &bytes.Buffer{},
		func(context.Context, config, []byte) (relayResponse, error) {
			calls++
			return relayResponse{}, errors.New("lost reply")
		})
	if err == nil || calls != 1 {
		t.Fatalf("err=%v calls=%d", err, calls)
	}
}

func TestUnifiedCLIUsesGatewayWithoutRemoteProfile(t *testing.T) {
	const secret = "private-local-token"
	const body = `{"jsonrpc":"2.0","id":4,"method":"tools/list"}`
	calls := 0
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		if r.URL.Path != "/mcp" || r.Header.Get("Authorization") != "Bearer "+secret ||
			r.Header.Get("X-Phone-Station-Remote-Profile") != "" {
			t.Error("unified route or authorization changed")
		}
		_, _ = w.Write([]byte(`{"jsonrpc":"2.0","id":4,"result":{}}`))
	}))
	defer server.Close()
	var out bytes.Buffer
	err := runRPCCLI(config{GatewayToken: secret}, strings.NewReader(body), &out,
		func(ctx context.Context, value config, body []byte) (relayResponse, error) {
			return gatewayViaHTTP(ctx, server.URL+"/mcp", value, body, false)
		})
	if err != nil || calls != 1 || strings.Contains(out.String(), secret) {
		t.Fatalf("err=%v calls=%d out=%s", err, calls, out.String())
	}
}

func TestCLIRejectsRedirectAndMismatchedResponseWithoutReplay(t *testing.T) {
	for _, redirect := range []bool{false, true} {
		calls := 0
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			calls++
			if redirect {
				http.Redirect(w, r, "/other", http.StatusTemporaryRedirect)
			} else {
				_, _ = w.Write([]byte(`{"jsonrpc":"2.0","id":99,"result":{}}`))
			}
		}))
		var out bytes.Buffer
		err := runRPCCLI(config{GatewayToken: "secret"},
			strings.NewReader(`{"jsonrpc":"2.0","id":4,"method":"tools/call"}`), &out,
			func(ctx context.Context, value config, body []byte) (relayResponse, error) {
				return gatewayViaHTTP(ctx, server.URL+"/mcp", value, body, false)
			})
		server.Close()
		if err == nil || calls != 1 || out.Len() != 0 {
			t.Fatalf("redirect=%v err=%v calls=%d out=%s", redirect, err, calls, out.String())
		}
	}
}

func TestCLIResponseIDsPreserveTypePrecisionAndStringMeaning(t *testing.T) {
	for _, pair := range [][2]string{{`"ab"`, `"a\u0062"`}, {`9007199254740993`, `9007199254740993`}} {
		if !sameRPCID(json.RawMessage(pair[0]), json.RawMessage(pair[1])) {
			t.Fatalf("equivalent IDs rejected: %v", pair)
		}
	}
	for _, pair := range [][2]string{{`1`, `"1"`}, {`9007199254740993`, `9007199254740992`}, {`null`, `null`}} {
		if sameRPCID(json.RawMessage(pair[0]), json.RawMessage(pair[1])) {
			t.Fatalf("different/invalid IDs accepted: %v", pair)
		}
	}
}
