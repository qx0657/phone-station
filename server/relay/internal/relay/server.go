package relay

import (
	"bytes"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"sort"
	"strings"
	"sync"
	"time"
)

const (
	maxBodyBytes       = 18 << 20
	pollWait           = 25 * time.Second
	phoneFresh         = 45 * time.Second
	callTimeout        = 3 * time.Minute
	resultKeep         = 10 * time.Minute
	maxRemembered      = 1024
	maxCachedResponses = 8
)

type Config struct {
	DeviceID     string
	PhoneToken   string
	DesktopToken string
}

type Server struct {
	config Config
	device *device
}

type device struct {
	mu         sync.Mutex
	changed    chan struct{}
	polling    bool
	lastSeen   time.Time
	active     *operation
	operations map[string]*operation
}

type operation struct {
	id             string
	payload        json.RawMessage
	digest         [32]byte
	responseDigest [32]byte
	state          string
	response       responseEnvelope
	createdAt      time.Time
	completedAt    time.Time
	startedAt      time.Time
	probe          bool
	done           chan struct{}
	doneClosed     bool
}

func New(config Config) *Server {
	return &Server{
		config: config,
		device: &device{changed: make(chan struct{}), operations: make(map[string]*operation)},
	}
}

func (s *Server) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Method == http.MethodPost && r.URL.Path == "/v1/phone/poll" {
		if !s.authorized(w, r, s.config.PhoneToken) {
			return
		}
		s.poll(w, r)
		return
	}
	if r.Method == http.MethodPost && r.URL.Path == "/v1/phone/result" {
		if !s.authorized(w, r, s.config.PhoneToken) {
			return
		}
		s.result(w, r)
		return
	}
	if r.Method == http.MethodPost && r.URL.Path == "/v1/desktop/call" {
		if !s.authorized(w, r, s.config.DesktopToken) {
			return
		}
		s.call(w, r)
		return
	}
	if r.Method == http.MethodGet && r.URL.Path == "/v1/desktop/status" {
		if !s.authorized(w, r, s.config.DesktopToken) {
			return
		}
		s.status(w)
		return
	}
	if r.Method == http.MethodGet && r.URL.Path == "/v1/desktop/operation" {
		if !s.authorized(w, r, s.config.DesktopToken) {
			return
		}
		s.operationStatus(w, r)
		return
	}
	w.Header().Set("Allow", "GET, POST")
	writeError(w, http.StatusNotFound, "unknown endpoint")
}

func (s *Server) authorized(w http.ResponseWriter, r *http.Request, want string) bool {
	const prefix = "Bearer "
	got := r.Header.Get("Authorization")
	if !strings.HasPrefix(got, prefix) {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return false
	}
	got = strings.TrimPrefix(got, prefix)
	if len(got) != len(want) || subtle.ConstantTimeCompare([]byte(got), []byte(want)) != 1 {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return false
	}
	return true
}

func (s *Server) poll(w http.ResponseWriter, r *http.Request) {
	d := s.device
	d.mu.Lock()
	if d.polling {
		d.mu.Unlock()
		writeError(w, http.StatusConflict, "a phone poll is already active")
		return
	}
	d.polling = true
	d.lastSeen = time.Now()
	d.mu.Unlock()
	defer func() {
		d.mu.Lock()
		d.polling = false
		d.mu.Unlock()
	}()

	deadline := time.NewTimer(pollWait)
	defer deadline.Stop()
	for {
		d.mu.Lock()
		d.lastSeen = time.Now()
		if op := d.active; op != nil && op.state == "queued" {
			if time.Since(op.createdAt) > callTimeout {
				op.state = "expired"
				op.payload = nil
				op.completedAt = time.Now()
				if !op.doneClosed {
					close(op.done)
					op.doneClosed = true
				}
				d.active = nil
				signal(d)
				d.mu.Unlock()
				continue
			}
			op.state = "running"
			op.startedAt = time.Now()
			out := pollReply{OperationID: op.id, Payload: op.payload}
			d.mu.Unlock()
			writeJSON(w, http.StatusOK, out)
			return
		}
		if op := d.active; op != nil && op.state == "running" &&
			(time.Since(op.startedAt) > callTimeout || (op.probe && time.Since(op.startedAt) > 15*time.Second)) {
			op.state = "unknown"
			op.completedAt = time.Now()
			op.payload = nil
			if !op.doneClosed {
				close(op.done)
				op.doneClosed = true
			}
			d.active = nil
			signal(d)
			d.mu.Unlock()
			continue
		}
		changed := d.changed
		d.mu.Unlock()

		select {
		case <-r.Context().Done():
			return
		case <-deadline.C:
			writeJSON(w, http.StatusOK, pollReply{Idle: true})
			return
		case <-changed:
		}
	}
}

