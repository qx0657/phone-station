package stationd

import "testing"

func TestMdnsParseDoesNotBrowse(t *testing.T) {
	if got := InstanceName("9:10:11  Add  2  3  local.  _adb-tls-connect._tcp.  adb-phone (2)", "_adb-tls-connect._tcp"); got != "adb-phone (2)" {
		t.Fatal(got)
	}
	records := ReadRecords("phone\t192.168.0.8\t43333\nbad\t1.2.3.4\nphone\t10.0.0.2\t37123\n")
	if len(records) != 2 || records[0].Port != 43333 {
		t.Fatal(records)
	}
	open := map[string]bool{"192.168.0.8:43333": false, "192.168.0.8:36001": true}
	targets := OrderedTargets([]Record{
		{Instance: "adb-phone (2)", IP: "192.168.0.8", Port: 43333},
		{Instance: "adb-phone", IP: "192.168.0.8", Port: 36001},
	}, func(ip string, port int) bool {
		return open[ip+":"+itoa(port)]
	})
	if len(targets) != 2 || targets[0][1] != "36001" || targets[1][1] != "43333" {
		t.Fatal(targets)
	}
	chosen, skipped := ChooseIP([]string{"10.1.2.3", "192.168.0.8", "172.19.0.1"}, func(ip string) bool {
		return ip == "172.19.0.1"
	})
	if chosen != "192.168.0.8" || len(skipped) != 1 || skipped[0] != "172.19.0.1" {
		t.Fatal(chosen, skipped)
	}
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	var buf [8]byte
	i := len(buf)
	for n > 0 {
		i--
		buf[i] = byte('0' + n%10)
		n /= 10
	}
	return string(buf[i:])
}
