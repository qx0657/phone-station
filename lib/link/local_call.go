package link

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"sync"
	"time"
)

type wireMsg struct {
	Type     string            `json:"type"`
	Op       string            `json:"op"`
	Method   string            `json:"method,omitempty"`
	Params   string            `json:"params,omitempty"`
	State    string            `json:"state,omitempty"`
	Result   string            `json:"result,omitempty"`
	Topic    string            `json:"topic,omitempty"`
	Cursor   string            `json:"cursor,omitempty"`
	Body     string            `json:"body,omitempty"`
	Topics   []string          `json:"topics,omitempty"`
	Ops      []string          `json:"ops,omitempty"`
	Rejected []string          `json:"rejected,omitempty"`
	Reasons  []string          `json:"reasons,omitempty"`
	Cursors  map[string]string `json:"cursors,omitempty"`
	Name     string            `json:"name,omitempty"`
	Size     int               `json:"size,omitempty"`
	Channel  int               `json:"channel,omitempty"`
}

// Call sends one method and waits for its result. The phone may answer while the work is still running.
func (s *LocalSession) Call(ctx context.Context, op, method string, params []byte) (string, []byte, error) {
	if s.Ended() {
		return "", nil, errors.New("ended")
	}
	payload, err := json.Marshal(wireMsg{Type: "call", Op: op, Method: method, Params: base64.StdEncoding.EncodeToString(params)})
	if err != nil {
		return "", nil, err
	}
	deadline := time.Now().Add(4 * time.Minute)
	if ctxDeadline, ok := ctx.Deadline(); ok && ctxDeadline.Before(deadline) {
		deadline = ctxDeadline
	}
	if err := s.io.c.SetReadDeadline(deadline); err != nil {
		return "", nil, err
	}
	if err := s.ensureSeal(); err != nil {
		_ = s.io.c.SetReadDeadline(time.Time{})
		return "", nil, err
	}
	_ = s.io.c.SetReadDeadline(time.Time{})
	s.startInbox()
	wait := s.waitResult(op)
	if err := s.Send(payload); err != nil {
		s.cancelWait(op, wait)
		return "", nil, err
	}
	select {
	case <-ctx.Done():
		s.cancelWait(op, wait)
		return "", nil, ctx.Err()
	case msg, ok := <-wait:
		if !ok {
			return "", nil, errors.New("ended")
		}
		var body []byte
		if msg.Result != "" {
			body, err = base64.StdEncoding.DecodeString(msg.Result)
			if err != nil {
				return "", nil, err
			}
		}
		return msg.State, body, nil
	}
}

// QueryReply is one receipt the phone already stored.
type QueryReply struct {
	Op    string
	State string
	Body  []byte
}

// Query reads receipts by their original ids. It does not submit the work again.
func (s *LocalSession) Query(ctx context.Context, ops []string) ([]QueryReply, error) {
	if s.Ended() || len(ops) == 0 || len(ops) > 64 {
		return nil, errors.New("query")
	}
	for _, op := range ops {
		if !opID(op) {
			return nil, errors.New("query")
		}
	}
	payload, err := json.Marshal(wireMsg{Type: "query", Ops: ops})
	if err != nil {
		return nil, err
	}
	if err := s.ensureSeal(); err != nil {
		return nil, err
	}
	s.startInbox()
	waiters := make([]chan wireMsg, len(ops))
	for i, op := range ops {
		waiters[i] = s.waitResult(op)
	}
	if err := s.Send(payload); err != nil {
		for i, op := range ops {
			s.cancelWait(op, waiters[i])
		}
		return nil, err
	}
	replies := make([]QueryReply, len(ops))
	for i, op := range ops {
		select {
		case <-ctx.Done():
			for j := i; j < len(ops); j++ {
				s.cancelWait(ops[j], waiters[j])
			}
			return nil, ctx.Err()
		case msg, ok := <-waiters[i]:
			if !ok {
				return nil, errors.New("ended")
			}
			var body []byte
			if msg.Result != "" {
				body, err = base64.StdEncoding.DecodeString(msg.Result)
				if err != nil {
					return nil, err
				}
			}
			replies[i] = QueryReply{Op: op, State: msg.State, Body: body}
		}
	}
	return replies, nil
}

func opID(op string) bool {
	if len(op) != 32 {
		return false
	}
	for _, c := range op {
		if (c < '0' || c > '9') && (c < 'a' || c > 'f') {
			return false
		}
	}
	return true
}

// Respond reads calls and writes results until the session ends. Tests use it as the phone side.
func (s *LocalSession) Respond(handle func(op, method string, params []byte) (string, []byte)) error {
	for {
		messages, err := s.readMessages()
		if err != nil {
			return err
		}
		for _, raw := range messages {
			var msg wireMsg
			if json.Unmarshal(raw, &msg) != nil || msg.Type != "call" {
				continue
			}
			params, err := base64.StdEncoding.DecodeString(msg.Params)
			if err != nil {
				return err
			}
			state, body := handle(msg.Op, msg.Method, params)
			reply := wireMsg{Type: "result", Op: msg.Op, State: state}
			if body != nil {
				reply.Result = base64.StdEncoding.EncodeToString(body)
			}
			encoded, err := json.Marshal(reply)
			if err != nil {
				return err
			}
			if err := s.Send(encoded); err != nil {
				return err
			}
		}
	}
}

