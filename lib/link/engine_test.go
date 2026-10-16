package link

import (
	"bytes"
	"context"
	"encoding/hex"
	"encoding/json"
	"os"
	"path/filepath"
	"runtime"
	"testing"
	"time"
)

func TestEngineCases(t *testing.T) {
	body, err := os.ReadFile(enginePath())
	if err != nil {
		t.Fatal(err)
	}
	var file struct {
		Cases []struct {
			Name       string `json:"name"`
			FromMac    bool   `json:"fromMac"`
			Mux        bool   `json:"mux"`
			Buffer     int    `json:"buffer"`
			Outbound   int    `json:"outbound"`
			MaxMessage int    `json:"maxMessage"`
			Frame      int    `json:"frame"`
			Inbox      int    `json:"inbox"`
			Steps      []step `json:"steps"`
		} `json:"cases"`
	}
	if err := json.Unmarshal(body, &file); err != nil {
		t.Fatal(err)
	}
	need := map[string]bool{
		"out-of-order": false, "interleaved": false, "window-send": false,
		"window-grant": false, "window-receive": false, "reset": false,
		"slow-receiver": false,
	}
	for _, item := range file.Cases {
		if _, ok := need[item.Name]; ok {
			need[item.Name] = true
		}
		t.Run(item.Name, func(t *testing.T) {
			runEngineCase(t, item.FromMac, item.Mux, item.Buffer, item.Outbound, item.MaxMessage, item.Frame, item.Inbox, item.Steps)
		})
	}
	for name, seen := range need {
		if !seen {
			t.Fatalf("missing case %s", name)
		}
	}
}

func TestServeDelivers(t *testing.T) {
	left := make(chan Frame, 4)
	right := make(chan Frame, 4)
	mac := NewEndpoint(true, 32, 64, 0, time.Now())
	phone := NewEndpoint(false, 32, 64, 0, time.Now())
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 2)
	go func() {
		done <- mac.Serve(ctx, memConn{r: right, w: left}, time.Now)
	}()
	go func() {
		done <- phone.Serve(ctx, memConn{r: left, w: right}, time.Now)
	}()
	if err := mac.Open(1); err != nil {
		t.Fatal(err)
	}
	if err := mac.Send(PriorityFile, Frame{Kind: KindData, Channel: 1, Flags: FlagEnd, Payload: []byte("hi")}); err != nil {
		t.Fatal(err)
	}
	deadline := time.Now().Add(2 * time.Second)
	var got Chunk
	for time.Now().Before(deadline) {
		items := phone.Inbox()
		if len(items) == 1 {
			got = items[0]
			break
		}
		time.Sleep(5 * time.Millisecond)
	}
	if got.Channel != 1 || !got.End || !bytes.Equal(got.Payload, []byte("hi")) {
		t.Fatalf("delivered %#v", got)
	}
	cancel()
	for i := 0; i < 2; i++ {
		select {
		case <-done:
		case <-time.After(2 * time.Second):
			t.Fatal("serve did not stop")
		}
	}
}

