// A private child of the Shizuku UserService. stdin EOF closes every PTY.
package main

import (
	"bufio"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"os/signal"
	"sync"
	"syscall"
	"time"
)

const outputLimit = 256 * 1024
const inputLimit = 4096
const pageLimit = 32768
const lease = 60 * time.Second
const lifetime = time.Hour

type terminal interface {
	Write([]byte) error
	Resize(int, int) error
	Close()
}
type factory func(int, int, func([]byte), func(int)) (terminal, error)
type request struct {
	Op       string `json:"op"`
	ID       string `json:"sessionId"`
	Columns  int    `json:"columns"`
	Rows     int    `json:"rows"`
	Offset   int64  `json:"offset"`
	Sequence int64  `json:"sequence"`
	Hex      string `json:"hex"`
}
type session struct {
	pty       terminal
	state     string
	reason    string
	exitCode  *int
	output    []byte
	head      int
	base      int64
	nextInput int64
	lastInput []byte
	created   time.Time
	seen      time.Time
}
type manager struct {
	mu       sync.Mutex
	sessions map[string]*session
	start    factory
	now      func() time.Time
}

func newManager(start factory) *manager {
	return &manager{sessions: make(map[string]*session), start: start, now: time.Now}
}
func failure(message string) map[string]any { return map[string]any{"error": message} }
func validID(id string) bool {
	if len(id) != 32 {
		return false
	}
	for _, c := range id {
		if !(c >= '0' && c <= '9' || c >= 'a' && c <= 'f') {
			return false
		}
	}
	return true
}
func dimensions(columns, rows int) bool {
	return columns >= 20 && columns <= 500 && rows >= 5 && rows <= 200
}
func (m *manager) handle(r request) map[string]any {
	m.mu.Lock()
	defer m.mu.Unlock()
	if r.Op == "ping" {
		return map[string]any{"protocol": 1}
	}
	if !validID(r.ID) {
		return failure("sessionId 需要 32 位小写十六进制")
	}
	s := m.sessions[r.ID]
	if r.Op == "open" {
		if !dimensions(r.Columns, r.Rows) {
			return failure("终端尺寸超出范围")
		}
		if s != nil {
			return m.result(r.ID, s, 0)
		} // Never replace an exited shell.
		active := 0
		for _, value := range m.sessions {
			if value.state == "running" {
				active++
			}
		}
		if active >= 2 || len(m.sessions) >= 256 {
			return failure("终端会话已满，请先关闭已有会话")
		}
		now := m.now()
		s = &session{state: "running", created: now, seen: now}
		m.sessions[r.ID] = s
		pty, err := m.start(r.Columns, r.Rows, func(data []byte) {
			m.mu.Lock()
			defer m.mu.Unlock()
			s.appendOutput(data)
		}, func(code int) {
			m.mu.Lock()
			defer m.mu.Unlock()
			if s.state == "running" {
				s.state = "exited"
			}
			s.exitCode = &code
		})
		if err != nil {
			s.state = "failed"
			s.reason = "无法启动手机伪终端"
		} else {
			s.pty = pty
		}
		return m.result(r.ID, s, 0)
	}
	if s == nil {
		return map[string]any{"sessionId": r.ID, "state": "lost", "reason": "原会话已不存在，不会重新创建"}
	}
	s.seen = m.now()
	switch r.Op {
	case "read":
		if r.Offset < 0 || r.Offset > s.base+int64(len(s.output)) {
			return failure("输出游标无效")
		}
		return m.result(r.ID, s, r.Offset)
	case "input":
		data, err := hex.DecodeString(r.Hex)
		if err != nil || len(data) == 0 || len(data) > inputLimit {
			return failure("输入需要 1–4096 字节的十六进制")
		}
		if r.Sequence == s.nextInput-1 && string(data) == string(s.lastInput) {
			return m.result(r.ID, s, s.base+int64(len(s.output)))
		}
		if s.state != "running" || s.pty == nil {
			return failure("终端会话已结束")
		}
		if r.Sequence < 0 || r.Sequence != s.nextInput {
			return failure("输入序号不连续；查询原会话，不重发输入")
		}
		// Consume the sequence before writing. A partial write is never replayed.
		s.nextInput++
		s.lastInput = append([]byte(nil), data...)
		if err := s.pty.Write(data); err != nil {
			m.finish(s, "input_unknown", "输入结果未确认，会话已关闭；不会重发")
		}
	case "resize":
		if !dimensions(r.Columns, r.Rows) {
			return failure("终端尺寸超出范围")
		}
		if s.state == "running" && s.pty != nil {
			if err := s.pty.Resize(r.Columns, r.Rows); err != nil {
				return failure("终端尺寸未确认")
			}
		}
	case "close":
		m.finish(s, "closed", "会话已关闭")
	default:
		return failure("没有这个终端操作")
	}
	return m.result(r.ID, s, s.base+int64(len(s.output)))
}
func (m *manager) result(id string, s *session, offset int64) map[string]any {
	dropped := int64(0)
	if offset < s.base {
		dropped = s.base - offset
		offset = s.base
	}
	start := int(offset - s.base)
	if start < 0 || start > len(s.output) {
		start = 0
		offset = s.base
	}
	end := start + pageLimit
	if end > len(s.output) {
		end = len(s.output)
	}
	page := make([]byte, end-start)
	if len(page) > 0 {
		physical := (s.head + start) % len(s.output)
		n := copy(page, s.output[physical:])
		copy(page[n:], s.output[:])
	}
	return map[string]any{"sessionId": id, "state": s.state, "reason": s.reason,
		"hex": hex.EncodeToString(page), "offset": offset,
		"nextOffset": offset + int64(end-start), "endOffset": s.base + int64(len(s.output)),
		"droppedBytes": dropped, "nextInputSequence": s.nextInput, "exitCode": s.exitCode,
		"leaseSeconds": 60, "maxLifetimeSeconds": 3600}
}

