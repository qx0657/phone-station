package stationd

import (
	"bytes"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
)

func TestStationMCPForwardsWithoutEchoingToken(t *testing.T) {
	const token = "gateway-token-not-for-stdout"
	var gotAuth string
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotAuth = r.Header.Get("Authorization")
		body, _ := io.ReadAll(r.Body)
		if !bytes.Contains(body, []byte(`"method":"initialize"`)) {
			t.Errorf("body %s", body)
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2026-07-28","serverInfo":{"name":"手机工位"}}}`))
	}))
	defer upstream.Close()
	var out bytes.Buffer
	err := ServeMCP(strings.NewReader("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}\n"), &out, func() (GatewayCredential, error) {
		return GatewayCredential{Endpoint: upstream.URL, Token: token}, nil
	})
	if err != nil {
		t.Fatal(err)
	}
	if gotAuth != "Bearer "+token {
		t.Fatal(gotAuth)
	}
	if !bytes.Contains(out.Bytes(), []byte("2026-07-28")) || bytes.Contains(out.Bytes(), []byte(token)) {
		t.Fatal(out.String())
	}
	dir := t.TempDir()
	path := dir + "/remote.json"
	if err := os.WriteFile(path, []byte(`{"gatewayToken":"abc"}`), 0o600); err != nil {
		t.Fatal(err)
	}
	loaded, err := LoadGatewayFile(path)
	if err != nil || loaded.Token != "abc" || loaded.Endpoint != "http://127.0.0.1:18765/mcp" {
		t.Fatal(err, loaded)
	}
}
