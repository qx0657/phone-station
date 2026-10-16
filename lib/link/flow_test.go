package link

import (
	"bytes"
	"errors"
	"testing"
	"time"
)

func TestReassembly(t *testing.T) {
	var assembly Assembly
	done, payload, end, err := assembly.Add(Frame{Flags: FlagMore, Payload: []byte("ab")})
	if done || payload != nil || end || err != nil {
		t.Fatal("first fragment")
	}
	done, payload, end, err = assembly.Add(Frame{Flags: FlagEnd, Payload: []byte("cd")})
	if !done || end != true || err != nil || !bytes.Equal(payload, []byte("abcd")) {
		t.Fatal("joined")
	}
	done, payload, _, err = assembly.Add(Frame{Payload: []byte("z")})
	if !done || err != nil || !bytes.Equal(payload, []byte("z")) {
		t.Fatal("next message")
	}
	chunk := bytes.Repeat([]byte{1}, MainPayload)
	for i := 0; i < MaxMessage/MainPayload; i++ {
		if _, _, _, err := assembly.Add(Frame{Flags: FlagMore, Payload: chunk}); err != nil {
			t.Fatal(err)
		}
	}
	if _, _, _, err := assembly.Add(Frame{Payload: []byte{2}}); err == nil || err.Error() != "limit" {
		t.Fatal("oversize message")
	}
}

func TestWindowAndStreams(t *testing.T) {
	window := NewWindow()
	if window.Credit != InitialWindow {
		t.Fatal("initial window")
	}
	if err := window.Take(InitialWindow); err != nil || window.Credit != 0 {
		t.Fatal("spend")
	}
	if err := window.Take(1); err == nil {
		t.Fatal("over window")
	}
	if err := window.Grant([]byte{0, 0, 0, 4}); err != nil || window.Credit != 4 {
		t.Fatal("grant")
	}
	if err := window.Take(4); err != nil {
		t.Fatal("after grant")
	}
	for i := 0; i < MaxStreams; i++ {
		if err := OpenStream(i, uint32(i*2+1), true); err != nil {
			t.Fatal(err)
		}
	}
	if err := OpenStream(MaxStreams, 17, true); err == nil || err.Error() != "streams" {
		t.Fatal("ninth stream")
	}
	if err := OpenStream(0, 2, true); err == nil || err.Error() != "channel" {
		t.Fatal("mac even")
	}
	if err := OpenStream(0, 1, false); err == nil {
		t.Fatal("phone odd")
	}
}

func TestPriorityAndMalformed(t *testing.T) {
	var scheduler Scheduler
	file := Frame{Kind: KindData, Channel: 1, Seq: 1, Payload: []byte("file")}
	terminal := Frame{Kind: KindData, Channel: 3, Seq: 2, Payload: []byte("term")}
	message := Frame{Kind: KindMessage, Channel: 0, Seq: 3, Payload: []byte("{}")}
	ping := Frame{Kind: KindLink, Payload: []byte("ping")}
	for _, item := range []struct {
		priority int
		frame    Frame
	}{
		{PriorityFile, file},
		{PriorityTerminal, terminal},
		{PriorityControl, message},
		{PriorityLink, ping},
	} {
		if err := scheduler.Push(item.priority, item.frame, MainPayload); err != nil {
			t.Fatal(err)
		}
	}
	var got []byte
	for {
		frame, ok := scheduler.Pop()
		if !ok {
			break
		}
		got = append(got, frame.Kind)
	}
	if !bytes.Equal(got, []byte{KindLink, KindMessage, KindData, KindData}) {
		t.Fatalf("order %v", got)
	}
	second, ok := scheduler.Pop()
	if ok || second.Kind != 0 {
		t.Fatal("empty")
	}
	oversized := Frame{Kind: KindData, Channel: 1, Payload: bytes.Repeat([]byte{1}, MainPayload+1)}
	if err := scheduler.Push(PriorityFile, oversized, MainPayload); err == nil || !errors.Is(err, ErrLimit) {
		t.Fatal("frame limit")
	}
	if _, err := Decode([]byte{1}, MainPayload); !errors.Is(err, ErrTruncated) {
		t.Fatal("truncated")
	}
	bad := make([]byte, HeaderSize)
	bad[0] = 1
	if _, err := Decode(bad, MainPayload); !errors.Is(err, ErrVersion) {
		t.Fatal("version")
	}
}

func TestHeartbeat(t *testing.T) {
	start := time.Unix(1_700_000_000, 0)
	send, dead := Heartbeat(start, start, start.Add(19*time.Second))
	if send || dead {
		t.Fatal("quiet")
	}
	send, dead = Heartbeat(start, start, start.Add(HeartbeatEvery))
	if !send || dead {
		t.Fatal("ping")
	}
	send, dead = Heartbeat(start, start, start.Add(HeartbeatTimeout))
	if send || !dead {
		t.Fatal("dead still pings")
	}
	send, dead = Heartbeat(start, start, start.Add(-time.Second))
	if send || dead {
		t.Fatal("clock step")
	}
}
