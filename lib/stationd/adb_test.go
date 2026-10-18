package stationd

import (
	"context"
	"encoding/binary"
	"io"
	"net"
	"strconv"
	"testing"
)

func TestDisconnectUsesHostService(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	got := make(chan string, 2)
	go func() {
		for i := 0; i < 2; i++ {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			service := readService(t, conn)
			writeOK(t, conn)
			conn.Close()
			got <- service
		}
	}()
	addr := ln.Addr().String()
	if err := Disconnect(context.Background(), addr, "192.168.0.8:5555"); err != nil {
		t.Fatal(err)
	}
	if err := Disconnect(context.Background(), addr, ""); err != nil {
		t.Fatal(err)
	}
	if <-got != "host:disconnect:192.168.0.8:5555" || <-got != "host:disconnect:" {
		t.Fatal("service")
	}
	if err := Disconnect(context.Background(), addr, "bad serial"); err == nil {
		t.Fatal("serial")
	}
	if err := Disconnect(context.Background(), "192.0.2.8:5037", ""); err == nil || err.Error() != "adb" {
		t.Fatal(err)
	}
}

func TestFakeAdbTrackForwardAndShell(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	go serveFakeAdb(t, ln)
	addr := ln.Addr().String()
	devices, err := Track(context.Background(), addr)
	if err != nil || len(devices) != 1 || devices[0].Serial != "emulator-5554" || devices[0].State != "device" {
		t.Fatal(devices, err)
	}
	if err := Forward(context.Background(), addr, "emulator-5554", "18766", "8765"); err != nil {
		t.Fatal(err)
	}
	out, code, err := Shell(context.Background(), addr, "emulator-5554", "echo ok")
	if err != nil || code != 0 || string(out) != "ok" {
		t.Fatalf("%q %d %v", out, code, err)
	}
	if _, err := Track(context.Background(), "192.0.2.8:5037"); err == nil || err.Error() != "adb" {
		t.Fatal(err)
	}
	if err := Forward(context.Background(), addr, "bad serial", "1", "1"); err == nil {
		t.Fatal("serial")
	}
}

func serveFakeAdb(t *testing.T, ln net.Listener) {
	t.Helper()
	for i := 0; i < 3; i++ {
		conn, err := ln.Accept()
		if err != nil {
			return
		}
		go func(conn net.Conn) {
			defer conn.Close()
			service := readService(t, conn)
			switch service {
			case "host:track-devices-l":
				writeOK(t, conn)
				writeBlock(t, conn, []byte("emulator-5554 device product:fake\n"))
			case "host-serial:emulator-5554:forward:tcp:18766;tcp:8765":
				writeOK(t, conn)
			case "host-serial:emulator-5554:shell,v2,raw:echo ok":
				writeOK(t, conn)
				writeShell(t, conn, 1, []byte("ok"))
				writeShell(t, conn, 3, []byte{0})
			default:
				t.Errorf("service %s", service)
			}
		}(conn)
	}
}

func readService(t *testing.T, conn net.Conn) string {
	t.Helper()
	head := make([]byte, 4)
	if _, err := io.ReadFull(conn, head); err != nil {
		t.Fatal(err)
	}
	n, err := strconv.ParseUint(string(head), 16, 16)
	if err != nil {
		t.Fatal(err)
	}
	body := make([]byte, n)
	if _, err := io.ReadFull(conn, body); err != nil {
		t.Fatal(err)
	}
	return string(body)
}

func writeOK(t *testing.T, conn net.Conn) {
	t.Helper()
	if _, err := conn.Write([]byte("OKAY")); err != nil {
		t.Fatal(err)
	}
}

func writeBlock(t *testing.T, conn net.Conn, body []byte) {
	t.Helper()
	head := []byte(strconv.FormatInt(int64(len(body)), 16))
	for len(head) < 4 {
		head = append([]byte{'0'}, head...)
	}
	if _, err := conn.Write(append(head, body...)); err != nil {
		t.Fatal(err)
	}
}

func writeShell(t *testing.T, conn net.Conn, id byte, body []byte) {
	t.Helper()
	var head [5]byte
	head[0] = id
	binary.LittleEndian.PutUint32(head[1:], uint32(len(body)))
	if _, err := conn.Write(append(head[:], body...)); err != nil {
		t.Fatal(err)
	}
}
