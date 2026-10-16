package main

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestUsageReconnectCountsOnlyTheReturn(t *testing.T) {
	var usage usageCounters
	usage.noteLink("local", false, false)
	usage.noteLink("local", false, true)
	usage.noteLink("local", true, true)
	usage.noteLink("local", true, false)
	usage.noteLink("remote", false, true)
	if usage.snapshot().Reconnects["local"] != 0 || usage.snapshot().Reconnects["remote"] != 0 {
		t.Fatalf("first connection is not a reconnect: %+v", usage.snapshot())
	}
	usage.noteLink("local", false, true)
	usage.noteLink("local", true, false)
	usage.noteLink("local", false, true)
	snap := usage.snapshot()
	if snap.Reconnects["local"] != 2 || snap.Reconnects["remote"] != 0 {
		t.Fatalf("reconnects = %+v", snap.Reconnects)
	}
}

func TestUsageSnapshotShapeHasNoSecrets(t *testing.T) {
	var usage usageCounters
	usage.request("local")
	usage.request("localProbe")
	usage.process("keychain")
	body, err := json.Marshal(usage.snapshot())
	if err != nil {
		t.Fatal(err)
	}
	text := string(body)
	if strings.Contains(text, "token") || strings.Contains(text, "Bearer") || strings.Contains(text, "secret") {
		t.Fatal(text)
	}
	var snap usageSnapshot
	if json.Unmarshal(body, &snap) != nil || snap.Requests["local"] != 1 || snap.Requests["localProbe"] != 1 ||
		snap.Requests["remote"] != 0 || snap.Processes["keychain"] != 1 || snap.Reconnects["local"] != 0 {
		t.Fatalf("%s", text)
	}
}

func TestStatusSafeDropsTokenAndKeepsUsage(t *testing.T) {
	gateway := usageSnapshot{Requests: map[string]uint64{"local": 3}, Processes: map[string]uint64{"keychain": 1}, Reconnects: map[string]uint64{"remote": 2}}
	app := &appUsage{Processes: appProcesses{Adb: 4, Script: 1}, Reconnects: appReconnects{Link: 1}, UpdatedAt: "2026-10-16T00:00:00Z"}
	body, err := encodeStatusSafe(statusReply{Ready: true, Mode: "local", Token: "secret-token", Endpoint: "http://127.0.0.1:18765/mcp"}, &gateway, app)
	if err != nil {
		t.Fatal(err)
	}
	text := string(body)
	if strings.Contains(text, "secret-token") || !strings.Contains(text, `"adb":4`) || !strings.Contains(text, `"local":3`) {
		t.Fatal(text)
	}
	empty, err := encodeStatusSafe(statusReply{Ready: true, Token: "secret-token"}, nil, nil)
	if err != nil || strings.Contains(string(empty), "usage") || strings.Contains(string(empty), "secret-token") {
		t.Fatalf("err=%v body=%s", err, empty)
	}
}

func TestLoadAppUsageRejectsGarbage(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "usage-app.json")
	good := []byte(`{"processes":{"adb":2,"script":1,"other":0},"reconnects":{"link":3},"updatedAt":"2026-10-16T00:00:00Z"}`)
	if err := os.WriteFile(path, good, 0o600); err != nil {
		t.Fatal(err)
	}
	loaded, err := loadAppUsage(path)
	if err != nil || loaded.Processes.Adb != 2 || loaded.Processes.Script != 1 || loaded.Reconnects.Link != 3 {
		t.Fatalf("err=%v loaded=%+v", err, loaded)
	}
	latency := []byte(`{"processes":{"adb":0,"script":0,"other":0},"reconnects":{"link":0},"clipboardLatency":{"samples":2,"lastMs":310,"uncertaintyMs":12,"maxMs":540},"updatedAt":"2026-10-16T00:00:00Z"}`)
	if err := os.WriteFile(path, latency, 0o600); err != nil {
		t.Fatal(err)
	}
	loaded, err = loadAppUsage(path)
	if err != nil || loaded.ClipboardLatency == nil || loaded.ClipboardLatency.LastMs != 310 || loaded.ClipboardLatency.Samples != 2 {
		t.Fatalf("err=%v loaded=%+v", err, loaded)
	}
	for _, bad := range []string{
		`{"processes":{"adb":0,"script":0,"other":0},"reconnects":{"link":0},"clipboardLatency":{"samples":1,"lastMs":-5,"uncertaintyMs":0,"maxMs":0},"updatedAt":"2026-10-16T00:00:00Z"}`,
		`{"processes":{"adb":-1,"script":0,"other":0},"reconnects":{"link":0},"updatedAt":"2026-10-16T00:00:00Z"}`,
		`{"processes":{"adb":0,"script":0,"other":0},"reconnects":{"link":0},"updatedAt":""}`,
		`{"processes":{"adb":0,"script":0,"other":0},"reconnects":{"link":0},"updatedAt":"2026-10-16T00:00:00Z","token":"x"}`,
		`{"processes":{"adb":0,"script":0,"other":0},"reconnects":{"link":0},"updatedAt":"bad` + "\n" + `time"}`,
	} {
		if err := os.WriteFile(path, []byte(bad), 0o600); err != nil {
			t.Fatal(err)
		}
		if _, err := loadAppUsage(path); err == nil {
			t.Fatal(bad)
		}
	}
}

func TestUsageEndpointIsSeparateFromStatus(t *testing.T) {
	g := testGateway("", func(ctx context.Context, value config, body []byte) (relayResponse, error) {
		t.Fatal("usage must not call the phone")
		return relayResponse{}, nil
	})
	g.usage.request("localProbe")
	status := httptest.NewRequest(http.MethodGet, "/__status", nil)
	status.Host = "127.0.0.1:18765"
	status.Header.Set("Authorization", "Bearer test")
	statusResponse := httptest.NewRecorder()
	g.ServeHTTP(statusResponse, status)
	if statusResponse.Code != http.StatusOK || strings.Contains(statusResponse.Body.String(), "localProbe") {
		t.Fatalf("status leaked usage: %d %s", statusResponse.Code, statusResponse.Body.String())
	}
	usage := httptest.NewRequest(http.MethodGet, "/__usage", nil)
	usage.Host = "127.0.0.1:18765"
	usage.Header.Set("Authorization", "Bearer test")
	usageResponse := httptest.NewRecorder()
	g.ServeHTTP(usageResponse, usage)
	if usageResponse.Code != http.StatusOK || !strings.Contains(usageResponse.Body.String(), `"localProbe":1`) ||
		strings.Contains(usageResponse.Body.String(), "Bearer") || strings.Contains(usageResponse.Body.String(), "test") {
		t.Fatalf("usage: %d %s", usageResponse.Code, usageResponse.Body.String())
	}
	denied := httptest.NewRequest(http.MethodGet, "/__usage", nil)
	denied.Host = "127.0.0.1:18765"
	deniedResponse := httptest.NewRecorder()
	g.ServeHTTP(deniedResponse, denied)
	if deniedResponse.Code != http.StatusUnauthorized {
		t.Fatalf("unauthorized usage: %d", deniedResponse.Code)
	}
}
