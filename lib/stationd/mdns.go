package stationd

import (
	"regexp"
	"sort"
	"strconv"
	"strings"
)

// Record is one wireless-debugging instance. The stationd parser does not browse the network.
type Record struct {
	Instance string
	IP       string
	Port     int
}

var collision = regexp.MustCompile(` \(\d+\)$`)

// InstanceName reads a dns-sd browse line. A colliding name such as "adb-x (2)" stays intact.
func InstanceName(line, service string) string {
	for _, token := range []string{service + ".", service} {
		start := strings.Index(line, token)
		if start < 0 {
			continue
		}
		name := strings.TrimSpace(line[start+len(token):])
		if name != "" {
			return name
		}
	}
	return ""
}

// ReadRecords parses instance, IP, and port separated by tabs.
func ReadRecords(text string) []Record {
	var out []Record
	for _, line := range strings.Split(text, "\n") {
		parts := strings.Split(strings.TrimRight(line, "\r"), "\t")
		if len(parts) != 3 {
			continue
		}
		port, err := strconv.Atoi(parts[2])
		if err != nil || strconv.Itoa(port) != parts[2] {
			continue
		}
		out = append(out, Record{Instance: parts[0], IP: parts[1], Port: port})
	}
	return out
}

// OrderedTargets orders each IP's ports: an open TCP port first, then a name without a collision suffix.
func OrderedTargets(records []Record, open func(ip string, port int) bool) [][2]string {
	grouped := map[string][]Record{}
	openPort := map[string]bool{}
	var ips []string
	for _, record := range records {
		if _, ok := grouped[record.IP]; !ok {
			ips = append(ips, record.IP)
		}
		grouped[record.IP] = append(grouped[record.IP], record)
		key := record.IP + ":" + strconv.Itoa(record.Port)
		if _, ok := openPort[key]; !ok {
			openPort[key] = open(record.IP, record.Port)
		}
	}
	var out [][2]string
	for _, ip := range ips {
		items := append([]Record(nil), grouped[ip]...)
		sort.SliceStable(items, func(i, j int) bool {
			left, right := rankParts(items[i], openPort), rankParts(items[j], openPort)
			if left[0] != right[0] {
				return left[0] < right[0]
			}
			if left[1] != right[1] {
				return left[1] < right[1]
			}
			return items[i].Port < items[j].Port
		})
		for _, item := range items {
			out = append(out, [2]string{item.IP, strconv.Itoa(item.Port)})
		}
	}
	return out
}

func rankParts(record Record, open map[string]bool) [2]int {
	openRank := 1
	if open[record.IP+":"+strconv.Itoa(record.Port)] {
		openRank = 0
	}
	collisionRank := 0
	if collision.MatchString(record.Instance) {
		collisionRank = 1
	}
	return [2]int{openRank, collisionRank}
}

// ChooseIP keeps the first non-tunnel address in LAN rank order. tun reports addresses that leave through utun.
func ChooseIP(ips []string, tun func(string) bool) (string, []string) {
	seen := map[string]bool{}
	var usable, skipped []string
	for _, ip := range ips {
		if seen[ip] {
			continue
		}
		seen[ip] = true
		if tun(ip) {
			skipped = append(skipped, ip)
			continue
		}
		usable = append(usable, ip)
	}
	if len(usable) == 0 {
		return "", skipped
	}
	sort.SliceStable(usable, func(i, j int) bool {
		return ipRank(usable[i]) < ipRank(usable[j])
	})
	return usable[0], skipped
}

func ipRank(ip string) int {
	parts := strings.Split(ip, ".")
	if len(parts) < 2 {
		return 3
	}
	a, errA := strconv.Atoi(parts[0])
	b, errB := strconv.Atoi(parts[1])
	if errA != nil || errB != nil {
		return 3
	}
	switch {
	case a == 192 && b == 168:
		return 0
	case a == 10:
		return 1
	case a == 172 && b >= 16 && b <= 31:
		return 4
	default:
		return 3
	}
}
