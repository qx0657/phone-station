package main

import (
	"bytes"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"io"
	"log"
	"math/big"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestVersion(t *testing.T) {
	var out bytes.Buffer
	if err := run([]string{"--version"}, &out); err != nil {
		t.Fatal(err)
	}
	if out.String() != "phone-station-relay 6\n" {
		t.Fatalf("version %q", out.String())
	}
}

func TestEnrollPrintsCodeOnce(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("PHONE_RELAY_STATE_DIR", dir)
	var logs bytes.Buffer
	log.SetOutput(&logs)
	t.Cleanup(func() { log.SetOutput(os.Stderr) })
	var out bytes.Buffer
	if err := enroll(&out); err != nil {
		t.Fatal(err)
	}
	code := strings.TrimSpace(out.String())
	if len(code) != 32 || strings.Trim(code, "0123456789abcdef") != "" {
		t.Fatal("enroll output")
	}
	body, err := os.ReadFile(filepath.Join(dir, "registry.json"))
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(body), code) || strings.Contains(logs.String(), code) {
		t.Fatal("enroll code stored")
	}
}

func TestEnrollRefusesRelay5State(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "state.json"), []byte("{}"), 0600); err != nil {
		t.Fatal(err)
	}
	t.Setenv("PHONE_RELAY_STATE_DIR", dir)
	var out bytes.Buffer
	if err := enroll(&out); err == nil || out.Len() != 0 {
		t.Fatal("enroll used an existing directory")
	}
}

func TestServeLoopbackOnlyInTest(t *testing.T) {
	dir := t.TempDir()
	certFile, keyFile := writeCert(t)
	running, err := start("127.0.0.1:0", certFile, keyFile, dir, "")
	if err != nil {
		t.Fatal(err)
	}
	defer running.close()
	host, _, err := net.SplitHostPort(running.addr)
	if err != nil || host != "127.0.0.1" {
		t.Fatalf("addr %s", running.addr)
	}
	client := &http.Client{Timeout: 2 * time.Second, Transport: &http.Transport{TLSClientConfig: &tls.Config{InsecureSkipVerify: true}}}
	response, err := client.Get("https://" + running.addr + "/v2/link")
	if err != nil {
		t.Fatal(err)
	}
	io.Copy(io.Discard, response.Body)
	response.Body.Close()
	if response.StatusCode == http.StatusNotFound {
		t.Fatal("missing /v2/link")
	}
	missing, err := client.Get("https://" + running.addr + "/mcp")
	if err != nil {
		t.Fatal(err)
	}
	io.Copy(io.Discard, missing.Body)
	missing.Body.Close()
	if missing.StatusCode != http.StatusNotFound {
		t.Fatalf("old path %d", missing.StatusCode)
	}
}

func TestBadProxyDoesNotOpenState(t *testing.T) {
	dir := t.TempDir()
	if _, err := start("127.0.0.1:0", "cert", "key", dir, "not-an-ip"); err == nil {
		t.Fatal("proxy")
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != 0 {
		t.Fatal("state created")
	}
}

func writeCert(t *testing.T) (string, string) {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	template := &x509.Certificate{
		SerialNumber: big.NewInt(1),
		Subject:      pkix.Name{CommonName: "127.0.0.1"},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(24 * time.Hour),
		IPAddresses:  []net.IP{net.ParseIP("127.0.0.1")},
		KeyUsage:     x509.KeyUsageDigitalSignature,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
	}
	der, err := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	pkcs8, err := x509.MarshalPKCS8PrivateKey(key)
	if err != nil {
		t.Fatal(err)
	}
	dir := t.TempDir()
	certFile := filepath.Join(dir, "server.crt")
	keyFile := filepath.Join(dir, "server.key")
	if err := os.WriteFile(certFile, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}), 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(keyFile, pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: pkcs8}), 0600); err != nil {
		t.Fatal(err)
	}
	return certFile, keyFile
}
