package link

import (
	"context"
	"encoding/binary"
	"errors"
	"sync"
	"time"
)

const DefaultOutbound = 4 * InitialWindow

// pingPayload is the link-layer keepalive. A relay rejects a bare "ping" as non-JSON.
var pingPayload = []byte(`{"type":"ping"}`)

// FrameConn is the engine's only I/O. It is not a WebSocket type.
type FrameConn interface {
	Read(ctx context.Context) (Frame, error)
	Write(ctx context.Context, frame Frame) error
}

// Chunk is one reassembled message or stream fragment group.
type Chunk struct {
	Channel uint32
	Payload []byte
	End     bool
}

// Endpoint is one side of a session: read path, write scheduling, and stream windows.
// Sequence numbers are assigned when a frame is pulled, so a higher-priority frame
// cannot leave with a later sequence than data queued before it. Link frames keep seq 0
// and do not use the session counter.
type Endpoint struct {
	mu            sync.Mutex
	cond          *sync.Cond
	fromMac       bool
	buffer        int
	outboundLimit int
	maxMessage    int
	outbound      int
	outSeq        uint64
	inSeq         uint64
	broken        bool
	lastIn        time.Time
	lastOut       time.Time
	sched         Scheduler
	streams       map[uint32]*stream
	control       Assembly
	controlLen    int
	controlKind   byte
	inbox         []Chunk
	inboxBytes    int
	inboxLimit    int
	frameLimit    int
	peer          [16]byte
	writeBlocked  bool
	writable      int
}

type stream struct {
	send        int
	peer        int
	unconsumed  int
	pendingLen  int
	pendingKind byte
	assembly    Assembly
	ended       bool
	dead        bool
}

// NewEndpoint starts both directions at the per-stream buffer. Zero buffer, outbound, or
// maxMessage selects the documented defaults. start is the clock origin for Advance.
func NewEndpoint(fromMac bool, buffer, outbound, maxMessage int, start time.Time) *Endpoint {
	if buffer <= 0 {
		buffer = InitialWindow
	}
	if outbound <= 0 {
		outbound = DefaultOutbound
	}
	if maxMessage <= 0 {
		maxMessage = MaxMessage
	}
	e := &Endpoint{
		fromMac:       fromMac,
		buffer:        buffer,
		outboundLimit: outbound,
		maxMessage:    maxMessage,
		lastIn:        start,
		lastOut:       start,
		inboxLimit:    8 << 20,
		frameLimit:    MainPayload,
		streams:       map[uint32]*stream{},
	}
	e.cond = sync.NewCond(&e.mu)
	return e
}

// SetPeer is the remote device id written on every frame except link control.
func (e *Endpoint) SetPeer(id [16]byte) {
	e.mu.Lock()
	e.peer = id
	e.mu.Unlock()
}

// SetFrameLimit is the largest piece SendMessage will queue. Zero keeps the main-link limit.
func (e *Endpoint) SetFrameLimit(n int) {
	if n <= 0 {
		return
	}
	e.mu.Lock()
	e.frameLimit = n
	e.mu.Unlock()
}

// SetInboxLimit caps channel 0 messages the application has not taken. Zero keeps 8 MiB.
func (e *Endpoint) SetInboxLimit(n int) {
	if n <= 0 {
		return
	}
	e.mu.Lock()
	e.inboxLimit = n
	e.mu.Unlock()
}

// TakeWritable reports how many times the outbound buffer dropped back under its limit.
func (e *Endpoint) TakeWritable() int {
	e.mu.Lock()
	defer e.mu.Unlock()
	n := e.writable
	e.writable = 0
	return n
}

// Head is the priority of the next queued frame, or false when nothing is queued.
func (e *Endpoint) Head() (int, bool) {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.sched.Head()
}

func (e *Endpoint) Open(channel uint32) error {
	e.mu.Lock()
	defer e.mu.Unlock()
	if _, ok := e.streams[channel]; ok {
		return errors.New("channel")
	}
	if err := OpenStream(e.active(), channel, e.fromMac); err != nil {
		return err
	}
	e.streams[channel] = e.newStream()
	return nil
}

