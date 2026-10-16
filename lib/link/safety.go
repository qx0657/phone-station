package link

import (
	"crypto/sha256"
	"encoding/binary"
	"errors"
	"fmt"
)

func SafetyCode(inviterSPKI, deviceSPKI, invitationID, relaySPKI []byte) (string, error) {
	if len(inviterSPKI) == 0 || len(deviceSPKI) == 0 || len(invitationID) != 16 || len(relaySPKI) == 0 {
		return "", errors.New("length")
	}
	hash := sha256.New()
	_, _ = hash.Write([]byte("ps-sas-v1"))
	_, _ = hash.Write(inviterSPKI)
	_, _ = hash.Write(deviceSPKI)
	_, _ = hash.Write(invitationID)
	_, _ = hash.Write(relaySPKI)
	sum := hash.Sum(nil)
	code := binary.BigEndian.Uint32(sum[:4]) % 1000000
	return fmt.Sprintf("%06d", code), nil
}