func (s *Server) result(w http.ResponseWriter, r *http.Request) {
	var input resultRequest
	if !decode(w, r, &input) {
		return
	}
	if input.OperationID == "" || input.Response.Status < 200 || input.Response.Status > 599 ||
		(input.Response.Status != http.StatusAccepted && (len(input.Response.Body) == 0 || !json.Valid(input.Response.Body))) ||
		(input.Response.Status == http.StatusAccepted && len(input.Response.Body) != 0 && !json.Valid(input.Response.Body)) {
		writeError(w, http.StatusBadRequest, "invalid result")
		return
	}
	d := s.device
	d.mu.Lock()
	defer d.mu.Unlock()
	d.lastSeen = time.Now()
	op := d.operations[input.OperationID]
	if op == nil || d.active != op || op.state != "running" {
		if op != nil && op.state == "complete" && op.response.Status == input.Response.Status &&
			op.responseDigest == sha256.Sum256(input.Response.Body) {
			writeJSON(w, http.StatusOK, map[string]string{"state": "complete"})
			return
		}
		if op != nil && op.state == "complete" {
			writeError(w, http.StatusConflict, "operation already completed with a different result")
			return
		}
		writeError(w, http.StatusNotFound, "operation is not active")
		return
	}
	op.response = responseEnvelope{
		Status: input.Response.Status,
		Body:   append(json.RawMessage(nil), input.Response.Body...),
	}
	op.responseDigest = sha256.Sum256(input.Response.Body)
	op.payload = nil
	op.state = "complete"
	op.completedAt = time.Now()
	d.active = nil
	if !op.doneClosed {
		close(op.done)
		op.doneClosed = true
	}
	signal(d)
	pruneResponses(d, op)
	writeJSON(w, http.StatusOK, map[string]string{"state": "complete"})
}

func (s *Server) call(w http.ResponseWriter, r *http.Request) {
	var input callRequest
	if !decode(w, r, &input) {
		return
	}
	if input.OperationID == "" || len(input.OperationID) > 128 || len(input.Payload) == 0 || !json.Valid(input.Payload) {
		writeError(w, http.StatusBadRequest, "invalid call")
		return
	}

	d := s.device
	d.mu.Lock()
	prune(d, time.Now())
	if d.lastSeen.IsZero() || time.Since(d.lastSeen) > phoneFresh {
		d.mu.Unlock()
		writeError(w, http.StatusServiceUnavailable, "phone is offline")
		return
	}
	digest := sha256.Sum256(input.Payload)
	if previous := d.operations[input.OperationID]; previous != nil {
		if previous.digest != digest {
			d.mu.Unlock()
			writeError(w, http.StatusConflict, "operation ID was already used with different content")
			return
		}
		if previous.state == "complete" {
			response := cloneResponse(previous.response)
			d.mu.Unlock()
			if response == nil {
				writeError(w, http.StatusGone, "operation completed; its response is no longer cached, do not execute it again")
				return
			}
			writeJSON(w, http.StatusOK, response)
			return
		}
		done := previous.done
		d.mu.Unlock()
		s.await(w, r, previous, done)
		return
	}
	if d.active != nil {
		d.mu.Unlock()
		writeError(w, http.StatusTooManyRequests, "another operation is still in progress")
		return
	}
	if len(d.operations) >= maxRemembered {
		d.mu.Unlock()
		writeError(w, http.StatusTooManyRequests, "operation history is full; wait for old entries to expire")
		return
	}
	op := &operation{
		id: input.OperationID, payload: append(json.RawMessage(nil), input.Payload...),
		digest: digest, state: "queued", createdAt: time.Now(), done: make(chan struct{}),
	}
	var method struct {
		Method string `json:"method"`
	}
	if json.Unmarshal(input.Payload, &method) == nil {
		op.probe = method.Method == "ping"
	}
	d.operations[op.id] = op
	d.active = op
	signal(d)
	d.mu.Unlock()
	s.await(w, r, op, op.done)
}

func (s *Server) await(w http.ResponseWriter, r *http.Request, op *operation, done <-chan struct{}) {
	timer := time.NewTimer(callTimeout)
	defer timer.Stop()
	select {
	case <-done:
		s.device.mu.Lock()
		state := op.state
		response := cloneResponse(op.response)
		s.device.mu.Unlock()
		if state == "expired" {
			writeError(w, http.StatusGone, "operation expired before the phone received it")
			return
		}
		if state != "complete" {
			writeError(w, http.StatusGatewayTimeout, "operation outcome is unknown; query the same operation ID before retrying")
			return
		}
		if response == nil {
			writeError(w, http.StatusGone, "operation completed; its response is no longer cached, do not execute it again")
			return
		}
		writeJSON(w, http.StatusOK, response)
	case <-r.Context().Done():
		// A queued call was never delivered, so cancellation can safely release
		// it. A dispatched ping also has no side effects. Other running calls
		// retain their operation ID and must never be silently replayed.
		s.device.mu.Lock()
		if s.device.active == op && (op.state == "queued" || (op.state == "running" && op.probe)) {
			if op.state == "queued" {
				op.state = "expired"
			} else {
				op.state = "unknown"
			}
			op.payload = nil
			op.completedAt = time.Now()
			s.device.active = nil
			if !op.doneClosed {
				close(op.done)
				op.doneClosed = true
			}
			signal(s.device)
		}
		s.device.mu.Unlock()
	case <-timer.C:
		s.device.mu.Lock()
		if s.device.active == op && (op.state == "queued" ||
			(op.state == "running" && time.Since(op.startedAt) > callTimeout)) {
			if op.state == "queued" {
				op.state = "expired"
			} else {
				op.state = "unknown"
			}
			op.payload = nil
			op.completedAt = time.Now()
			s.device.active = nil
			if !op.doneClosed {
				close(op.done)
				op.doneClosed = true
			}
			signal(s.device)
		}
		s.device.mu.Unlock()
		writeJSON(w, http.StatusGatewayTimeout, map[string]string{
			"error":       "operation outcome is unknown; query the same operation ID before retrying",
			"operationId": op.id,
		})
	}
}

