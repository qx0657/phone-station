import AppKit
import AVFoundation
import Foundation

@main struct RemoteScreenSessionTest {
    @MainActor static func main() async throws {
        // Generated solid-color H.264 and sine-wave AAC fixture, with interleaved timestamps.
        let fixture = URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("fixtures/screen-av.json")
        let json = try JSONSerialization.jsonObject(with: Data(contentsOf: fixture)) as! [String: Any]
        func hex(_ string: String) -> Data {
            let b = Array(string.utf8); return Data(stride(from: 0, to: b.count, by: 2).map { i in UInt8(String(bytes: b[i..<i+2], encoding: .utf8)!, radix: 16)! })
        }
        func packet(channel: UInt8, flags: UInt64, bytes: Data) throws -> ScreenPacket {
            try ScreenPacket(Data([channel]) + ScreenPacket.bigEndian(flags, bytes: 8) + ScreenPacket.bigEndian(UInt64(bytes.count), bytes: 4) + bytes)
        }
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("screen-record-test-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let file = dir.appendingPathComponent("record.mp4"), session = RemoteScreenSession()
        var done = false, saved: URL?
        session.onRecordingEnded = { _, url in done = true; saved = url }
        try session.receive(packet(channel: 1, flags: 1 << 62, bytes: hex(json["videoConfig"] as! String)))
        try session.receive(packet(channel: 3, flags: 1 << 62, bytes: hex(json["audioConfig"] as! String)))
        session.startRecording(file)
        for value in json["events"] as! [[String: Any]] {
            let flags = UInt64(value["pts"] as! Int) | ((value["key"] as! Bool) ? 1 << 61 : 0)
            try session.receive(packet(channel: UInt8(value["channel"] as! Int), flags: flags, bytes: hex(value["hex"] as! String)))
            try await Task.sleep(nanoseconds: 5_000_000)
        }
        session.stopRecording()
        let deadline = Date().addingTimeInterval(8)
        while !done && Date() < deadline { try await Task.sleep(nanoseconds: 20_000_000) }
        precondition(saved == file, "MP4 did not finalize")
        let asset = AVURLAsset(url: file)
        let video = try await asset.loadTracks(withMediaType: .video), audio = try await asset.loadTracks(withMediaType: .audio)
        precondition(video.count == 1 && audio.count == 1, "recording must preserve both streams")
        let duration = try await asset.load(.duration); precondition(duration.seconds > 1)
        let reader = try AVAssetReader(asset: asset)
        let output = AVAssetReaderTrackOutput(track: video[0], outputSettings: [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA])
        reader.add(output); precondition(reader.startReading())
        var frames = 0
        while let sample = output.copyNextSampleBuffer() { precondition(CMSampleBufferGetImageBuffer(sample) != nil); frames += 1 }
        precondition(frames == 12 && reader.status == .completed, "compressed samples must decode after finalization")
        let audioReader = try AVAssetReader(asset: asset)
        let decoded = AVAssetReaderTrackOutput(track: audio[0], outputSettings: [AVFormatIDKey: kAudioFormatLinearPCM])
        audioReader.add(decoded); precondition(audioReader.startReading())
        var packets = 0
        while let sample = decoded.copyNextSampleBuffer() { precondition(CMSampleBufferGetNumSamples(sample) > 0); packets += 1 }
        precondition(packets > 0 && audioReader.status == .completed, "AAC must decode into PCM")
        print("Remote screen native H.264/AAC and MP4 tests ok")
    }
}
