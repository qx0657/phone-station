package link

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path"
	"path/filepath"
	"strings"
	"time"
)

const phoneHome = "/storage/emulated/0"

// DropPhonePath is where a Mac drag lands. The name is one file component.
func DropPhonePath(name string) (string, error) {
	clean, err := fileName(name)
	if err != nil {
		return "", err
	}
	return phoneHome + "/Download/" + clean, nil
}

// ShareMacName is the file name a phone share offers to the Mac.
func ShareMacName(phonePath string) (string, error) {
	normal, err := phoneFile(phonePath)
	if err != nil {
		return "", err
	}
	return path.Base(normal), nil
}

// ResumeOffset is how far a transfer may continue.
// A drag onto the phone always restarts. A share to the Mac may continue.
func ResumeOffset(toPhone bool, have, size int) (int, error) {
	if have < 0 || size < 0 || have > size {
		return 0, errors.New("share")
	}
	if toPhone {
		return 0, nil
	}
	return have, nil
}

// DataFrames splits a payload into KindData frames. The last frame ends the stream.
func DataFrames(channel uint32, payload []byte, limit int) ([]Frame, error) {
	if channel == 0 || limit <= 0 {
		return nil, errors.New("share")
	}
	if len(payload) == 0 {
		return []Frame{{Kind: KindData, Flags: FlagEnd, Channel: channel}}, nil
	}
	frames := make([]Frame, 0, len(payload)/limit+1)
	for off := 0; off < len(payload); {
		next := off + limit
		if next > len(payload) {
			next = len(payload)
		}
		flags := byte(FlagMore)
		if next == len(payload) {
			flags = FlagEnd
		}
		frames = append(frames, Frame{
			Kind: KindData, Flags: flags, Channel: channel,
			Payload: append([]byte(nil), payload[off:next]...),
		})
		off = next
	}
	return frames, nil
}

// PutFile sends one file to the phone's Download directory. The bytes ride KindData.
// Files larger than the initial window are refused.
func (s *LocalSession) PutFile(ctx context.Context, name string, body []byte) error {
	if s.Ended() {
		return errors.New("ended")
	}
	if len(body) > InitialWindow {
		return errors.New("share")
	}
	header, channel, err := shareHeader(name, len(body), s.nextShareChannel())
	if err != nil {
		return err
	}
	s.startInbox()
	wait := make(chan wireMsg, 1)
	s.inbox.mu.Lock()
	if s.inbox.err != nil {
		s.inbox.mu.Unlock()
		return s.inbox.err
	}
	s.inbox.share = wait
	s.inbox.mu.Unlock()
	if err := s.Send(header); err != nil {
		s.cancelShare(wait)
		return err
	}
	frames, err := DataFrames(uint32(channel), body, MainPayload)
	if err != nil {
		s.cancelShare(wait)
		return err
	}
	s.engineMu.Lock()
	if err := s.engine.Open(uint32(channel)); err != nil {
		s.engineMu.Unlock()
		s.cancelShare(wait)
		return err
	}
	for _, frame := range frames {
		if err := s.engine.Send(PriorityFile, frame); err != nil {
			s.engineMu.Unlock()
			s.cancelShare(wait)
			return err
		}
	}
	err = s.flush()
	s.engineMu.Unlock()
	if err != nil {
		s.cancelShare(wait)
		return err
	}
	timer := time.NewTimer(5 * time.Second)
	if deadline, ok := ctx.Deadline(); ok {
		timer.Stop()
		timer = time.NewTimer(time.Until(deadline))
	}
	defer timer.Stop()
	select {
	case msg, ok := <-wait:
		if !ok || msg.Type != "shared" || msg.Name != name || msg.Size != len(body) {
			return errors.New("share")
		}
		return nil
	case <-ctx.Done():
		s.cancelShare(wait)
		return ctx.Err()
	case <-timer.C:
		s.cancelShare(wait)
		return errors.New("share")
	}
}

