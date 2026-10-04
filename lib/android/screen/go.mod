module phone-station/screen

go 1.23

require (
	github.com/coder/websocket v1.8.14
	github.com/qx0657/phone-station/server/relay v0.0.0
)

replace github.com/qx0657/phone-station/server/relay => ../../../server/relay
