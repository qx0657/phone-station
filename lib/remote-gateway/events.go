package main

import (
	"context"
	"net/http"
	"strings"
	"time"

	"github.com/coder/websocket"
)

func (g *gateway) subscriptions(w http.ResponseWriter, r *http.Request, value config) {
	if r.Method != http.MethodGet || r.URL.RawQuery != "" || len(r.Header.Values("Origin")) != 0 {
		http.Error(w, "native event stream only", 403)
		return
	}
	if !authorized(r, value.GatewayToken) {
		http.Error(w, "unauthorized", 401)
		return
	}
	resolve := g.screenToken
	if resolve == nil {
		resolve = loadRemoteToken
	}
	token, err := resolve(value)
	if err != nil || value.RemoteURL == "" || value.RemotePin == "" {
		http.Error(w, "remote unavailable", 503)
		return
	}
	transport := pinnedTransport(value.RemotePin)
	defer transport.CloseIdleConnections()
	dialCtx, stop := context.WithTimeout(r.Context(), 10*time.Second)
	remote, _, err := websocket.Dial(dialCtx, strings.Replace(strings.TrimRight(value.RemoteURL, "/"), "https://", "wss://", 1)+"/v1/desktop/events", &websocket.DialOptions{
		HTTPClient: &http.Client{Transport: transport, CheckRedirect: rejectRelayRedirect}, HTTPHeader: http.Header{"Authorization": []string{"Bearer " + token}},
	})
	stop()
	if err != nil {
		http.Error(w, "event stream unavailable", 502)
		return
	}
	defer remote.CloseNow()
	local, err := websocket.Accept(w, r, &websocket.AcceptOptions{CompressionMode: websocket.CompressionDisabled})
	if err != nil {
		return
	}
	defer local.CloseNow()
	local.SetReadLimit(4096)
	remote.SetReadLimit(4096)
	ctx, cancel := context.WithCancel(r.Context())
	defer cancel()
	done := make(chan struct{}, 2)
	copyMessages := func(dst, src *websocket.Conn) {
		defer func() { done <- struct{}{} }()
		for {
			readCtx, stop := context.WithTimeout(ctx, 45*time.Second)
			kind, data, err := src.Read(readCtx)
			stop()
			if err != nil || kind != websocket.MessageText {
				return
			}
			writeCtx, stop := context.WithTimeout(ctx, 5*time.Second)
			err = dst.Write(writeCtx, kind, data)
			stop()
			if err != nil {
				return
			}
		}
	}
	go copyMessages(local, remote)
	go copyMessages(remote, local)
	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()
loop:
	for {
		select {
		case <-done:
			break loop
		case <-ctx.Done():
			<-done
			break loop
		case <-ticker.C:
			current, err := g.config()
			if err != nil || current.remoteProfileKey() != value.remoteProfileKey() || current.GatewayToken != value.GatewayToken {
				cancel()
				<-done
				break loop
			}
		}
	}
	cancel()
	local.CloseNow()
	remote.CloseNow()
	<-done
}
