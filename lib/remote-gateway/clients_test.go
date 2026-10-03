package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
)

func createTestClient(t *testing.T, file, name, scopes string) (string, string) {
	t.Helper()
	var output bytes.Buffer
	if err := clientsCLI([]string{"create", name, scopes}, file, &output); err != nil {
		t.Fatal(err)
	}
	var created struct{ ID, Token string }
	if err := json.Unmarshal(output.Bytes(), &created); err != nil {
		t.Fatal(err)
	}
	return created.ID, created.Token
}
func scopedCall(g *gateway, token, endpoint, body string) *httptest.ResponseRecorder {
	r := httptest.NewRequest(http.MethodPost, endpoint, strings.NewReader(body))
	r.Host = "127.0.0.1:18765"
	r.Header.Set("Authorization", "Bearer "+token)
	w := httptest.NewRecorder()
	g.ServeHTTP(w, r)
	return w
}
func TestClientCreateListRevokeAndPersistence(t *testing.T) {
	file := filepath.Join(t.TempDir(), "private", "clients.json")
	id, token := createTestClient(t, file, "reader", "status,files.read,status")
	body, _ := os.ReadFile(file)
	if bytes.Contains(body, []byte(token)) {
		t.Fatal("plaintext persisted")
	}
	info, _ := os.Stat(file)
	dir, _ := os.Stat(filepath.Dir(file))
	if info.Mode().Perm() != 0600 || dir.Mode().Perm() != 0700 {
		t.Fatal("incorrect permissions")
	}
	var output bytes.Buffer
	if err := clientsCLI([]string{"list"}, file, &output); err != nil {
		t.Fatal(err)
	}
	if strings.Contains(output.String(), token) || strings.Contains(output.String(), "tokenHash") {
		t.Fatal("list exposed secret")
	}
	g := testGateway("", nil)
	g.readClients = func() (clientRegistry, error) { return readClients(file) }
	g.state.mode = "remote"
	var forwarded int
	g.callRemote = func(_ context.Context, _ config, _ []byte) (relayResponse, error) {
		forwarded++
		return relayResponse{Status: 200, Body: []byte(`{"jsonrpc":"2.0","id":1,"result":{}}`)}, nil
	}
	request := `{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"station_file_list"}}`
	if got := scopedCall(g, token, "/mcp", request); got.Code != 200 || forwarded != 1 {
		t.Fatal(got.Code, got.Body.String(), forwarded)
	}
	if err := clientsCLI([]string{"revoke", id}, file, &output); err != nil {
		t.Fatal(err)
	}
	if got := scopedCall(g, token, "/mcp", request); got.Code != 401 || forwarded != 1 {
		t.Fatal("revoked token reached phone")
	}
	if got := scopedCall(g, "test", "/mcp", request); got.Code != 200 {
		t.Fatal("owner regressed")
	}
}
func TestClientConcurrentUpdatesDoNotLoseGrants(t *testing.T) {
	file := filepath.Join(t.TempDir(), "clients.json")
	var wg sync.WaitGroup
	for i := 0; i < 12; i++ {
		wg.Add(1)
		go func(i int) { defer wg.Done(); createTestClient(t, file, fmt.Sprint(i), "status") }(i)
	}
	wg.Wait()
	value, err := readClients(file)
	if err != nil || len(value.Clients) != 12 {
		t.Fatal(err, len(value.Clients))
	}
}
func TestClientFailuresDoNotChangeRegistry(t *testing.T) {
	file := filepath.Join(t.TempDir(), "clients.json")
	createTestClient(t, file, "reader", "files.read")
	before, _ := os.ReadFile(file)
	for _, args := range [][]string{{"create", "reader", "shell"}, {"create", "other", "unknown"}, {"revoke", "absent"}} {
		if clientsCLI(args, file, &bytes.Buffer{}) == nil {
			t.Fatal("accepted invalid change")
		}
		after, _ := os.ReadFile(file)
		if !bytes.Equal(before, after) {
			t.Fatal("invalid request changed registry")
		}
	}
	if err := os.WriteFile(file, []byte(`{"version":999,"clients":[]}`), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := readClients(file); err == nil {
		t.Fatal("unsupported format accepted")
	}
}
func TestClientPreflightBeforeAnyDispatch(t *testing.T) {
	token := "ps_test"
	g := testGateway("", func(context.Context, config, []byte) (relayResponse, error) {
		t.Fatal("unauthorized dispatch")
		return relayResponse{}, nil
	})
	g.state.mode = "remote"
	g.readClients = func() (clientRegistry, error) {
		return clientRegistry{Version: 1, Clients: []clientGrant{{TokenHash: tokenDigest(token), Scopes: []string{"files.read", "status"}}}}, nil
	}
	forbidden := []string{
		`{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"station_file_delete"}}`,
		`{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"station_file_open"}}`,
		`{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"station_clipboard_state"}}`,
		`{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"station_notification_poll"}}`,
		`{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"station_operation_status"}}`,
		`{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"station_shell_exec"}}`,
		`{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"station_file_future"}}`,
		`{"jsonrpc":"2.0","id":1,"method":"resources/read"}`,
		`{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"Name":"station_file_list"}}`,
		`{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"station_file_list","name":"station_shell_exec"}}`,
		`[{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"station_file_list"}},{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"station_shell_exec"}}]`,
		`[{"jsonrpc":"2.0","id":1,"method":"ping"},{"jsonrpc":"2.0","id":1,"method":"ping"}]`,
		`{"jsonrpc":"2.0","method":"tools/call","params":{"name":"station_file_list"}}`,
	}
	for _, body := range forbidden {
		if got := scopedCall(g, token, "/mcp", body); got.Code != 403 {
			t.Fatal(got.Code, body, got.Body.String())
		}
	}
	for _, endpoint := range []string{"/__remote-call", "/__status", "/__events"} {
		if got := scopedCall(g, token, endpoint, `{"jsonrpc":"2.0","id":1,"method":"ping"}`); got.Code < 400 {
			t.Fatal("owner endpoint exposed")
		}
	}
	g.readClients = func() (clientRegistry, error) { return clientRegistry{}, errors.New("bad file") }
	if got := scopedCall(g, token, "/mcp", `{"jsonrpc":"2.0","id":1,"method":"ping"}`); got.Code != 503 {
		t.Fatal("registry failure must close access")
	}
}
func TestClientToolsListMatchesCallPermissionsLocalAndRemote(t *testing.T) {
	token := "ps_test"
	list := `{"jsonrpc":"2.0","id":"list","result":{"tools":[{"name":"station_file_list"},{"name":"station_file_delete"},{"name":"station_shell_exec"},{"name":"station_file_future"},{"name":"station_file_open"}]}}`
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := readBody(r.Body, maxBody)
		if bytes.Contains(body, []byte("local-probe")) {
			fmt.Fprint(w, pingReply)
		} else {
			fmt.Fprint(w, list)
		}
	}))
	defer server.Close()
	for _, mode := range []string{"local", "remote"} {
		g := testGateway(server.URL, func(context.Context, config, []byte) (relayResponse, error) {
			return relayResponse{Status: 200, Body: []byte(list)}, nil
		})
		g.state.mode = mode
		g.readClients = func() (clientRegistry, error) {
			return clientRegistry{Clients: []clientGrant{{TokenHash: tokenDigest(token), Scopes: []string{"files.read"}}}}, nil
		}
		got := scopedCall(g, token, "/mcp", `{"jsonrpc":"2.0","id":"list","method":"tools/list"}`)
		if got.Code != 200 || !strings.Contains(got.Body.String(), "station_file_list") || strings.Contains(got.Body.String(), "station_shell_exec") || strings.Contains(got.Body.String(), "station_file_delete") || strings.Contains(got.Body.String(), "station_file_future") || strings.Contains(got.Body.String(), "station_file_open") {
			t.Fatal(mode, got.Code, got.Body.String())
		}
	}
	c := clientGrant{Scopes: []string{"files.write"}}
	body, err := c.filtered([]byte("["+list+`,{"jsonrpc":"2.0","id":2,"result":{}}]`), []json.RawMessage{json.RawMessage(`"list"`)})
	if err != nil || strings.Contains(string(body), "station_file_list") || !strings.Contains(string(body), "station_file_delete") {
		t.Fatal(err, string(body))
	}
	if c.allows("station_file_open") || c.allows("station_operation_status") || !c.allows("station_file_create_directory") || !c.allows("station_file_truncate_bytes") {
		t.Fatal("wrong scope mapping")
	}
}

