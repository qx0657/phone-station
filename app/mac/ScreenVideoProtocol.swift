import Foundation

/// Version 1 multiplexing: scrcpy 4.1 metadata and access units, never arbitrary files.
struct ScreenPacket {
    let channel: UInt8
    let flags: UInt64
    let payload: Data
    var configuration: Bool { flags & (1 << 62) != 0 }
    var keyFrame: Bool { flags & (1 << 61) != 0 }
    var session: Bool { flags & (1 << 63) != 0 }
    var microseconds: Int64 { Int64(flags & ((1 << 61) - 1)) }
    init(_ bytes: Data) throws {
        let b = [UInt8](bytes)
        guard let channel = b.first, channel <= 3 else { throw ScreenProtocolError.invalid }
        self.channel = channel
        if channel == 0 || channel == 2 {
            guard b.count == 5 else { throw ScreenProtocolError.invalid }
            flags = 0; payload = Data(b.dropFirst()); return
        }
        guard b.count >= 13, b.count <= 4 * 1024 * 1024 else { throw ScreenProtocolError.invalid }
        flags = Self.number(b[1..<9])
        if flags & (1 << 63) != 0 {
            guard channel == 1, b.count == 13 else { throw ScreenProtocolError.invalid }
            payload = Data(b[5..<13]); return
        }
        let size = Self.number(b[9..<13])
        guard size > 0, size == UInt64(b.count - 13) else { throw ScreenProtocolError.invalid }
        payload = Data(b.dropFirst(13))
    }
    static func number<C: Collection>(_ bytes: C) -> UInt64 where C.Element == UInt8 {
        bytes.reduce(0) { ($0 << 8) | UInt64($1) }
    }
    static func nals(_ data: Data) throws -> [Data] {
        let b = [UInt8](data); var starts: [(Int, Int)] = []; var i = 0
        while i + 2 < b.count {
            if b[i] == 0 && b[i + 1] == 0 {
                if b[i + 2] == 1 { starts.append((i, i + 3)); i += 3; continue }
                if i + 3 < b.count && b[i + 2] == 0 && b[i + 3] == 1 { starts.append((i, i + 4)); i += 4; continue }
            }
            i += 1
        }
        guard starts.first?.0 == 0 else { throw ScreenProtocolError.invalid }
        var result: [Data] = []
        for (index, start) in starts.enumerated() {
            let end = index + 1 < starts.count ? starts[index + 1].0 : b.count
            guard end > start.1 else { throw ScreenProtocolError.invalid }
            result.append(Data(b[start.1..<end]))
        }
        return result
    }
    static func avcc(_ nals: [Data]) -> Data {
        var data = Data()
        for nal in nals { data.append(bigEndian(UInt64(nal.count), bytes: 4)); data.append(nal) }
        return data
    }
    static func bigEndian(_ value: UInt64, bytes: Int) -> Data {
        Data((0..<bytes).reversed().map { UInt8(truncatingIfNeeded: value >> ($0 * 8)) })
    }
    static func touch(action: UInt8, x: UInt32, y: UInt32, width: UInt16, height: UInt16) -> Data {
        var b = Data([2, action]); b.append(bigEndian(UInt64.max, bytes: 8))
        b.append(bigEndian(UInt64(x), bytes: 4)); b.append(bigEndian(UInt64(y), bytes: 4))
        b.append(bigEndian(UInt64(width), bytes: 2)); b.append(bigEndian(UInt64(height), bytes: 2))
        b.append(bigEndian(action == 1 ? 0 : 65535, bytes: 2)); b.append(bigEndian(1, bytes: 4)); b.append(bigEndian(action == 1 ? 0 : 1, bytes: 4))
        return b
    }
    static func key(_ code: UInt32, down: Bool) -> Data {
        var b = Data([0, down ? 0 : 1]); b.append(bigEndian(UInt64(code), bytes: 4)); b.append(bigEndian(0, bytes: 4)); b.append(bigEndian(0, bytes: 4)); return b
    }
}
enum ScreenProtocolError: Error { case invalid }
