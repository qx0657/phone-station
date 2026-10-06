package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"strings"
	"time"
)

type controlHealth struct {
	Online    bool   `json:"online"`
	Protocol  string `json:"controlProtocol"`
	Connected bool   `json:"controlConnected"`
	Session   string `json:"controlSession"`
}

// Called under remoteMu. Reuse TLS for presence checks; only MCP probes enter the durable queue.
func (g *gateway) readControlHealth(ctx context.Context, value config) (controlHealth, error) {
	if g.controlStatus != nil {
		return g.controlStatus(ctx, value)
	}
	key := value.remoteProfileKey()
	if g.statusProfile != key || g.statusClient == nil {
		token, err := loadRemoteToken(value)
		if err != nil {
			return controlHealth{}, err
		}
		g.statusToken = token
		if g.statusClient != nil {
			g.statusClient.CloseIdleConnections()
		}
		g.statusClient = &http.Client{Transport: pinnedTransport(value.RemotePin), Timeout: 3 * time.Second, CheckRedirect: rejectRelayRedirect}
		g.statusProfile = key
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, strings.TrimRight(value.RemoteURL, "/")+"/v1/desktop/status", nil)
	if err != nil {
		return controlHealth{}, err
	}
	request.Header.Set("Authorization", "Bearer "+g.statusToken)
	response, err := g.statusClient.Do(request)
	if err != nil {
		return controlHealth{}, err
	}
	defer response.Body.Close()
	if response.StatusCode == http.StatusUnauthorized {
		g.statusClient.CloseIdleConnections()
		g.statusClient = nil
		g.statusToken = ""
	}
	body, err := readBody(response.Body, 16384)
	if err != nil {
		return controlHealth{}, err
	}
	body = bytes.TrimSpace(body)
	var state controlHealth
	if response.StatusCode != 200 || len(body) == 0 || body[0] != '{' || json.Unmarshal(body, &state) != nil {
		return state, errors.New("invalid control presence")
	}
	return state, nil
}
