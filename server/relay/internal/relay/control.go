package relay

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"time"
	"unicode/utf8"

	"github.com/coder/websocket"
)

var errControlMessage = errors.New("control requires text messages")

// Control has its own socket; screen traffic never shares this queue.
func (s *Server) control(w http.ResponseWriter, r *http.Request) {
	s.controlWithTiming(w, r, 20*time.Second, phoneFresh)
}

func (s *Server) controlWithTiming(w http.ResponseWriter, r *http.Request, heartbeat, freshness time.Duration) {
	session, err := randomEpoch()
	if err != nil {
		writeError(w, 503, "cannot create control session")
		return
	}
	d := s.device
	d.mu.Lock()
	if !s.available(w) {
		d.mu.Unlock()
		return
	}
	c, err := websocket.Accept(w, r, &websocket.AcceptOptions{CompressionMode: websocket.CompressionDisabled})
	if err != nil {
		d.mu.Unlock()
		return
	}
	generation, replaced := s.claimPhoneLocked(true)
	d.controlSession = session
	d.mu.Unlock()
	ctx, cancel := context.WithCancel(r.Context())
	defer cancel()
	defer c.CloseNow()
	defer func() {
		d.mu.Lock()
		current := d.pollGeneration == generation
		if current {
			d.polling, d.control = false, false
			d.pollCancel = nil
			d.controlSession = ""
			d.lastSeen = time.Time{}
		}
		d.mu.Unlock()
		if current {
			s.eventsHub.reset()
		}
	}()
	c.SetReadLimit(maxBodyBytes)
	type incoming struct {
		data []byte
		err  error
	}
	messages := make(chan incoming, 1)
	go func() {
		for {
			readCtx, stop := context.WithTimeout(ctx, freshness)
			kind, data, err := c.Read(readCtx)
			stop()
			if err == nil && kind != websocket.MessageText {
				err = errControlMessage
			}
			select {
			case messages <- incoming{data, err}:
			case <-ctx.Done():
				return
			}
			if err != nil {
				return
			}
		}
	}()
	write := func(value any) bool {
		data, err := json.Marshal(value)
		if err != nil || len(data) > maxBodyBytes {
			return false
		}
		writeCtx, stop := context.WithTimeout(ctx, 10*time.Second)
		defer stop()
		return c.Write(writeCtx, websocket.MessageText, data) == nil
	}
	if !write(map[string]any{"type": "ready", "protocol": "control-v1"}) {
		return
	}
	ticker := time.NewTicker(heartbeat)
	defer ticker.Stop()
	lastSubscription := ""
	for {
		subscription, subscriptionsChanged := s.eventsHub.snapshot()
		if subscription.ID != lastSubscription {
			if !write(subscription) {
				return
			}
			lastSubscription = subscription.ID
		}
		d.mu.Lock()
		if d.closed || (s.store != nil && s.store.err != nil) || generation != d.pollGeneration {
			d.mu.Unlock()
			return
		}
		op, err := s.nextOperationLocked()
		changed := d.changed
		d.mu.Unlock()
		if err != nil {
			return
		}
		if op != nil && !write(map[string]any{"type": "operation", "operationId": op.OperationID, "payload": op.Payload}) {
			return
		}
		select {
		case <-ctx.Done():
			return
		case <-replaced:
			return
		case <-subscriptionsChanged:
		case <-changed:
		case <-ticker.C:
			if subscription.ID != "" && !write(subscription) {
				return
			}
			if !write(map[string]string{"type": "heartbeat"}) {
				return
			}
		case message := <-messages:
			if message.err != nil || !utf8.Valid(message.data) || !unambiguousJSON(message.data) {
				return
			}
			var frame struct {
				Type           string `json:"type"`
				SubscriptionID string `json:"subscriptionId"`
				Topic          string `json:"topic"`
				Clipboard      bool   `json:"clipboard"`
				Notifications  bool   `json:"notifications"`
				resultRequest
			}
			if json.Unmarshal(message.data, &frame) != nil {
				return
			}
			// An obsolete socket cannot refresh the new network's presence.
			d.mu.Lock()
			current := generation == d.pollGeneration && !d.closed
			if current {
				d.lastSeen = time.Now()
			}
			d.mu.Unlock()
			if !current {
				return
			}
			switch frame.Type {
			case "event":
				if frame.Topic != "clipboard" && frame.Topic != "notifications" {
					return
				}
				s.eventsHub.publish(map[string]any{"type": "event", "subscriptionId": frame.SubscriptionID, "topic": frame.Topic})
			case "subscribed":
				s.eventsHub.publish(map[string]any{"type": "subscribed", "subscriptionId": frame.SubscriptionID, "clipboard": frame.Clipboard, "notifications": frame.Notifications})
			case "heartbeat":
			case "result":
				status, _ := s.acceptResult(frame.resultRequest)
				if !write(map[string]any{"type": "result", "operationId": frame.OperationID, "status": status}) || status == 503 {
					return
				}
			default:
				return
			}
		}
	}
}
