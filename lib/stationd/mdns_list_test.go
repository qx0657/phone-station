package stationd

import (
	"context"
	"os/exec"
	"strings"
	"testing"
	"time"
)

func TestWirelessLinesFromRecordedDNS(t *testing.T) {
	service := connectService
	spaced := "12:00:00.000  Add        2  14 local.               _adb-tls-connect._tcp.  adb-phone (2)"
	plain := "12:00:00.000  Add        2  14 local.               _adb-tls-connect._tcp.  adb-phone"
	names := BrowseNames([]string{spaced, plain, spaced, "no service here"}, service)
	if len(names) != 2 || names[0] != "adb-phone (2)" || names[1] != "adb-phone" {
		t.Fatal(names)
	}
	host, port, ok := ReachAt("3:00:00.000  adb-phone._adb-tls-connect._tcp.local. can be reached at adb-phone.local.:43333")
	if !ok || host != "adb-phone.local." || port != 43333 {
		t.Fatal(host, port, ok)
	}
	if _, _, ok := ReachAt("still looking"); ok {
		t.Fatal("reach")
	}
	if got := IPv4s("Addr 192.168.0.8 10.1.2.3"); len(got) != 2 || got[0] != "192.168.0.8" {
		t.Fatal(got)
	}
	report := ReportWireless([]Sighting{{
		Instance: "adb-phone (2)",
		Port:     43333,
		IPs:      []string{"172.19.0.1", "192.168.0.8"},
	}}, func(ip string) bool { return ip == "172.19.0.1" }, func(string) string { return "utun4" })
	if report.Code != 0 || !strings.Contains(report.Stdout, "use\tadb-phone (2)\t192.168.0.8\t43333\n") || !strings.Contains(report.Stdout, "skip\tadb-phone (2)\t172.19.0.1\tutun4\n") {
		t.Fatal(report)
	}
	onlyTun := ReportWireless([]Sighting{{Instance: "phone", Port: 1, IPs: []string{"172.19.0.1"}}}, func(string) bool { return true }, func(string) string { return "" })
	if onlyTun.Code != 2 || !strings.Contains(onlyTun.Stderr, "utun") || !strings.Contains(onlyTun.Stdout, "\ttun\n") {
		t.Fatal(onlyTun)
	}
	none := ReportWireless(nil, func(string) bool { return false }, func(string) string { return "" })
	if none.Code != 1 || !strings.Contains(none.Stderr, "没有发现无线调试") {
		t.Fatal(none)
	}
}

func TestBrowseConnectReturns(t *testing.T) {
	if _, err := exec.LookPath("dns-sd"); err != nil {
		t.Skip(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	if _, err := BrowseConnect(ctx, 300*time.Millisecond); err != nil {
		t.Fatal(err)
	}
}
