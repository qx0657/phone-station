package stationd

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net"
	"os"
	"strings"
)

// Request is one command on the local control socket.
type Request struct {
	Op      string `json:"op"`
	Serial  string `json:"serial,omitempty"`
	Local   string `json:"local,omitempty"`
	Remote  string `json:"remote,omitempty"`
	Command string `json:"command,omitempty"`
}

// Response is the single reply to a Request.
type Response struct {
	OK      bool     `json:"ok"`
	Error   string   `json:"error,omitempty"`
	Devices []Device `json:"devices,omitempty"`
	Exit    int      `json:"exit,omitempty"`
	Stdout  string   `json:"stdout,omitempty"`
}

// Server answers control commands after the peer check. It does not listen on TCP.
type Server struct {
	Socket string
	Adb    string
	Allow  func(*net.UnixConn) error
}

// Serve accepts commands until the context ends. The socket is removed on return.
func (s Server) Serve(ctx context.Context) error {
	if !absPath(s.Socket) {
		return errors.New("socket")
	}
	if s.Allow == nil {
		s.Allow = AllowPeer
	}
	if s.Adb == "" {
		s.Adb = DefaultAdb()
	}
	ln, err := ListenControl(s.Socket)
	if err != nil {
		return err
	}
	defer os.Remove(s.Socket)
	defer ln.Close()
	go func() {
		<-ctx.Done()
		ln.Close()
	}()
	for {
		conn, err := ln.AcceptUnix()
		if err != nil {
			if ctx.Err() != nil {
				return nil
			}
			return err
		}
		go s.client(conn)
	}
}

func (s Server) client(conn *net.UnixConn) {
	defer conn.Close()
	if err := s.Allow(conn); err != nil {
		writeResponse(conn, Response{Error: err.Error()})
		return
	}
	reader := bufio.NewReaderSize(conn, 8192)
	for {
		line, err := reader.ReadSlice('\n')
		if err != nil {
			return
		}
		if len(line) > 8192 {
			writeResponse(conn, Response{Error: "request"})
			return
		}
		var request Request
		dec := json.NewDecoder(strings.NewReader(string(line)))
		dec.DisallowUnknownFields()
		if err := dec.Decode(&request); err != nil {
			writeResponse(conn, Response{Error: "request"})
			return
		}
		writeResponse(conn, s.handle(request))
	}
}

func (s Server) handle(request Request) Response {
	ctx := context.Background()
	switch request.Op {
	case "devices":
		devices, err := Track(ctx, s.Adb)
		if err != nil {
			return Response{Error: "adb"}
		}
		return Response{OK: true, Devices: devices}
	case "forward":
		if err := Forward(ctx, s.Adb, request.Serial, request.Local, request.Remote); err != nil {
			return Response{Error: "forward"}
		}
		return Response{OK: true}
	case "shell":
		out, code, err := Shell(ctx, s.Adb, request.Serial, request.Command)
		if err != nil {
			return Response{Error: "shell"}
		}
		return Response{OK: true, Exit: code, Stdout: string(out)}
	default:
		return Response{Error: "request"}
	}
}

func writeResponse(conn net.Conn, response Response) {
	body, err := json.Marshal(response)
	if err != nil {
		return
	}
	_, _ = conn.Write(append(body, '\n'))
}

// Ask sends one command to a running server and reads one response.
func Ask(ctx context.Context, socket string, request Request) (Response, error) {
	var dialer net.Dialer
	conn, err := dialer.DialContext(ctx, "unix", socket)
	if err != nil {
		return Response{}, err
	}
	defer conn.Close()
	body, err := json.Marshal(request)
	if err != nil {
		return Response{}, err
	}
	if _, err := conn.Write(append(body, '\n')); err != nil {
		return Response{}, err
	}
	reader := bufio.NewReaderSize(conn, 8192)
	line, err := reader.ReadBytes('\n')
	if err != nil && !errors.Is(err, io.EOF) {
		return Response{}, err
	}
	var response Response
	if err := json.Unmarshal(line, &response); err != nil {
		return Response{}, err
	}
	return response, nil
}
