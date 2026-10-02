package dev.phonestation.adbkeep;

final class RelayProfileTest {
    public static void main(String[] args) {
        String[][] valid = {
                {"https://relay.example.com", "https://relay.example.com"},
                {" https://relay.example.com:24443/ ", "https://relay.example.com:24443"},
                {"https://192.0.2.10:443/station/", "https://192.0.2.10:443/station"},
                {"https://[2001:db8::1]:24443/relay/v2/", "https://[2001:db8::1]:24443/relay/v2"},
                {"https://localhost", "https://localhost"},
        };
        for (String[] pair : valid) {
            if (!pair[1].equals(RelayProfile.normalizeEndpoint(pair[0]))) {
                throw new AssertionError("valid relay rejected: " + pair[0]);
            }
        }
        String[] invalid = {null, "", "http://relay.example.com", "https://user@relay.example.com",
                "https://relay.example.com?token=secret", "https://relay.example.com#fragment",
                "https://relay.example.com:0", "https://relay.example.com:65536", "https://relay.example.com:",
                "https://relay.example.com/../other", "https://relay.example.com/a/./b",
                "https://relay.example.com/a b", "https://relay.example.com/$(whoami)", "https://relay.example.com/'",
                "https://[bad]", "https://a.-b", "https://a..b"};
        for (String value : invalid) {
            if (RelayProfile.normalizeEndpoint(value) != null) {
                throw new AssertionError("invalid relay accepted: " + value);
            }
        }
        String address = "https://relay.example.com";
        String pin = "a".repeat(64);
        String token = "b".repeat(64);
        expect(null, RelayProfile.validationMessage(address + "/", pin.toUpperCase(), "", address, pin, true));
        expect(null, RelayProfile.validationMessage(address, pin, token, "", "", false));
        expectError(RelayProfile.validationMessage(address, pin, "", "", "", false));
        expectError(RelayProfile.validationMessage("https://other.example.com", pin, "", address, pin, true));
        expectError(RelayProfile.validationMessage(address, "c".repeat(64), "", address, pin, true));
        expectError(RelayProfile.validationMessage(address, pin, "invalid", address, pin, true));
        System.out.println("RelayProfileTest ok");
    }

    private static void expect(String expected, String actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) { throw new AssertionError(actual); }
    }
    private static void expectError(String actual) {
        if (actual == null) { throw new AssertionError("credential validation was bypassed"); }
    }
}
