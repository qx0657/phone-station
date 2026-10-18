package stationd

import (
	"io"
	"net"
	"os"
	"path/filepath"
	"testing"
)

func TestPeerDecision(t *testing.T) {
	if err := PeerDecision(1, 2, "/bin/a", "/bin/a", "", "", "", ""); err == nil || err.Error() != "uid" {
		t.Fatal(err)
	}
	if err := PeerDecision(1, 1, "/bin/a", "/bin/a", "", "", "", ""); err != nil {
		t.Fatal(err)
	}
	if err := PeerDecision(1, 1, "/bin/a", "/bin/b", "stationd", "TEAM", "stationd", "TEAM"); err != nil {
		t.Fatal(err)
	}
	if err := PeerDecision(1, 1, "/bin/a", "/bin/b", "stationd", "", "stationd", ""); err == nil || err.Error() != "signature" {
		t.Fatal(err)
	}
}

func TestControlSocketAcceptsSameUser(t *testing.T) {
	dir, err := os.MkdirTemp("/tmp", "stationd")
	if err != nil {
		t.Fatal(err)
	}
	defer os.RemoveAll(dir)
	path := filepath.Join(dir, "c.sock")
	ln, err := ListenControl(path)
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	info, err := os.Stat(path)
	if err != nil || info.Mode().Perm() != 0600 {
		t.Fatal(info.Mode())
	}
	passed := make(chan error, 1)
	readDone := make(chan error, 1)
	go func() {
		conn, err := AcceptChecked(ln, AllowPeer)
		passed <- err
		if err != nil {
			return
		}
		defer conn.Close()
		buf := make([]byte, 4)
		_, err = io.ReadFull(conn, buf)
		readDone <- err
	}()
	client, err := net.DialUnix("unix", nil, &net.UnixAddr{Name: path, Net: "unix"})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := client.Write([]byte("ping")); err != nil {
		t.Fatal(err)
	}
	if err := <-passed; err != nil {
		t.Fatal(err)
	}
	client.Close()
	if err := <-readDone; err != nil {
		t.Fatal(err)
	}
	rejected := make(chan error, 1)
	go func() {
		_, err := AcceptChecked(ln, func(*net.UnixConn) error { return os.ErrPermission })
		rejected <- err
	}()
	other, err := net.DialUnix("unix", nil, &net.UnixAddr{Name: path, Net: "unix"})
	if err != nil {
		t.Fatal(err)
	}
	other.Close()
	if err := <-rejected; err == nil {
		t.Fatal("accepted")
	}
}
