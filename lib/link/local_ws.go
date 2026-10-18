package link

import (
	"bufio"
	"crypto/rand"
	"crypto/sha1"
	"encoding/base64"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"strings"
	"time"
)

const wsGUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

// wsConn is a binary WebSocket on one TCP connection. Compression stays off.
// The phone test server uses this instead of a Java-WebSocket server.
type wsConn struct {
	c      net.Conn
	r      *bufio.Reader
	client bool
	write  chan struct{}
}

func newWS(conn net.Conn, client bool) *wsConn {
	return &wsConn{c: conn, r: bufio.NewReader(conn), client: client, write: make(chan struct{}, 1)}
}

func (w *wsConn) Close() error {
	return w.c.Close()
}

func (w *wsConn) relax() {
	_ = w.c.SetDeadline(time.Time{})
}

func (w *wsConn) ReadFrame() (Frame, error) {
	payload, err := w.readData()
	if err != nil {
		return Frame{}, err
	}
	return Decode(payload, MainPayload)
}

func (w *wsConn) WriteFrame(frame Frame) error {
	raw, err := Encode(frame, MainPayload)
	if err != nil {
		return err
	}
	return w.writeData(raw)
}

func dialWS(addr string) (*wsConn, error) {
	conn, err := net.Dial("tcp", addr)
	if err != nil {
		return nil, err
	}
	_ = conn.SetDeadline(time.Now().Add(5 * time.Second))
	if tcp, ok := conn.(*net.TCPConn); ok {
		_ = tcp.SetNoDelay(true)
	}
	key := make([]byte, 16)
	if _, err := rand.Read(key); err != nil {
		conn.Close()
		return nil, err
	}
	nonce := base64.StdEncoding.EncodeToString(key)
	host := addr
	req := "GET " + LocalPath + " HTTP/1.1\r\n" +
		"Host: " + host + "\r\n" +
		"Upgrade: websocket\r\n" +
		"Connection: Upgrade\r\n" +
		"Sec-WebSocket-Key: " + nonce + "\r\n" +
		"Sec-WebSocket-Version: 13\r\n\r\n"
	if _, err := io.WriteString(conn, req); err != nil {
		conn.Close()
		return nil, err
	}
	ws := newWS(conn, true)
	status, headers, err := readHead(ws.r)
	if err != nil {
		conn.Close()
		return nil, err
	}
	if !strings.HasPrefix(status, "HTTP/1.1 101 ") {
		conn.Close()
		return nil, errors.New(statusCode(status))
	}
	if !headerHas(headers, "Upgrade", "websocket") || !headerHas(headers, "Connection", "upgrade") {
		conn.Close()
		return nil, errors.New("upgrade")
	}
	if headers["sec-websocket-accept"] != wsAccept(nonce) {
		conn.Close()
		return nil, errors.New("accept")
	}
	return ws, nil
}

func acceptWS(conn net.Conn) (*wsConn, error) {
	_ = conn.SetDeadline(time.Now().Add(5 * time.Second))
	if tcp, ok := conn.(*net.TCPConn); ok {
		_ = tcp.SetNoDelay(true)
	}
	ws := newWS(conn, false)
	method, target, headers, err := readRequest(ws.r)
	if err != nil {
		writeStatus(conn, 400, "bad request")
		return nil, err
	}
	origin := false
	if _, ok := headers["origin"]; ok {
		origin = true
	}
	if err := AdmitLocal(method, target, headers["host"], origin); err != nil {
		status := 400
		switch err.Error() {
		case "method":
			status = 405
		case "path":
			status = 404
		case "host", "origin":
			status = 403
		}
		writeStatus(conn, status, err.Error())
		return nil, err
	}
	if !headerHas(headers, "Upgrade", "websocket") || !headerHas(headers, "Connection", "upgrade") || headers["sec-websocket-version"] != "13" {
		writeStatus(conn, 400, "upgrade")
		return nil, errors.New("upgrade")
	}
	rawKey := headers["sec-websocket-key"]
	decoded, err := base64.StdEncoding.DecodeString(rawKey)
	if err != nil || len(decoded) != 16 {
		writeStatus(conn, 400, "key")
		return nil, errors.New("key")
	}
	resp := "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + wsAccept(rawKey) + "\r\n\r\n"
	if _, err := io.WriteString(conn, resp); err != nil {
		return nil, err
	}
	return ws, nil
}

