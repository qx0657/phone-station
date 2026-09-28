import AudioToolbox
import CoreAudio
import Darwin
import Foundation

// 采集的是各进程送去播放的混音，不是麦克风。
// muteBehavior 必须是 unmuted，否则一开采集电脑就没声音。
// 只往 stdout 打音量，不落盘。

private let meter = Meter()
private let cleanLock = NSLock()
private var cleaned = false
private var tapID = AudioObjectID(kAudioObjectUnknown)
private var aggregateID = AudioObjectID(kAudioObjectUnknown)
private var procID: AudioDeviceIOProcID?

private final class Meter: @unchecked Sendable {
    private let lock = NSLock()
    private var sum: Float = 0
    private var count = 0

    func add(_ samples: UnsafePointer<Float>, count sampleCount: Int) {
        var local: Float = 0
        for i in 0..<sampleCount {
            let sample = samples[i]
            local += sample * sample
        }
        lock.lock()
        sum += local
        count += sampleCount
        lock.unlock()
    }

    func rms() -> Float {
        lock.lock()
        let total = sum
        let n = count
        sum = 0
        count = 0
        lock.unlock()
        if n == 0 { return 0 }
        return sqrt(total / Float(n))
    }
}

private func fourcc(_ status: OSStatus) -> String {
    let value = UInt32(bitPattern: status)
    let bytes = [
        UInt8((value >> 24) & 0xff),
        UInt8((value >> 16) & 0xff),
        UInt8((value >> 8) & 0xff),
        UInt8(value & 0xff),
    ]
    guard bytes.allSatisfy({ $0 >= 32 && $0 < 127 }) else { return "" }
    return String(bytes: bytes, encoding: .utf8).map { " '\($0)'" } ?? ""
}

private func cleanup() {
    cleanLock.lock()
    if cleaned {
        cleanLock.unlock()
        return
    }
    cleaned = true
    let proc = procID
    let aggregate = aggregateID
    let tap = tapID
    procID = nil
    aggregateID = AudioObjectID(kAudioObjectUnknown)
    tapID = AudioObjectID(kAudioObjectUnknown)
    cleanLock.unlock()

    if let proc, aggregate != AudioObjectID(kAudioObjectUnknown) {
        AudioDeviceStop(aggregate, proc)
        AudioDeviceDestroyIOProcID(aggregate, proc)
    }
    if aggregate != AudioObjectID(kAudioObjectUnknown) {
        AudioHardwareDestroyAggregateDevice(aggregate)
    }
    if tap != AudioObjectID(kAudioObjectUnknown) {
        AudioHardwareDestroyProcessTap(tap)
    }
}

private func fail(_ status: OSStatus, _ what: String) -> Never {
    fputs("\(what)失败：\(status)\(fourcc(status))\n", stderr)
    fputs("到系统设置 → 隐私与安全性，允许「屏幕与系统音频录制」或「系统音频录制」。允许之后重新运行。\n", stderr)
    fputs("这里只读取正在播放的音量，不录屏幕，也不改输出设备。\n", stderr)
    fflush(stderr)
    cleanup()
    exit(1)
}

private func consume(_ list: UnsafePointer<AudioBufferList>, format: AudioStreamBasicDescription) {
    let buffers = UnsafeMutableAudioBufferListPointer(UnsafeMutablePointer(mutating: list))
    let bytesPerSample = Int(format.mBitsPerChannel) / 8
    let isFloat = (format.mFormatFlags & kAudioFormatFlagIsFloat) != 0
    guard bytesPerSample > 0 else { return }
    for buffer in buffers {
        guard let data = buffer.mData else { continue }
        let sampleCount = Int(buffer.mDataByteSize) / bytesPerSample
        guard sampleCount > 0 else { continue }
        if isFloat && bytesPerSample == 4 {
            meter.add(data.assumingMemoryBound(to: Float.self), count: sampleCount)
            continue
        }
        if bytesPerSample == 2 {
            let samples = data.assumingMemoryBound(to: Int16.self)
            var floats = [Float](repeating: 0, count: sampleCount)
            for i in 0..<sampleCount {
                floats[i] = Float(samples[i]) / 32768
            }
            floats.withUnsafeBufferPointer { pointer in
                guard let base = pointer.baseAddress else { return }
                meter.add(base, count: sampleCount)
            }
        }
    }
}

