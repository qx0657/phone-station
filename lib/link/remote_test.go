package link

import (
	"bytes"
	"testing"
)

func TestDialRelayStaysLocal(t *testing.T) {
	pin := bytes.Repeat([]byte{7}, 32)
	if got, err := RelayAddress("192.0.2.8:443"); err != nil || got != "192.0.2.8" {
		t.Fatal(err)
	}
	if _, err := RelayAddress("example.test:443"); err == nil || err.Error() != "host" {
		t.Fatal(err)
	}
	if _, err := RelayAddress("0.0.0.0:1"); err == nil || err.Error() != "host" {
		t.Fatal(err)
	}
	if _, err := DialRelay("192.0.2.8:443", pin, Identity{}, "main", "test"); err == nil || err.Error() != "host" {
		t.Fatal(err)
	}
	if _, err := DialRelayPinned("example.test:443", pin, Identity{}, "main", "test"); err == nil || err.Error() != "host" {
		t.Fatal(err)
	}
	if _, err := DialRelay("127.0.0.1:9", pin, Identity{}, "other", "test"); err == nil || err.Error() != "purpose" {
		t.Fatal(err)
	}
}
