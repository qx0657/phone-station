package stationd

import (
	"bytes"
	"context"
	"os/exec"
	"regexp"
	"strconv"
	"strings"
	"time"

	"golang.org/x/sys/unix"
)

const connectService = "_adb-tls-connect._tcp"

var (
	reachPattern = regexp.MustCompile(`can be reached at\s+(\S+?):(\d+)`)
	ipv4Pattern  = regexp.MustCompile(`\b(?:\d{1,3}\.){3}\d{1,3}\b`)
)

// BrowseNames keeps the first instance of each dns-sd Add line.
func BrowseNames(lines []string, service string) []string {
	var found []string
	seen := map[string]bool{}
	for _, line := range lines {
		if !strings.Contains(line, " Add ") {
			continue
		}
		name := InstanceName(line, service)
		if name == "" || seen[name] {
			continue
		}
		seen[name] = true
		found = append(found, name)
	}
	return found
}

// ReachAt reads a dns-sd lookup line.
func ReachAt(line string) (string, int, bool) {
	match := reachPattern.FindStringSubmatch(line)
	if match == nil {
		return "", 0, false
	}
	port, err := strconv.Atoi(match[2])
	if err != nil || port <= 0 || port > 65535 {
		return "", 0, false
	}
	return match[1], port, true
}

// IPv4s returns the addresses in one resolve line, in order.
func IPv4s(line string) []string {
	return ipv4Pattern.FindAllString(line, -1)
}

// Sighting is one wireless-debugging instance after lookup and address resolution.
type Sighting struct {
	Instance string
	Port     int
	IPs      []string
}

// WirelessReport is the same text adb_mdns.py list prints.
type WirelessReport struct {
	Stdout string
	Stderr string
	Code   int
}

// ReportWireless formats usable and tunnel addresses. tun and iface are injected so tests do not call route.
func ReportWireless(items []Sighting, tun func(string) bool, iface func(string) string) WirelessReport {
	var out strings.Builder
	usable := 0
	for _, item := range items {
		chosen, skipped := ChooseIP(item.IPs, tun)
		if chosen != "" {
			usable++
			out.WriteString("use\t")
			out.WriteString(item.Instance)
			out.WriteByte('\t')
			out.WriteString(chosen)
			out.WriteByte('\t')
			out.WriteString(strconv.Itoa(item.Port))
			out.WriteByte('\n')
		}
		for _, ip := range skipped {
			name := iface(ip)
			if name == "" {
				name = "tun"
			}
			out.WriteString("skip\t")
			out.WriteString(item.Instance)
			out.WriteByte('\t')
			out.WriteString(ip)
			out.WriteByte('\t')
			out.WriteString(name)
			out.WriteByte('\n')
		}
	}
	report := WirelessReport{Stdout: out.String()}
	if usable > 0 {
		return report
	}
	if len(items) > 0 {
		report.Stderr = "只发现了走 utun 的地址（常见是手机上显示的 172.19.0.1）。那个地址会被 Clash 隧道吃掉，adb 会报 protocol fault。\n"
		report.Code = 2
		return report
	}
	report.Stderr = "没有发现无线调试。手机打开开发者选项里的无线调试，和电脑连同一个 Wi-Fi。\n"
	report.Code = 1
	return report
}

// DNSLines runs dns-sd and returns the lines it printed. done is checked after each
// line and whenever a read waits; returning true stops early. This opens a local
// pty because dns-sd buffers when its output is not a terminal. It does not connect to a phone.
func DNSLines(ctx context.Context, args []string, timeout time.Duration, done func([]string, time.Time) bool) ([]string, error) {
	master, slave, err := openPty()
	if err != nil {
		return nil, err
	}
	defer master.Close()
	cmd := exec.CommandContext(ctx, "dns-sd", args...)
	cmd.Stdin = nil
	cmd.Stdout = slave
	cmd.Stderr = slave
	if err := cmd.Start(); err != nil {
		slave.Close()
		return nil, err
	}
	slave.Close()
	finished := make(chan struct{})
	go func() {
		_ = cmd.Wait()
		close(finished)
	}()
	defer func() {
		if cmd.Process != nil {
			_ = cmd.Process.Kill()
		}
		<-finished
	}()
	_ = unix.SetNonblock(int(master.Fd()), true)
	deadline := time.Now().Add(timeout)
	var lines []string
	var buf []byte
	tmp := make([]byte, 4096)
	for {
		remaining := time.Until(deadline)
		if remaining <= 0 {
			break
		}
		wait := remaining
		if wait > 200*time.Millisecond {
			wait = 200 * time.Millisecond
		}
		ready, err := unix.Poll([]unix.PollFd{{
			Fd:     int32(master.Fd()),
			Events: unix.POLLIN,
		}}, int(wait.Milliseconds()))
		if err != nil || ready == 0 {
			continue
		}
		n, err := master.Read(tmp)
		if n > 0 {
			buf = append(buf, tmp[:n]...)
			for {
				i := bytes.IndexByte(buf, '\n')
				if i < 0 {
					break
				}
				line := strings.TrimRight(string(buf[:i]), "\r")
				buf = buf[i+1:]
				lines = append(lines, line)
				if done != nil && done(lines, time.Now()) {
					return lines, nil
				}
			}
		}
		if err != nil && n == 0 {
			break
		}
		if done != nil && done(lines, time.Now()) {
			return lines, nil
		}
	}
	return lines, nil
}

