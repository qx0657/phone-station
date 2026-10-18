package main

import (
	"context"
	"flag"
	"fmt"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"
	"time"

	"phone-station/stationd"
)

func main() {
	if len(os.Args) < 2 {
		usage()
		os.Exit(2)
	}
	switch os.Args[1] {
	case "devices":
		devices()
	case "disconnect":
		disconnect()
	case "forward":
		forward()
	case "shell":
		shell()
	case "serve":
		serve()
	case "plist":
		plist()
	case "plist-bundle":
		fmt.Print(stationd.BundleAgentPlist())
	case "wireless":
		wireless()
	case "screen":
		screen()
	case "mcp":
		if err := stationd.ServeMCP(os.Stdin, os.Stdout, loadGatewayCredential); err != nil {
			fmt.Fprintln(os.Stderr, "mcp")
			os.Exit(1)
		}
	default:
		usage()
		os.Exit(2)
	}
}

func disconnect() {
	serial := ""
	switch len(os.Args) {
	case 2:
	case 3:
		serial = os.Args[2]
	default:
		usage()
		os.Exit(2)
	}
	if err := stationd.Disconnect(context.Background(), stationd.DefaultAdb(), serial); err != nil {
		fmt.Fprintln(os.Stderr, "disconnect")
		os.Exit(1)
	}
	devices()
}

func devices() {
	list, err := stationd.Track(context.Background(), stationd.DefaultAdb())
	if err != nil {
		fmt.Fprintln(os.Stderr, "adb")
		os.Exit(1)
	}
	fmt.Print(stationd.FormatDevices(list))
}

func forward() {
	if len(os.Args) != 5 {
		usage()
		os.Exit(2)
	}
	if err := stationd.Forward(context.Background(), stationd.DefaultAdb(), os.Args[2], os.Args[3], os.Args[4]); err != nil {
		fmt.Fprintln(os.Stderr, "forward")
		os.Exit(1)
	}
}

func shell() {
	if len(os.Args) < 4 {
		usage()
		os.Exit(2)
	}
	out, code, err := stationd.Shell(context.Background(), stationd.DefaultAdb(), os.Args[2], strings.Join(os.Args[3:], " "))
	if err != nil {
		fmt.Fprintln(os.Stderr, "shell")
		os.Exit(1)
	}
	os.Stdout.Write(out)
	os.Exit(code)
}

func serve() {
	fs := flag.NewFlagSet("serve", flag.ExitOnError)
	socket := fs.String("socket", "", "unix socket path")
	adb := fs.String("adb", stationd.DefaultAdb(), "local adb server")
	_ = fs.Parse(os.Args[2:])
	sock := *socket
	if sock == "" {
		var err error
		sock, err = stationd.DefaultSocket()
		if err != nil {
			fmt.Fprintln(os.Stderr, "socket")
			os.Exit(1)
		}
	}
	if err := os.MkdirAll(filepath.Dir(sock), 0o700); err != nil {
		fmt.Fprintln(os.Stderr, "socket")
		os.Exit(1)
	}
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	if err := (stationd.Server{Socket: sock, Adb: *adb}).Serve(ctx); err != nil {
		fmt.Fprintln(os.Stderr, "serve")
		os.Exit(1)
	}
}

func plist() {
	fs := flag.NewFlagSet("plist", flag.ExitOnError)
	binary := fs.String("binary", "", "station binary")
	socket := fs.String("socket", "", "unix socket path")
	_ = fs.Parse(os.Args[2:])
	text, err := stationd.LaunchAgentPlist(*binary, *socket)
	if err != nil {
		fmt.Fprintln(os.Stderr, "plist")
		os.Exit(1)
	}
	fmt.Print(text)
}

func screen() {
	info, err := os.Stdin.Stat()
	hasFrame := err == nil && info.Mode()&os.ModeCharDevice == 0
	if stationd.RouteScreen(hasFrame) != "native" {
		fmt.Fprintln(os.Stderr, "本地画面桥还没有接到手机")
		os.Exit(2)
	}
	addr, stop, err := stationd.ServeNative(os.Stdin)
	if err != nil {
		fmt.Fprintln(os.Stderr, "screen")
		os.Exit(2)
	}
	defer stop()
	fmt.Println(addr)
	wait := make(chan os.Signal, 1)
	signal.Notify(wait, syscall.SIGINT, syscall.SIGTERM)
	<-wait
}

func wireless() {
	report, err := stationd.WirelessNow(context.Background(), 4*time.Second)
	if err != nil {
		os.Exit(3)
	}
	fmt.Fprint(os.Stdout, report.Stdout)
	fmt.Fprint(os.Stderr, report.Stderr)
	os.Exit(report.Code)
}

func loadGatewayCredential() (stationd.GatewayCredential, error) {
	root, err := os.UserConfigDir()
	if err != nil {
		return stationd.GatewayCredential{}, err
	}
	return stationd.LoadGatewayFile(filepath.Join(root, "Phone Station", "remote.json"))
}

func usage() {
	fmt.Fprintln(os.Stderr, "用法: station devices | disconnect [SERIAL] | wireless | forward SERIAL LOCAL REMOTE | shell SERIAL COMMAND | screen | mcp | serve [-socket PATH] | plist -binary PATH -socket PATH | plist-bundle")
}
