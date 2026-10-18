import Foundation

@main
enum PhoneRosterTest {
    static func main() {
        run()
    }
}

private func transport(_ serial: String) -> String {
    serial.contains(":") || serial.contains("._adb") ? "无线连接" : "USB 连接"
}

private func run() {
let same = PhoneRoster.collapse(serials: ["192.0.2.8:5555", "usb-1"], identity: { _ in "same-phone" }, transport: transport)
precondition(same.count == 1 && same[0].serial == "usb-1", "same phone keeps the USB serial")
precondition(PhoneRoster.choose(same, preferred: "192.0.2.8:5555")?.serial == "usb-1", "wireless alias still selects that phone")

let two = PhoneRoster.collapse(serials: ["usb-a", "usb-b"], identity: { $0 }, transport: transport)
precondition(PhoneRoster.choose(two, preferred: nil)?.serial == "usb-a", "without a choice the first phone stays current")
precondition(PhoneRoster.choose(two, preferred: "usb-b")?.serial == "usb-b", "menu selection switches phones")
precondition(PhoneRoster.choose(two, preferred: "missing")?.serial == "usb-a", "a missing phone falls back to the first")
precondition(PhoneRoster.choose([], preferred: "usb-a") == nil, "no phone means nothing to switch")

let suite = UserDefaults(suiteName: "phone-roster-test")!
suite.removePersistentDomain(forName: "phone-roster-test")
precondition(PhoneRoster.remembered(defaults: suite) == nil, "empty selection")
PhoneRoster.remember("usb-b", defaults: suite)
precondition(PhoneRoster.remembered(defaults: suite) == "usb-b", "selection is kept")
PhoneRoster.remember("bad serial", defaults: suite)
precondition(PhoneRoster.remembered(defaults: suite) == nil, "a serial with a space is ignored")
suite.removePersistentDomain(forName: "phone-roster-test")
print("PhoneRosterTest ok")
}
