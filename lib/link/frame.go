package link

import (
	"encoding/binary"
	"errors"
)

const (
	Version       = 2
	HeaderSize    = 32
	KindLink      = 0x01
	KindHandshake = 0x02
	KindMessage   = 0x03
	KindData      = 0x04
	KindCredit    = 0x05
	KindReset     = 0x06
	FlagEncrypted = 0x01
	FlagEnd       = 0x02
	FlagMore      = 0x04
	MainPayload   = 32 << 10
	MediaPayload  = 256 << 10
)

var (
	ErrTruncated = errors.New("truncated")
	ErrVersion   = errors.New("version")
	ErrReserved  = errors.New("reserved")
	ErrKind      = errors.New("kind")
	ErrFlags     = errors.New("flags")
	ErrPeer      = errors.New("peer")
	ErrChannel   = errors.New("channel")
	ErrSeq       = errors.New("seq")
	ErrCredit    = errors.New("credit")
	ErrLimit     = errors.New("limit")
)

type Frame struct {
	Kind    byte
	Flags   byte
	Peer    [16]byte
	Channel uint32
	Seq     uint64
	Payload []byte
}

func Encode(frame Frame, limit int) ([]byte, error) {
	if err := validate(frame, limit); err != nil {
		return nil, err
	}
	return raw(frame), nil
}

func Decode(in []byte, limit int) (Frame, error) {
	if len(in) < HeaderSize {
		return Frame{}, ErrTruncated
	}
	var frame Frame
	if in[0] != Version {
		return Frame{}, ErrVersion
	}
	frame.Kind = in[1]
	frame.Flags = in[2]
	if in[3] != 0 {
		return Frame{}, ErrReserved
	}
	copy(frame.Peer[:], in[4:20])
	frame.Channel = binary.BigEndian.Uint32(in[20:24])
	frame.Seq = binary.BigEndian.Uint64(in[24:32])
	frame.Payload = append([]byte(nil), in[HeaderSize:]...)
	if err := validate(frame, limit); err != nil {
		return Frame{}, err
	}
	return frame, nil
}

func validate(frame Frame, limit int) error {
	if frame.Kind < KindLink || frame.Kind > KindReset {
		return ErrKind
	}
	if frame.Flags&^(FlagEncrypted|FlagEnd|FlagMore) != 0 {
		return ErrFlags
	}
	if limit < 0 || len(frame.Payload) > limit {
		return ErrLimit
	}
	if frame.Kind == KindLink {
		var zero [16]byte
		if frame.Peer != zero {
			return ErrPeer
		}
		if frame.Channel != 0 {
			return ErrChannel
		}
		if frame.Seq != 0 {
			return ErrSeq
		}
	}
	if frame.Kind == KindCredit && len(frame.Payload) != 4 {
		return ErrCredit
	}
	return nil
}

func raw(frame Frame) []byte {
	out := make([]byte, HeaderSize+len(frame.Payload))
	out[0] = Version
	out[1] = frame.Kind
	out[2] = frame.Flags
	copy(out[4:20], frame.Peer[:])
	binary.BigEndian.PutUint32(out[20:24], frame.Channel)
	binary.BigEndian.PutUint64(out[24:32], frame.Seq)
	copy(out[HeaderSize:], frame.Payload)
	return out
}
