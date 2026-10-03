package main

import (
	"bytes"
	"encoding/hex"
	"errors"
	"strings"
	"testing"
	"time"
)

const testID = "0123456789abcdef0123456789abcdef"

type fakeTerminal struct {
	input         []byte
	closed        bool
	fail          bool
	columns, rows int
}

func (p *fakeTerminal) Write(b []byte) error {
	p.input = append(p.input, b...)
	if p.fail {
		return errors.New("partial write")
	}
	return nil
}
func (p *fakeTerminal) Resize(c, r int) error { p.columns, p.rows = c, r; return nil }
func (p *fakeTerminal) Close()                { p.closed = true }
func fixture() (*manager, *fakeTerminal, *func([]byte), *func(int)) {
	p := &fakeTerminal{}
	var output func([]byte)
	var done func(int)
	m := newManager(func(c, r int, out func([]byte), exit func(int)) (terminal, error) {
		p.columns = c
		p.rows = r
		output = out
		done = exit
		return p, nil
	})
	m.handle(request{Op: "open", ID: testID, Columns: 80, Rows: 24})
	return m, p, &output, &done
}
func TestInputSequence(t *testing.T) {
	m, p, _, _ := fixture()
	r := request{Op: "input", ID: testID, Sequence: 0, Hex: "706d0d"}
	if m.handle(r)["nextInputSequence"] != int64(1) {
		t.Fatal("missing acknowledgement")
	}
	m.handle(r)
	if !bytes.Equal(p.input, []byte("pm\r")) {
		t.Fatal("replayed input")
	}
	r.Hex = "69640d"
	if m.handle(r)["error"] == nil {
		t.Fatal("reused sequence accepted")
	}
	r.Sequence = 3
	if m.handle(r)["error"] == nil {
		t.Fatal("out of order input accepted")
	}
	r.Sequence = 1
	p.fail = true
	if m.handle(r)["state"] != "input_unknown" || !p.closed {
		t.Fatal("partial input left alive")
	}
	m.handle(r)
	if len(p.input) != 6 {
		t.Fatal("partial input replayed")
	}
}
func TestOutputCursorAndBounds(t *testing.T) {
	m, _, out, _ := fixture()
	(*out)(bytes.Repeat([]byte("x"), outputLimit+99))
	r := m.handle(request{Op: "read", ID: testID})
	if r["droppedBytes"] != int64(99) || r["nextOffset"] != int64(99+pageLimit) || len(r["hex"].(string)) != pageLimit*2 {
		t.Fatal(r)
	}
	r2 := m.handle(request{Op: "read", ID: testID})
	if r["hex"] != r2["hex"] || r["nextOffset"] != r2["nextOffset"] {
		t.Fatal("read consumed output")
	}
	if m.handle(request{Op: "read", ID: testID, Offset: outputLimit + 100})["error"] == nil {
		t.Fatal("future cursor")
	}
	// Output pages preserve byte order after the ring wraps more than once.
	(*out)([]byte("中文\r\n"))
	(*out)(bytes.Repeat([]byte("y"), pageLimit))
	end := m.sessions[testID].base + int64(len(m.sessions[testID].output))
	r = m.handle(request{Op: "read", ID: testID, Offset: end - pageLimit - 8})
	decoded, _ := hex.DecodeString(r["hex"].(string))
	if !bytes.HasPrefix(decoded, []byte("中文\r\n")) || !bytes.Equal(decoded[8:], bytes.Repeat([]byte("y"), pageLimit-8)) {
		t.Fatal("ring reordered bytes")
	}
}
func TestLifetimeCloseAndNoRecreate(t *testing.T) {
	m, p, _, done := fixture()
	m.handle(request{Op: "close", ID: testID})
	if !p.closed || m.handle(request{Op: "open", ID: testID, Columns: 80, Rows: 24})["state"] != "closed" {
		t.Fatal("recreated closed session")
	}
	(*done)(0)
	if m.handle(request{Op: "read", ID: testID})["state"] != "closed" {
		t.Fatal("exit overwrote close")
	}
	m, p, _, _ = fixture()
	m.now = func() time.Time { return m.sessions[testID].seen.Add(lease) }
	m.expire()
	if !p.closed || m.handle(request{Op: "read", ID: testID})["state"] != "expired" {
		t.Fatal("lease not enforced")
	}
	m, p, _, _ = fixture()
	m.now = func() time.Time { return m.sessions[testID].created.Add(lifetime) }
	m.sessions[testID].seen = m.now()
	m.expire()
	if !p.closed {
		t.Fatal("absolute lifetime not enforced")
	}
	if m.handle(request{Op: "read", ID: strings.Repeat("f", 32)})["state"] != "lost" {
		t.Fatal("read created session")
	}
}
func TestRequestLimits(t *testing.T) {
	m, _, _, _ := fixture()
	for _, r := range []request{
		{Op: "read", ID: "../bad"}, {Op: "input", ID: testID, Hex: "z"},
		{Op: "input", ID: testID, Hex: strings.Repeat("00", inputLimit+1)},
		{Op: "resize", ID: testID, Columns: 0, Rows: 24},
	} {
		if m.handle(r)["error"] == nil {
			t.Fatal("invalid request accepted", r.Op)
		}
	}
}
