package stationd

import (
	"encoding/binary"
	"errors"
	"io"
	"net"
)

const maxScreenPacket = 4 << 20

// RouteScreen picks the local mirror and record backend.
// A supplied frame uses the native decoder. Without one, the entry stays on scrcpy.
func RouteScreen(hasFrame bool) string {
	if hasFrame {
		return "native"
	}
	return "scrcpy"
}

// ReadScrcpy turns one scrcpy 4.1 socket into the packets the native decoder already accepts.
// channel 0 is video and 2 is audio, matching the phone bridge. It does not start scrcpy or adb.
func ReadScrcpy(r io.Reader, channel byte) ([][]byte, error) {
	var packets [][]byte
	err := CopyScrcpy(r, channel, func(packet []byte) error {
		packets = append(packets, packet)
		return nil
	})
	return packets, err
}

// CopyScrcpy emits each native packet as soon as it is complete.
func CopyScrcpy(r io.Reader, channel byte, emit func([]byte) error) error {
	if channel != 0 && channel != 2 || emit == nil {
		return errors.New("screen")
	}
	var codec [4]byte
	if _, err := io.ReadFull(r, codec[:]); err != nil {
		return err
	}
	if channel == 2 && binary.BigEndian.Uint32(codec[:]) < 2 {
		codec = [4]byte{}
		return emit(append([]byte{channel}, codec[:]...))
	}
	if err := emit(append([]byte{channel}, codec[:]...)); err != nil {
		return err
	}
	for {
		header := make([]byte, 12)
		if _, err := io.ReadFull(r, header); err != nil {
			if errors.Is(err, io.EOF) {
				return nil
			}
			return err
		}
		flags := binary.BigEndian.Uint64(header[:8])
		size := binary.BigEndian.Uint32(header[8:])
		if flags&(1<<63) != 0 {
			if channel != 0 {
				return errors.New("screen")
			}
			size = 0
		}
		if int(size) > maxScreenPacket-13 {
			return errors.New("screen")
		}
		packet := make([]byte, 13+int(size))
		packet[0] = channel + 1
		copy(packet[1:], header)
		if size > 0 {
			if _, err := io.ReadFull(r, packet[13:]); err != nil {
				return err
			}
		}
		if err := emit(packet); err != nil {
			return err
		}
	}
}

// ServeNative listens on 127.0.0.1 and writes length-prefixed native packets to one local client.
// It does not open a route to any other address.
func ServeNative(video io.Reader) (string, func() error, error) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return "", nil, err
	}
	go func() {
		defer ln.Close()
		conn, err := ln.Accept()
		if err != nil {
			return
		}
		defer conn.Close()
		host, _, err := net.SplitHostPort(conn.RemoteAddr().String())
		if err != nil || host != "127.0.0.1" {
			return
		}
		_ = CopyScrcpy(video, 0, func(packet []byte) error {
			var head [4]byte
			binary.BigEndian.PutUint32(head[:], uint32(len(packet)))
			if _, err := conn.Write(head[:]); err != nil {
				return err
			}
			_, err := conn.Write(packet)
			return err
		})
	}()
	return ln.Addr().String(), ln.Close, nil
}
