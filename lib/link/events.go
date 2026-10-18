package link

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
)

// LinkEvent is one pushed clipboard, notification, state, or op update.
type LinkEvent struct {
	Type   string
	Topic  string
	Cursor string
	Op     string
	State  string
	Body   []byte
}

// Subscribed is the phone's reply to subscribe.
type Subscribed struct {
	Topics   []string
	Rejected []string
	Reasons  []string
}

// Subscribe asks for topics on this session. Later events stay readable while a call is running.
func (s *LocalSession) Subscribe(ctx context.Context, topics []string, cursors map[string]string) (Subscribed, error) {
	if s.Ended() {
		return Subscribed{}, errors.New("ended")
	}
	payload, err := subscribePayload(topics, cursors)
	if err != nil {
		return Subscribed{}, err
	}
	if err := s.ensureSeal(); err != nil {
		return Subscribed{}, err
	}
	s.startInbox()
	wait := make(chan []byte, 1)
	s.inbox.mu.Lock()
	s.inbox.sub = wait
	s.inbox.mu.Unlock()
	if err := s.Send(payload); err != nil {
		s.inbox.mu.Lock()
		if s.inbox.sub == wait {
			s.inbox.sub = nil
		}
		s.inbox.mu.Unlock()
		return Subscribed{}, err
	}
	select {
	case <-ctx.Done():
		s.inbox.mu.Lock()
		if s.inbox.sub == wait {
			s.inbox.sub = nil
		}
		s.inbox.mu.Unlock()
		return Subscribed{}, ctx.Err()
	case raw, ok := <-wait:
		if !ok {
			return Subscribed{}, errors.New("ended")
		}
		var msg wireMsg
		if json.Unmarshal(raw, &msg) != nil || msg.Type != "subscribed" {
			return Subscribed{}, errors.New("subscribed")
		}
		return Subscribed{Topics: msg.Topics, Rejected: msg.Rejected, Reasons: msg.Reasons}, nil
	}
}

// NextEvent returns the next event or reset. Events that arrived during a call are kept.
func (s *LocalSession) NextEvent(ctx context.Context) (LinkEvent, error) {
	if s.Ended() && len(s.inbox.events) == 0 {
		return LinkEvent{}, errors.New("ended")
	}
	s.startInbox()
	for {
		s.inbox.mu.Lock()
		if len(s.inbox.events) > 0 {
			ev := s.inbox.events[0]
			s.inbox.events = s.inbox.events[1:]
			s.inbox.mu.Unlock()
			return ev, nil
		}
		err := s.inbox.err
		wake := s.inbox.eventWake
		s.inbox.mu.Unlock()
		if err != nil {
			return LinkEvent{}, err
		}
		select {
		case <-ctx.Done():
			return LinkEvent{}, ctx.Err()
		case <-wake:
		}
	}
}

func subscribePayload(topics []string, cursors map[string]string) ([]byte, error) {
	if len(topics) == 0 || len(topics) > 8 {
		return nil, errors.New("subscribe")
	}
	var text strings.Builder
	text.WriteString(`{"type":"subscribe","topics":[`)
	seen := map[string]bool{}
	written := 0
	for _, topic := range topics {
		if !topicName(topic) || seen[topic] {
			return nil, errors.New("subscribe")
		}
		seen[topic] = true
		if written > 0 {
			text.WriteByte(',')
		}
		written++
		text.WriteByte('"')
		text.WriteString(topic)
		text.WriteByte('"')
	}
	text.WriteByte(']')
	if len(cursors) > 0 {
		text.WriteString(`,"cursors":{`)
		first := true
		for _, topic := range topics {
			cursor, ok := cursors[topic]
			if !ok {
				continue
			}
			if !cursorText(cursor) {
				return nil, errors.New("subscribe")
			}
			if !first {
				text.WriteByte(',')
			}
			first = false
			text.WriteByte('"')
			text.WriteString(topic)
			text.WriteString(`":"`)
			text.WriteString(cursor)
			text.WriteByte('"')
		}
		text.WriteByte('}')
	}
	text.WriteByte('}')
	return []byte(text.String()), nil
}

func topicName(topic string) bool {
	if len(topic) < 1 || len(topic) > 32 {
		return false
	}
	for i := 0; i < len(topic); i++ {
		if topic[i] < 'a' || topic[i] > 'z' {
			return false
		}
	}
	return true
}

func cursorText(cursor string) bool {
	if cursor == "0" {
		return true
	}
	if len(cursor) < 1 || len(cursor) > 18 || cursor[0] < '1' || cursor[0] > '9' {
		return false
	}
	for i := 1; i < len(cursor); i++ {
		if cursor[i] < '0' || cursor[i] > '9' {
			return false
		}
	}
	return true
}
