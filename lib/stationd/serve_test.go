package stationd

import (
	"context"
	"errors"
	"net"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestServeFakeAdbAndPlist(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	go serveFakeAdb(t, ln)
	dir, err := os.MkdirTemp("/tmp", "stationd")
	if err != nil {
		t.Fatal(err)
	}
	defer os.RemoveAll(dir)
	socket := filepath.Join(dir, "s")
	ctx, cancel := context.WithCancel(context.Background())
	errc := make(chan error, 1)
	go func() {
		errc <- (Server{Socket: socket, Adb: ln.Addr().String()}).Serve(ctx)
	}()
	devices := waitDevices(t, socket)
	if len(devices.Devices) != 1 || devices.Devices[0].Serial != "emulator-5554" || !devices.OK {
		t.Fatal(devices)
	}
	text := FormatDevices(devices.Devices)
	if text != "List of devices attached\nemulator-5554 device product:fake\n\n" {
		t.Fatalf("%q", text)
	}
	forwarded, err := Ask(context.Background(), socket, Request{Op: "forward", Serial: "emulator-5554", Local: "18766", Remote: "8765"})
	if err != nil || !forwarded.OK {
		t.Fatal(forwarded, err)
	}
	shell, err := Ask(context.Background(), socket, Request{Op: "shell", Serial: "emulator-5554", Command: "echo ok"})
	if err != nil || !shell.OK || shell.Exit != 0 || shell.Stdout != "ok" {
		t.Fatal(shell, err)
	}
	cancel()
	select {
	case err := <-errc:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("serve")
	}
	if err := (Server{Socket: "station.sock"}).Serve(context.Background()); err == nil || err.Error() != "socket" {
		t.Fatal(err)
	}
	plist, err := LaunchAgentPlist("/usr/local/bin/station", socket)
	if err != nil || !strings.Contains(plist, AgentLabel) || !strings.Contains(plist, "<key>RunAtLoad</key>") || !strings.Contains(plist, socket) {
		t.Fatal(err, plist)
	}
	if strings.Contains(plist, "0.0.0.0") || strings.Contains(plist, "18765") {
		t.Fatal(plist)
	}
	if _, err := LaunchAgentPlist("station", socket); err == nil {
		t.Fatal("relative")
	}
	bundle := BundleAgentPlist()
	if !strings.Contains(bundle, "BundleProgram") || !strings.Contains(bundle, BundleProgram) || !strings.Contains(bundle, "<string>serve</string>") {
		t.Fatal(bundle)
	}
	if strings.Contains(bundle, "0.0.0.0") || strings.Contains(bundle, "18765") {
		t.Fatal(bundle)
	}
	homeSocket, err := DefaultSocket()
	if err != nil || !strings.HasSuffix(homeSocket, "/Library/Application Support/phone-station/stationd.sock") {
		t.Fatal(homeSocket, err)
	}
}

func TestServeRejectsPeer(t *testing.T) {
	dir, err := os.MkdirTemp("/tmp", "stationd")
	if err != nil {
		t.Fatal(err)
	}
	defer os.RemoveAll(dir)
	socket := filepath.Join(dir, "s")
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	errc := make(chan error, 1)
	go func() {
		errc <- (Server{
			Socket: socket,
			Adb:    "127.0.0.1:1",
			Allow:  func(*net.UnixConn) error { return errors.New("uid") },
		}).Serve(ctx)
	}()
	deadline := time.Now().Add(2 * time.Second)
	var response Response
	for {
		response, err = Ask(context.Background(), socket, Request{Op: "devices"})
		if err == nil || time.Now().After(deadline) {
			break
		}
		time.Sleep(20 * time.Millisecond)
	}
	if err != nil || response.OK || response.Error != "uid" {
		t.Fatal(response, err)
	}
	cancel()
	select {
	case err := <-errc:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("serve")
	}
}

func waitDevices(t *testing.T, socket string) Response {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	var last error
	for time.Now().Before(deadline) {
		response, err := Ask(context.Background(), socket, Request{Op: "devices"})
		if err == nil {
			return response
		}
		last = err
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatal(last)
	return Response{}
}
