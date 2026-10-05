package main

import (
	"context"
	"crypto/subtle"
	"crypto/tls"
	"errors"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/qx0657/phone-station/server/relay/internal/relay"
)

func main() {
	if len(os.Args) == 2 && os.Args[1] == "--version" {
		fmt.Println("phone-station-relay 4 (durable-epochs-v1, screen-v1, strict-requests)")
		return
	}
	if len(os.Args) != 1 {
		log.Fatal("usage: phone-relay [--version]")
	}
	addr := env("PHONE_RELAY_LISTEN", "0.0.0.0:24443")
	deviceID := env("PHONE_RELAY_DEVICE_ID", "pgt-an20")
	phoneToken := os.Getenv("PHONE_RELAY_PHONE_TOKEN")
	desktopToken := os.Getenv("PHONE_RELAY_DESKTOP_TOKEN")
	certFile := env("PHONE_RELAY_CERT", "/etc/phone-station-relay/server.crt")
	keyFile := env("PHONE_RELAY_KEY", "/etc/phone-station-relay/server.key")
	if len(phoneToken) < 32 || len(desktopToken) < 32 || subtle.ConstantTimeCompare([]byte(phoneToken), []byte(desktopToken)) == 1 {
		log.Fatal("phone and desktop tokens must be distinct, random values of at least 32 characters")
	}
	if strings.ContainsAny(deviceID, "/?#") || strings.TrimSpace(deviceID) == "" {
		log.Fatal("PHONE_RELAY_DEVICE_ID must be a non-empty path segment")
	}

	handler, err := relay.Open(relay.Config{DeviceID: deviceID, PhoneToken: phoneToken, DesktopToken: desktopToken, StateDir: env("PHONE_RELAY_STATE_DIR", "/var/lib/phone-station-relay")})
	if err != nil {
		log.Fatal("cannot open durable relay state: ", err)
	}
	defer handler.Close()
	server := &http.Server{
		Addr:              addr,
		Handler:           handler,
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       2 * time.Minute,
		WriteTimeout:      4 * time.Minute,
		IdleTimeout:       45 * time.Second,
		MaxHeaderBytes:    16 << 10,
		TLSConfig:         &tls.Config{MinVersion: tls.VersionTLS12},
	}
	stopping := make(chan os.Signal, 1)
	signal.Notify(stopping, syscall.SIGINT, syscall.SIGTERM)
	go func() {
		<-stopping
		_ = handler.Close()
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = server.Shutdown(ctx)
	}()
	log.Printf("phone station relay listening on %s for device %s", addr, deviceID)
	if err := server.ListenAndServeTLS(certFile, keyFile); err != nil && !errors.Is(err, http.ErrServerClosed) {
		log.Fatal(fmt.Errorf("serve TLS: %w", err))
	}
}

func env(name, fallback string) string {
	if value := os.Getenv(name); value != "" {
		return value
	}
	return fallback
}
