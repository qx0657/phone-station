package stationd

import (
	"bufio"
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"os"
	"strings"
)

// GatewayCredential is the local MCP endpoint. The token stays in the loader
// and is not written back to the client.
type GatewayCredential struct {
	Endpoint string
	Token    string
}

// LoadGatewayFile reads the gateway token from the private config file.
// It does not print the token. The endpoint is the local gateway.
func LoadGatewayFile(path string) (GatewayCredential, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return GatewayCredential{}, err
	}
	var file struct {
		GatewayToken string `json:"gatewayToken"`
	}
	if json.Unmarshal(raw, &file) != nil || file.GatewayToken == "" {
		return GatewayCredential{}, errors.New("mcp")
	}
	return GatewayCredential{Endpoint: "http://127.0.0.1:18765/mcp", Token: file.GatewayToken}, nil
}

// ServeMCP is the stdio entry for station mcp. It reads one JSON-RPC message
// per line, forwards it to the local gateway, and writes the response.
// The token is sent only on the gateway request.
func ServeMCP(r io.Reader, w io.Writer, load func() (GatewayCredential, error)) error {
	if load == nil {
		return errors.New("mcp")
	}
	cred, err := load()
	if err != nil || cred.Endpoint == "" || cred.Token == "" {
		return errors.New("mcp")
	}
	client := &http.Client{}
	scanner := bufio.NewScanner(r)
	scanner.Buffer(make([]byte, 0, 64*1024), 1<<20)
	out := bufio.NewWriter(w)
	for scanner.Scan() {
		line := bytes.TrimSpace(scanner.Bytes())
		if len(line) == 0 {
			continue
		}
		body, err := forwardMCP(client, cred, line)
		if err != nil {
			return err
		}
		if bytes.Contains(body, []byte(cred.Token)) {
			return errors.New("mcp")
		}
		if _, err := out.Write(body); err != nil {
			return err
		}
		if err := out.WriteByte('\n'); err != nil {
			return err
		}
		if err := out.Flush(); err != nil {
			return err
		}
	}
	return scanner.Err()
}

func forwardMCP(client *http.Client, cred GatewayCredential, line []byte) ([]byte, error) {
	endpoint := strings.TrimRight(cred.Endpoint, "/")
	request, err := http.NewRequest(http.MethodPost, endpoint, bytes.NewReader(line))
	if err != nil {
		return nil, err
	}
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("Authorization", "Bearer "+cred.Token)
	response, err := client.Do(request)
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()
	body, err := io.ReadAll(io.LimitReader(response.Body, 1<<20))
	if err != nil {
		return nil, err
	}
	if !json.Valid(body) {
		return nil, errors.New("mcp")
	}
	return body, nil
}
