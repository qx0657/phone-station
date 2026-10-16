package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"sync"
	"unicode/utf8"
)

// Counts live in the gateway process. The menu-bar app writes its own snapshot
// beside the config; status-safe reads that file and does not start processes
// or contact the phone. Numbers restart with the process that owns them.
type usageCounters struct {
	mu         sync.Mutex
	requests   map[string]uint64
	processes  map[string]uint64
	reconnects map[string]uint64
	seenUp     map[string]bool
}

type usageSnapshot struct {
	Requests   map[string]uint64 `json:"requests"`
	Processes  map[string]uint64 `json:"processes"`
	Reconnects map[string]uint64 `json:"reconnects"`
}

type appProcesses struct {
	Adb    int `json:"adb"`
	Script int `json:"script"`
	Other  int `json:"other"`
}

type appReconnects struct {
	Link int `json:"link"`
}

type appUsage struct {
	Processes  appProcesses  `json:"processes"`
	Reconnects appReconnects `json:"reconnects"`
	UpdatedAt  string        `json:"updatedAt"`
}

type statusUsage struct {
	Gateway *usageSnapshot `json:"gateway,omitempty"`
	App     *appUsage      `json:"app,omitempty"`
}

type statusSafeReport struct {
	statusReply
	Usage *statusUsage `json:"usage,omitempty"`
}

func (u *usageCounters) request(kind string) {
	u.add(&u.requests, kind)
}

func (u *usageCounters) process(kind string) {
	u.add(&u.processes, kind)
}

func (u *usageCounters) add(bucket *map[string]uint64, key string) {
	u.mu.Lock()
	defer u.mu.Unlock()
	if *bucket == nil {
		*bucket = map[string]uint64{}
	}
	(*bucket)[key]++
}

// A reconnect is a later successful link after this process has already seen
// that link up. The first success is the initial connection.
func (u *usageCounters) noteLink(kind string, wasOnline, nowOnline bool) {
	u.mu.Lock()
	defer u.mu.Unlock()
	if u.reconnects == nil {
		u.reconnects = map[string]uint64{}
	}
	if u.seenUp == nil {
		u.seenUp = map[string]bool{}
	}
	if nowOnline && !wasOnline && u.seenUp[kind] {
		u.reconnects[kind]++
	}
	if nowOnline {
		u.seenUp[kind] = true
	}
}

func (u *usageCounters) snapshot() usageSnapshot {
	u.mu.Lock()
	defer u.mu.Unlock()
	return usageSnapshot{
		Requests:   fixedCounts([]string{"local", "localProbe", "remote", "remoteProbe", "remoteStatus"}, u.requests),
		Processes:  fixedCounts([]string{"keychain"}, u.processes),
		Reconnects: fixedCounts([]string{"local", "remote"}, u.reconnects),
	}
}

func fixedCounts(keys []string, source map[string]uint64) map[string]uint64 {
	out := make(map[string]uint64, len(keys))
	for _, key := range keys {
		out[key] = source[key]
	}
	return out
}

func appUsagePath() (string, error) {
	root, err := os.UserConfigDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(root, "Phone Station", "usage-app.json"), nil
}

func loadAppUsage(path string) (*appUsage, error) {
	body, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	if len(body) == 0 || len(body) > 4096 {
		return nil, errors.New("app usage snapshot is the wrong size")
	}
	var usage appUsage
	decoder := json.NewDecoder(bytes.NewReader(body))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&usage); err != nil {
		return nil, err
	}
	if decoder.More() {
		return nil, errors.New("app usage snapshot has trailing data")
	}
	if usage.Processes.Adb < 0 || usage.Processes.Script < 0 || usage.Processes.Other < 0 || usage.Reconnects.Link < 0 {
		return nil, errors.New("app usage snapshot has a negative count")
	}
	if usage.UpdatedAt == "" || len(usage.UpdatedAt) > 40 || !utf8.ValidString(usage.UpdatedAt) {
		return nil, errors.New("app usage snapshot has no timestamp")
	}
	for _, r := range usage.UpdatedAt {
		if r < 0x20 || r == 0x7f {
			return nil, errors.New("app usage snapshot has no timestamp")
		}
	}
	return &usage, nil
}

func encodeStatusSafe(status statusReply, gateway *usageSnapshot, app *appUsage) ([]byte, error) {
	status.Token = ""
	var usage *statusUsage
	if gateway != nil || app != nil {
		usage = &statusUsage{Gateway: gateway, App: app}
	}
	var buf bytes.Buffer
	encoder := json.NewEncoder(&buf)
	encoder.SetEscapeHTML(false)
	if err := encoder.Encode(statusSafeReport{statusReply: status, Usage: usage}); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}