func (s *LocalSession) nextShareChannel() int {
	s.engineMu.Lock()
	defer s.engineMu.Unlock()
	if s.shareChannel < 7 {
		if s.engine.fromMac {
			s.shareChannel = 7
		} else {
			s.shareChannel = 8
		}
	}
	channel := s.shareChannel
	s.shareChannel += 2
	return channel
}

// SetShareDir is where a file offered by the phone is written. An empty dir refuses offers.
func (s *LocalSession) SetShareDir(dir string) {
	s.mu.Lock()
	s.shareDir = dir
	s.mu.Unlock()
	if dir != "" {
		s.startInbox()
	}
}

func (s *LocalSession) noteShare(msg wireMsg) {
	s.mu.Lock()
	dir := s.shareDir
	s.mu.Unlock()
	if dir == "" || msg.Channel < 7 || msg.Channel > 63 || msg.Size < 0 || msg.Size > InitialWindow {
		_ = s.Send([]byte(`{"type":"share-error","code":"share"}`))
		return
	}
	if _, err := DropPhonePath(msg.Name); err != nil {
		_ = s.Send([]byte(`{"type":"share-error","code":"share"}`))
		return
	}
	s.inbox.mu.Lock()
	s.inbox.pendingName = msg.Name
	s.inbox.pendingSize = msg.Size
	s.inbox.pendingChannel = msg.Channel
	s.inbox.mu.Unlock()
}

func (s *LocalSession) takeShareData(channel uint32, payload []byte) {
	s.inbox.mu.Lock()
	name := s.inbox.pendingName
	size := s.inbox.pendingSize
	expect := s.inbox.pendingChannel
	if name == "" || uint32(expect) != channel {
		s.inbox.mu.Unlock()
		return
	}
	s.inbox.pendingName = ""
	s.inbox.mu.Unlock()
	s.mu.Lock()
	dir := s.shareDir
	s.mu.Unlock()
	if dir == "" || len(payload) != size {
		_ = s.Send([]byte(`{"type":"share-error","code":"share"}`))
		return
	}
	if err := os.WriteFile(filepath.Join(dir, name), payload, 0o600); err != nil {
		_ = s.Send([]byte(`{"type":"share-error","code":"share"}`))
		return
	}
	_ = s.Send([]byte(fmt.Sprintf(`{"type":"shared","name":"%s","size":%d}`, name, size)))
}

func (s *LocalSession) cancelShare(wait chan wireMsg) {
	s.inbox.mu.Lock()
	if s.inbox.share == wait {
		s.inbox.share = nil
	}
	s.inbox.mu.Unlock()
}

func shareHeader(name string, size, channel int) ([]byte, int, error) {
	if _, err := DropPhonePath(name); err != nil {
		return nil, 0, err
	}
	for _, r := range name {
		if r < 0x20 || r > 0x7e || r == '"' || r == '\\' {
			return nil, 0, errors.New("share")
		}
	}
	if channel < 7 || channel > 63 || size < 0 || size > InitialWindow {
		return nil, 0, errors.New("share")
	}
	return []byte(fmt.Sprintf(`{"type":"share","name":"%s","size":%d,"channel":%d}`, name, size, channel)), channel, nil
}

func fileName(name string) (string, error) {
	if name == "" || strings.ContainsAny(name, "/\\\x00") || name == "." || name == ".." {
		return "", errors.New("share")
	}
	return name, nil
}

func phoneFile(input string) (string, error) {
	if input == "" || strings.Contains(input, "\x00") || strings.Contains(input, "\\") {
		return "", errors.New("share")
	}
	normal := path.Clean(input)
	if !strings.HasPrefix(normal, phoneHome+"/") || normal == phoneHome {
		return "", errors.New("share")
	}
	rel := strings.TrimPrefix(normal, phoneHome+"/")
	if rel == "Android" || strings.HasPrefix(rel, "Android/") {
		return "", errors.New("share")
	}
	if path.Base(normal) == "." || path.Base(normal) == "/" {
		return "", errors.New("share")
	}
	return normal, nil
}
