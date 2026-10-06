package relay

import (
	"context"
	"encoding/json"
	"net/http"
	"sync"
	"time"
	"unicode/utf8"

	"github.com/coder/websocket"
)

// Only invalidations and subscription leases cross this channel, never clipboard or notification bodies.
type eventSubscription struct {
	RequestID     string `json:"requestId,omitempty"`
	Type          string `json:"type"`
	ID            string `json:"subscriptionId"`
	Clipboard     string `json:"clipboard"`
	Notifications string `json:"notifications"`
}
type eventPeer struct {
	conn         *websocket.Conn
	out          chan any
	subscription eventSubscription
}
type eventHub struct {
	mu      sync.Mutex
	peer    *eventPeer
	changed chan struct{}
	closed  bool
}

func (h *eventHub) signalLocked() { close(h.changed); h.changed = make(chan struct{}) }
func (h *eventHub) snapshot() (eventSubscription, <-chan struct{}) {
	h.mu.Lock()
	defer h.mu.Unlock()
	sub := eventSubscription{Type: "subscribe"}
	if h.peer != nil {
		sub = h.peer.subscription
	}
	return sub, h.changed
}
func (h *eventHub) publish(frame map[string]any) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if p := h.peer; p != nil {
		id, _ := frame["subscriptionId"].(string)
		if id != p.subscription.ID {
			return
		}
		if frame["type"] == "event" {
			if (frame["topic"] == "clipboard" && p.subscription.Clipboard == "") || (frame["topic"] == "notifications" && p.subscription.Notifications == "") {
				return
			}
		}
		select {
		case p.out <- frame:
		default:
			p.conn.CloseNow()
		} // reconnect and reconcile instead of dropping unnoticed
	}
}
func (h *eventHub) reset() {
	h.mu.Lock()
	defer h.mu.Unlock()
	if p := h.peer; p != nil {
		select {
		case p.out <- map[string]string{"type": "reset"}:
		default:
			p.conn.CloseNow()
		}
	}
}
func (h *eventHub) close() {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.closed = true
	if h.peer != nil {
		h.peer.conn.CloseNow()
	}
	h.signalLocked()
}

func (s *Server) events(w http.ResponseWriter, r *http.Request) {
	h := &s.eventsHub
	h.mu.Lock()
	if h.closed {
		h.mu.Unlock()
		writeError(w, 503, "relay closed")
		return
	}
	c, err := websocket.Accept(w, r, &websocket.AcceptOptions{CompressionMode: websocket.CompressionDisabled})
	if err != nil {
		h.mu.Unlock()
		return
	}
	if h.peer != nil {
		h.peer.conn.CloseNow()
	}
	p := &eventPeer{conn: c, out: make(chan any, 16), subscription: eventSubscription{Type: "subscribe"}}
	h.peer = p
	h.signalLocked()
	h.mu.Unlock()
	defer c.CloseNow()
	defer func() {
		h.mu.Lock()
		if h.peer == p {
			h.peer = nil
			h.signalLocked()
		}
		h.mu.Unlock()
	}()
	c.SetReadLimit(4096)
	ctx, cancel := context.WithCancel(r.Context())
	defer cancel()
	messages := make(chan []byte, 1)
	go func() {
		defer cancel()
		for {
			readCtx, stop := context.WithTimeout(ctx, phoneFresh)
			kind, data, err := c.Read(readCtx)
			stop()
			if err != nil || kind != websocket.MessageText {
				return
			}
			select {
			case messages <- data:
			case <-ctx.Done():
				return
			}
		}
	}()
	ticker := time.NewTicker(20 * time.Second)
	defer ticker.Stop()
	write := func(value any) bool {
		data, _ := json.Marshal(value)
		writeCtx, stop := context.WithTimeout(ctx, 5*time.Second)
		defer stop()
		return c.Write(writeCtx, websocket.MessageText, data) == nil
	}
	if !write(map[string]string{"type": "ready", "protocol": "events-v1"}) {
		return
	}
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			if !write(map[string]string{"type": "heartbeat"}) {
				return
			}
		case frame := <-p.out:
			if !write(frame) {
				return
			}
		case data := <-messages:
			if !utf8.Valid(data) || !unambiguousJSON(data) {
				return
			}
			var input eventSubscription
			if json.Unmarshal(data, &input) != nil {
				return
			}
			if input.Type == "heartbeat" {
				continue
			}
			if input.Type != "subscribe" || len(input.Clipboard) > 100 || len(input.Notifications) > 128 || len(input.RequestID) > 128 {
				return
			}
			id, err := randomEpoch()
			if err != nil {
				return
			}
			input.Type, input.ID = "subscribe", id
			h.mu.Lock()
			if h.peer != p {
				h.mu.Unlock()
				return
			}
			p.subscription = input
			select {
			case p.out <- map[string]string{"type": "subscribing", "subscriptionId": id, "requestId": input.RequestID}:
			default:
				h.mu.Unlock()
				return
			}
			h.signalLocked()
			h.mu.Unlock()
		}
	}
}
