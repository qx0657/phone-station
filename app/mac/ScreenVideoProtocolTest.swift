import Foundation

@main struct ScreenVideoProtocolTest {
    static func main() throws {
        let session = Data([1]) + ScreenPacket.bigEndian(1 << 31, bytes: 4) + ScreenPacket.bigEndian(720, bytes: 4) + ScreenPacket.bigEndian(1600, bytes: 4)
        let p = try ScreenPacket(session)
        precondition(p.session && p.payload.count == 8)
        var frame = Data([1]); frame.append(ScreenPacket.bigEndian(1 << 61 | 1234, bytes: 8))
        frame.append(ScreenPacket.bigEndian(5, bytes: 4)); frame.append(Data([0, 0, 1, 0x65, 0]))
        let f = try ScreenPacket(frame); precondition(f.keyFrame && f.microseconds == 1234)
        let nals = try ScreenPacket.nals(Data([0,0,0,1,0x67,1,0,0,1,0x68,2]))
        precondition(nals == [Data([0x67,1]), Data([0x68,2])])
        precondition(ScreenPacket.avcc(nals) == Data([0,0,0,2,0x67,1,0,0,0,2,0x68,2]))
        for invalid in [Data(), Data([9]), Data([1,0]), Data([0,1,2,3]), frame + Data([1])] {
            do { _ = try ScreenPacket(invalid); preconditionFailure("invalid framing accepted") } catch {}
        }
        do { _ = try ScreenPacket.nals(Data([0,0,1])); preconditionFailure("empty NAL accepted") } catch {}
        let touch = ScreenPacket.touch(action: 0, x: 20, y: 30, width: 720, height: 1600)
        precondition(touch.count == 32 && touch[0] == 2 && ScreenPacket.key(4, down: true).count == 14)
        print("Screen video protocol tests ok")
    }
}