func TestServeStopsOnControlSeq(t *testing.T) {
	inbound := make(chan Frame, 1)
	outbound := make(chan Frame, 1)
	endpoint := NewEndpoint(true, 32, 64, 0, time.Now())
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() {
		done <- endpoint.Serve(ctx, memConn{r: inbound, w: outbound}, time.Now)
	}()
	inbound <- Frame{Kind: KindMessage, Channel: 0, Seq: 1, Payload: []byte("{}")}
	select {
	case err := <-done:
		if err == nil || err.Error() != "seq" {
			t.Fatalf("serve error %v", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("serve kept a broken session")
	}
}

type step struct {
	Op       string `json:"op"`
	Kind     int    `json:"kind"`
	Flags    int    `json:"flags"`
	Channel  uint32 `json:"channel"`
	Seq      uint64 `json:"seq"`
	Priority int    `json:"priority"`
	Session  int    `json:"session"`
	Payload  string `json:"payload"`
	Peer     string `json:"peer"`
	Error    string `json:"error"`
	N        int    `json:"n"`
	Ms       int64  `json:"ms"`
	End      bool   `json:"end"`
	Frames   []step `json:"frames"`
	Items    []step `json:"items"`
}

func runEngineCase(t *testing.T, fromMac, mux bool, buffer, outbound, maxMessage, frame, inbox int, steps []step) {
	t.Helper()
	start := time.Unix(0, 0)
	count := 1
	if mux {
		count = 2
	}
	sessions := make([]*Endpoint, count)
	for i := range sessions {
		sessions[i] = NewEndpoint(fromMac, buffer, outbound, maxMessage, start)
		sessions[i].SetFrameLimit(frame)
		sessions[i].SetInboxLimit(inbox)
	}
	link := NewLink(start, sessions...)
	for i, item := range steps {
		if item.Session < 0 || item.Session >= len(sessions) {
			t.Fatalf("step %d %s: session %d", i, item.Op, item.Session)
		}
		if err := runStep(sessions[item.Session], link, start, item); err != nil {
			t.Fatalf("step %d %s: %v", i, item.Op, err)
		}
	}
}

func runStep(endpoint *Endpoint, link *Link, start time.Time, item step) error {
	switch item.Op {
	case "open":
		return matchErr(item.Error, endpoint.Open(item.Channel))
	case "write":
		frame, err := stepFrame(item)
		if err != nil {
			return err
		}
		return matchErr(item.Error, endpoint.Send(item.Priority, frame))
	case "message":
		payload, err := hex.DecodeString(item.Payload)
		if err != nil {
			return err
		}
		return matchErr(item.Error, endpoint.SendMessage(item.Priority, item.Channel, byte(item.Kind), payload))
	case "peer":
		raw, err := hex.DecodeString(item.Payload)
		if err != nil {
			return err
		}
		if len(raw) != 16 {
			return errString{want: "16", got: "peer"}
		}
		var id [16]byte
		copy(id[:], raw)
		endpoint.SetPeer(id)
		return nil
	case "writable":
		if got := endpoint.TakeWritable(); got != item.N {
			return errString{want: "writable", got: "count"}
		}
		return nil
	case "in":
		frame, err := stepFrame(item)
		if err != nil {
			return err
		}
		return matchErr(item.Error, endpoint.Accept(frame))
	case "consume":
		return matchErr(item.Error, endpoint.Consume(item.Channel, item.N))
	case "tick":
		return matchErr(item.Error, link.Advance(start.Add(time.Duration(item.Ms)*time.Millisecond)))
	case "out":
		return matchOut(link.Pull, item.Frames)
	case "inbox":
		return matchInbox(endpoint, item.Items)
	default:
		return os.ErrInvalid
	}
}

func stepFrame(item step) (Frame, error) {
	payload, err := hex.DecodeString(item.Payload)
	if err != nil {
		return Frame{}, err
	}
	return Frame{Kind: byte(item.Kind), Flags: byte(item.Flags), Channel: item.Channel, Seq: item.Seq, Payload: payload}, nil
}

func matchErr(want string, err error) error {
	got := ""
	if err != nil {
		got = err.Error()
	}
	if got != want {
		return errString{want: want, got: got}
	}
	return nil
}

func matchOut(pull func() (Frame, bool), frames []step) error {
	for _, want := range frames {
		got, ok := pull()
		if !ok {
			return errString{want: "frame", got: "empty"}
		}
		expect, err := stepFrame(want)
		if err != nil {
			return err
		}
		peer := [16]byte{}
		if want.Peer != "" {
			raw, err := hex.DecodeString(want.Peer)
			if err != nil || len(raw) != 16 {
				return errString{want: "peer", got: "bad"}
			}
			copy(peer[:], raw)
		}
		if got.Kind != expect.Kind || got.Flags != expect.Flags || got.Channel != expect.Channel || got.Seq != expect.Seq || !bytes.Equal(got.Payload, expect.Payload) || got.Peer != peer {
			return errString{want: "match", got: "mismatch"}
		}
	}
	if _, ok := pull(); ok {
		return errString{want: "empty", got: "extra"}
	}
	return nil
}

func matchInbox(endpoint *Endpoint, items []step) error {
	got := endpoint.Inbox()
	if len(got) != len(items) {
		return errString{want: "inbox", got: "len"}
	}
	for i, want := range items {
		payload, err := hex.DecodeString(want.Payload)
		if err != nil {
			return err
		}
		if got[i].Channel != want.Channel || got[i].End != want.End || !bytes.Equal(got[i].Payload, payload) {
			return errString{want: "chunk", got: "mismatch"}
		}
	}
	return nil
}

type errString struct{ want, got string }

func (e errString) Error() string { return e.want + " != " + e.got }

type memConn struct {
	r <-chan Frame
	w chan<- Frame
}

func (c memConn) Read(ctx context.Context) (Frame, error) {
	select {
	case <-ctx.Done():
		return Frame{}, ctx.Err()
	case frame, ok := <-c.r:
		if !ok {
			return Frame{}, os.ErrClosed
		}
		return frame, nil
	}
}

func (c memConn) Write(ctx context.Context, frame Frame) error {
	select {
	case <-ctx.Done():
		return ctx.Err()
	case c.w <- frame:
		return nil
	}
}

func enginePath() string {
	_, file, _, _ := runtime.Caller(0)
	return filepath.Join(filepath.Dir(file), "testdata", "engine.json")
}
