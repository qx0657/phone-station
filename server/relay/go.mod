module github.com/qx0657/phone-station/server/relay

go 1.23

require (
	github.com/coder/websocket v1.8.14
	phone-station/link v0.0.0
)

replace phone-station/link => ../../lib/link
