package relay

import (
	"bytes"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/url"
	"os"
	"sort"
	"strings"
	"sync"
	"time"
	"unicode/utf8"
)

const (
	maxBodyBytes       = 18 << 20
	pollWait           = 25 * time.Second
	phoneFresh         = 45 * time.Second
	callTimeout        = 3 * time.Minute
	resultKeep         = 10 * time.Minute
	maxRemembered      = 4096
	maxCachedResponses = 8
)

type Config struct {
	DeviceID     string
	PhoneToken   string
	DesktopToken string
	StateDir     string
}

type Server struct {
	config  Config
	screens screenHub
	device  *device
	store   *diskStore
}

type device struct {
	mu             sync.Mutex
	changed        chan struct{}
	polling        bool
	pollGeneration uint64
	pollCancel     chan struct{}
	lastSeen       time.Time
	active         *operation
	operations     map[string]*operation
	closed         bool
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

// Open is the production constructor. New is an in-memory test fixture only.
func Open(config Config) (*Server, error) {
	if config.PhoneToken == "" || config.DesktopToken == "" || config.PhoneToken == config.DesktopToken {
		return nil, errors.New("distinct non-empty role tokens are required")
	}
	store, err := openStore(config)
	if err != nil {
		return nil, err
	}
	s := New(config)
	s.store = store
	if err = store.rotate(config, time.Now()); err == nil {
		s.device.operations, err = store.load()
	}
	if err == nil {
		err = s.prune(time.Now(), nil)
	}
	if err != nil {
		store.close()
		return nil, err
	}
	// Bodies without a committed cached receipt and interrupted temp writes are disposable.
	entries, err := os.ReadDir(store.dir)
	if err == nil {
		keep := make(map[string]bool)
		for _, op := range s.device.operations {
			if op.response.Body != nil {
				keep[store.name(op.id)+".body"] = true
			}
		}
		for _, entry := range entries {
			if strings.HasPrefix(entry.Name(), ".pending-") || (strings.HasSuffix(entry.Name(), ".body") && !keep[entry.Name()]) {
				if err = store.remove(entry.Name()); err != nil {
					break
				}
			}
		}
	}
	if err != nil {
		store.close()
		return nil, err
	}
	return s, nil
}

func (s *Server) Close() error {
	s.screens.close()
	s.device.mu.Lock()
	defer s.device.mu.Unlock()
	if s.device.closed {
		return nil
	}
	s.device.closed = true
	for _, op := range s.device.operations {
		if !op.doneClosed {
			op.state = "unknown"
			close(op.done)
			op.doneClosed = true
		}
	}
	signal(s.device)
	if s.store != nil {
		s.store.close()
	}
	return nil
}

func (s *Server) available(w http.ResponseWriter) bool {
	if s.device.closed || (s.store != nil && s.store.err != nil) {
		writeError(w, http.StatusServiceUnavailable, "relay storage unavailable; outcome may be unknown")
		return false
	}
	return true
}

func (s *Server) persist(op *operation) bool {
	if s.store == nil {
		return true
	}
	if err := s.store.save(op); err != nil {
		s.store.err = err
		signal(s.device)
		return false
	}
	return true
}

func (s *Server) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	// Native clients never send Origin or credentials in a URL. Reject ambiguous
	// requests before authentication, storage maintenance or queue changes.
	if len(r.Header.Values("Origin")) != 0 {
		writeError(w, http.StatusForbidden, "native clients only")
		return
	}
	if r.URL.RawQuery != "" {
		query, err := url.ParseQuery(r.URL.RawQuery)
		if r.URL.Path != "/v1/desktop/operation" || err != nil || len(query) != 1 || len(query["id"]) != 1 || query.Get("id") == "" {
			writeError(w, http.StatusBadRequest, "unexpected query parameters")
			return
		}
	}
	if strings.HasPrefix(r.URL.Path, "/v1/screen/") {
		s.screen(w, r)
		return
	}
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
	headers := r.Header.Values("Authorization")
	if want == "" || len(headers) != 1 || !strings.HasPrefix(headers[0], prefix) {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return false
	}
	got := strings.TrimPrefix(headers[0], prefix)
	if len(got) != len(want) || subtle.ConstantTimeCompare([]byte(got), []byte(want)) != 1 {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return false
	}
	return true
}

