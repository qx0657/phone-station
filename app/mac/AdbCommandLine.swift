import Foundation

/// Splits the text after `adb -s <serial>` into arguments.
/// Quotes group words. Nothing is expanded: no variables, globs, or command substitution.
enum AdbCommandLine {
    struct ParseError: Error, Equatable {
        var message: String
    }

    static func normalize(_ source: String) -> String {
        source
            .replacingOccurrences(of: "\u{201C}", with: "\"")
            .replacingOccurrences(of: "\u{201D}", with: "\"")
            .replacingOccurrences(of: "\u{2018}", with: "'")
            .replacingOccurrences(of: "\u{2019}", with: "'")
    }

    static func validate(_ source: String) throws -> [String] {
        let tokens = try tokenize(source)
        if tokens.isEmpty {
            throw ParseError(message: "这条命令没有参数。")
        }
        if tokens.contains(where: { $0 == "-s" || $0 == "--serial" }) {
            throw ParseError(message: "序列号由当前手机决定，参数里不要写 -s。")
        }
        return tokens
    }

    static func tokenize(_ source: String) throws -> [String] {
        let characters = Array(normalize(source))
        var tokens: [String] = []
        var current = ""
        var inToken = false
        var quote: Character?
        var escape = false

        for character in characters {
            if escape {
                current.append(character)
                inToken = true
                escape = false
                continue
            }
            if character == "\\" && quote != "'" {
                escape = true
                inToken = true
                continue
            }
            if quote == nil && (character == "\"" || character == "'") {
                quote = character
                inToken = true
                continue
            }
            if let active = quote, character == active {
                quote = nil
                inToken = true
                continue
            }
            if quote == nil && character.isWhitespace {
                if inToken {
                    tokens.append(current)
                    current = ""
                    inToken = false
                }
                continue
            }
            current.append(character)
            inToken = true
        }
        if escape || quote != nil {
            throw ParseError(message: "参数的引号没有成对。")
        }
        if inToken {
            tokens.append(current)
        }
        return tokens
    }

    /// Last lines, for the panel. Copy uses this same text.
    static func displayedOutput(_ raw: String, maxLines: Int = 12) -> String {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return "" }
        let lines = trimmed.split(separator: "\n", omittingEmptySubsequences: false).map(String.init)
        var text = lines.suffix(maxLines).joined(separator: "\n")
        if text.count > 2400 {
            text = String(text.suffix(2400))
        }
        return text
    }
}

enum BatteryReport {
    struct Reading: Equatable {
        var percent: Int
        var charging: Bool
    }

    static func parse(_ output: String) -> Reading? {
        var level: Int?
        var scale = 100
        var status: Int?
        for line in output.split(separator: "\n") {
            let parts = line.split(separator: ":", maxSplits: 1).map {
                $0.trimmingCharacters(in: .whitespaces)
            }
            guard parts.count == 2 else { continue }
            switch parts[0] {
            case "level":
                level = Int(parts[1])
            case "scale":
                if let value = Int(parts[1]), value > 0 { scale = value }
            case "status":
                status = Int(parts[1])
            default:
                break
            }
        }
        guard let level, level >= 0 else { return nil }
        let percent = min(100, max(0, Int((Double(level) / Double(scale) * 100).rounded())))
        // BatteryManager.BATTERY_STATUS_CHARGING
        return Reading(percent: percent, charging: status == 2)
    }
}

/// 与手机 StayAwake.held 一致；长息屏时间本身不代表工位已接管。
enum StayAwakeReport {
    static func held(_ output: String) -> Bool {
        let values = output.split(whereSeparator: { $0.isWhitespace }).map(String.init)
        return values == ["2147483647", "7"]
    }
}