private func inputFormat(of device: AudioDeviceID) -> AudioStreamBasicDescription? {
    var address = AudioObjectPropertyAddress(
        mSelector: kAudioDevicePropertyStreamFormat,
        mScope: kAudioObjectPropertyScopeInput,
        mElement: AudioObjectPropertyElement(kAudioObjectPropertyElementMain)
    )
    var format = AudioStreamBasicDescription()
    var size = UInt32(MemoryLayout<AudioStreamBasicDescription>.size)
    let status = AudioObjectGetPropertyData(device, &address, 0, nil, &size, &format)
    if status != noErr { return nil }
    return format
}

private func startTap() {
    let description = CATapDescription(stereoGlobalTapButExcludeProcesses: [])
    description.name = "adb-torch"
    description.muteBehavior = .unmuted
    description.isPrivate = true

    var createdTap = AudioObjectID(kAudioObjectUnknown)
    var status = AudioHardwareCreateProcessTap(description, &createdTap)
    if status != noErr { fail(status, "创建系统声音采集") }
    tapID = createdTap

    let aggregateUID = UUID().uuidString
    let tapUID = description.uuid.uuidString
    let aggregate: [String: Any] = [
        kAudioAggregateDeviceNameKey: "adb-torch",
        kAudioAggregateDeviceUIDKey: aggregateUID,
        kAudioAggregateDeviceIsPrivateKey: NSNumber(value: 1),
        kAudioAggregateDeviceIsStackedKey: NSNumber(value: 0),
        kAudioAggregateDeviceTapListKey: [
            [kAudioSubTapUIDKey: tapUID],
        ],
    ]
    var createdAggregate = AudioObjectID(kAudioObjectUnknown)
    status = AudioHardwareCreateAggregateDevice(aggregate as CFDictionary, &createdAggregate)
    if status != noErr { fail(status, "打开系统声音") }
    aggregateID = createdAggregate

    let format = inputFormat(of: createdAggregate) ?? AudioStreamBasicDescription(
        mSampleRate: 48000,
        mFormatID: kAudioFormatLinearPCM,
        mFormatFlags: kAudioFormatFlagIsFloat | kAudioFormatFlagIsPacked,
        mBytesPerPacket: 8,
        mFramesPerPacket: 1,
        mBytesPerFrame: 8,
        mChannelsPerFrame: 2,
        mBitsPerChannel: 32,
        mReserved: 0
    )

    var createdProc: AudioDeviceIOProcID?
    status = AudioDeviceCreateIOProcIDWithBlock(&createdProc, createdAggregate, nil) { _, input, _, output, _ in
        // 不把输出缓冲清掉的话，聚合设备会把残数据放出来，再被采集回去。
        let played = UnsafeMutableAudioBufferListPointer(output)
        for buffer in played {
            guard let data = buffer.mData, buffer.mDataByteSize > 0 else { continue }
            memset(data, 0, Int(buffer.mDataByteSize))
        }
        consume(input, format: format)
    }
    if status != noErr { fail(status, "读取系统声音") }
    procID = createdProc
    status = AudioDeviceStart(createdAggregate, createdProc)
    if status != noErr { fail(status, "开始系统声音") }
}

private func onExit() {
    cleanup()
}

setvbuf(stdout, nil, _IONBF, 0)
atexit(onExit)
signal(SIGINT, SIG_IGN)
signal(SIGTERM, SIG_IGN)

let sigint = DispatchSource.makeSignalSource(signal: SIGINT, queue: .main)
let sigterm = DispatchSource.makeSignalSource(signal: SIGTERM, queue: .main)
sigint.setEventHandler {
    cleanup()
    exit(0)
}
sigterm.setEventHandler {
    cleanup()
    exit(0)
}
sigint.resume()
sigterm.resume()

startTap()
fputs("audio-ready\n", stderr)
fflush(stderr)

let timer = DispatchSource.makeTimerSource(queue: DispatchQueue(label: "adb-torch-rms"))
timer.schedule(deadline: .now() + .milliseconds(30), repeating: .milliseconds(30))
timer.setEventHandler {
    let line = String(format: "%.6f\n", meter.rms())
    _ = line.withCString { fputs($0, stdout) }
}
timer.resume()
dispatchMain()
