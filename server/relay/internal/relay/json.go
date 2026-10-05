package relay

import (
	"bytes"
	"encoding/json"
	"io"
)

// Reject duplicate keys (including escaped aliases) and nesting the phone cannot
// parse. Proxy, receipt and phone must agree on the exact operation being sent.
func unambiguousJSON(body []byte) bool {
	d := json.NewDecoder(bytes.NewReader(body))
	d.UseNumber()
	var value func(int) bool
	value = func(depth int) bool {
		if depth > 40 {
			return false
		}
		token, err := d.Token()
		if err != nil {
			return false
		}
		delim, compound := token.(json.Delim)
		if !compound {
			return true
		}
		if delim != '{' && delim != '[' {
			return false
		}
		keys := map[string]bool{}
		for d.More() {
			if delim == '{' {
				token, err = d.Token()
				key, valid := token.(string)
				if err != nil || !valid || keys[key] {
					return false
				}
				keys[key] = true
			}
			if !value(depth + 1) {
				return false
			}
		}
		end, err := d.Token()
		return err == nil && ((delim == '{' && end == json.Delim('}')) || (delim == '[' && end == json.Delim(']')))
	}
	if !value(0) {
		return false
	}
	_, err := d.Token()
	return err == io.EOF
}