func (e *Endpoint) Send(priority int, frame Frame) error {
	e.mu.Lock()
	defer e.mu.Unlock()
	frame.Peer = e.peer
	account := frame.Kind == KindData || frame.Kind == KindMessage
	var st *stream
	if frame.Channel != 0 {
		var err error
		st, err = e.live(frame.Channel)
		if err != nil {
			return err
		}
		if frame.Kind != KindData && frame.Kind != KindMessage {
			return errors.New("kind")
		}
		if len(frame.Payload) > st.send {
			return errors.New("window")
		}
	} else if frame.Kind != KindMessage && frame.Kind != KindHandshake {
		return errors.New("kind")
	}
	if err := e.queue(priority, frame, account); err != nil {
		return err
	}
	if st != nil {
		st.send -= len(frame.Payload)
		if frame.Flags&FlagEnd != 0 {
			st.ended = true
		}
	}
	return nil
}

// SendMessage queues every fragment of one message, or none. The caller does not slice it.
func (e *Endpoint) SendMessage(priority int, channel uint32, kind byte, payload []byte) error {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.broken {
		return errors.New("link")
	}
	if payload == nil {
		payload = []byte{}
	}
	if len(payload) > e.maxMessage {
		return errors.New("limit")
	}
	limit := e.frameLimit
	if limit <= 0 {
		limit = MainPayload
	}
	frames := make([]Frame, 0, len(payload)/limit+1)
	if len(payload) == 0 {
		frames = append(frames, Frame{Kind: kind, Flags: FlagEnd, Channel: channel})
	} else {
		for off := 0; off < len(payload); {
			next := off + limit
			if next > len(payload) {
				next = len(payload)
			}
			flags := byte(FlagMore)
			if next == len(payload) {
				flags = FlagEnd
			}
			frames = append(frames, Frame{Kind: kind, Flags: flags, Channel: channel, Payload: append([]byte(nil), payload[off:next]...)})
			off = next
		}
	}
	account := kind == KindData || kind == KindMessage
	total := 0
	if account {
		for _, frame := range frames {
			total += len(frame.Payload)
		}
		if e.outbound+total > e.outboundLimit {
			e.writeBlocked = true
			return errors.New("backpressure")
		}
	}
	var st *stream
	if channel != 0 {
		var err error
		st, err = e.live(channel)
		if err != nil {
			return err
		}
		if kind != KindData && kind != KindMessage {
			return errors.New("kind")
		}
		if total > st.send {
			return errors.New("window")
		}
	} else if kind != KindMessage && kind != KindHandshake {
		return errors.New("kind")
	}
	for _, frame := range frames {
		if err := e.queue(priority, frame, account); err != nil {
			return err
		}
	}
	if st != nil {
		st.send -= total
		if frames[len(frames)-1].Flags&FlagEnd != 0 {
			st.ended = true
		}
	}
	return nil
}

// Consume records that the application accepted n bytes and grants that much credit.
func (e *Endpoint) Consume(channel uint32, n int) error {
	e.mu.Lock()
	defer e.mu.Unlock()
	st, ok := e.streams[channel]
	if !ok || st.dead || n < 0 || n > st.unconsumed {
		return errors.New("credit")
	}
	if n == 0 {
		return nil
	}
	st.unconsumed -= n
	st.peer += n
	var payload [4]byte
	binary.BigEndian.PutUint32(payload[:], uint32(n))
	return e.queue(PriorityControl, Frame{Kind: KindCredit, Channel: channel, Payload: append([]byte(nil), payload[:]...)}, false)
}

func (e *Endpoint) Accept(frame Frame) error {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.broken {
		return errors.New("link")
	}
	if frame.Kind == KindLink {
		return nil
	}
	if frame.Seq != e.inSeq {
		// A gap on any channel means the session lost a frame. Resetting one stream
		// would leave every later frame permanently out of order.
		e.broken = true
		return errors.New("seq")
	}
	e.inSeq++
	if frame.Channel == 0 {
		switch frame.Kind {
		case KindMessage, KindHandshake:
			return e.finishControl(frame)
		default:
			return errors.New("channel")
		}
	}
	switch frame.Kind {
	case KindCredit:
		return e.grant(frame)
	case KindReset:
		e.kill(frame.Channel, "")
		return nil
	case KindData, KindMessage:
		return e.inbound(frame)
	default:
		return errors.New("kind")
	}
}

func (e *Endpoint) Inbox() []Chunk {
	e.mu.Lock()
	defer e.mu.Unlock()
	out := append([]Chunk(nil), e.inbox...)
	e.inbox = nil
	e.inboxBytes = 0
	return out
}

