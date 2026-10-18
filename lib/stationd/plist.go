package stationd

import (
	"encoding/xml"
	"errors"
	"os"
	"os/user"
	"path/filepath"
	"strings"
)

// AgentLabel is the LaunchAgent label. Registering it is a separate step.
const AgentLabel = "dev.phonestation.stationd"

// AgentPlistName is the file SMAppService reads from the app bundle.
const AgentPlistName = "dev.phonestation.stationd.plist"

// BundleProgram is the station executable relative to the app bundle.
const BundleProgram = "Contents/Resources/phone-station/lib/station"

// LaunchAgentPlist is the text of a LaunchAgent that runs `station serve`.
// It keeps the process on this Mac and does not publish a TCP port.
func LaunchAgentPlist(binary, socket string) (string, error) {
	if !absPath(binary) || !absPath(socket) {
		return "", errors.New("plist")
	}
	args := []string{binary, "serve", "-socket", socket}
	var b strings.Builder
	b.WriteString(`<?xml version="1.0" encoding="UTF-8"?>` + "\n")
	b.WriteString(`<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">` + "\n")
	b.WriteString(`<plist version="1.0"><dict>` + "\n")
	writeKey(&b, "Label")
	writeString(&b, AgentLabel)
	writeKey(&b, "ProgramArguments")
	b.WriteString("<array>\n")
	for _, arg := range args {
		writeString(&b, arg)
	}
	b.WriteString("</array>\n")
	writeKey(&b, "RunAtLoad")
	b.WriteString("<true/>\n")
	writeKey(&b, "KeepAlive")
	b.WriteString("<true/>\n")
	b.WriteString("</dict></plist>\n")
	text := b.String()
	if strings.Contains(text, "0.0.0.0") || strings.Contains(text, "18765") {
		return "", errors.New("plist")
	}
	return text, nil
}

// BundleAgentPlist is the SMAppService agent. The executable stays inside the
// app bundle, and serve does not receive a TCP port.
func BundleAgentPlist() string {
	var b strings.Builder
	b.WriteString(`<?xml version="1.0" encoding="UTF-8"?>` + "\n")
	b.WriteString(`<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">` + "\n")
	b.WriteString(`<plist version="1.0"><dict>` + "\n")
	writeKey(&b, "Label")
	writeString(&b, AgentLabel)
	writeKey(&b, "BundleProgram")
	writeString(&b, BundleProgram)
	writeKey(&b, "ProgramArguments")
	b.WriteString("<array>\n")
	writeString(&b, BundleProgram)
	writeString(&b, "serve")
	b.WriteString("</array>\n")
	writeKey(&b, "RunAtLoad")
	b.WriteString("<true/>\n")
	writeKey(&b, "KeepAlive")
	b.WriteString("<true/>\n")
	b.WriteString("</dict></plist>\n")
	return b.String()
}

// DefaultSocket is the per-user control socket. It is not a TCP port.
func DefaultSocket() (string, error) {
	home, err := os.UserHomeDir()
	if err != nil || home == "" {
		account, lookupErr := user.Current()
		if lookupErr != nil || account.HomeDir == "" {
			return "", errors.New("socket")
		}
		home = account.HomeDir
	}
	return filepath.Join(home, "Library", "Application Support", "phone-station", "stationd.sock"), nil
}

func absPath(path string) bool {
	if path == "" || !filepath.IsAbs(path) || strings.ContainsAny(path, "\r\n") {
		return false
	}
	return true
}

func writeKey(b *strings.Builder, key string) {
	b.WriteString("<key>")
	xmlEscape(b, key)
	b.WriteString("</key>\n")
}

func writeString(b *strings.Builder, value string) {
	b.WriteString("<string>")
	xmlEscape(b, value)
	b.WriteString("</string>\n")
}

func xmlEscape(b *strings.Builder, value string) {
	_ = xml.EscapeText(b, []byte(value))
}
