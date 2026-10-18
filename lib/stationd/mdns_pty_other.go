//go:build !darwin

package stationd

import (
	"errors"
	"os"
)

func openPty() (*os.File, *os.File, error) {
	return nil, nil, errors.New("pty")
}
