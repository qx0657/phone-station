package link

import (
	"errors"
	"time"
)

// Link is one connection. It owns the keepalive and pulls the next frame across sessions,
// so one session's file bytes do not hold another session's control message.
type Link struct {
	sessions []*Endpoint
	pings    []Frame
	lastIn   time.Time
	lastOut  time.Time
}

func NewLink(start time.Time, sessions ...*Endpoint) *Link {
	return &Link{sessions: sessions, lastIn: start, lastOut: start}
}

// Advance sends one JSON ping for the whole connection, or reports that it has gone quiet.
func (l *Link) Advance(now time.Time) error {
	send, dead := Heartbeat(l.lastIn, l.lastOut, now)
	if dead {
		return errors.New("dead")
	}
	if !send {
		return nil
	}
	l.pings = append(l.pings, Frame{Kind: KindLink, Payload: append([]byte(nil), pingPayload...)})
	l.lastOut = now
	return nil
}

// Pull returns a pending ping, or the highest-priority frame of any session.
func (l *Link) Pull() (Frame, bool) {
	if len(l.pings) > 0 {
		frame := l.pings[0]
		l.pings = l.pings[1:]
		return frame, true
	}
	var chosen *Endpoint
	best := 0
	for _, session := range l.sessions {
		priority, ok := session.Head()
		if !ok {
			continue
		}
		if chosen == nil || priority < best {
			chosen = session
			best = priority
		}
	}
	if chosen == nil {
		return Frame{}, false
	}
	return chosen.Pull()
}
