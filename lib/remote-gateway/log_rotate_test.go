package main

import (
	"bytes"
	"os"
	"path/filepath"
	"testing"
)

func TestRotateGatewayLog(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "remote-gateway.log")
	if err := os.WriteFile(path, []byte("ok\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := rotateGatewayLog(dir); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(path + ".1"); !os.IsNotExist(err) {
		t.Fatal("small log was rotated")
	}
	big := bytes.Repeat([]byte("x"), gatewayLogMax)
	if err := os.WriteFile(path, big, 0o600); err != nil {
		t.Fatal(err)
	}
	if err := rotateGatewayLog(dir); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(path); !os.IsNotExist(err) {
		t.Fatal("full log stayed in place")
	}
	moved, err := os.ReadFile(path + ".1")
	if err != nil || len(moved) != gatewayLogMax {
		t.Fatalf("previous log: %v %d", err, len(moved))
	}
	next := bytes.Repeat([]byte("y"), gatewayLogMax)
	if err := os.WriteFile(path, next, 0o600); err != nil {
		t.Fatal(err)
	}
	if err := rotateGatewayLog(dir); err != nil {
		t.Fatal(err)
	}
	moved, err = os.ReadFile(path + ".1")
	if err != nil || !bytes.Equal(moved, next) {
		t.Fatal("older log was not replaced")
	}
}