// BrowseConnect reads _adb-tls-connect names. After the first name it waits 700ms
// of quiet, and it never waits past timeout.
func BrowseConnect(ctx context.Context, timeout time.Duration) ([]string, error) {
	last := 0
	quiet := time.Time{}
	lines, err := DNSLines(ctx, []string{"-B", connectService, "local"}, timeout, func(lines []string, now time.Time) bool {
		names := BrowseNames(lines, connectService)
		if len(names) > last {
			last = len(names)
			quiet = now.Add(700 * time.Millisecond)
		}
		return last > 0 && !quiet.IsZero() && !now.Before(quiet)
	})
	if err != nil {
		return nil, err
	}
	return BrowseNames(lines, connectService), nil
}

// LookupInstance resolves one browsed name to a host and port.
func LookupInstance(ctx context.Context, instance string, timeout time.Duration) (string, int, error) {
	lines, err := DNSLines(ctx, []string{"-L", instance, connectService, "local"}, timeout, func(lines []string, _ time.Time) bool {
		for _, line := range lines {
			if _, _, ok := ReachAt(line); ok {
				return true
			}
		}
		return false
	})
	if err != nil {
		return "", 0, err
	}
	for _, line := range lines {
		if host, port, ok := ReachAt(line); ok {
			return host, port, nil
		}
	}
	return "", 0, nil
}

// ResolveV4 returns IPv4 addresses for a lookup host. A literal address is not resolved again.
func ResolveV4(ctx context.Context, host string, timeout time.Duration) ([]string, error) {
	if ipv4Pattern.MatchString(host) && ipv4Pattern.FindString(host) == host {
		return []string{host}, nil
	}
	var found []string
	seen := map[string]bool{}
	quiet := time.Time{}
	_, err := DNSLines(ctx, []string{"-G", "v4", host}, timeout, func(lines []string, now time.Time) bool {
		for _, line := range lines {
			for _, ip := range IPv4s(line) {
				if !seen[ip] {
					seen[ip] = true
					found = append(found, ip)
					quiet = now.Add(600 * time.Millisecond)
				}
			}
		}
		return len(found) > 0 && !quiet.IsZero() && !now.Before(quiet)
	})
	return found, err
}

// RouteInterface reads the interface from `route -n get`. It does not change routes.
func RouteInterface(ip string) string {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	out, err := exec.CommandContext(ctx, "route", "-n", "get", ip).Output()
	if err != nil {
		return ""
	}
	for _, line := range strings.Split(string(out), "\n") {
		fields := strings.Fields(strings.TrimSpace(line))
		if len(fields) >= 2 && fields[0] == "interface:" {
			return fields[len(fields)-1]
		}
	}
	return ""
}

// ViaTun reports addresses whose route leaves through utun.
func ViaTun(ip string) bool {
	return strings.HasPrefix(RouteInterface(ip), "utun")
}

// WirelessNow browses connect records and formats the list. A dns-sd failure is returned
// so the caller can fall back; finding nothing is a report with code 1.
func WirelessNow(ctx context.Context, timeout time.Duration) (WirelessReport, error) {
	names, err := BrowseConnect(ctx, timeout)
	if err != nil {
		return WirelessReport{}, err
	}
	var items []Sighting
	for _, name := range names {
		host, port, err := LookupInstance(ctx, name, 6*time.Second)
		if err != nil || host == "" {
			continue
		}
		ips, err := ResolveV4(ctx, host, 3*time.Second)
		if err != nil || len(ips) == 0 {
			continue
		}
		items = append(items, Sighting{Instance: name, Port: port, IPs: ips})
	}
	return ReportWireless(items, ViaTun, RouteInterface), nil
}
