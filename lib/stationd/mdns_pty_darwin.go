//go:build darwin

package stationd

/*
#include <util.h>
*/
import "C"
import (
	"errors"
	"os"
)

func openPty() (*os.File, *os.File, error) {
	var master, slave C.int
	var size C.struct_winsize
	size.ws_row = 60
	size.ws_col = 240
	if C.openpty(&master, &slave, nil, nil, &size) != 0 {
		return nil, nil, errors.New("pty")
	}
	return os.NewFile(uintptr(master), "pty-master"), os.NewFile(uintptr(slave), "pty-slave"), nil
}
