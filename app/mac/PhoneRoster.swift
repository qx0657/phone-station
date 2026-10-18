import Foundation

/// 同一台手机的 USB 和无线序列号合成一项。不同身份留在列表里，供菜单栏切换。
struct PhoneRoster {
    struct Seat: Equatable, Sendable {
        var serial: String
        var identity: String
        var transport: String
        var aliases: [String]
    }

    static let preferenceKey = "phone-station.selected-phone"

    static func collapse(serials: [String], identity: (String) -> String, transport: (String) -> String) -> [Seat] {
        var order: [String] = []
        var groups: [String: [String]] = [:]
        for serial in serials {
            let id = identity(serial)
            if groups[id] == nil {
                order.append(id)
                groups[id] = []
            }
            groups[id]?.append(serial)
        }
        return order.compactMap { id in
            guard let members = groups[id], let best = members.min(by: { rank($0, transport: transport($0)) < rank($1, transport: transport($1)) }) else {
                return nil
            }
            return Seat(serial: best, identity: id, transport: transport(best), aliases: members)
        }
    }

    static func choose(_ seats: [Seat], preferred: String?) -> Seat? {
        guard !seats.isEmpty else { return nil }
        if let preferred, let match = seats.first(where: { $0.serial == preferred || $0.aliases.contains(preferred) }) {
            return match
        }
        return seats[0]
    }

    static func remembered(defaults: UserDefaults) -> String? {
        let value = defaults.string(forKey: preferenceKey)?.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let value, !value.isEmpty, value.count <= 128, value.allSatisfy({ $0.isASCII && !$0.isWhitespace }) else { return nil }
        return value
    }

    static func remember(_ serial: String, defaults: UserDefaults) {
        defaults.set(serial, forKey: preferenceKey)
    }

    private static func rank(_ serial: String, transport: String) -> Int {
        if transport == "USB 连接" { return 0 }
        if serial.contains(where: \.isWhitespace) { return 2 }
        return 1
    }
}
