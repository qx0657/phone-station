package main

import (
	"encoding/json"
	"strconv"
	"strings"
	"sync"
	"testing"
)

func TestRemoteEndpointSupportsUserHostedRelays(t *testing.T) {
	for input, expected := range map[string]string{
		"https://relay.example.com":             "https://relay.example.com",
		" https://relay.example.com:24443/ ":    "https://relay.example.com:24443",
		"https://192.0.2.10:443/station/":       "https://192.0.2.10:443/station",
		"https://[2001:db8::1]:24443/relay/v2/": "https://[2001:db8::1]:24443/relay/v2",
		"https://localhost":                     "https://localhost",
	} {
		actual, err := normalizeRemoteEndpoint(input)
		if err != nil || actual != expected {
			t.Errorf("normalize %q: got %q, %v", input, actual, err)
		}
	}
	for _, input := range []string{"", "http://relay.example.com", "https://user@relay.example.com",
		"https://relay.example.com?token=secret", "https://relay.example.com#fragment",
		"https://relay.example.com:0", "https://relay.example.com:65536", "https://relay.example.com:",
		"https://relay.example.com/../other", "https://relay.example.com/a/./b",
		"https://relay.example.com/a b", "https://relay.example.com/$(whoami)", "https://relay.example.com/'",
		"https://[bad]", "https://a.-b", "https://a..b"} {
		if _, err := normalizeRemoteEndpoint(input); err == nil {
			t.Errorf("accepted invalid endpoint %q", input)
		}
	}
}

func TestProfileIsEmptyByDefaultAndNeverExportsTokens(t *testing.T) {
	if profile := publicRemoteProfile(config{}); profile.Configured || profile.Endpoint != "" || profile.Pin != "" {
		t.Fatalf("unexpected default relay: %+v", profile)
	}
	value := config{GatewayToken: "gateway-secret", LocalToken: "phone-secret",
		RemoteURL: "https://relay.example.com/station", RemotePin: strings.Repeat("a", 64)}
	body, err := json.Marshal(publicRemoteProfile(value))
	if err != nil {
		t.Fatal(err)
	}
	var fields map[string]any
	if err := json.Unmarshal(body, &fields); err != nil {
		t.Fatal(err)
	}
	if len(fields) != 3 || fields["configured"] != true || fields["endpoint"] != value.RemoteURL || fields["pin"] != value.RemotePin {
		t.Fatalf("unexpected public profile: %s", body)
	}
	if strings.Contains(string(body), "secret") {
		t.Fatal("profile exported a credential")
	}
}

func TestChangingRelayCannotRetainOldOnlineHealth(t *testing.T) {
	current := config{RemoteURL: "https://old.example.com", RemotePin: "pin", RemoteRevision: "one"}
	g := &gateway{readConfig: func() (config, error) { return current, nil }}
	old, _ := g.config()
	g.noteRemote(old, probeResult{ok: true})
	if g.mode() != "remote" {
		t.Fatal("old relay did not become online")
	}
	current.RemoteURL = "https://new.example.com/station"
	current.RemoteRevision = "two"
	next, _ := g.config()
	if g.mode() != "offline" {
		t.Fatal("new relay inherited old online status")
	}
	g.noteRemote(old, probeResult{ok: true})
	if g.mode() != "offline" {
		t.Fatal("old in-flight probe restored stale health")
	}
	g.noteRemote(next, probeResult{ok: true})
	if g.mode() != "remote" {
		t.Fatal("new relay did not become online")
	}
	// Credential rotation on the same server also requires a fresh probe.
	current.RemoteRevision = "three"
	_, _ = g.config()
	if g.mode() != "offline" {
		t.Fatal("rotated credentials inherited old online status")
	}
}

func TestConfigTransactionsPreserveConcurrentChanges(t *testing.T) {
	t.Setenv("HOME", t.TempDir())
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	var workers sync.WaitGroup
	for i := 0; i < 20; i++ {
		workers.Add(1)
		go func() {
			defer workers.Done()
			if err := updateConfig(func(value *config) error {
				number, _ := strconv.Atoi(value.RemoteRevision)
				value.RemoteRevision = strconv.Itoa(number + 1)
				return nil
			}); err != nil {
				t.Error(err)
			}
		}()
	}
	workers.Wait()
	value, err := loadConfig()
	if err != nil || value.RemoteRevision != "20" {
		t.Fatalf("lost config update: %+v, %v", value, err)
	}
	if err := updateLocalTokenValue(strings.Repeat("a", 32)); err != nil {
		t.Fatal(err)
	}
	value, err = loadConfig()
	if err != nil || value.RemoteRevision != "20" || value.LocalToken != strings.Repeat("a", 32) {
		t.Fatalf("local refresh lost the remote profile: %+v, %v", value, err)
	}
}
