import AppKit
@preconcurrency import AVFoundation
import AudioToolbox
import Foundation

@MainActor
final class RemoteScreenSession: NSObject, NSWindowDelegate, URLSessionTaskDelegate {
    var onEnded: (String, URL?) -> Void = { _, _ in }
    var onReady: () -> Void = {}
    var onRecordingEnded: (String, URL?) -> Void = { _, _ in }
    private var socket: URLSessionWebSocketTask?
    private var transport: URLSession?
    private var task: Task<Void, Never>?
    private var window: NSWindow?
    private var view: RemoteScreenView?
    private var route: (String, String)?
    private var id: String?
    private var ended = false
    private var display = AVSampleBufferDisplayLayer()
    private var audio = AVSampleBufferAudioRenderer()
    private var synchronizer = AVSampleBufferRenderSynchronizer()
    private var videoFormat: CMVideoFormatDescription?
    private var audioFormat: CMAudioFormatDescription?
    private var sps: Data?
    private var pps: Data?
    private var origin: Int64?
    private var writer: AVAssetWriter?
    private var videoInput: AVAssetWriterInput?
    private var audioInput: AVAssetWriterInput?
    private var recordURL: URL?
    private var recordOrigin: Int64?
    private var awaitingRecording = false
    private var audioKnown = false
    private var requestedRecordingAt: Date?
    private var receivedVideo = false
    private var width = 1
    private var height = 1
    private var inputQueue: [Data] = []
    private var sendingInput = false
    private var pendingKey = "remoteScreen.pending.v1"

    func start(endpoint: String, token: String, recording: URL?) {
        guard task == nil else { return }
        route = (endpoint, token); id = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased(); ended = false
        inputQueue.removeAll(); sendingInput = false
        recordURL = recording; awaitingRecording = recording != nil
        audioKnown = false; requestedRecordingAt = recording == nil ? nil : Date()
        videoFormat = nil; audioFormat = nil; sps = nil; pps = nil; origin = nil; receivedVideo = false
        display = AVSampleBufferDisplayLayer(); audio = AVSampleBufferAudioRenderer()
        synchronizer = AVSampleBufferRenderSynchronizer()
        synchronizer.addRenderer(display); synchronizer.addRenderer(audio)
        guard let id else { return }
        let previous = UserDefaults.standard.string(forKey: pendingKey)
        UserDefaults.standard.set(id, forKey: pendingKey)
        task = Task { [weak self] in
            guard let self else { return }
            do {
                // Recover only cleanup from an interrupted App; never reopen old capture.
                if let previous { _ = try? await Self.call(endpoint: endpoint, token: token, name: "station_screen_close", arguments: ["sessionId": previous]) }
                guard !Task.isCancelled else { return }
                let urlString = endpoint.replacingOccurrences(of: "http://", with: "ws://").replacingOccurrences(of: "/mcp", with: "/__screen/\(id)")
                guard let url = URL(string: urlString), url.host == "127.0.0.1", url.scheme == "ws" else { throw ScreenProtocolError.invalid }
                var request = URLRequest(url: url); request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
                let config = URLSessionConfiguration.ephemeral; config.timeoutIntervalForRequest = 30
                let transport = URLSession(configuration: config, delegate: self, delegateQueue: nil); self.transport = transport
                let socket = transport.webSocketTask(with: request); socket.maximumMessageSize = 4 * 1024 * 1024
                self.socket = socket; socket.resume()
                // Start exactly once; loss of this response is resolved by querying its job.
                do { _ = try await Self.call(endpoint: endpoint, token: token, name: "station_screen_open", arguments: ["sessionId": id]) }
                catch {
                    let job = try await Self.call(endpoint: endpoint, token: token, name: "station_operation_status", arguments: ["jobId": id])
                    let state = job["state"] as? String ?? "missing"
                    guard ["queued", "running", "completed"].contains(state) else { throw error }
                }
                guard !Task.isCancelled else { return }
                self.showWindow()
                let timeout = Task { [weak self] in
                    try? await Task.sleep(nanoseconds: 20_000_000_000)
                    guard !Task.isCancelled, let self, !self.receivedVideo else { return }
                    self.stop(reason: "未收到画面；请检查手机 Shizuku 和中继版本。")
                }
                defer { timeout.cancel() }
                while !Task.isCancelled {
                    let message = try await socket.receive()
                    guard case let .data(data) = message else { throw ScreenProtocolError.invalid }
                    try self.receive(ScreenPacket(data))
                }
            } catch { if !self.ended { self.stop(reason: "远程投屏已结束：\(error.localizedDescription)") } }
        }
    }