// Pull takes the next frame the write loop would send.
func (e *Endpoint) Pull() (Frame, bool) {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.pullLocked()
}

// Advance does not emit a keepalive. One connection uses Link.Advance so every session on it
// shares a single JSON ping and the 45 second deadline.
func (e *Endpoint) Advance(now time.Time) error {
	return nil
}

func (e *Endpoint) beat(now time.Time) error {
	e.mu.Lock()
	defer e.mu.Unlock()
	send, dead := Heartbeat(e.lastIn, e.lastOut, now)
	if dead {
		return errors.New("dead")
	}
	if !send {
		return nil
	}
	if err := e.queue(PriorityLink, Frame{Kind: KindLink, Payload: append([]byte(nil), pingPayload...)}, false); err != nil {
		return err
	}
	e.lastOut = now
	return nil
}

// Serve reads frames, writes scheduled frames, and advances the heartbeat from now.
// A stream protocol error is reported by Accept and does not stop the link; the reset
// frame is written by the same loop. now may be nil and then defaults to time.Now.
func (e *Endpoint) Serve(ctx context.Context, rw FrameConn, now func() time.Time) error {
	if now == nil {
		now = time.Now
	}
	e.mu.Lock()
	e.lastIn = now()
	e.lastOut = now()
	e.mu.Unlock()
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	go func() {
		<-ctx.Done()
		e.mu.Lock()
		e.cond.Broadcast()
		e.mu.Unlock()
	}()
	errc := make(chan error, 2)
	go func() {
		errc <- e.readLoop(ctx, rw, now)
	}()
	go func() {
		errc <- e.writeLoop(ctx, rw, now)
	}()
	timer := time.NewTimer(HeartbeatEvery)
	defer timer.Stop()
	for {
		select {
		case err := <-errc:
			cancel()
			return err
		case <-timer.C:
			if err := e.beat(now()); err != nil {
				cancel()
				return err
			}
			timer.Reset(HeartbeatEvery)
		case <-ctx.Done():
			return ctx.Err()
		}
	}
}

func (e *Endpoint) readLoop(ctx context.Context, rw FrameConn, now func() time.Time) error {
	for {
		frame, err := rw.Read(ctx)
		if err != nil {
			return err
		}
		e.mu.Lock()
		e.lastIn = now()
		e.mu.Unlock()
		if err := e.Accept(frame); err != nil && fatalLink(err) {
			return err
		}
	}
}

func (e *Endpoint) writeLoop(ctx context.Context, rw FrameConn, now func() time.Time) error {
	for {
		frame, err := e.take(ctx)
		if err != nil {
			return err
		}
		if err := rw.Write(ctx, frame); err != nil {
			return err
		}
		e.mu.Lock()
		e.lastOut = now()
		e.mu.Unlock()
	}
}

func (e *Endpoint) take(ctx context.Context) (Frame, error) {
	e.mu.Lock()
	defer e.mu.Unlock()
	for {
		if frame, ok := e.pullLocked(); ok {
			return frame, nil
		}
		if err := ctx.Err(); err != nil {
			return Frame{}, err
		}
		e.cond.Wait()
	}
}

func (e *Endpoint) newStream() *stream {
	return &stream{send: e.buffer, peer: e.buffer}
}

func (e *Endpoint) active() int {
	n := 0
	for _, st := range e.streams {
		if !st.dead && !st.ended {
			n++
		}
	}
	return n
}

func (e *Endpoint) live(channel uint32) (*stream, error) {
	st, ok := e.streams[channel]
	if !ok || st.dead || st.ended {
		return nil, errors.New("channel")
	}
	return st, nil
}

func (e *Endpoint) queue(priority int, frame Frame, account bool) error {
	if account && e.outbound+len(frame.Payload) > e.outboundLimit {
		e.writeBlocked = true
		return errors.New("backpressure")
	}
	if err := e.sched.Push(priority, frame, MainPayload); err != nil {
		return err
	}
	if account {
		e.outbound += len(frame.Payload)
	}
	e.cond.Broadcast()
	return nil
}

