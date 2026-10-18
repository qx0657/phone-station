package stationd

import (
	"errors"
	"net"
	"os"
)

// PeerDecision is the result of comparing a Unix-socket peer with this process.
// A matching executable path is enough. Different paths need the same signing
// identifier and team. An empty team is not treated as a signature.
func PeerDecision(uid, selfUID int, peerPath, selfPath, peerID, peerTeam, selfID, selfTeam string) error {
	if uid != selfUID {
		return errors.New("uid")
	}
	if peerPath == "" || selfPath == "" {
		return errors.New("signature")
	}
	if peerPath == selfPath {
		return nil
	}
	if peerTeam == "" || selfTeam == "" || peerID == "" || selfID == "" || peerID != selfID || peerTeam != selfTeam {
		return errors.New("signature")
	}
	return nil
}

// ListenControl listens on a local filesystem socket. The caller removes the path.
func ListenControl(path string) (*net.UnixListener, error) {
	if err := os.Remove(path); err != nil && !errors.Is(err, os.ErrNotExist) {
		return nil, err
	}
	ln, err := net.ListenUnix("unix", &net.UnixAddr{Name: path, Net: "unix"})
	if err != nil {
		return nil, err
	}
	if err := os.Chmod(path, 0600); err != nil {
		ln.Close()
		return nil, err
	}
	return ln, nil
}

// AcceptChecked accepts one connection and closes it when allow returns an error.
func AcceptChecked(ln *net.UnixListener, allow func(*net.UnixConn) error) (*net.UnixConn, error) {
	conn, err := ln.AcceptUnix()
	if err != nil {
		return nil, err
	}
	if err := allow(conn); err != nil {
		conn.Close()
		return nil, err
	}
	return conn, nil
}
