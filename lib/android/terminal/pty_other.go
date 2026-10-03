//go:build !linux

package main

import "errors"

func startPTY(int, int, func([]byte), func(int)) (terminal, error) {
	return nil, errors.New("PTY integration runs on Linux/Android")
}
