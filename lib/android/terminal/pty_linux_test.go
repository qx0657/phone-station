//go:build linux

package main

import (
	"encoding/hex"
	"strings"
	"testing"
	"time"
)

func waitOutput(t *testing.T, m *manager, offset *int64, wanted string) string {
	t.Helper()
	text := ""
	deadline := time.Now().Add(4 * time.Second)
	for time.Now().Before(deadline) {
		r := m.handle(request{Op: "read", ID: testID, Offset: *offset})
		b, _ := hex.DecodeString(r["hex"].(string))
		text += string(b)
		*offset = r["nextOffset"].(int64)
		if strings.Contains(text, wanted) {
			return text
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("missing %q in %q", wanted, text)
	return ""
}
func TestRealPTY(t *testing.T) {
	m := newManager(startPTY)
	defer m.close()
	r := m.handle(request{Op: "open", ID: testID, Columns: 80, Rows: 24})
	if r["state"] != "running" {
		t.Fatal(r)
	}
	offset := int64(0)
	write := func(seq int64, text string) {
		t.Helper()
		r := m.handle(request{Op: "input", ID: testID, Sequence: seq, Hex: hex.EncodeToString([]byte(text))})
		if r["error"] != nil || r["state"] != "running" {
			t.Fatal(r)
		}
	}
	waitOutput(t, m, &offset, "phone $ ")
	write(0, "test -t 0 && printf 'PTY_%s\\n' ready\r")
	waitOutput(t, m, &offset, "PTY_ready")
	write(1, "cd /tmp\r")
	waitOutput(t, m, &offset, "phone $ ")
	write(2, "printf 'DIR_%s\\n' \"$PWD\"\r")
	waitOutput(t, m, &offset, "DIR_/tmp")
	// A foreground command must stop without killing the interactive shell.
	write(3, "sleep 30\r")
	time.Sleep(100 * time.Millisecond)
	write(4, "\x03")
	waitOutput(t, m, &offset, "phone $ ")
	m.handle(request{Op: "resize", ID: testID, Columns: 110, Rows: 37})
	write(5, "stty size\r")
	waitOutput(t, m, &offset, "37 110")
	write(6, "printf 'UTF_%s\\n' 中文\r")
	waitOutput(t, m, &offset, "UTF_中文")
	m.handle(request{Op: "close", ID: testID})
	if m.handle(request{Op: "read", ID: testID, Offset: offset})["state"] != "closed" {
		t.Fatal("not closed")
	}
}
