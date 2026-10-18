//go:build darwin

package stationd

/*
#include <libproc.h>
#include <stdint.h>
*/
import "C"
import (
	"errors"
	"net"
	"os"
	"path/filepath"
	"unsafe"

	"golang.org/x/sys/unix"
)

// AllowPeer accepts a peer with this user id and this executable, or the same
// Developer ID identifier and team. It does not treat an ad-hoc signature as notarized.
func AllowPeer(conn *net.UnixConn) error {
	uid, pid, err := peerIDs(conn)
	if err != nil {
		return err
	}
	peerPath, err := peerPath(pid)
	if err != nil {
		return err
	}
	selfPath, err := os.Executable()
	if err != nil {
		return err
	}
	if samePath(peerPath, selfPath) {
		return PeerDecision(uid, os.Getuid(), peerPath, peerPath, "", "", "", "")
	}
	peerID, peerTeam := signatureOf(peerPath)
	selfID, selfTeam := signatureOf(selfPath)
	return PeerDecision(uid, os.Getuid(), peerPath, selfPath, peerID, peerTeam, selfID, selfTeam)
}

func peerIDs(conn *net.UnixConn) (int, int, error) {
	raw, err := conn.SyscallConn()
	if err != nil {
		return 0, 0, err
	}
	var uid, pid int
	var uerr, perr error
	if err := raw.Control(func(fd uintptr) {
		cred, err := unix.GetsockoptXucred(int(fd), unix.SOL_LOCAL, unix.LOCAL_PEERCRED)
		if err != nil {
			uerr = err
			return
		}
		uid = int(cred.Uid)
		pid, perr = unix.GetsockoptInt(int(fd), unix.SOL_LOCAL, unix.LOCAL_PEERPID)
	}); err != nil {
		return 0, 0, err
	}
	if uerr != nil || perr != nil {
		return 0, 0, errors.New("peer")
	}
	return uid, pid, nil
}

func samePath(left, right string) bool {
	if left == right {
		return true
	}
	a, errA := filepath.EvalSymlinks(left)
	b, errB := filepath.EvalSymlinks(right)
	return errA == nil && errB == nil && a == b
}

func peerPath(pid int) (string, error) {
	buf := make([]byte, C.PROC_PIDPATHINFO_MAXSIZE)
	n := C.proc_pidpath(C.int(pid), unsafe.Pointer(&buf[0]), C.uint32_t(len(buf)))
	if n <= 0 {
		return "", errors.New("signature")
	}
	return string(buf[:int(n)]), nil
}
