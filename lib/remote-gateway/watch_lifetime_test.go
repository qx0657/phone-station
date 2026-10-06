package main

import (
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestStatusWatcherStopsWhenAppPipeCloses(t *testing.T) {
	for _, headers := range []bool{false, true} {
		t.Run(map[bool]string{false: "waiting-for-headers", true: "idle-stream"}[headers], func(t *testing.T) {
			connected := make(chan struct{})
			disconnected := make(chan struct{})
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.Header.Get("Authorization") != "Bearer test" {
					t.Error("missing authorization")
				}
				if headers {
					_, _ = io.WriteString(w, `{"ready":true}`+"\n")
					w.(http.Flusher).Flush()
				}
				close(connected)
				<-r.Context().Done()
				close(disconnected)
			}))
			defer server.Close()
			reader, writer := io.Pipe()
			defer reader.Close()
			defer writer.Close()
			done := make(chan error, 1)
			go func() { done <- streamJSONStatus(server.URL, "test", reader, io.Discard) }()
			select {
			case <-connected:
			case <-time.After(2 * time.Second):
				t.Fatal("watch did not connect")
			}
			select {
			case <-done:
				t.Fatal("watch exited while its owner was alive")
			case <-time.After(30 * time.Millisecond):
			}
			writer.Close() // Same EOF as the app exiting, including SIGKILL.
			select {
			case <-done:
			case <-time.After(500 * time.Millisecond):
				t.Fatal("idle watcher survived owner exit")
			}
			select {
			case <-disconnected:
			case <-time.After(time.Second):
				t.Fatal("HTTP subscription was not cancelled")
			}
		})
	}
}
