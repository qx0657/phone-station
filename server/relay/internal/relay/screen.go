package relay

import (
	"context"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/coder/websocket"
	"github.com/qx0657/phone-station/server/relay/media"
)

type screenPair struct {
	id             string
	phone, desktop *websocket.Conn
	ready          chan struct{}
	done           chan struct{}
	paired         bool
}
type screenHub struct {
	mu      sync.Mutex
	current *screenPair
	retired map[string]time.Time
	closed  bool
}

func (s *Server) screen(w http.ResponseWriter, r *http.Request) {
	pieces := strings.Split(strings.TrimPrefix(r.URL.Path, "/v1/screen/"), "/")
	if r.Method != http.MethodGet || len(pieces) != 2 || !media.ID.MatchString(pieces[1]) || r.URL.RawQuery != "" {
		http.NotFound(w, r)
		return
	}
	role, id := pieces[0], pieces[1]
	token := s.config.DesktopToken
	if role == "phone" {
		token = s.config.PhoneToken
	} else if role != "desktop" {
		http.NotFound(w, r)
		return
	}
	if !s.authorized(w, r, token) {
		return
	}
	h := &s.screens
	h.mu.Lock()
	for previous, at := range h.retired {
		if time.Since(at) >= 24*time.Hour {
			delete(h.retired, previous)
		}
	}
	_, retired := h.retired[id]
	if h.closed || retired || (h.current != nil && h.current.id != id) || len(h.retired) >= 4096 {
		h.mu.Unlock()
		http.Error(w, "screen unavailable or session ended", http.StatusConflict)
		return
	}
	if h.current == nil {
		h.current = &screenPair{id: id, ready: make(chan struct{}), done: make(chan struct{})}
	}
	p := h.current
	if (role == "phone" && p.phone != nil) || (role == "desktop" && p.desktop != nil) {
		h.mu.Unlock()
		http.Error(w, "role already connected", http.StatusConflict)
		return
	}
	c, err := media.Accept(w, r)
	if err != nil {
		if p.phone == nil && p.desktop == nil {
			h.current = nil
		}
		h.mu.Unlock()
		return
	}
	if role == "phone" {
		p.phone = c
	} else {
		p.desktop = c
	}
	if p.phone != nil && p.desktop != nil && !p.paired {
		p.paired = true
		close(p.ready)
		go func() {
			ctx, stop := context.WithTimeout(context.Background(), 5*time.Second)
			err := p.phone.Write(ctx, websocket.MessageBinary, nil)
			stop()
			if err == nil {
				media.Bridge(context.Background(), p.phone, p.desktop)
			}
			h.finish(p)
		}()
	}
	h.mu.Unlock()
	timer := time.NewTimer(20 * time.Second)
	defer timer.Stop()
	select {
	case <-p.ready:
		<-p.done
	case <-p.done:
	case <-timer.C:
		h.finish(p)
	case <-r.Context().Done():
		h.finish(p)
	}
}
func (h *screenHub) finish(p *screenPair) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.current != p {
		return
	}
	if p.phone != nil {
		p.phone.CloseNow()
	}
	if p.desktop != nil {
		p.desktop.CloseNow()
	}
	if h.retired == nil {
		h.retired = map[string]time.Time{}
	}
	h.retired[p.id] = time.Now()
	h.current = nil
	close(p.done)
}
func (h *screenHub) close() {
	h.mu.Lock()
	h.closed = true
	p := h.current
	h.mu.Unlock()
	if p != nil {
		h.finish(p)
	}
}
