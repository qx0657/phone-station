//go:build darwin

package stationd

import (
	"os/exec"
	"strings"
)

func signatureOf(path string) (string, string) {
	cmd := exec.Command("/usr/bin/codesign", "-dv", "--verbose=4", path)
	out, err := cmd.CombinedOutput()
	if err != nil {
		return "", ""
	}
	var id, team string
	for _, line := range strings.Split(string(out), "\n") {
		if value, ok := strings.CutPrefix(line, "Identifier="); ok {
			id = strings.TrimSpace(value)
		}
		if value, ok := strings.CutPrefix(line, "TeamIdentifier="); ok {
			team = strings.TrimSpace(value)
		}
	}
	if team == "not set" {
		team = ""
	}
	return id, team
}
