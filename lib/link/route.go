package link

// Side is one of the two channels between a Mac and a phone.
type Side int

const (
	// SideLocal is the adb loopback link.
	SideLocal Side = iota
	// SideRemote is the relay link.
	SideRemote
)

// Route picks a channel for new work. An operation that already started stays
// on its channel while that channel is up. After it drops, a query for the same
// op uses the other channel. Streams are removed with their channel and are not
// moved.
type Route struct {
	up       [2]bool
	inflight map[string]Side
	streams  map[uint64]Side
}

// NewRoute returns an empty route. Both channels start down.
func NewRoute() *Route {
	return &Route{
		inflight: map[string]Side{},
		streams:  map[uint64]Side{},
	}
}

// Up records whether that channel can take new work and queries.
func (r *Route) Up(side Side, open bool) {
	r.up[side] = open
	if open {
		return
	}
	for id, bound := range r.streams {
		if bound == side {
			delete(r.streams, id)
		}
	}
}

// Prefer is the channel for a new operation. The boolean is false when neither
// channel is up.
func (r *Route) Prefer() (Side, bool) {
	if r.up[SideLocal] {
		return SideLocal, true
	}
	if r.up[SideRemote] {
		return SideRemote, true
	}
	return 0, false
}

// Begin records the channel that accepted a new op. A second begin does not move it.
func (r *Route) Begin(op string, side Side) {
	if _, ok := r.inflight[op]; ok {
		return
	}
	r.inflight[op] = side
}

// Query is the channel that should read this op. The original channel wins while
// it is still up; otherwise the other channel is used. The boolean is false when
// the op is unknown or both channels are down.
func (r *Route) Query(op string) (Side, bool) {
	side, ok := r.inflight[op]
	if !ok {
		return 0, false
	}
	if r.up[side] {
		return side, true
	}
	other := SideRemote
	if side == SideRemote {
		other = SideLocal
	}
	if r.up[other] {
		return other, true
	}
	return 0, false
}

// OpenStream binds a stream to the channel that opened it.
func (r *Route) OpenStream(id uint64, side Side) {
	r.streams[id] = side
}

// StreamOpen reports whether that stream's channel is still up. A dropped
// channel removes the stream instead of moving it.
func (r *Route) StreamOpen(id uint64) bool {
	side, ok := r.streams[id]
	return ok && r.up[side]
}