func TestClientMethodsAndResponseFailClosed(t *testing.T) {
	token := "ps_test"
	g := testGateway("", nil)
	g.readClients = func() (clientRegistry, error) {
		return clientRegistry{Clients: []clientGrant{{TokenHash: tokenDigest(token), Scopes: []string{"files.read"}}}}, nil
	}
	for _, endpoint := range []string{"/__status", "/__events", "/mcp"} {
		r := httptest.NewRequest(http.MethodGet, endpoint, nil)
		r.Host = "localhost:18765"
		r.Header.Set("Authorization", "Bearer "+token)
		w := httptest.NewRecorder()
		g.ServeHTTP(w, r)
		if endpoint == "/mcp" {
			if w.Code != 405 {
				t.Fatal(w.Code)
			}
		} else if w.Code != 401 {
			t.Fatal("owner status exposed")
		}
	}
	c := clientGrant{Scopes: []string{"files.read"}}
	if _, err := c.filtered([]byte(`{"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"station_shell_exec"}]}}`), []json.RawMessage{json.RawMessage(`1`)}); err == nil {
		t.Fatal("unexpected ID passed inventory")
	}
	for _, scope := range []string{"status", "files.read", "files.write", "shell"} {
		grant := clientGrant{Scopes: []string{scope}}
		for name, required := range clientToolScopes {
			if grant.allows(name) != (scope == required) {
				t.Fatal("scope mismatch", name, scope)
			}
		}
	}
}
