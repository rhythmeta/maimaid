import AVFoundation
import Foundation
import os

/// PCM samples of the SFX set at the mixer's sample rate.
nonisolated protocol ChartSfxSampleProviding: AnyObject {
    var sampleRate: Int { get }

    /// Mono samples for `kind`, or nil when the asset is missing.
    func sample(_ kind: ChartSfxKind) -> [Int16]?
}

/// Read-only random access to the music PCM cache. Frames are interleaved stereo 16-bit, addressed
/// by music frame so seeking never has to rewind a decoder by hand.
nonisolated protocol ChartPcmFrameSource: AnyObject {
    var frameCount: Int64 { get }

    /// False while a background decoder may still deliver frames (used for starvation metrics).
    var isComplete: Bool { get }

    /// Reads up to `frames` frames starting at `startFrame`; returns the frames actually written.
    func readFrames(startFrame: Int64, into out: inout [Int16], outOffset: Int, frames: Int) -> Int

    func close()
}

nonisolated extension ChartPcmFrameSource {
    var isComplete: Bool { true }

    func close() {}
}

/// Thread-safe, append-only store for interleaved stereo 16-bit samples.
///
/// The decode task appends while the audio thread reads, so every access is guarded by one lock; both
/// operations are pure array copies, which keeps the audio thread's critical section far below one
/// output buffer. The buffer is append-only (and therefore unbounded for a whole track) because a
/// fixed ring would force decoder backpressure and would make "seek anywhere" impossible without a
/// full decoder restart.
nonisolated final class ChartPcmChunkBuffer: @unchecked Sendable {
    static let defaultChunkSamples = 1 << 15

    private struct Storage: Sendable {
        var chunks: [[Int16]] = []
        var current: [Int16]
        var used = 0
        var totalSamples: Int64 = 0
        var baseSample: Int64 = 0
    }

    private let chunkSamples: Int
    private let lock: OSAllocatedUnfairLock<Storage>

    init(chunkSamples: Int = ChartPcmChunkBuffer.defaultChunkSamples) {
        let size = max(1, chunkSamples)
        self.chunkSamples = size
        lock = OSAllocatedUnfairLock(initialState: Storage(current: [Int16](repeating: 0, count: size)))
    }

    /// Samples currently available (interleaved, so two per frame).
    var availableSamples: Int64 { lock.withLockUnchecked { $0.totalSamples } }

    var availableFrames: Int64 { availableSamples / 2 }

    /// First sample kept in the buffer; non-zero after a decoder restart at a seek target.
    var base: Int64 { lock.withLockUnchecked { $0.baseSample } }

    func append(_ source: [Int16], offset: Int, count: Int) {
        guard count > 0, offset >= 0, offset + count <= source.count else { return }
        lock.withLockUnchecked { storage in
            var read = offset
            var remaining = count
            while remaining > 0 {
                if storage.used == storage.current.count {
                    storage.chunks.append(storage.current)
                    storage.current = [Int16](repeating: 0, count: chunkSamples)
                    storage.used = 0
                }
                let copy = min(remaining, storage.current.count - storage.used)
                let destination = storage.used
                for index in 0..<copy {
                    storage.current[destination + index] = source[read + index]
                }
                storage.used += copy
                storage.totalSamples += Int64(copy)
                read += copy
                remaining -= copy
            }
        }
    }

    func readFrames(startFrame: Int64, into out: inout [Int16], outOffset: Int, frames: Int) -> Int {
        guard frames > 0, outOffset >= 0 else { return 0 }
        return lock.withLockUnchecked { storage -> Int in
            let chunk = Int64(chunkSamples)
            let startSample = startFrame * 2 - storage.baseSample
            guard startSample >= 0, startSample < storage.totalSamples else { return 0 }
            let available = Int(min(Int64(frames) * 2, storage.totalSamples - startSample))
            guard available > 0, out.count >= outOffset + available else { return 0 }
            var sample = startSample
            var written = 0
            var remaining = available
            while remaining > 0 {
                let chunkIndex = Int(sample / chunk)
                let withinChunk = Int(sample % chunk)
                let isSealed = chunkIndex < storage.chunks.count
                let sourceChunk = isSealed ? storage.chunks[chunkIndex] : storage.current
                let chunkAvailable = isSealed ? sourceChunk.count : storage.used
                if withinChunk >= chunkAvailable { break }
                let copy = min(remaining, chunkAvailable - withinChunk)
                for index in 0..<copy {
                    out[outOffset + written + index] = sourceChunk[withinChunk + index]
                }
                sample += Int64(copy)
                written += copy
                remaining -= copy
            }
            return available / 2
        }
    }

    func clear() {
        lock.withLockUnchecked { storage in
            storage.chunks.removeAll(keepingCapacity: false)
            storage.current = [Int16](repeating: 0, count: chunkSamples)
            storage.used = 0
            storage.totalSamples = 0
            storage.baseSample = 0
        }
    }

    /// Drops everything and makes `startFrame` the first frame this buffer can serve.
    func resetAt(startFrame: Int64) {
        lock.withLockUnchecked { storage in
            storage.chunks.removeAll(keepingCapacity: false)
            storage.current = [Int16](repeating: 0, count: chunkSamples)
            storage.used = 0
            storage.totalSamples = 0
            storage.baseSample = startFrame * 2
        }
    }
}

