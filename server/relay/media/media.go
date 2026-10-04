// Package media carries transient screen streams separately from durable MCP operations.
package media

import (
	"context"
	"errors"
	"net/http"
	"regexp"
	"time"

	"github.com/coder/websocket"
)

const MaxPacket = 4 << 20

var ID = regexp.MustCompile(`^[0-9a-f]{32}$`)

func Accept(w http.ResponseWriter, r *http.Request) (*websocket.Conn, error) {
	// Browser origins are unnecessary: this channel is only for authenticated native clients.
	if r.Header.Get("Origin") != "" {
		http.Error(w, "native clients only", http.StatusForbidden)
		return nil, errors.New("origin")
	}
	c, err := websocket.Accept(w, r, nil)
	if err == nil {
		c.SetReadLimit(MaxPacket)
	}
	return c, err
}

// Bridge has no application queue. A stalled destination terminates the session;
// continuing with discarded H.264 reference frames would corrupt decoding.
func Bridge(ctx context.Context, a, b *websocket.Conn) {
	ctx, cancel := context.WithTimeout(ctx, time.Hour)
	defer cancel()
	defer a.CloseNow()
	defer b.CloseNow()
	done := make(chan struct{}, 2)
	copyMessages := func(dst, src *websocket.Conn) {
		defer func() { done <- struct{}{} }()
		for {
			kind, data, err := src.Read(ctx)
			if err != nil || kind != websocket.MessageBinary {
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
	go copyMessages(a, b)
	go copyMessages(b, a)
	<-done
	cancel()
	a.CloseNow()
	b.CloseNow()
	<-done
}
