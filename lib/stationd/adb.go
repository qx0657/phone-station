package stationd

import (
	"context"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"os"
	"strconv"
	"strings"
)

// Device is one line from host:track-devices-l.
type Device struct {
	Serial string `json:"serial"`
	State  string `json:"state"`
	Info   string `json:"info,omitempty"`
}

// DefaultAdb is the local adb server. A port from the environment stays on loopback.
func DefaultAdb() string {
	port := os.Getenv("ANDROID_ADB_SERVER_PORT")
	if portOK(port) {
		return "127.0.0.1:" + port
	}
	return "127.0.0.1:5037"
}

// FormatDevices matches the text of `adb devices -l`.
func FormatDevices(devices []Device) string {
	var b strings.Builder
	b.WriteString("List of devices attached\n")
	for _, device := range devices {
		b.WriteString(device.Serial)
		b.WriteByte(' ')
		b.WriteString(device.State)
		if device.Info != "" {
			b.WriteByte(' ')
			b.WriteString(device.Info)
		}
		b.WriteByte('\n')
	}
	b.WriteByte('\n')
	return b.String()
}

// Track reads one device list from an adb server. It does not start adb.
func Track(ctx context.Context, addr string) ([]Device, error) {
	conn, err := dial(ctx, addr)
	if err != nil {
		return nil, err
	}
	defer conn.Close()
	if err := call(conn, "host:track-devices-l"); err != nil {
		return nil, err
	}
	body, err := readBlock(conn)
	if err != nil {
		return nil, err
	}
	return parseDevices(string(body)), nil
}

// Disconnect drops one wireless serial, or every wireless serial when serial is empty.
// It does not disconnect a USB device and does not start adb.
func Disconnect(ctx context.Context, addr, serial string) error {
	service := "host:disconnect:"
	if serial != "" {
		if !serialOK(serial) {
			return errors.New("disconnect")
		}
		service += serial
	}
	conn, err := dial(ctx, addr)
	if err != nil {
		return err
	}
	defer conn.Close()
	if err := call(conn, service); err != nil {
		return errors.New("disconnect")
	}
	return nil
}

// Forward asks the adb server to map a local TCP port to the device.
// The stationd process does not open that port itself.
func Forward(ctx context.Context, addr, serial, local, remote string) error {
	if !serialOK(serial) || !portOK(local) || !portOK(remote) {
		return errors.New("forward")
	}
	conn, err := dial(ctx, addr)
	if err != nil {
		return err
	}
	defer conn.Close()
	return call(conn, "host-serial:"+serial+":forward:tcp:"+local+";tcp:"+remote)
}

// Shell runs one command with the adb shell v2 packet stream and returns stdout and the exit code.
func Shell(ctx context.Context, addr, serial, command string) ([]byte, int, error) {
	if !serialOK(serial) || !commandOK(command) {
		return nil, 0, errors.New("shell")
	}
	conn, err := dial(ctx, addr)
	if err != nil {
		return nil, 0, err
	}
	defer conn.Close()
	if err := call(conn, "host-serial:"+serial+":shell,v2,raw:"+command); err != nil {
		return nil, 0, err
	}
	var stdout []byte
	for {
		var head [5]byte
		if _, err := io.ReadFull(conn, head[:]); err != nil {
			return nil, 0, err
		}
		size := binary.LittleEndian.Uint32(head[1:])
		if size > 1<<20 {
			return nil, 0, errors.New("shell")
		}
		body := make([]byte, size)
		if size > 0 {
			if _, err := io.ReadFull(conn, body); err != nil {
				return nil, 0, err
			}
		}
		switch head[0] {
		case 1:
			if len(stdout)+len(body) > 1<<20 {
				return nil, 0, errors.New("shell")
			}
			stdout = append(stdout, body...)
		case 2:
		case 3:
			if size != 1 {
				return nil, 0, errors.New("shell")
			}
			return stdout, int(body[0]), nil
		default:
			return nil, 0, errors.New("shell")
		}
	}
}

func dial(ctx context.Context, addr string) (net.Conn, error) {
	host, _, err := net.SplitHostPort(addr)
	if err != nil || host != "127.0.0.1" {
		return nil, errors.New("adb")
	}
	return (&net.Dialer{}).DialContext(ctx, "tcp", addr)
}

func call(conn net.Conn, service string) error {
	if len(service) > 4096 {
		return errors.New("adb")
	}
	header := make([]byte, 4)
	text := strconv.FormatInt(int64(len(service)), 16)
	if len(text) > 4 {
		return errors.New("adb")
	}
	for i := range header {
		header[i] = '0'
	}
	copy(header[4-len(text):], text)
	if _, err := conn.Write(append(header, service...)); err != nil {
		return err
	}
	status := make([]byte, 4)
	if _, err := io.ReadFull(conn, status); err != nil {
		return err
	}
	switch string(status) {
	case "OKAY":
		return nil
	case "FAIL":
		_, _ = readBlock(conn)
		return errors.New("adb")
	default:
		return errors.New("adb")
	}
}

func readBlock(conn net.Conn) ([]byte, error) {
	head := make([]byte, 4)
	if _, err := io.ReadFull(conn, head); err != nil {
		return nil, err
	}
	n, err := strconv.ParseUint(string(head), 16, 16)
	if err != nil {
		return nil, errors.New("adb")
	}
	body := make([]byte, n)
	if _, err := io.ReadFull(conn, body); err != nil {
		return nil, err
	}
	return body, nil
}

func parseDevices(text string) []Device {
	var out []Device
	for _, line := range strings.Split(text, "\n") {
		line = strings.TrimRight(line, "\r")
		if line == "" {
			continue
		}
		parts := strings.SplitN(line, " ", 3)
		if len(parts) < 2 || parts[0] == "" || parts[1] == "" {
			continue
		}
		info := ""
		if len(parts) == 3 {
			info = parts[2]
		}
		out = append(out, Device{Serial: parts[0], State: parts[1], Info: info})
	}
	return out
}

func serialOK(serial string) bool {
	if serial == "" || len(serial) > 128 {
		return false
	}
	for _, r := range serial {
		if (r < 'a' || r > 'z') && (r < 'A' || r > 'Z') && (r < '0' || r > '9') && r != ':' && r != '.' && r != '_' && r != '-' {
			return false
		}
	}
	return true
}

func portOK(port string) bool {
	n, err := strconv.Atoi(port)
	return err == nil && n > 0 && n <= 65535 && strconv.Itoa(n) == port
}

func commandOK(command string) bool {
	if command == "" || len(command) > 4096 || strings.ContainsAny(command, "\r\n") {
		return false
	}
	return true
}
