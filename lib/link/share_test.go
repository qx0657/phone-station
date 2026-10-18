package link

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestSharePaths(t *testing.T) {
	got, err := DropPhonePath("photo.png")
	if err != nil || got != "/storage/emulated/0/Download/photo.png" {
		t.Fatalf("%v %s", err, got)
	}
	for _, name := range []string{"", ".", "..", "a/b", `a\b`} {
		if _, err := DropPhonePath(name); err == nil {
			t.Fatalf("accepted %q", name)
		}
	}
	name, err := ShareMacName("/storage/emulated/0/Download/photo.png")
	if err != nil || name != "photo.png" {
		t.Fatalf("%v %s", err, name)
	}
	for _, phone := range []string{"/storage/emulated/0", "/storage/emulated/0/Android/data/secret", "/tmp/a", "../a"} {
		if _, err := ShareMacName(phone); err == nil {
			t.Fatalf("accepted %s", phone)
		}
	}
}

func TestShareResumeAndFrames(t *testing.T) {
	if off, err := ResumeOffset(true, 4, 9); err != nil || off != 0 {
		t.Fatalf("upload resume %v %d", err, off)
	}
	if off, err := ResumeOffset(false, 4, 9); err != nil || off != 4 {
		t.Fatalf("download resume %v %d", err, off)
	}
	if _, err := ResumeOffset(false, 10, 9); err == nil {
		t.Fatal("offset past the end")
	}
	payload := bytes.Repeat([]byte("ab"), 50)
	frames, err := DataFrames(7, payload, 30)
	if err != nil {
		t.Fatal(err)
	}
	var joined []byte
	for i, frame := range frames {
		if frame.Kind != KindData || frame.Channel != 7 || len(frame.Payload) > 30 {
			t.Fatalf("frame %d %#v", i, frame)
		}
		last := i == len(frames)-1
		if last && frame.Flags != FlagEnd {
			t.Fatal("last frame does not end")
		}
		if !last && frame.Flags != FlagMore {
			t.Fatal("middle frame ends early")
		}
		joined = append(joined, frame.Payload...)
	}
	if !bytes.Equal(joined, payload) {
		t.Fatal("frames did not reassemble")
	}
}

func TestShareOverLink(t *testing.T) {
	dest, err := DropPhonePath("note.txt")
	if err != nil || dest != "/storage/emulated/0/Download/note.txt" {
		t.Fatal(err)
	}
	body := []byte("hello phone")
	frames, err := DataFrames(3, body, 4)
	if err != nil {
		t.Fatal(err)
	}
	left := make(chan Frame, 8)
	right := make(chan Frame, 8)
	mac := NewEndpoint(true, 64, 64, 0, time.Now())
	phone := NewEndpoint(false, 64, 64, 0, time.Now())
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 2)
	go func() { done <- mac.Serve(ctx, memConn{r: right, w: left}, time.Now) }()
	go func() { done <- phone.Serve(ctx, memConn{r: left, w: right}, time.Now) }()
	if err := mac.Open(3); err != nil {
		t.Fatal(err)
	}
	for _, frame := range frames {
		if err := mac.Send(PriorityFile, frame); err != nil {
			t.Fatal(err)
		}
	}
	deadline := time.Now().Add(2 * time.Second)
	var got Chunk
	for time.Now().Before(deadline) {
		items := phone.Inbox()
		if len(items) == 1 && items[0].End {
			got = items[0]
			break
		}
		time.Sleep(5 * time.Millisecond)
	}
	if got.Channel != 3 || !bytes.Equal(got.Payload, body) {
		t.Fatalf("phone received %#v", got)
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

func TestPutFile(t *testing.T) {
	if _, _, err := shareHeader("../x", 1, 7); err == nil {
		t.Fatal("escaped")
	}
	phone, mac := newPair(t)
	listener := listenPair(t, phone, mac)
	defer listener.Close()
	accepted := acceptAsync(listener)
	session, err := DialLocal(listener.Addr(), mac.identity, phone.peer)
	if err != nil {
		t.Fatal(err)
	}
	defer session.Close()
	remote := awaitSession(t, accepted)
	defer remote.Close()
	dir := t.TempDir()
	done := make(chan struct{})
	go func() {
		defer close(done)
		receiveShare(t, remote, dir)
	}()
	body := []byte("hello phone")
	if err := session.PutFile(context.Background(), "note.txt", body); err != nil {
		t.Fatal(err)
	}
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("receive")
	}
	got, err := os.ReadFile(filepath.Join(dir, "note.txt"))
	if err != nil || !bytes.Equal(got, body) {
		t.Fatalf("saved %v %q", err, got)
	}
}

func TestOfferFileToMac(t *testing.T) {
	phone, mac := newPair(t)
	listener := listenPair(t, phone, mac)
	defer listener.Close()
	accepted := acceptAsync(listener)
	session, err := DialLocal(listener.Addr(), mac.identity, phone.peer)
	if err != nil {
		t.Fatal(err)
	}
	defer session.Close()
	remote := awaitSession(t, accepted)
	defer remote.Close()
	dir := t.TempDir()
	session.SetShareDir(dir)
	body := []byte("from phone")
	if err := remote.PutFile(context.Background(), "note.txt", body); err != nil {
		t.Fatal(err)
	}
	got, err := os.ReadFile(filepath.Join(dir, "note.txt"))
	if err != nil || !bytes.Equal(got, body) {
		t.Fatalf("saved %v %q", err, got)
	}
}

func receiveShare(t *testing.T, phone *LocalSession, dir string) {
	t.Helper()
	var header struct {
		Type string `json:"type"`
		Name string `json:"name"`
		Size int    `json:"size"`
	}
	var body []byte
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) && (header.Type != "share" || len(body) < header.Size) {
		messages, err := phone.readMessages()
		if err != nil {
			t.Error(err)
			return
		}
		for _, raw := range messages {
			if len(raw) > 0 && raw[0] == '{' && json.Unmarshal(raw, &header) == nil && header.Type == "share" {
				continue
			}
			body = append(body, raw...)
		}
	}
	if header.Type != "share" || header.Name != "note.txt" || len(body) != header.Size {
		t.Errorf("share %s %d", header.Type, len(body))
		return
	}
	if err := os.WriteFile(filepath.Join(dir, header.Name), body, 0o600); err != nil {
		t.Error(err)
		return
	}
	reply := []byte(fmt.Sprintf(`{"type":"shared","name":"%s","size":%d}`, header.Name, len(body)))
	if err := phone.Send(reply); err != nil {
		t.Error(err)
	}
}
