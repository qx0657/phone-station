package main

import (
	"context"
	"net/http"
	"strings"
	"time"

	"github.com/coder/websocket"
	"github.com/qx0657/phone-station/server/relay/media"
)

func (g *gateway) screen(w http.ResponseWriter, r *http.Request, value config) {
	id := strings.TrimPrefix(r.URL.Path, "/__screen/")
	if r.Method != http.MethodGet || !media.ID.MatchString(id) || r.URL.RawQuery != "" {
		http.NotFound(w, r)
		return
	}
	// Screen viewing and remote input require the full native App identity.
	if !authorized(r, value.GatewayToken) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	if value.RemoteURL == "" || value.RemotePin == "" {
		http.Error(w, "remote relay not configured", http.StatusServiceUnavailable)
		return
	}
	resolveToken := g.screenToken
	if resolveToken == nil {
		resolveToken = loadRemoteToken
	}
	token, err := resolveToken(value)
	if err != nil {
		http.Error(w, "remote credential unavailable", http.StatusServiceUnavailable)
		return
	}
	dialCtx, stop := context.WithTimeout(r.Context(), 10*time.Second)
	remote, _, err := websocket.Dial(dialCtx, strings.Replace(value.RemoteURL, "https://", "wss://", 1)+"/v1/screen/desktop/"+id, &websocket.DialOptions{
		HTTPClient: &http.Client{Transport: pinnedTransport(value.RemotePin), CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }},
		HTTPHeader: http.Header{"Authorization": []string{"Bearer " + token}},
	})
	stop()
	if err != nil {
		http.Error(w, "screen channel unavailable; relay upgrade required", http.StatusBadGateway)
		return
	}
	defer remote.CloseNow()
	remote.SetReadLimit(media.MaxPacket)
	local, err := media.Accept(w, r)
	if err != nil {
		return
	}
	media.Bridge(r.Context(), local, remote)
}
