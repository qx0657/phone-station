// A shell-owned scrcpy bridge. The application supplies credentials over stdin,
// never argv or files. A broken stream ends capture and is never auto-restarted.
package main

import (
	"bufio"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"crypto/tls"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"os/exec"
	"os/signal"
	"strings"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/coder/websocket"
	"github.com/qx0657/phone-station/server/relay/media"
)

type profile struct {
	Endpoint, Pin, Token, SessionID, Server string
	Addresses                               []string
}

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGTERM, syscall.SIGINT)
	defer stop()
	if err := run(ctx); err != nil {
		fmt.Println(`{"state":"failed","reason":"视频通道已结束，请重新打开投屏"}`)
		os.Exit(1)
	}
}
func run(ctx context.Context) error {
	ctx, leaseCancel := context.WithCancel(ctx)
	defer leaseCancel()
	input := bufio.NewReaderSize(os.Stdin, 16384)
	line, readErr := input.ReadSlice('\n')
	if readErr != nil {
		return readErr
	}
	var p profile
	if err := json.Unmarshal(line, &p); err != nil {
		return err
	}
	if !media.ID.MatchString(p.SessionID) || len(p.Token) != 64 || !strings.HasPrefix(p.Endpoint, "https://") {
		return errors.New("profile")
	}
	go func() { _, _ = io.Copy(io.Discard, input); leaseCancel() }()
	pin, err := hex.DecodeString(p.Pin)
	if err != nil || len(pin) != 32 {
		return errors.New("pin")
	}
	transport := &http.Transport{DialContext: func(ctx context.Context, network, address string) (net.Conn, error) {
		if len(p.Addresses) == 0 {
			return nil, errors.New("Android DNS addresses missing")
		}
		_, port, err := net.SplitHostPort(address)
		if err != nil {
			return nil, err
		}
		for _, ip := range p.Addresses {
			if net.ParseIP(ip) == nil {
				return nil, errors.New("invalid resolved IP")
			}
			c, err := (&net.Dialer{Timeout: 5 * time.Second}).DialContext(ctx, network, net.JoinHostPort(ip, port))
			if err == nil {
				return c, nil
			}
		}
		return nil, errors.New("relay unreachable")
	}, TLSClientConfig: &tls.Config{MinVersion: tls.VersionTLS12, InsecureSkipVerify: true, VerifyConnection: func(s tls.ConnectionState) error {
		if len(s.PeerCertificates) == 0 {
			return errors.New("certificate")
		}
		actual := sha256.Sum256(s.PeerCertificates[0].RawSubjectPublicKeyInfo)
		if subtle.ConstantTimeCompare(pin, actual[:]) != 1 {
			return errors.New("pin mismatch")
		}
		return nil
	}}}
	dialCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	ws, _, err := websocket.Dial(dialCtx, strings.Replace(p.Endpoint, "https://", "wss://", 1)+"/v1/screen/phone/"+p.SessionID, &websocket.DialOptions{
		HTTPClient: &http.Client{Transport: transport, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }},
		HTTPHeader: http.Header{"Authorization": []string{"Bearer " + p.Token}},
	})
	cancel()
	p.Token = ""
	if err != nil {
		return err
	}
	defer ws.CloseNow()
	ws.SetReadLimit(8192)
	readyCtx, readyCancel := context.WithTimeout(ctx, 20*time.Second)
	kind, ready, readyErr := ws.Read(readyCtx)
	readyCancel()
	if readyErr != nil || kind != websocket.MessageBinary || len(ready) != 0 {
		return errors.New("desktop not ready")
	}
	ctx, cancel = context.WithTimeout(ctx, time.Hour)
	defer cancel()
	var random [4]byte
	_, err = rand.Read(random[:])
	if err != nil {
		return err
	}
	scid := binary.BigEndian.Uint32(random[:]) & 0x7fffffff
	cmd := exec.CommandContext(ctx, "/system/bin/app_process", "/", "com.genymobile.scrcpy.Server", "4.1",
		fmt.Sprintf("scid=%08x", scid), "tunnel_forward=true", "send_dummy_byte=false", "send_device_meta=false",
		"video_codec=h264", "audio_codec=aac", "audio=true", "control=true", "clipboard_autosync=false",
		"max_size=1600", "max_fps=30", "video_codec_options=i-frame-interval=1", "video_bit_rate=4000000", "audio_bit_rate=128000", "cleanup=false", "power_on=false", "log_level=error")
	cmd.Env = append(os.Environ(), "CLASSPATH="+p.Server)
	// Do not forward backend stderr: errors may contain environment or device content.
	cmd.Stdout = io.Discard
	cmd.Stderr = io.Discard
	if err = cmd.Start(); err != nil {
		return err
	}
	defer func() { _ = cmd.Process.Kill(); _ = cmd.Wait() }()
	sockets := make([]net.Conn, 0, 3)
	defer func() {
		for _, s := range sockets {
			_ = s.Close()
		}
	}()
	deadline := time.Now().Add(8 * time.Second)
	for len(sockets) < 3 {
		s, e := net.DialTimeout("unix", fmt.Sprintf("\x00scrcpy_%08x", scid), time.Second)
		if e != nil {
			if time.Now().After(deadline) {
				return e
			}
			select {
			case <-ctx.Done():
				return ctx.Err()
			case <-time.After(50 * time.Millisecond):
			}
			continue
		}
		sockets = append(sockets, s)
	}
	// Closing all sockets is what wakes blocked scrcpy reads on cancellation.
	go func() {
		<-ctx.Done()
		ws.CloseNow()
		for _, s := range sockets {
			_ = s.Close()
		}
	}()
	var frames atomic.Uint64
	done := make(chan error, 3)
	go func() { done <- stream(ctx, ws, sockets[0], 0, &frames) }()
	go func() { done <- stream(ctx, ws, sockets[1], 2, nil) }()
	go func() { done <- controls(ctx, ws, sockets[2]) }()
	// Ignore unsolicited backend clipboard events; they are not part of this session.
	go func() { _, _ = io.Copy(io.Discard, sockets[2]) }()
	writer := bufio.NewWriter(os.Stdout)
	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()
	for {
		select {
		case err = <-done:
			cancel()
			return err
		case <-ctx.Done():
			return ctx.Err()
		case <-ticker.C:
			if time.Now().Unix()%5 == 0 {
				pingCtx, endPing := context.WithTimeout(ctx, 5*time.Second)
				err = ws.Ping(pingCtx)
				endPing()
				if err != nil {
					return err
				}
			}
			fmt.Fprintf(writer, "{\"state\":\"running\",\"videoFrames\":%d}\n", frames.Load())
			_ = writer.Flush()
		}
	}
}
func stream(ctx context.Context, ws *websocket.Conn, r io.Reader, channel byte, frames *atomic.Uint64) error {
	var codec [4]byte
	if _, err := io.ReadFull(r, codec[:]); err != nil {
		if channel == 2 {
			<-ctx.Done()
			return ctx.Err()
		}
		return err
	}
	if err := send(ctx, ws, append([]byte{channel}, codec[:]...)); err != nil {
		return err
	}
	if channel == 2 && binary.BigEndian.Uint32(codec[:]) < 2 {
		// Audio capture is optional; do not end the video when Android refuses audio.
		<-ctx.Done()
		return ctx.Err()
	}
	for {
		header := make([]byte, 12)
		if _, err := io.ReadFull(r, header); err != nil {
			return err
		}
		flags := binary.BigEndian.Uint64(header[:8])
		size := binary.BigEndian.Uint32(header[8:])
		if flags&(1<<63) != 0 {
			if channel != 0 {
				return errors.New("audio session")
			}
			size = 0
		}
		if size > media.MaxPacket-13 {
			return errors.New("frame too large")
		}
		packet := make([]byte, 13+int(size))
		packet[0] = channel + 1
		copy(packet[1:], header)
		if _, err := io.ReadFull(r, packet[13:]); err != nil {
			return err
		}
		if err := send(ctx, ws, packet); err != nil {
			return err
		}
		if frames != nil && flags&(1<<63|1<<62) == 0 {
			frames.Add(1)
		}
	}
}
func send(ctx context.Context, ws *websocket.Conn, data []byte) error {
	ctx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	return ws.Write(ctx, websocket.MessageBinary, data)
}
func controls(ctx context.Context, ws *websocket.Conn, w io.Writer) error {
	for {
		kind, data, err := ws.Read(ctx)
		if err != nil {
			return err
		}
		if kind != websocket.MessageBinary || !validControl(data) {
			return errors.New("invalid input")
		}
		if _, err = w.Write(data); err != nil {
			return err
		}
	}
}
func validControl(b []byte) bool {
	if len(b) == 0 {
		return false
	}
	switch b[0] {
	case 0:
		return len(b) == 14 && b[1] <= 1
	case 1:
		return len(b) >= 5 && len(b) <= 4096 && int(binary.BigEndian.Uint32(b[1:5])) == len(b)-5
	case 2:
		return len(b) == 32 && (b[1] == 0 || b[1] == 1 || b[1] == 2)
	case 3:
		return len(b) == 21
	case 4:
		return len(b) == 2 && b[1] <= 1
	}
	return false
}
