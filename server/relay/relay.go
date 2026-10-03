// Package relay exposes the durable server for the joint client/server tests.
package relay

import "github.com/qx0657/phone-station/server/relay/internal/relay"

type Config = relay.Config
type Server = relay.Server

func Open(config Config) (*Server, error) { return relay.Open(config) }