func (s *LocalSession) readMessages() ([][]byte, error) {
	chunks, err := s.readChunks()
	if err != nil {
		return nil, err
	}
	out := make([][]byte, 0, len(chunks))
	for _, chunk := range chunks {
		out = append(out, chunk.payload)
	}
	return out, nil
}

type readChunk struct {
	channel uint32
	payload []byte
}

func (s *LocalSession) readChunks() ([]readChunk, error) {
	frame, err := s.io.ReadFrame()
	if err != nil {
		s.markEnded()
		return nil, err
	}
	s.engineMu.Lock()
	defer s.engineMu.Unlock()
	if err := s.engine.Accept(frame); err != nil && fatalLink(err) {
		s.markEnded()
		return nil, err
	}
	chunks := s.engine.Inbox()
	if err := s.flush(); err != nil {
		return nil, err
	}
	out := make([]readChunk, 0, len(chunks))
	for _, chunk := range chunks {
		raw := append([]byte(nil), chunk.Payload...)
		if s.codec != nil {
			used, sealErr := s.takeSeal(raw)
			if sealErr != nil {
				s.markEnded()
				return nil, sealErr
			}
			if used {
				continue
			}
		}
		out = append(out, readChunk{channel: chunk.Channel, payload: raw})
	}
	return out, nil
}

type sessionInbox struct {
	mu             sync.Mutex
	waiters        map[string]chan wireMsg
	sub            chan []byte
	share          chan wireMsg
	events         []LinkEvent
	pendingName    string
	pendingSize    int
	pendingChannel int
	eventWake      chan struct{}
	err            error
}

func (s *LocalSession) startInbox() {
	s.inboxOnce.Do(func() {
		s.inbox.waiters = map[string]chan wireMsg{}
		s.inbox.eventWake = make(chan struct{})
		go s.pumpInbox()
	})
}

func (s *LocalSession) pumpInbox() {
	for {
		chunks, err := s.readChunks()
		if err != nil {
			s.failInbox(err)
			return
		}
		for _, chunk := range chunks {
			if chunk.channel != 0 {
				s.takeShareData(chunk.channel, chunk.payload)
				continue
			}
			s.routeInbox(chunk.payload)
		}
	}
}

func (s *LocalSession) waitResult(op string) chan wireMsg {
	wait := make(chan wireMsg, 1)
	s.inbox.mu.Lock()
	s.inbox.waiters[op] = wait
	s.inbox.mu.Unlock()
	return wait
}

func (s *LocalSession) cancelWait(op string, wait chan wireMsg) {
	s.inbox.mu.Lock()
	if s.inbox.waiters[op] == wait {
		delete(s.inbox.waiters, op)
	}
	s.inbox.mu.Unlock()
}

func (s *LocalSession) failInbox(err error) {
	s.inbox.mu.Lock()
	if s.inbox.err == nil {
		if err == nil {
			err = errors.New("ended")
		}
		s.inbox.err = err
	}
	for op, wait := range s.inbox.waiters {
		close(wait)
		delete(s.inbox.waiters, op)
	}
	if s.inbox.sub != nil {
		close(s.inbox.sub)
		s.inbox.sub = nil
	}
	if s.inbox.share != nil {
		close(s.inbox.share)
		s.inbox.share = nil
	}
	wake := s.inbox.eventWake
	s.inbox.eventWake = make(chan struct{})
	s.inbox.mu.Unlock()
	if wake != nil {
		close(wake)
	}
}

func (s *LocalSession) routeInbox(raw []byte) {
	var msg wireMsg
	if json.Unmarshal(raw, &msg) != nil {
		return
	}
	switch msg.Type {
	case "result", "status":
		s.inbox.mu.Lock()
		wait := s.inbox.waiters[msg.Op]
		delete(s.inbox.waiters, msg.Op)
		s.inbox.mu.Unlock()
		if wait != nil {
			wait <- msg
		}
	case "subscribed":
		s.inbox.mu.Lock()
		wait := s.inbox.sub
		s.inbox.sub = nil
		s.inbox.mu.Unlock()
		if wait != nil {
			wait <- append([]byte(nil), raw...)
		}
	case "share":
		s.noteShare(msg)
	case "shared", "share-error":
		s.inbox.mu.Lock()
		wait := s.inbox.share
		s.inbox.share = nil
		s.inbox.mu.Unlock()
		if wait != nil {
			wait <- msg
		}
	case "event", "reset":
		ev := LinkEvent{Type: msg.Type, Topic: msg.Topic, Cursor: msg.Cursor, Op: msg.Op, State: msg.State}
		if msg.Body != "" {
			body, err := base64.StdEncoding.DecodeString(msg.Body)
			if err != nil {
				return
			}
			ev.Body = body
		}
		s.inbox.mu.Lock()
		s.inbox.events = append(s.inbox.events, ev)
		wake := s.inbox.eventWake
		s.inbox.eventWake = make(chan struct{})
		s.inbox.mu.Unlock()
		close(wake)
	}
}
