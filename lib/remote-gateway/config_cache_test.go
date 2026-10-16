package main

import (
	"encoding/json"
	"os"
	"testing"
)

func TestConfigCacheFollowsFile(t *testing.T) {
	t.Setenv("HOME", t.TempDir())
	t.Cleanup(func() { clearConfig("") })
	first := config{GatewayToken: "gateway-one", LocalToken: "local-one"}
	if err := saveConfig(first); err != nil {
		t.Fatal(err)
	}
	loaded, err := loadConfig()
	if err != nil || loaded != first {
		t.Fatal("saved config was not read back")
	}
	path, err := configPath()
	if err != nil {
		t.Fatal(err)
	}
	second := first
	second.LocalToken = "local-two"
	body, err := json.Marshal(second)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, body, 0600); err != nil {
		t.Fatal(err)
	}
	loaded, err = loadConfig()
	if err != nil || loaded != second {
		t.Fatal("changed config stayed cached")
	}
	if err := os.Remove(path); err != nil {
		t.Fatal(err)
	}
	loaded, err = loadConfig()
	if err != nil || loaded != (config{}) {
		t.Fatal("deleted config stayed cached")
	}
}

func TestRemoteTokenCache(t *testing.T) {
	const revision = "rev-cache-test"
	const token = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
	t.Cleanup(func() { forgetRemoteToken(revision) })
	rememberRemoteToken(revision, token)
	got, hit := cachedRemoteToken(revision)
	if !hit || got != token {
		t.Fatal("token cache miss")
	}
	if _, hit := cachedRemoteToken("other-revision"); hit {
		t.Fatal("token cache ignored revision")
	}
	forgetRemoteToken(revision)
	if _, hit := cachedRemoteToken(revision); hit {
		t.Fatal("forgotten token still cached")
	}
}