/// In-memory source used by tests and as a fallback for very small tracks.
nonisolated final class ChartMemoryPcmSource: ChartPcmFrameSource, Sendable {
    private let samples: [Int16]
    let frameCount: Int64

    init(_ samples: [Int16]) {
        self.samples = samples
        frameCount = Int64(samples.count / 2)
    }

    func readFrames(startFrame: Int64, into out: inout [Int16], outOffset: Int, frames: Int) -> Int {
        guard frames > 0, startFrame >= 0, startFrame < frameCount else { return 0 }
        let available = Int(min(Int64(frames), frameCount - startFrame))
        guard out.count >= outOffset + available * 2 else { return 0 }
        let sourceIndex = Int(startFrame * 2)
        for index in 0..<(available * 2) {
            out[outOffset + index] = samples[sourceIndex + index]
        }
        return available
    }
}

/// Decodes a chart track into memory on a background task and exposes the decoded part as a
/// `ChartPcmFrameSource`.
///
/// Decoding a whole track with `AVAudioFile` is fast, but blocking the preview on it would still
/// delay playback on a long song. Instead the decode runs concurrently with playback: `awaitReady`
/// returns as soon as a short lead is decoded, and the mixer simply reads whatever is available
/// afterwards. `AVAudioFile` supports random access, so a seek past the decoded frontier restarts the
/// decoder at the target frame instead of leaving the music silent until it catches up.
nonisolated final class ChartPcmStream: ChartPcmFrameSource, @unchecked Sendable {
    /// Lead time decoded before playback is allowed to start.
    static let prerollMs: Int64 = 400
    private static let formatTimeout: Duration = .milliseconds(3_000)
    private static let decodeChunkFrames: AVAudioFrameCount = 16_384

    private struct State: Sendable {
        var sampleRate = 0
        var durationMs = 0.0
        var declaredFrameCount: Int64 = 0
        var isComplete = false
        var isClosed = false
        var failure: String?
        var pendingSeekFrame: Int64 = -1
        var started = false
        var decodeTask: Task<Void, Never>?
    }

    private let url: URL
    private let buffer = ChartPcmChunkBuffer()
    private let lock = OSAllocatedUnfairLock(initialState: State())

    init(url: URL) {
        self.url = url
    }

    var sampleRate: Int { lock.withLockUnchecked { $0.sampleRate } }

    var durationMs: Double { lock.withLockUnchecked { $0.durationMs } }

    var failureDescription: String? { lock.withLockUnchecked { $0.failure } }

    var frameCount: Int64 {
        let declared = lock.withLockUnchecked { $0.declaredFrameCount }
        if declared > 0 { return declared }
        return buffer.base / 2 + buffer.availableFrames
    }

    var isComplete: Bool { lock.withLockUnchecked { $0.isComplete } }

    /// First frame the buffer can still serve (moves forward after a decoder restart).
    var baseFrame: Int64 { buffer.base / 2 }

    /// One past the last decodable frame currently in memory.
    var decodedFrames: Int64 { baseFrame + buffer.availableFrames }

    func readFrames(startFrame: Int64, into out: inout [Int16], outOffset: Int, frames: Int) -> Int {
        buffer.readFrames(startFrame: startFrame, into: &out, outOffset: outOffset, frames: frames)
    }

    /// Starts the background decode. Idempotent.
    func start() {
        var shouldStart = false
        var alreadyClosed = false
        lock.withLockUnchecked { state in
            if state.isClosed {
                alreadyClosed = true
            } else if !state.started {
                state.started = true
                shouldStart = true
            }
        }
        guard shouldStart, !alreadyClosed else { return }
        let task = Task.detached(priority: .userInitiated) { [self] in
            await decodeLoop()
        }
        lock.withLockUnchecked { $0.decodeTask = task }
    }

    /// Suspends until the format is known and a short lead is decoded, or the track failed.
    func awaitReady() async -> Bool {
        let deadline = ContinuousClock.now + Self.formatTimeout
        while true {
            let snapshot = lock.withLockUnchecked { $0 }
            if snapshot.failure != nil || snapshot.isClosed { return false }
            let prerollFrames = Int64(snapshot.sampleRate) * Self.prerollMs / 1000
            if snapshot.sampleRate > 0, buffer.availableFrames >= prerollFrames { return true }
            if snapshot.isComplete { return true }
            if ContinuousClock.now >= deadline {
                return snapshot.sampleRate > 0 && buffer.availableFrames > 0
            }
            try? await Task.sleep(for: .milliseconds(5))
        }
    }

    /// Asks the decoder to jump to `frame`. Used when a seek lands outside the decoded range, where
    /// the sequential decoder would otherwise leave the music silent until it caught up.
    func requestSeek(frame: Int64) {
        lock.withLockUnchecked { state in
            guard !state.isComplete, !state.isClosed else { return }
            state.pendingSeekFrame = max(0, frame)
        }
    }

    func close() {
        let task = lock.withLockUnchecked { state -> Task<Void, Never>? in
            state.isClosed = true
            let task = state.decodeTask
            state.decodeTask = nil
            return task
        }
        task?.cancel()
        buffer.clear()
    }

    // MARK: - Decoding

    private func decodeLoop() async {
        do {
            let file = try AVAudioFile(forReading: url)
            let format = file.processingFormat
            let rate = Int(format.sampleRate.rounded())
            guard rate > 0 else {
                throw ChartSfxSampleError(message: "Invalid sample rate for \(url.lastPathComponent)")
            }
            let length = file.length
            lock.withLockUnchecked { state in
                state.sampleRate = rate
                state.declaredFrameCount = length
                state.durationMs = length > 0 ? Double(length) * 1000.0 / Double(rate) : 0
            }
            guard let pcm = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: Self.decodeChunkFrames) else {
                throw ChartSfxSampleError(message: "Unable to allocate a decode buffer")
            }
            let channels = Int(format.channelCount)
            while true {
                if lock.withLockUnchecked({ $0.isClosed }) { break }
                if let target = takePendingSeek() {
                    file.framePosition = min(max(0, target), file.length)
                    buffer.resetAt(startFrame: file.framePosition)
                }
                pcm.frameLength = 0
                try file.read(into: pcm, frameCount: Self.decodeChunkFrames)
                if pcm.frameLength == 0 { break }
                append(pcm, channels: channels)
                // Keep the cooperative pool responsive while decoding several megabytes.
                await Task.yield()
            }
            lock.withLockUnchecked { $0.isComplete = true }
        } catch {
            lock.withLockUnchecked { state in
                if !state.isClosed { state.failure = error.localizedDescription }
            }
        }
    }

    private func takePendingSeek() -> Int64? {
        lock.withLockUnchecked { state in
            let frame = state.pendingSeekFrame
            state.pendingSeekFrame = -1
            return frame >= 0 ? frame : nil
        }
    }

    /// Converts one decoder buffer into interleaved stereo and appends it. Mirrors the reference
    /// decoder: mono is duplicated to both channels, stereo is copied, anything wider is averaged.
    private func append(_ source: AVAudioPCMBuffer, channels: Int) {
        guard let channelData = source.floatChannelData else { return }
        let frames = Int(source.frameLength)
        guard frames > 0, channels > 0 else { return }
        var stereo = [Int16](repeating: 0, count: frames * 2)
        let left = channelData[0]
        if channels == 1 {
            for frame in 0..<frames {
                let value = Self.int16Sample(left[frame])
                stereo[frame * 2] = value
                stereo[frame * 2 + 1] = value
            }
        } else if channels == 2 {
            let right = channelData[1]
            for frame in 0..<frames {
                stereo[frame * 2] = Self.int16Sample(left[frame])
                stereo[frame * 2 + 1] = Self.int16Sample(right[frame])
            }
        } else {
            for frame in 0..<frames {
                var sum: Float = 0
                for channel in 0..<channels {
                    sum += channelData[channel][frame]
                }
                let value = Self.int16Sample(sum / Float(channels))
                stereo[frame * 2] = value
                stereo[frame * 2 + 1] = value
            }
        }
        buffer.append(stereo, offset: 0, count: stereo.count)
    }

    @inline(__always)
    private static func int16Sample(_ value: Float) -> Int16 {
        let scaled = (value * 32_767.0).rounded()
        guard scaled.isFinite else { return 0 }
        return Int16(clamping: Int(max(-32_768.0, min(32_767.0, scaled))))
    }
}
