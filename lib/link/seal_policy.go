package link

import (
	"errors"
	"time"
)

// SealChoice decides whether a frame may stay plaintext.
// Local links are never encrypted. Required remote links reject plaintext
// instead of falling back.
type SealChoice struct {
	Required bool
	Local    bool
}

// AdmitApplication rejects a plaintext application frame when encryption is
// required. The error does not return the original payload, so the caller
// cannot downgrade it to plaintext.
func AdmitApplication(required bool, frame Frame) (Frame, error) {
	if frame.Kind != KindMessage && frame.Kind != KindData {
		return Frame{}, errors.New("kind")
	}
	if err := (SealChoice{Required: required}).Admit(frame.Flags); err != nil {
		return Frame{}, err
	}
	out := frame
	out.Payload = append([]byte(nil), frame.Payload...)
	return out, nil
}

func (c SealChoice) Admit(flags byte) error {
	encrypted := flags&FlagEncrypted != 0
	if c.Local {
		if encrypted {
			return errors.New("local")
		}
		return nil
	}
	if c.Required && !encrypted {
		return errors.New("plaintext")
	}
	return nil
}

// ReplayWindow rejects a repeated or older seq and asks for a new handshake
// after 24 hours or 2^32 frames.
type ReplayWindow struct {
	seen   bool
	last   uint64
	frames uint64
	start  time.Time
}

func (w *ReplayWindow) Accept(seq uint64, now time.Time) error {
	if !w.start.IsZero() && (now.Sub(w.start) >= 24*time.Hour || w.frames >= 1<<32) {
		return errors.New("rekey")
	}
	if w.seen && seq <= w.last {
		return errors.New("replay")
	}
	if !w.seen {
		w.start = now
	}
	w.seen = true
	w.last = seq
	w.frames++
	return nil
}