func (e *Endpoint) pullLocked() (Frame, bool) {
	frame, ok := e.sched.Pop()
	if !ok {
		return Frame{}, false
	}
	if frame.Kind == KindData || frame.Kind == KindMessage {
		e.outbound -= len(frame.Payload)
		if e.writeBlocked && e.outbound < e.outboundLimit {
			e.writeBlocked = false
			e.writable++
		}
	}
	if frame.Kind != KindLink {
		frame.Peer = e.peer
		frame.Seq = e.outSeq
		e.outSeq++
	}
	return frame, true
}

func fatalLink(err error) bool {
	switch err.Error() {
	case "link", "seq", "inbox":
		return true
	default:
		return false
	}
}

func (e *Endpoint) grant(frame Frame) error {
	st, ok := e.streams[frame.Channel]
	if !ok || st.dead {
		return errors.New("channel")
	}
	if len(frame.Payload) != 4 {
		e.kill(frame.Channel, "window")
		return errors.New("window")
	}
	amount := binary.BigEndian.Uint32(frame.Payload)
	if amount > 1<<30 || int(amount) > e.buffer-st.send {
		e.kill(frame.Channel, "window")
		return errors.New("window")
	}
	st.send += int(amount)
	return nil
}

func (e *Endpoint) inbound(frame Frame) error {
	st, ok := e.streams[frame.Channel]
	if !ok {
		if err := OpenStream(e.active(), frame.Channel, !e.fromMac); err != nil {
			return err
		}
		st = e.newStream()
		e.streams[frame.Channel] = st
	}
	if st.dead || st.ended {
		return errors.New("channel")
	}
	if st.pendingKind != 0 && frame.Kind != st.pendingKind {
		e.kill(frame.Channel, "fragment")
		return errors.New("fragment")
	}
	if len(frame.Payload) > st.peer || st.pendingLen+len(frame.Payload) > e.maxMessage {
		code := "window"
		if st.pendingLen+len(frame.Payload) > e.maxMessage {
			code = "limit"
		}
		e.kill(frame.Channel, code)
		return errors.New(code)
	}
	st.peer -= len(frame.Payload)
	done, payload, end, err := st.assembly.Add(frame)
	if err != nil {
		e.kill(frame.Channel, "limit")
		return errors.New("limit")
	}
	if !done {
		st.pendingKind = frame.Kind
		st.pendingLen += len(frame.Payload)
		return nil
	}
	st.pendingKind = 0
	st.pendingLen = 0
	st.unconsumed += len(payload)
	e.inbox = append(e.inbox, Chunk{Channel: frame.Channel, Payload: payload, End: end})
	if end {
		st.ended = true
	}
	return nil
}

func (e *Endpoint) finishControl(frame Frame) error {
	if e.controlKind != 0 && frame.Kind != e.controlKind {
		e.control = Assembly{}
		e.controlLen = 0
		e.controlKind = 0
		return errors.New("fragment")
	}
	if e.controlLen+len(frame.Payload) > e.maxMessage {
		e.control = Assembly{}
		e.controlLen = 0
		e.controlKind = 0
		return errors.New("limit")
	}
	done, payload, end, err := e.control.Add(frame)
	if err != nil {
		e.control = Assembly{}
		e.controlLen = 0
		e.controlKind = 0
		return errors.New("limit")
	}
	if !done {
		e.controlKind = frame.Kind
		e.controlLen += len(frame.Payload)
		return nil
	}
	if e.inboxBytes+len(payload) > e.inboxLimit {
		e.broken = true
		e.control = Assembly{}
		e.controlLen = 0
		e.controlKind = 0
		return errors.New("inbox")
	}
	e.controlKind = 0
	e.controlLen = 0
	e.inboxBytes += len(payload)
	e.inbox = append(e.inbox, Chunk{Payload: payload, End: end})
	return nil
}

func (e *Endpoint) kill(channel uint32, code string) {
	st, ok := e.streams[channel]
	if ok && st.dead {
		return
	}
	if !ok {
		st = e.newStream()
		e.streams[channel] = st
	}
	st.dead = true
	st.assembly = Assembly{}
	st.pendingKind = 0
	st.pendingLen = 0
	for _, frame := range e.sched.Drop(channel) {
		if frame.Kind == KindData || frame.Kind == KindMessage {
			e.outbound -= len(frame.Payload)
		}
	}
	if code == "" {
		return
	}
	_ = e.queue(PriorityControl, Frame{Kind: KindReset, Channel: channel, Payload: []byte(`{"code":"` + code + `"}`)}, false)
}