func (s *Server) poll(w http.ResponseWriter, r *http.Request) {
	wait := pollWait
	if r.ContentLength != 0 {
		var input struct {
			WaitMS int `json:"waitMs"`
		}
		if !decode(w, r, &input) {
			return
		}
		if input.WaitMS != 0 {
			if input.WaitMS < 250 || input.WaitMS > int(pollWait.Milliseconds()) {
				writeError(w, http.StatusBadRequest, "poll wait must be between 250 and 25000 milliseconds")
				return
			}
			wait = time.Duration(input.WaitMS) * time.Millisecond
		}
	}
	d := s.device
	d.mu.Lock()
	if !s.available(w) {
		d.mu.Unlock()
		return
	}
	if d.polling {
		// Wi-Fi may disappear without delivering a TCP close. Let the same
		// authenticated phone take over its idle poll on the new network.
		close(d.pollCancel)
	}
	d.polling = true
	d.pollGeneration++
	generation := d.pollGeneration
	replaced := make(chan struct{})
	d.pollCancel = replaced
	d.lastSeen = time.Now()
	d.mu.Unlock()
	defer func() {
		d.mu.Lock()
		if d.pollGeneration == generation {
			d.polling = false
			d.pollCancel = nil
		}
		d.mu.Unlock()
	}()

	deadline := time.NewTimer(wait)
	defer deadline.Stop()
	for {
		d.mu.Lock()
		if !s.available(w) {
			d.mu.Unlock()
			return
		}
		if d.pollGeneration != generation {
			d.mu.Unlock()
			return
		}
		d.lastSeen = time.Now()
		if op := d.active; op != nil && op.state == "queued" {
			if time.Since(op.createdAt) > callTimeout {
				op.state = "expired"
				op.payload = nil
				op.completedAt = time.Now()
				if !s.persist(op) {
					s.available(w)
					d.mu.Unlock()
					return
				}
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
			if !s.persist(op) {
				s.available(w)
				d.mu.Unlock()
				return
			}
			out := pollReply{OperationID: op.id, Payload: op.payload}
			d.mu.Unlock()
			writeJSON(w, http.StatusOK, out)
			return
		}
		if op := d.active; op != nil && op.state == "running" &&
			(time.Since(op.startedAt) > callTimeout || (op.probe && time.Since(op.startedAt) > 15*time.Second)) {
			op.state = "unknown"
			op.completedAt = time.Now()
			if !s.persist(op) {
				s.available(w)
				d.mu.Unlock()
				return
			}
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
		case <-replaced:
			return
		case <-r.Context().Done():
			return
		case <-deadline.C:
			writeJSON(w, http.StatusOK, map[string]any{"idle": true, "operationId": nil})
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
	if !s.available(w) {
		return
	}
	d.lastSeen = time.Now()
	op := d.operations[input.OperationID]
	if len(input.OperationID) > 128 {
		writeError(w, http.StatusBadRequest, "invalid operation ID")
		return
	}
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
	next := *op
	next.response = responseEnvelope{
		Status: input.Response.Status,
		Body:   append(json.RawMessage(nil), input.Response.Body...),
	}
	next.responseDigest = sha256.Sum256(input.Response.Body)
	next.payload = nil
	next.state = "complete"
	next.completedAt = time.Now()
	if err := s.prune(time.Now(), &next); err != nil {
		s.store.err = err
		signal(s.device)
		s.available(w)
		return
	}
	if s.store != nil && next.response.Body != nil {
		if err := s.store.atomic(s.store.name(next.id)+".body", next.response.Body); err != nil {
			s.store.err = err
			signal(s.device)
			s.available(w)
			return
		}
	}
	if !s.persist(&next) {
		s.available(w)
		return
	}
	op.response, op.responseDigest = next.response, next.responseDigest
	op.payload, op.state, op.completedAt = next.payload, next.state, next.completedAt
	d.active = nil
	if !op.doneClosed {
		close(op.done)
		op.doneClosed = true
	}
	signal(d)
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
	if !s.available(w) {
		d.mu.Unlock()
		return
	}
	if err := s.prune(time.Now(), nil); err != nil {
		s.store.err = err
		signal(s.device)
		s.available(w)
		d.mu.Unlock()
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
		if previous.state == "unknown" || previous.state == "expired" {
			d.mu.Unlock()
			writeError(w, http.StatusGone, "operation is expired or its outcome is unknown; do not execute again")
			return
		}
		done := previous.done
		d.mu.Unlock()
		s.await(w, r, previous, done)
		return
	}
	if s.store != nil && !s.store.currentID(input.OperationID) {
		d.mu.Unlock()
		writeError(w, http.StatusGone, "operation epoch expired; do not replay this ID")
		return
	}
	if d.lastSeen.IsZero() || time.Since(d.lastSeen) > phoneFresh {
		d.mu.Unlock()
		writeError(w, http.StatusServiceUnavailable, "phone is offline")
		return
	}
	if d.active != nil {
		d.mu.Unlock()
		w.Header().Set("X-Phone-Station-Not-Queued", "1")
		writeError(w, http.StatusTooManyRequests, "another operation is still in progress")
		return
	}
	if len(d.operations) >= maxRemembered {
		d.mu.Unlock()
		w.Header().Set("X-Phone-Station-Not-Queued", "1")
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
	if !s.persist(op) {
		s.available(w)
		d.mu.Unlock()
		return
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
			if !s.persist(op) {
				s.device.mu.Unlock()
				return
			}
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
			if !s.persist(op) {
				s.available(w)
				s.device.mu.Unlock()
				return
			}
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
	if !s.available(w) {
		d.mu.Unlock()
		return
	}
	epoch := ""
	if s.store != nil {
		if d.active == nil {
			if err := s.store.rotate(s.config, time.Now()); err != nil {
				s.store.err = err
				signal(s.device)
				s.available(w)
				d.mu.Unlock()
				return
			}
		}
		if err := s.prune(time.Now(), nil); err != nil {
			s.store.err = err
			signal(s.device)
			s.available(w)
			d.mu.Unlock()
			return
		}
		epoch = s.store.epoch
	}
	online := !d.lastSeen.IsZero() && time.Since(d.lastSeen) <= phoneFresh
	lastSeen := d.lastSeen
	d.mu.Unlock()
	var seen *time.Time
	if !lastSeen.IsZero() {
		seen = &lastSeen
	}
	writeJSON(w, http.StatusOK, statusReply{DeviceID: s.config.DeviceID, Online: online, LastSeen: seen, Version: "2", OperationEpoch: epoch, Durable: s.store != nil})
}

func (s *Server) operationStatus(w http.ResponseWriter, r *http.Request) {
	id := r.URL.Query().Get("id")
	d := s.device
	d.mu.Lock()
	if !s.available(w) {
		d.mu.Unlock()
		return
	}
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
	var body json.RawMessage
	if err := decoder.Decode(&body); err != nil {
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
	if !utf8.Valid(body) || !unambiguousJSON(body) || json.Unmarshal(body, target) != nil {
		writeError(w, http.StatusBadRequest, "invalid or ambiguous JSON body")
		return false
	}
	return true
}

func (s *Server) prune(now time.Time, incoming *operation) error {
	d := s.device
	for id, op := range d.operations {
		if op == d.active || op.completedAt.IsZero() || now.Sub(op.completedAt) <= resultKeep {
			continue
		}
		if s.store == nil {
			delete(d.operations, id)
			continue
		}
		if op.response.Body != nil {
			next := *op
			next.response.Body = nil
			if err := s.store.save(&next); err != nil {
				return err
			}
			if err := s.store.remove(s.store.name(id) + ".body"); err != nil {
				return err
			}
			op.response.Body = nil
		}
		if !s.store.currentID(id) {
			if err := s.store.remove(s.store.name(id) + ".json"); err != nil {
				return err
			}
			delete(d.operations, id)
		}
	}
	completed := make([]*operation, 0, len(d.operations))
	count, total := 0, 0
	if incoming != nil && incoming.response.Body != nil {
		count++
		total += len(incoming.response.Body)
	}
	for _, op := range d.operations {
		if op.state == "complete" && op.response.Body != nil {
			completed = append(completed, op)
			count++
			total += len(op.response.Body)
		}
	}
	sort.Slice(completed, func(i, j int) bool {
		return completed[i].completedAt.Before(completed[j].completedAt)
	})
	for _, op := range completed {
		if count <= maxCachedResponses && total <= maxCachedBytes && now.Sub(op.completedAt) <= resultKeep {
			continue
		}
		if s.store != nil {
			next := *op
			next.response.Body = nil
			if err := s.store.save(&next); err != nil {
				return err
			}
			if err := s.store.remove(s.store.name(op.id) + ".body"); err != nil {
				return err
			}
		}
		count--
		total -= len(op.response.Body)
		op.response.Body = nil
	}
	return nil
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
	DeviceID       string     `json:"deviceId"`
	Online         bool       `json:"online"`
	LastSeen       *time.Time `json:"lastSeen,omitempty"`
	Version        string     `json:"version"`
	OperationEpoch string     `json:"operationEpoch,omitempty"`
	Durable        bool       `json:"durable"`
}

type operationReply struct {
	OperationID string `json:"operationId"`
	State       string `json:"state"`
}