    nonisolated func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
    private static func call(endpoint: String, token: String, name: String, arguments: [String: Any]) async throws -> [String: Any] {
        let data = try await ClipboardRPC.call(endpoint: endpoint, token: token, name: name, arguments: arguments)
        guard let value = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw ScreenProtocolError.invalid }
        return value
    }
    private func showWindow() {
        let view = RemoteScreenView(frame: NSRect(x: 0, y: 0, width: 420, height: 760))
        view.wantsLayer = true; view.layer?.backgroundColor = NSColor.black.cgColor; view.layer?.addSublayer(display)
        view.display = display
        view.input = { [weak self] action, point in self?.touch(action: action, point: point) }
        view.key = { [weak self] code in self?.sendKey(code) }
        view.text = { [weak self] text in self?.sendText(text) }
        view.scroll = { [weak self] point, x, y in self?.scroll(point: point, x: x, y: y) }
        self.view = view
        let w = NSWindow(contentRect: view.bounds, styleMask: [.titled, .closable, .resizable, .miniaturizable], backing: .buffered, defer: false)
        w.title = "手机工位 · 远程投屏"; w.contentView = view; w.delegate = self; w.isReleasedWhenClosed = false
        w.center(); w.makeKeyAndOrderFront(nil); NSApp.activate(ignoringOtherApps: true); window = w
    }
    func windowWillClose(_ notification: Notification) { stop(reason: "远程投屏已关闭。") }

    func receive(_ packet: ScreenPacket) throws {
        if packet.channel == 0 {
            guard ScreenPacket.number(packet.payload) == 0x68323634 else { throw ScreenProtocolError.invalid }; return
        }
        if packet.channel == 2 {
            let codec = ScreenPacket.number(packet.payload)
            guard codec == 0x00616163 || codec == 0 else { throw ScreenProtocolError.invalid }; if codec == 0 { audioKnown = true }; return
        }
        if packet.session {
            let b = [UInt8](packet.payload)
            let w = Int(ScreenPacket.number(b[0..<4])), h = Int(ScreenPacket.number(b[4..<8]))
            guard w > 0, h > 0, w <= 8192, h <= 8192 else { throw ScreenProtocolError.invalid }
            if writer != nil && (w != width || h != height) {
                finishRecording(message: "画面尺寸改变，录屏已保存；可再次开始录屏。")
            }
            width = w; height = h; view?.videoSize = CGSize(width: w, height: h)
            window?.contentAspectRatio = NSSize(width: w, height: h); return
        }
        if packet.configuration {
            if packet.channel == 1 { try configureVideo(packet.payload) }
            else { try configureAudio(packet.payload); audioKnown = true }
            return
        }
        guard packet.microseconds >= 0 else { throw ScreenProtocolError.invalid }
        if origin == nil {
            origin = packet.microseconds
            synchronizer.setRate(1, time: CMTime(value: -80_000, timescale: 1_000_000))
        }
        let isVideo = packet.channel == 1
        guard let format = isVideo ? videoFormat : audioFormat else { return }
        let payload = isVideo ? ScreenPacket.avcc(try ScreenPacket.nals(packet.payload)) : packet.payload
        let live = try sample(payload, format: format, pts: packet.microseconds - (origin ?? 0), video: isVideo, key: packet.keyFrame)
        if isVideo {
            if display.status == .failed { display.flush() }
            // Let the hardware decoder consume reference frames even when the window is occluded.
            display.enqueue(live)
            if !receivedVideo { receivedVideo = true; onReady() }
        } else if audio.isReadyForMoreMediaData { audio.enqueue(live) }
        if awaitingRecording && isVideo && packet.keyFrame && (audioKnown || Date().timeIntervalSince(requestedRecordingAt ?? Date()) > 2) { try beginRecording(format: format, pts: packet.microseconds) }
        if let writer, let base = recordOrigin {
            let input = isVideo ? videoInput : audioInput
            if let input, packet.microseconds >= base {
                guard input.isReadyForMoreMediaData else { finishRecording(message: "录屏写入跟不上，已保存现有视频。"); return }
                let recorded = try sample(payload, format: format, pts: packet.microseconds - base, video: isVideo, key: packet.keyFrame)
                if !input.append(recorded) || writer.status == .failed { finishRecording(message: "录屏保存失败。"); return }
            }
        }
    }
    private func configureVideo(_ bytes: Data) throws {
        for nal in try ScreenPacket.nals(bytes) {
            if nal.first.map({ $0 & 31 }) == 7 { sps = nal }
            if nal.first.map({ $0 & 31 }) == 8 { pps = nal }
        }
        guard let sps, let pps else { throw ScreenProtocolError.invalid }
        var format: CMFormatDescription?
        let status = sps.withUnsafeBytes { s in pps.withUnsafeBytes { p in
            let pointers = [s.bindMemory(to: UInt8.self).baseAddress!, p.bindMemory(to: UInt8.self).baseAddress!]
            let sizes = [sps.count, pps.count]
            return CMVideoFormatDescriptionCreateFromH264ParameterSets(allocator: kCFAllocatorDefault, parameterSetCount: 2, parameterSetPointers: pointers, parameterSetSizes: sizes, nalUnitHeaderLength: 4, formatDescriptionOut: &format)
        }}
        guard status == noErr, let format else { throw ScreenProtocolError.invalid }; videoFormat = format
    }
    private func configureAudio(_ cookie: Data) throws {
        let b = [UInt8](cookie); guard b.count >= 2 else { throw ScreenProtocolError.invalid }
        let rates: [Double] = [96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350]
        let index = Int((b[0] & 7) << 1 | b[1] >> 7), channels = UInt32((b[1] >> 3) & 15)
        guard index < rates.count, channels >= 1, channels <= 2 else { throw ScreenProtocolError.invalid }
        var desc = AudioStreamBasicDescription(mSampleRate: rates[index], mFormatID: kAudioFormatMPEG4AAC, mFormatFlags: 2, mBytesPerPacket: 0, mFramesPerPacket: 1024, mBytesPerFrame: 0, mChannelsPerFrame: channels, mBitsPerChannel: 0, mReserved: 0)
        // Core Audio expects an ES descriptor magic cookie, rather than Android's raw AudioSpecificConfig.
        guard cookie.count < 100, b[0] >> 3 == 2 else { throw ScreenProtocolError.invalid }
        let decoder = Data([0x40, 0x15, 0, 0, 0, 0, 1, 0xf4, 0, 0, 1, 0xf4, 0, 5, UInt8(cookie.count)]) + cookie
        let es = Data([0, 1, 0, 4, UInt8(decoder.count)]) + decoder + Data([6, 1, 2])
        let magic = Data([3, UInt8(es.count)]) + es
        var format: CMAudioFormatDescription?
        let status = magic.withUnsafeBytes { ptr in CMAudioFormatDescriptionCreate(allocator: kCFAllocatorDefault, asbd: &desc, layoutSize: 0, layout: nil, magicCookieSize: magic.count, magicCookie: ptr.baseAddress, extensions: nil, formatDescriptionOut: &format) }
        guard status == noErr, let format else { throw ScreenProtocolError.invalid }; audioFormat = format
    }
    private func sample(_ data: Data, format: CMFormatDescription, pts: Int64, video: Bool, key: Bool) throws -> CMSampleBuffer {
        var block: CMBlockBuffer?
        guard CMBlockBufferCreateWithMemoryBlock(allocator: kCFAllocatorDefault, memoryBlock: nil, blockLength: data.count, blockAllocator: kCFAllocatorDefault, customBlockSource: nil, offsetToData: 0, dataLength: data.count, flags: 0, blockBufferOut: &block) == noErr, let block else { throw ScreenProtocolError.invalid }
        let copied = data.withUnsafeBytes { ptr in CMBlockBufferReplaceDataBytes(with: ptr.baseAddress!, blockBuffer: block, offsetIntoDestination: 0, dataLength: data.count) }
        guard copied == noErr else { throw ScreenProtocolError.invalid }
        if !video {
            var description = AudioStreamPacketDescription(mStartOffset: 0, mVariableFramesInPacket: 0, mDataByteSize: UInt32(data.count))
            var audioSample: CMSampleBuffer?
            guard CMAudioSampleBufferCreateReadyWithPacketDescriptions(allocator: kCFAllocatorDefault, dataBuffer: block, formatDescription: format, sampleCount: 1, presentationTimeStamp: CMTime(value: pts, timescale: 1_000_000), packetDescriptions: &description, sampleBufferOut: &audioSample) == noErr, let audioSample else { throw ScreenProtocolError.invalid }
            return audioSample
        }
        var timing = CMSampleTimingInfo(duration: video ? .invalid : CMTime(value: 1024, timescale: 48000), presentationTimeStamp: CMTime(value: pts, timescale: 1_000_000), decodeTimeStamp: .invalid)
        var size = data.count; var sample: CMSampleBuffer?
        guard CMSampleBufferCreateReady(allocator: kCFAllocatorDefault, dataBuffer: block, formatDescription: format, sampleCount: 1, sampleTimingEntryCount: 1, sampleTimingArray: &timing, sampleSizeEntryCount: 1, sampleSizeArray: &size, sampleBufferOut: &sample) == noErr, let sample else { throw ScreenProtocolError.invalid }
        if video, let attachments = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: true) {
            let dict = unsafeBitCast(CFArrayGetValueAtIndex(attachments, 0), to: CFMutableDictionary.self)
            CFDictionarySetValue(dict, Unmanaged.passUnretained(kCMSampleAttachmentKey_NotSync).toOpaque(), Unmanaged.passUnretained(key ? kCFBooleanFalse : kCFBooleanTrue).toOpaque())
        }
        return sample
    }
    func startRecording(_ url: URL) { guard writer == nil, !awaitingRecording else { return }; recordURL = url; awaitingRecording = true; requestedRecordingAt = Date() }
    private func beginRecording(format: CMFormatDescription, pts: Int64) throws {
        guard let recordURL else { return }
        let partial = recordURL.deletingLastPathComponent().appendingPathComponent(".\(recordURL.lastPathComponent).partial.mp4")
        let writer = try AVAssetWriter(outputURL: partial, fileType: .mp4)
        let v = AVAssetWriterInput(mediaType: .video, outputSettings: nil, sourceFormatHint: format); v.expectsMediaDataInRealTime = true
        guard writer.canAdd(v) else { throw ScreenProtocolError.invalid }; writer.add(v)
        if let audioFormat {
            let a = AVAssetWriterInput(mediaType: .audio, outputSettings: nil, sourceFormatHint: audioFormat); a.expectsMediaDataInRealTime = true
            guard writer.canAdd(a) else { throw ScreenProtocolError.invalid }; writer.add(a); audioInput = a
        }
        guard writer.startWriting() else { throw writer.error ?? ScreenProtocolError.invalid }
        writer.startSession(atSourceTime: .zero); self.writer = writer; videoInput = v; recordOrigin = pts; awaitingRecording = false
    }
    func stopRecording() { finishRecording(message: "录屏已保存。") }
    private func finishRecording(message: String, completion: (() -> Void)? = nil) {
        let w = writer, url = recordURL
        writer = nil; recordURL = nil; recordOrigin = nil; awaitingRecording = false
        videoInput?.markAsFinished(); audioInput?.markAsFinished(); videoInput = nil; audioInput = nil
        guard let w else { onRecordingEnded("未收到可录制的画面。", nil); completion?(); return }
        Task { @MainActor [weak self] in
            await w.finishWriting()
            var saved: URL?
            if w.status == .completed, let url {
                do { try FileManager.default.moveItem(at: w.outputURL, to: url); saved = url } catch {}
            }
            self?.onRecordingEnded(saved != nil ? message : "录屏保存失败。", saved); completion?()
        }
    }
    func stop(reason: String = "远程投屏已关闭。") {
        guard !ended else { return }; ended = true
        task?.cancel(); task = nil; socket?.cancel(with: .normalClosure, reason: nil); socket = nil
        transport?.invalidateAndCancel(); transport = nil
        synchronizer.rate = 0; display.flushAndRemoveImage(); audio.flush()
        window?.delegate = nil; window?.close(); window = nil; view = nil
        if let id, let (endpoint, token) = route {
            Task { _ = try? await Self.call(endpoint: endpoint, token: token, name: "station_screen_close", arguments: ["sessionId": id]) }
        }
        let report: () -> Void = { [weak self] in self?.onEnded(reason, nil) }
        if writer != nil || awaitingRecording { finishRecording(message: "录屏已保存。", completion: report) } else { report() }
    }
    private func send(_ data: Data) {
        guard socket != nil, !ended else { return }
        guard inputQueue.count < 64 else { stop(reason: "远程输入连接拥堵，投屏已结束。"); return }
        inputQueue.append(data)
        guard !sendingInput else { return }; sendingInput = true
        Task {
            defer { self.sendingInput = false }
            do {
                while !self.inputQueue.isEmpty, !self.ended, let socket = self.socket {
                    let input = self.inputQueue.removeFirst(); try await socket.send(.data(input))
                }
            } catch { self.stop(reason: "远程控制连接已断开。") }
        }
    }
    private func touch(action: UInt8, point: CGPoint) {
        guard width > 0, height > 0 else { return }
        let x = UInt32(max(0, min(Double(width - 1), point.x * Double(width))))
        let y = UInt32(max(0, min(Double(height - 1), point.y * Double(height))))
        send(ScreenPacket.touch(action: action, x: x, y: y, width: UInt16(width), height: UInt16(height)))
    }
    private func scroll(point: CGPoint, x: Double, y: Double) {
        var b = Data([3])
        b.append(ScreenPacket.bigEndian(UInt64(max(0, min(Double(width - 1), point.x * Double(width)))), bytes: 4))
        b.append(ScreenPacket.bigEndian(UInt64(max(0, min(Double(height - 1), point.y * Double(height)))), bytes: 4))
        b.append(ScreenPacket.bigEndian(UInt64(width), bytes: 2)); b.append(ScreenPacket.bigEndian(UInt64(height), bytes: 2))
        for axis in [x, y] { b.append(ScreenPacket.bigEndian(UInt64(UInt16(bitPattern: Int16(max(-32768, min(32767, axis / 16 * 32767))))), bytes: 2)) }
        b.append(ScreenPacket.bigEndian(0, bytes: 4)); send(b)
    }
    private func sendKey(_ code: UInt32) { send(ScreenPacket.key(code, down: true)); send(ScreenPacket.key(code, down: false)) }
    private func sendText(_ text: String) {
        let bytes = Data(text.utf8); guard !bytes.isEmpty, bytes.count <= 4091 else { return }
        var b = Data([1]); b.append(ScreenPacket.bigEndian(UInt64(bytes.count), bytes: 4)); b.append(bytes); send(b)
    }
}

