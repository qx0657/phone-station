package stationd

import (
	"bytes"
	"encoding/binary"
	"io"
	"net"
	"testing"
	"time"
)

func TestRouteScreen(t *testing.T) {
	if RouteScreen(true) != "native" || RouteScreen(false) != "scrcpy" {
		t.Fatal(RouteScreen(true), RouteScreen(false))
	}
}

func TestReadScrcpyVideoAndDisabledAudio(t *testing.T) {
	var video bytes.Buffer
	video.Write([]byte("h264"))
	header := make([]byte, 12)
	binary.BigEndian.PutUint64(header[:8], 1<<62)
	binary.BigEndian.PutUint32(header[8:], 3)
	video.Write(header)
	video.Write([]byte{9, 8, 7})
	raw := append([]byte(nil), video.Bytes()...)
	packets, err := ReadScrcpy(bytes.NewReader(raw), 0)
	if err != nil || len(packets) != 2 || packets[0][0] != 0 || string(packets[0][1:]) != "h264" {
		t.Fatal(packets, err)
	}
	if packets[1][0] != 1 || len(packets[1]) != 16 || string(packets[1][13:]) != string([]byte{9, 8, 7}) {
		t.Fatal(packets[1])
	}
	disabled, err := ReadScrcpy(bytes.NewReader([]byte{0, 0, 0, 1}), 2)
	if err != nil || len(disabled) != 1 || len(disabled[0]) != 5 || disabled[0][0] != 2 || disabled[0][1] != 0 {
		t.Fatal(disabled, err)
	}
	if _, err := ReadScrcpy(bytes.NewReader([]byte{0, 0, 0, 2}), 1); err == nil {
		t.Fatal("channel")
	}
	addr, stop, err := ServeNative(bytes.NewReader(raw))
	if err != nil {
		t.Fatal(err)
	}
	defer stop()
	conn, err := net.DialTimeout("tcp", addr, time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(time.Second))
	head := make([]byte, 4)
	if _, err := io.ReadFull(conn, head); err != nil {
		t.Fatal(err)
	}
	n := binary.BigEndian.Uint32(head)
	body := make([]byte, n)
	if _, err := io.ReadFull(conn, body); err != nil || n != 5 || string(body) != "\x00h264" {
		t.Fatal(n, body, err)
	}
	host, _, err := net.SplitHostPort(addr)
	if err != nil || host != "127.0.0.1" {
		t.Fatal(addr)
	}
}