func (s *Server) status(w http.ResponseWriter) {
	d := s.device
	d.mu.Lock()
	online := !d.lastSeen.IsZero() && time.Since(d.lastSeen) <= phoneFresh
	lastSeen := d.lastSeen
	d.mu.Unlock()
	var seen *time.Time
	if !lastSeen.IsZero() {
		seen = &lastSeen
	}
	writeJSON(w, http.StatusOK, statusReply{DeviceID: s.config.DeviceID, Online: online, LastSeen: seen})
}

func (s *Server) operationStatus(w http.ResponseWriter, r *http.Request) {
	id := r.URL.Query().Get("id")
	d := s.device
	d.mu.Lock()
	op := d.operations[id]
	if op == nil {
		d.mu.Unlock()
		writeJSON(w, http.StatusOK, operationReply{OperationID: id, State: "unknown"})
		return
	}
	state := op.state
	d.mu.Unlock()
	writeJSON(w, http.StatusOK, operationReply{OperationID: id, State: state})
}

func decode(w http.ResponseWriter, r *http.Request, target any) bool {
	r.Body = http.MaxBytesReader(w, r.Body, maxBodyBytes)
	defer r.Body.Close()
	decoder := json.NewDecoder(r.Body)
	if err := decoder.Decode(target); err != nil {
		status := http.StatusBadRequest
		var tooLarge *http.MaxBytesError
		if errors.As(err, &tooLarge) {
			status = http.StatusRequestEntityTooLarge
		}
		writeError(w, status, "invalid or oversized JSON body")
		return false
	}
	if err := decoder.Decode(new(any)); !errors.Is(err, io.EOF) {
		writeError(w, http.StatusBadRequest, "request must contain one JSON value")
		return false
	}
	return true
}

func prune(d *device, now time.Time) {
	for id, op := range d.operations {
		if op != d.active && (op.state == "complete" || op.state == "expired" || op.state == "unknown") &&
			now.Sub(op.completedAt) > resultKeep {
			delete(d.operations, id)
		}
	}
}

func pruneResponses(d *device, newest *operation) {
	completed := make([]*operation, 0, len(d.operations))
	for _, op := range d.operations {
		if op.state == "complete" && op.response.Body != nil {
			completed = append(completed, op)
		}
	}
	if len(completed) <= maxCachedResponses {
		return
	}
	sort.Slice(completed, func(i, j int) bool {
		return completed[i].completedAt.After(completed[j].completedAt)
	})
	for _, op := range completed[maxCachedResponses:] {
		if op != newest {
			op.response.Body = nil
		}
	}
}

func cloneResponse(response responseEnvelope) *responseEnvelope {
	if response.Body == nil && response.Status != http.StatusAccepted {
		return nil
	}
	return &responseEnvelope{Status: response.Status, Body: append(json.RawMessage(nil), response.Body...)}
}

func signal(d *device) {
	close(d.changed)
	d.changed = make(chan struct{})
}

func writeError(w http.ResponseWriter, status int, message string) {
	writeJSON(w, status, map[string]string{"error": message})
}

func writeJSON(w http.ResponseWriter, status int, value any) {
	body, err := json.Marshal(value)
	if err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	writeJSONRaw(w, status, body)
}

func writeJSONRaw(w http.ResponseWriter, status int, body []byte) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.WriteHeader(status)
	_, _ = io.Copy(w, bytes.NewReader(body))
}

type callRequest struct {
	OperationID string          `json:"operationId"`
	Payload     json.RawMessage `json:"payload"`
}

type pollReply struct {
	Idle        bool            `json:"idle,omitempty"`
	OperationID string          `json:"operationId,omitempty"`
	Payload     json.RawMessage `json:"payload,omitempty"`
}

type resultRequest struct {
	OperationID string           `json:"operationId"`
	Response    responseEnvelope `json:"response"`
}

type responseEnvelope struct {
	Status int             `json:"status"`
	Body   json.RawMessage `json:"body,omitempty"`
}

type statusReply struct {
	DeviceID string     `json:"deviceId"`
	Online   bool       `json:"online"`
	LastSeen *time.Time `json:"lastSeen,omitempty"`
}

type operationReply struct {
	OperationID string `json:"operationId"`
	State       string `json:"state"`
}
