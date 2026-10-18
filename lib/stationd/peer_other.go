//go:build !darwin

package stationd

import (
	"errors"
	"net"
)

func AllowPeer(conn *net.UnixConn) error {
	return errors.New("peer")
}
