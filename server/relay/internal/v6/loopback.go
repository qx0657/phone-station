package v6

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"errors"
	"io"
	"log"
	"math/big"
	"net"
	"net/http"
	"time"
)

// Loopback is one relay 6 listener. It accepts only 127.0.0.1 and does not
// read the production relay configuration.
type Loopback struct {
	Server   *Server
	Addr     string
	Pin      []byte
	listener net.Listener
	http     *http.Server
}

// ServeLoopback binds relay 6 to 127.0.0.1. Any other address is refused
// before a socket is opened. The certificate name is that same address.
func ServeLoopback(dir, addr string) (*Loopback, error) {
	host, port, err := net.SplitHostPort(addr)
	if err != nil || host != "127.0.0.1" || port == "" {
		return nil, errors.New("listen")
	}
	server, err := Open(dir)
	if err != nil {
		return nil, err
	}
	cert, pin, err := loopbackCert()
	if err != nil {
		server.Close()
		return nil, err
	}
	listener, err := tls.Listen("tcp", net.JoinHostPort("127.0.0.1", port), &tls.Config{
		Certificates: []tls.Certificate{cert},
		MinVersion:   tls.VersionTLS12,
	})
	if err != nil {
		server.Close()
		return nil, err
	}
	got, _, err := net.SplitHostPort(listener.Addr().String())
	if err != nil || got != "127.0.0.1" {
		listener.Close()
		server.Close()
		return nil, errors.New("listen")
	}
	httpServer := &http.Server{
		Handler:           server,
		ReadHeaderTimeout: 5 * time.Second,
		ErrorLog:          log.New(io.Discard, "", 0),
	}
	go httpServer.Serve(listener)
	return &Loopback{Server: server, Addr: listener.Addr().String(), Pin: pin, listener: listener, http: httpServer}, nil
}

// Close stops the listener and closes the registry.
func (l *Loopback) Close() error {
	if l == nil {
		return nil
	}
	err := l.http.Close()
	if closeErr := l.Server.Close(); err == nil {
		err = closeErr
	}
	return err
}

func loopbackCert() (tls.Certificate, []byte, error) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return tls.Certificate{}, nil, err
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
		return tls.Certificate{}, nil, err
	}
	pkcs8, err := x509.MarshalPKCS8PrivateKey(key)
	if err != nil {
		return tls.Certificate{}, nil, err
	}
	cert, err := tls.X509KeyPair(
		pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}),
		pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: pkcs8}),
	)
	if err != nil {
		return tls.Certificate{}, nil, err
	}
	leaf, err := x509.ParseCertificate(der)
	if err != nil {
		return tls.Certificate{}, nil, err
	}
	sum := sha256.Sum256(leaf.RawSubjectPublicKeyInfo)
	return cert, sum[:], nil
}
