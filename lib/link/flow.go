package link

import (
	"encoding/binary"
	"errors"
	"time"
)

const (
	MaxMessage       = 1 << 20
	MaxStreams       = 8
	InitialWindow    = 128 << 10
	HeartbeatEvery   = 20 * time.Second
	HeartbeatTimeout = 45 * time.Second
	PriorityLink     = 0
	PriorityControl  = 1
	PriorityTerminal = 2
	PriorityFile     = 3
)

// Assembly joins fragments of one message. FlagMore means another fragment follows.
type Assembly struct {
	buf []byte
}

func (a *Assembly) Add(frame Frame) (done bool, payload []byte, end bool, err error) {
	if len(a.buf)+len(frame.Payload) > MaxMessage {
		a.buf = nil
		return false, nil, false, errors.New("limit")
	}
	a.buf = append(a.buf, frame.Payload...)
	if frame.Flags&FlagMore != 0 {
		return false, nil, false, nil
	}
	out := append([]byte(nil), a.buf...)
	a.buf = nil
	return true, out, frame.Flags&FlagEnd != 0, nil
}

// Window is one direction of a stream. The sender spends credit; the receiver grants it.
type Window struct {
	Credit int
}

func NewWindow() Window { return Window{Credit: InitialWindow} }

func (w *Window) Take(n int) error {
	if n < 0 || n > w.Credit {
		return errors.New("window")
	}
	w.Credit -= n
	return nil
}

func (w *Window) Grant(payload []byte) error {
	if len(payload) != 4 {
		return ErrCredit
	}
	amount := binary.BigEndian.Uint32(payload)
	if amount > 1<<30 {
		return errors.New("window")
	}
	w.Credit += int(amount)
	return nil
}

func OpenStream(open int, channel uint32, fromMac bool) error {
	if open >= MaxStreams {
		return errors.New("streams")
	}
	if channel == 0 || (channel%2 == 1) != fromMac {
		return errors.New("channel")
	}
	return nil
}

// Scheduler sends link control before session messages, then terminal bytes, then file bytes.
type Scheduler struct {
	queues [4][]Frame
}

func (s *Scheduler) Push(priority int, frame Frame, limit int) error {
	if priority < PriorityLink || priority > PriorityFile {
		return errors.New("priority")
	}
	if _, err := Encode(frame, limit); err != nil {
		return err
	}
	s.queues[priority] = append(s.queues[priority], frame)
	return nil
}

func (s *Scheduler) Pop() (Frame, bool) {
	for priority := range s.queues {
		if len(s.queues[priority]) == 0 {
			continue
		}
		frame := s.queues[priority][0]
		s.queues[priority] = s.queues[priority][1:]
		return frame, true
	}
	return Frame{}, false
}

// Heartbeat reports a ping when nothing has been sent for 20 seconds.
// Forty-five seconds without an inbound frame closes the link, and a dead link does not ping.
func Heartbeat(lastInbound, lastOutbound, now time.Time) (send bool, dead bool) {
	if now.Before(lastInbound) || now.Before(lastOutbound) {
		return false, false
	}
	if now.Sub(lastInbound) >= HeartbeatTimeout {
		return false, true
	}
	return now.Sub(lastOutbound) >= HeartbeatEvery, false
}