@MainActor
private final class RemoteScreenView: NSView {
    var display: AVSampleBufferDisplayLayer?
    var videoSize = CGSize(width: 1, height: 1) { didSet { needsLayout = true } }
    var input: (UInt8, CGPoint) -> Void = { _, _ in }
    var key: (UInt32) -> Void = { _ in }
    var scroll: (CGPoint, Double, Double) -> Void = { _, _, _ in }
    var text: (String) -> Void = { _ in }
    private var touching = false
    private var lastTouch = CGPoint(x: 0.5, y: 0.5)
    override var acceptsFirstResponder: Bool { true }
    override func layout() { super.layout(); display?.frame = bounds; display?.videoGravity = .resizeAspect }
    private func point(_ event: NSEvent) -> CGPoint? {
        let scale = min(bounds.width / videoSize.width, bounds.height / videoSize.height)
        let size = CGSize(width: videoSize.width * scale, height: videoSize.height * scale)
        let rect = CGRect(x: (bounds.width - size.width) / 2, y: (bounds.height - size.height) / 2, width: size.width, height: size.height)
        let p = convert(event.locationInWindow, from: nil)
        guard rect.contains(p) else { return nil }
        return CGPoint(x: (p.x - rect.minX) / rect.width, y: 1 - (p.y - rect.minY) / rect.height)
    }
    override func mouseDown(with event: NSEvent) { window?.makeFirstResponder(self); if let p = point(event) { touching = true; lastTouch = p; input(0, p) } }
    override func mouseDragged(with event: NSEvent) { if touching, let p = point(event) { lastTouch = p; input(2, p) } }
    override func mouseUp(with event: NSEvent) { if touching { input(1, point(event) ?? lastTouch); touching = false } }
    override func scrollWheel(with event: NSEvent) { scroll(point(event) ?? CGPoint(x: 0.5, y: 0.5), event.scrollingDeltaX / 10, event.scrollingDeltaY / 10) }
    override func rightMouseDown(with event: NSEvent) { key(4) }
    override func keyDown(with event: NSEvent) {
        switch event.keyCode {
        case 53: key(4)
        case 36: key(66)
        case 51: key(67)
        case 123: key(21)
        case 124: key(22)
        case 125: key(20)
        case 126: key(19)
        case 115: key(3)
        default: if let characters = event.characters, !event.modifierFlags.contains(.command) { text(characters) }
        }
    }
}
