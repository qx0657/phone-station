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
