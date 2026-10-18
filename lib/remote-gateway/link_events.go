package main

import (
	"bytes"
	"context"
	"sync"
	"time"
)

type liveEvents struct {
	mu           sync.Mutex
	clip         int64
	notes        int64
	wake         chan struct{}
	device       []byte
	grants       []byte
	capabilities []byte
	generation   uint64
	stateWake    chan struct{}
}

func newLiveEvents() *liveEvents {
	return &liveEvents{wake: make(chan struct{}), stateWake: make(chan struct{})}
}

// keep stores a device, grants, or capabilities body. An unchanged body does not
// bump the generation, so a waiter is not woken again.
func (e *liveEvents) keep(topic string, body []byte) bool {
	e.mu.Lock()
	defer e.mu.Unlock()
	var slot *[]byte
	switch topic {
	case "device":
		slot = &e.device
	case "grants":
		slot = &e.grants
	case "capabilities":
		slot = &e.capabilities
	default:
		return false
	}
	if bytes.Equal(*slot, body) {
		return false
	}
	*slot = append([]byte(nil), body...)
	e.generation++
	wake := e.stateWake
	e.stateWake = make(chan struct{})
	close(wake)
	return true
}

func (e *liveEvents) counts() (int64, int64) {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.clip, e.notes
}

func (e *liveEvents) snapshot() (uint64, []byte, []byte, []byte) {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.generation, append([]byte(nil), e.device...), append([]byte(nil), e.grants...), append([]byte(nil), e.capabilities...)
}

func (e *liveEvents) note(topic string) {
	e.mu.Lock()
	switch topic {
	case "clipboard":
		e.clip++
	case "notifications":
		e.notes++
	default:
		e.mu.Unlock()
		return
	}
	wake := e.wake
	e.wake = make(chan struct{})
	e.mu.Unlock()
	close(wake)
}

func (e *liveEvents) await(ctx context.Context, clip, notes int64) (int64, int64, error) {
	timer := time.NewTimer(25 * time.Second)
	defer timer.Stop()
	for {
		e.mu.Lock()
		if e.clip != clip || e.notes != notes {
			currentClip, currentNotes := e.clip, e.notes
			e.mu.Unlock()
			return currentClip, currentNotes, nil
		}
		wake := e.wake
		e.mu.Unlock()
		select {
		case <-wake:
		case <-timer.C:
			e.mu.Lock()
			currentClip, currentNotes := e.clip, e.notes
			e.mu.Unlock()
			return currentClip, currentNotes, nil
		case <-ctx.Done():
			return 0, 0, ctx.Err()
		}
	}
}
