//go:build relaycontract

package main

import (
	"crypto/sha256"
	"encoding/hex"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"

	relay "github.com/qx0657/phone-station/server/relay"
)

// The same black-box contract runs against the actual source, with real TLS,
// on-disk state, and a recreated server. It never contacts a phone or public host.
func TestRepositoryRelayContract(t *testing.T) {
	cfg := relay.Config{DeviceID: "isolated-test", PhoneToken: "phone-test-token-000", DesktopToken: "desktop-test-token-000", StateDir: t.TempDir()}
	current, err := relay.Open(cfg)
	if err != nil {
		t.Fatal(err)
	}
	var mu sync.RWMutex
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { mu.RLock(); defer mu.RUnlock(); current.ServeHTTP(w, r) }))
	defer server.Close()
	defer func() { mu.Lock(); defer mu.Unlock(); _ = current.Close() }()
	digest := sha256.Sum256(server.Certificate().RawSubjectPublicKeyInfo)
	transport := pinnedTransport(hex.EncodeToString(digest[:]))
	defer transport.CloseIdleConnections()
	peer := contractPeer{endpoint: server.URL, phone: cfg.PhoneToken, desktop: cfg.DesktopToken, client: &http.Client{Timeout: 12 * time.Second, Transport: transport, CheckRedirect: rejectRelayRedirect}}
	peer.restart = func() error {
		mu.Lock()
		defer mu.Unlock()
		_ = current.Close()
		next, err := relay.Open(cfg)
		if err == nil {
			current = next
		}
		return err
	}
	if err := runRelayContract(peer); err != nil {
		t.Fatal(err)
	}
}
