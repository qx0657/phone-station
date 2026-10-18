package link

import "testing"

func TestRoutePrefersLocalAndKeepsInflight(t *testing.T) {
	route := NewRoute()
	if _, ok := route.Prefer(); ok {
		t.Fatal("down")
	}
	route.Up(SideRemote, true)
	side, ok := route.Prefer()
	if !ok || side != SideRemote {
		t.Fatal("remote")
	}
	const op = "0123456789abcdef0123456789abcdef"
	route.Begin(op, side)
	route.Up(SideLocal, true)
	if again, ok := route.Prefer(); !ok || again != SideLocal {
		t.Fatal("local")
	}
	route.Begin(op, SideLocal)
	if got, ok := route.Query(op); !ok || got != SideRemote {
		t.Fatal("stay")
	}
	route.OpenStream(7, SideRemote)
	route.Up(SideRemote, false)
	if got, ok := route.Query(op); !ok || got != SideLocal {
		t.Fatal("query")
	}
	if route.StreamOpen(7) {
		t.Fatal("stream")
	}
	route.OpenStream(8, SideLocal)
	if !route.StreamOpen(8) {
		t.Fatal("other")
	}
	route.Up(SideLocal, false)
	if _, ok := route.Query(op); ok || route.StreamOpen(8) {
		t.Fatal("none")
	}
}
