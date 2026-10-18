package main

import (
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/qx0657/phone-station/server/relay/internal/v6"
)

func main() {
	log.SetFlags(0)
	if err := run(os.Args[1:], os.Stdout); err != nil {
		log.Fatal(err)
	}
}

func run(args []string, stdout io.Writer) error {
	switch {
	case len(args) == 1 && args[0] == "--version":
		fmt.Fprintln(stdout, "phone-station-relay 6")
		return nil
	case len(args) == 1 && args[0] == "enroll":
		return enroll(stdout)
	case len(args) == 0:
		return serve()
	default:
		return errors.New("usage: phone-relay [--version|enroll]")
	}
}

func enroll(stdout io.Writer) error {
	dir := env("PHONE_RELAY_STATE_DIR", "/var/lib/phone-station-relay")
	server, err := v6.Open(dir)
	if err != nil {
		return err
	}
	defer server.Close()
	code, err := server.IssueEnroll()
	if err != nil {
		return err
	}
	_, err = fmt.Fprintln(stdout, code)
	return err
}

func serve() error {
	addr := env("PHONE_RELAY_LISTEN", "0.0.0.0:24443")
	running, err := start(addr, env("PHONE_RELAY_CERT", "/etc/phone-station-relay/server.crt"), env("PHONE_RELAY_KEY", "/etc/phone-station-relay/server.key"), env("PHONE_RELAY_STATE_DIR", "/var/lib/phone-station-relay"), os.Getenv("PHONE_RELAY_TRUSTED_PROXIES"))
	if err != nil {
		return err
	}
	log.Printf("phone station relay 6 listening on %s", running.addr)
	stopping := make(chan os.Signal, 1)
	signal.Notify(stopping, syscall.SIGINT, syscall.SIGTERM)
	<-stopping
	return running.close()
}

type running struct {
	addr   string
	server *v6.Server
	http   *http.Server
}

func start(addr, certFile, keyFile, stateDir, proxies string) (*running, error) {
	trusted, err := v6.ParseTrustedProxies(proxies)
	if err != nil {
		return nil, err
	}
	server, err := v6.Open(stateDir)
	if err != nil {
		return nil, err
	}
	server.SetTrustedProxies(trusted)
	listener, err := net.Listen("tcp", addr)
	if err != nil {
		server.Close()
		return nil, err
	}
	got, _, err := net.SplitHostPort(listener.Addr().String())
	if err != nil {
		listener.Close()
		server.Close()
		return nil, err
	}
	httpServer := &http.Server{
		Handler:           server,
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       2 * time.Minute,
		IdleTimeout:       45 * time.Second,
		MaxHeaderBytes:    16 << 10,
		TLSConfig:         &tls.Config{MinVersion: tls.VersionTLS12},
	}
	go func() {
		if err := httpServer.ServeTLS(listener, certFile, keyFile); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Printf("serve TLS: %v", err)
		}
	}()
	return &running{addr: net.JoinHostPort(got, portOf(listener)), server: server, http: httpServer}, nil
}

func (r *running) close() error {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	err := r.http.Shutdown(ctx)
	if closeErr := r.server.Close(); err == nil {
		err = closeErr
	}
	return err
}

func portOf(listener net.Listener) string {
	_, port, err := net.SplitHostPort(listener.Addr().String())
	if err != nil {
		return ""
	}
	return port
}

func env(name, fallback string) string {
	if value := os.Getenv(name); value != "" {
		return value
	}
	return fallback
}
