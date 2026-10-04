package main

import (
	"bytes"
	"context"
	"encoding/binary"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/coder/websocket"
)

func TestStreamSessionFramesAndBounds(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	received := make(chan [][]byte, 1)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		c, e := websocket.Accept(w, r, nil)
		if e != nil {
			return
		}
		defer c.CloseNow()
		var packets [][]byte
		for i := 0; i < 4; i++ {
			_, b, e := c.Read(ctx)
			if e != nil {
				return
			}
			packets = append(packets, b)
		}
		received <- packets
	}))
	defer server.Close()
	c, _, err := websocket.Dial(ctx, strings.Replace(server.URL, "http://", "ws://", 1), nil)
	if err != nil {
		t.Fatal(err)
	}
	defer c.CloseNow()
	var raw bytes.Buffer
	binary.Write(&raw, binary.BigEndian, uint32(0x68323634))
	binary.Write(&raw, binary.BigEndian, uint32(0x80000000))
	binary.Write(&raw, binary.BigEndian, uint32(720))
	binary.Write(&raw, binary.BigEndian, uint32(1600))
	binary.Write(&raw, binary.BigEndian, uint64(1<<62))
	binary.Write(&raw, binary.BigEndian, uint32(4))
	raw.Write([]byte{0, 0, 1, 0x67})
	binary.Write(&raw, binary.BigEndian, uint64(1<<61|1234))
	binary.Write(&raw, binary.BigEndian, uint32(5))
	raw.Write([]byte{0, 0, 1, 0x65, 0})
	binary.Write(&raw, binary.BigEndian, uint64(1235))
	binary.Write(&raw, binary.BigEndian, uint32(10<<20))
	var frames atomic.Uint64
	if err = stream(ctx, c, &raw, 0, &frames); err == nil {
		t.Fatal("oversized frame accepted")
	}
	packets := <-received
	if len(packets[0]) != 5 || len(packets[1]) != 13 || packets[1][0] != 1 || frames.Load() != 1 {
		t.Fatal("session interpreted as payload or wrong frame count")
	}
}
func TestInputBoundary(t *testing.T) {
	for _, b := range [][]byte{{8, 0}, {2}, make([]byte, 13), {4, 2}, {1, 0, 0, 0, 99}} {
		if validControl(b) {
			t.Fatal("invalid control accepted")
		}
	}
	key := make([]byte, 14)
	if !validControl(key) {
		t.Fatal("key")
	}
	touch := make([]byte, 32)
	touch[0] = 2
	if !validControl(touch) {
		t.Fatal("touch")
	}
	text := []byte{1, 0, 0, 0, 3, 'a', 'b', 'c'}
	if !validControl(text) {
		t.Fatal("text")
	}
}

func TestAudioUnavailableKeepsSessionUntilCancelled(t *testing.T) {
	for _, code := range []byte{0, 1} {
		t.Run(string(rune('0'+code)), func(t *testing.T) {
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
			received := make(chan []byte, 1)
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				c, err := websocket.Accept(w, r, nil)
				if err != nil {
					return
				}
				defer c.CloseNow()
				_, packet, err := c.Read(ctx)
				if err == nil {
					received <- packet
				}
				<-ctx.Done()
			}))
			defer server.Close()
			c, _, err := websocket.Dial(ctx, strings.Replace(server.URL, "http://", "ws://", 1), nil)
			if err != nil {
				t.Fatal(err)
			}
			defer c.CloseNow()
			done := make(chan error, 1)
			go func() { done <- stream(ctx, c, bytes.NewReader([]byte{0, 0, 0, code}), 2, nil) }()
			select {
			case packet := <-received:
				if !bytes.Equal(packet, []byte{2, 0, 0, 0, 0}) {
					t.Fatal("audio failure was exposed as an unsupported codec")
				}
			case <-ctx.Done():
				t.Fatal("missing disabled audio marker")
			}
			select {
			case <-done:
				t.Fatal("optional audio ended the live session")
			default:
			}
			cancel()
			if err = <-done; err != context.Canceled {
				t.Fatal(err)
			}
		})
	}
}
