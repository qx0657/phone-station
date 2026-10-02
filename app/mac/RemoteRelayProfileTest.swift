import Foundation

@main
struct RemoteRelayProfileTest {
    static func main() throws {
        let profile = RemoteRelayProfile()
        precondition(!profile.configured && profile.endpoint.isEmpty && profile.pin.isEmpty)
        let saved = try JSONDecoder().decode(RemoteRelayProfile.self, from: Data(
            "{\"endpoint\":\"https://relay.example.com/station\",\"pin\":\"abc\",\"configured\":true}".utf8))
        precondition(saved.configured && saved.endpoint == "https://relay.example.com/station")
        var draft = RemoteRelayDraft(endpoint: " https://relay.example.com/station/ ",
                                     pin: String(repeating: "A", count: 64),
                                     phoneToken: String(repeating: "B", count: 64),
                                     desktopToken: String(repeating: "C", count: 64))
        precondition(draft.validationMessage == nil)
        draft.phoneToken = ""
        precondition(draft.validationMessage(configurePhone: false) == nil)
        precondition(draft.validationMessage(configurePhone: true) != nil)
        let single = StationRunner.capture(URL(fileURLWithPath: "/bin/cat"), [], input: draft.desktopCredentialInput)
        precondition(single.succeeded && single.output == String(repeating: "c", count: 64))
        draft.phoneToken = String(repeating: "B", count: 64)
        precondition(draft.normalized.endpoint == "https://relay.example.com/station/")
        // The runner must send two complete input lines and close stdin, without
        // putting either credential in its argument list.
        let result = StationRunner.capture(URL(fileURLWithPath: "/bin/cat"), [], input: draft.credentialInput)
        precondition(result.succeeded && result.output == String(data: draft.credentialInput, encoding: .utf8)!.trimmingCharacters(in: .newlines))
        draft.desktopToken = draft.phoneToken.lowercased()
        precondition(draft.validationMessage != nil)
        draft.desktopToken = "invalid"
        precondition(draft.validationMessage != nil)
        draft.endpoint = "http://relay.example.com"
        precondition(draft.validationMessage != nil)
        print("RemoteRelayProfileTest ok")
    }
}