func (s *session) appendOutput(data []byte) {
	if len(data) >= outputLimit {
		s.base += int64(len(s.output) + len(data) - outputLimit)
		s.output = append(s.output[:0], data[len(data)-outputLimit:]...)
		s.head = 0
		return
	}
	if len(s.output) < outputLimit {
		fit := outputLimit - len(s.output)
		if fit > len(data) {
			fit = len(data)
		}
		s.output = append(s.output, data[:fit]...)
		data = data[fit:]
	}
	if len(data) == 0 {
		return
	}
	n := copy(s.output[s.head:], data)
	copy(s.output, data[n:])
	s.head = (s.head + len(data)) % outputLimit
	s.base += int64(len(data))
}

// Close does not wait for the reader/exit callback, which takes the manager lock.
func (m *manager) finish(s *session, state, reason string) {
	if s.state != "running" {
		return
	}
	s.state = state
	s.reason = reason
	if s.pty != nil {
		s.pty.Close()
	}
}
func (m *manager) expire() {
	m.mu.Lock()
	defer m.mu.Unlock()
	now := m.now()
	for _, s := range m.sessions {
		if now.Sub(s.seen) >= lease {
			m.finish(s, "expired", "连接已中断 60 秒，会话已关闭")
		}
		if now.Sub(s.created) >= lifetime {
			m.finish(s, "expired", "会话已达到 1 小时时限")
		}
	}
}
func (m *manager) close() {
	m.mu.Lock()
	defer m.mu.Unlock()
	for _, s := range m.sessions {
		m.finish(s, "closed", "终端服务已停止")
	}
}
func main() {
	m := newManager(startPTY)
	defer m.close()
	stop := make(chan os.Signal, 1)
	signal.Notify(stop, syscall.SIGTERM, syscall.SIGHUP, syscall.SIGINT, syscall.SIGPIPE)
	go func() { <-stop; m.close(); os.Exit(0) }()
	go func() {
		ticker := time.NewTicker(time.Second)
		defer ticker.Stop()
		for range ticker.C {
			m.expire()
		}
	}()
	scanner := bufio.NewScanner(os.Stdin)
	scanner.Buffer(make([]byte, 16384), 32768)
	encoder := json.NewEncoder(os.Stdout)
	for scanner.Scan() {
		var r request
		var out map[string]any
		if json.Unmarshal(scanner.Bytes(), &r) != nil {
			out = failure("终端请求格式无效")
		} else {
			out = m.handle(r)
		}
		if err := encoder.Encode(out); err != nil {
			return
		}
	}
	if scanner.Err() != nil {
		fmt.Fprintln(os.Stderr, "terminal input closed")
	}
}