func (w *wsConn) readData() ([]byte, error) {
	for {
		header := make([]byte, 2)
		if _, err := io.ReadFull(w.r, header); err != nil {
			return nil, err
		}
		if header[0]&0x70 != 0 || header[0]&0x80 == 0 {
			return nil, errors.New("frame")
		}
		opcode := header[0] & 0x0f
		masked := header[1]&0x80 != 0
		if w.client == masked {
			return nil, errors.New("mask")
		}
		length := int(header[1] & 0x7f)
		if length == 127 {
			return nil, errors.New("length")
		}
		if length == 126 {
			ext := make([]byte, 2)
			if _, err := io.ReadFull(w.r, ext); err != nil {
				return nil, err
			}
			length = int(binary.BigEndian.Uint16(ext))
		}
		if length < 0 || length > HeaderSize+MainPayload {
			return nil, errors.New("length")
		}
		var mask [4]byte
		if masked {
			if _, err := io.ReadFull(w.r, mask[:]); err != nil {
				return nil, err
			}
		}
		payload := make([]byte, length)
		if _, err := io.ReadFull(w.r, payload); err != nil {
			return nil, err
		}
		if masked {
			for i := range payload {
				payload[i] ^= mask[i%4]
			}
		}
		switch opcode {
		case 0x2:
			return payload, nil
		case 0x8:
			return nil, io.EOF
		case 0x9, 0xA:
			continue
		default:
			return nil, errors.New("opcode")
		}
	}
}

func (w *wsConn) writeData(payload []byte) error {
	if len(payload) > HeaderSize+MainPayload {
		return errors.New("length")
	}
	w.write <- struct{}{}
	defer func() { <-w.write }()
	header := []byte{0x82, 0}
	if len(payload) < 126 {
		header[1] = byte(len(payload))
	} else {
		header[1] = 126
		header = append(header, byte(len(payload)>>8), byte(len(payload)))
	}
	body := payload
	if w.client {
		header[1] |= 0x80
		var mask [4]byte
		if _, err := rand.Read(mask[:]); err != nil {
			return err
		}
		body = append([]byte(nil), payload...)
		for i := range body {
			body[i] ^= mask[i%4]
		}
		header = append(header, mask[:]...)
	}
	_, err := w.c.Write(append(header, body...))
	return err
}

func wsAccept(key string) string {
	sum := sha1.Sum([]byte(key + wsGUID))
	return base64.StdEncoding.EncodeToString(sum[:])
}

func writeStatus(conn net.Conn, code int, reason string) {
	status := "400"
	switch code {
	case 403:
		status = "403"
	case 404:
		status = "404"
	case 405:
		status = "405"
	}
	_, _ = io.WriteString(conn, "HTTP/1.1 "+status+" "+reason+"\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
}

func readRequest(r *bufio.Reader) (string, string, map[string]string, error) {
	remain := 8192
	line, err := readLimitedLine(r, &remain)
	if err != nil {
		return "", "", nil, err
	}
	parts := strings.Split(line, " ")
	if len(parts) != 3 || parts[2] != "HTTP/1.1" {
		return "", "", nil, errors.New("request")
	}
	headers, err := readHeaderBlock(r, &remain)
	if err != nil {
		return "", "", nil, err
	}
	return parts[0], parts[1], headers, nil
}

func readHead(r *bufio.Reader) (string, map[string]string, error) {
	remain := 8192
	line, err := readLimitedLine(r, &remain)
	if err != nil {
		return "", nil, err
	}
	headers, err := readHeaderBlock(r, &remain)
	if err != nil {
		return "", nil, err
	}
	return line, headers, nil
}

func readHeaderBlock(r *bufio.Reader, remain *int) (map[string]string, error) {
	headers := map[string]string{}
	for {
		line, err := readLimitedLine(r, remain)
		if err != nil {
			return nil, err
		}
		if line == "" {
			return headers, nil
		}
		name, value, ok := strings.Cut(line, ":")
		if !ok || strings.TrimSpace(name) == "" {
			return nil, errors.New("header")
		}
		key := strings.ToLower(strings.TrimSpace(name))
		if _, exists := headers[key]; exists {
			return nil, errors.New("header")
		}
		headers[key] = strings.TrimSpace(value)
	}
}

func readLimitedLine(r *bufio.Reader, remain *int) (string, error) {
	var buf []byte
	for {
		if *remain <= 0 {
			return "", errors.New("header")
		}
		b, err := r.ReadByte()
		if err != nil {
			return "", err
		}
		*remain--
		buf = append(buf, b)
		if b == '\n' {
			break
		}
		if len(buf) > 8192 {
			return "", errors.New("header")
		}
	}
	if len(buf) >= 2 && buf[len(buf)-2] == '\r' {
		buf = buf[:len(buf)-2]
	} else if len(buf) > 0 {
		buf = buf[:len(buf)-1]
	}
	return string(buf), nil
}

func headerHas(headers map[string]string, name, want string) bool {
	value := strings.ToLower(headers[strings.ToLower(name)])
	for _, part := range strings.Split(value, ",") {
		if strings.TrimSpace(part) == want {
			return true
		}
	}
	return false
}

func statusCode(line string) string {
	parts := strings.SplitN(line, " ", 3)
	if len(parts) < 2 {
		return "status"
	}
	return parts[1]
}
