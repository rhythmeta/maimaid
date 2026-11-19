import Foundation

/// Live counters of one mixer instance, read by `ChartAudioRenderer.debugSummary`.
nonisolated final class ChartMixerStats {
    var eventsDue: Int64 = 0
    var eventsMixed: Int64 = 0
    var eventsMuted: Int64 = 0
    var eventsWithoutSamples: Int64 = 0
    var maxFrameError: Int = 0
    var seeks: Int64 = 0
    var maxVoices: Int = 0
    var voiceEvictions: Int = 0
    var voiceHardStops: Int = 0
    var mixedFrames: Int64 = 0
    var starvedBuffers: Int64 = 0

    func reset() {
        eventsDue = 0
        eventsMixed = 0
        eventsMuted = 0
        eventsWithoutSamples = 0
        maxFrameError = 0
        seeks = 0
        maxVoices = 0
        voiceEvictions = 0
        voiceHardStops = 0
        mixedFrames = 0
        starvedBuffers = 0
    }
}

/// Pure, sample-accurate mixer for one music PCM stream plus the chart's SFX voices.
///
/// The mix timeline is the music PCM frame position. Events are addressed in frames (see
/// `ChartMixEvents`) and are started at their exact sample offset inside a mix buffer, so
/// high-density sections cannot bunch several hits into one UI frame.
///
/// Like the reference implementation the class is single-threaded: the engine drives it from the
/// audio render callback only, and publishes commands through its own mailbox, so no lock is needed
/// around the mixing hot path.
nonisolated final class ChartMixer: @unchecked Sendable {
    static let defaultMaxVoices = 32
    static let defaultFadeReserve = 8
    static let defaultMonoFadeMs = 15.0
    static let defaultEvictFadeMs = 8.0
    static let defaultMaxFramesPerBuffer = 2_048
    private static let softKnee: Float = 0.7

    let sampleRate: Int
    let maxFramesPerBuffer: Int

    private let bank: ChartSfxSampleProviding
    private let monoFadeFrames: Int
    private let voicePool: ChartVoicePool
    private var musicBuffer: [Int16]
    private var mixBuffer: [Float]
    private var layerVolumes: [Float] = [1, 1]

    private var musicSource: ChartPcmFrameSource?
    private var events: [ChartMixEvent] = []
    private var touchHoldEvents: [ChartMixEvent] = []
    private var eventCursor = 0

    private(set) var currentFrame: Int64 = 0

    var musicVolume: Float = 1
    var answerVolume: Float = 1
    var judgeVolume: Float = 1
    var sfxEnabled: Bool = true

    let stats = ChartMixerStats()

    init(
        sampleRate: Int,
        bank: ChartSfxSampleProviding,
        maxVoices: Int = ChartMixer.defaultMaxVoices,
        reserve: Int = ChartMixer.defaultFadeReserve,
        monoFadeMs: Double = ChartMixer.defaultMonoFadeMs,
        evictFadeMs: Double = ChartMixer.defaultEvictFadeMs,
        maxFramesPerBuffer: Int = ChartMixer.defaultMaxFramesPerBuffer
    ) {
        self.sampleRate = sampleRate
        self.bank = bank
        monoFadeFrames = max(1, Int(monoFadeMs * Double(sampleRate) / 1000.0))
        self.maxFramesPerBuffer = max(64, maxFramesPerBuffer)
        voicePool = ChartVoicePool(
            maxVoices: max(1, maxVoices),
            reserve: max(2, reserve),
            evictFadeFrames: max(1, Int(evictFadeMs * Double(sampleRate) / 1000.0))
        )
        let frames = maxFramesPerBuffer
        musicBuffer = [Int16](repeating: 0, count: frames * 2)
        mixBuffer = [Float](repeating: 0, count: frames * 2)
    }

    func setMusicSource(_ source: ChartPcmFrameSource?) {
        musicSource = source
    }

    /// Publishes a new event list (chart load, offset change). The cursor is re-anchored at the
    /// current frame so past events are not replayed; a touch-hold that covers the current frame is
    /// restored as a looping voice.
    func setEvents(_ newEvents: [ChartMixEvent]) {
        events = newEvents.sorted { $0.frame < $1.frame }
        touchHoldEvents = events.filter(\.hasTouchHoldLoop)
        reanchorCursor()
        restoreTouchHold()
    }

    func seekToFrame(_ frame: Int64) {
        currentFrame = max(0, frame)
        voicePool.stopAll()
        reanchorCursor()
        restoreTouchHold()
        stats.seeks += 1
    }

    /// Stops every voice (pause, mute transition, page leave).
    func stopAllVoices() {
        voicePool.stopAll()
    }

    /// Re-anchors after a pause: restores a touch-hold that covers the current frame.
    func restoreAtCurrentFrame() {
        restoreTouchHold()
    }

    /// Renders `frames` frames of interleaved stereo into `out` and advances the timeline.
    /// Returns the music frame of the start of the rendered buffer.
    @discardableResult
    func render(_ out: inout [Int16], frames: Int) -> Int64 {
        precondition(frames >= 1 && frames <= maxFramesPerBuffer, "frames=\(frames) outside 1...\(maxFramesPerBuffer)")
        precondition(out.count >= frames * 2, "output buffer too small")
        let bufferStart = currentFrame

        for index in 0..<(frames * 2) { mixBuffer[index] = 0 }

        voicePool.updateLoops(bufferStartFrame: bufferStart, fadeFrames: monoFadeFrames)
        triggerEvents(bufferStart: bufferStart, frames: frames)

        layerVolumes[0] = answerVolume
        layerVolumes[1] = judgeVolume
        // Standing gain compensation: N simultaneous voices would otherwise grow the sum with N.
        let baseGain = 1 / (Float(max(voicePool.audibleVoices, 1)).squareRoot())
        voicePool.render(
            into: &mixBuffer,
            frames: frames,
            bufferStartFrame: bufferStart,
            layerVolumes: layerVolumes,
            voiceBaseGain: baseGain
        )

        addMusic(bufferStart: bufferStart, frames: frames)

        writeOutput(&out, frames: frames)
        currentFrame += Int64(frames)
        stats.mixedFrames += Int64(frames)
        if voicePool.audibleVoices > stats.maxVoices { stats.maxVoices = voicePool.audibleVoices }
        stats.voiceEvictions = voicePool.evictions
        stats.voiceHardStops = voicePool.hardStops
        return bufferStart
    }

    func release() {
        voicePool.stopAll()
        musicSource?.close()
        musicSource = nil
    }

    // MARK: - Events

    private func triggerEvents(bufferStart: Int64, frames: Int) {
        let bufferEnd = bufferStart + Int64(frames)
        while eventCursor < events.count && events[eventCursor].frame < bufferEnd {
            let event = events[eventCursor]
            eventCursor += 1
            stats.eventsDue += 1
            if !sfxEnabled {
                stats.eventsMuted += 1
                continue
            }
            let offset = Int(min(max(event.frame - bufferStart, 0), Int64(frames - 1)))
            let error = (bufferStart + Int64(offset)) - event.frame
            if abs(error) > abs(Int64(stats.maxFrameError)) { stats.maxFrameError = Int(error) }
            var started = false
            if event.playsAnswer {
                if let sample = bank.sample(.answer) {
                    started = voicePool.start(
                        sample: sample,
                        layerIndex: 0,
                        monoGroupIndex: -1,
                        frame: event.frame,
                        offsetInBuffer: offset,
                        isLooping: false,
                        loopEnd: -1,
                        monoFadeFrames: monoFadeFrames
                    ) || started
                } else {
                    stats.eventsWithoutSamples += 1
                }
            }
            for kindOrdinal in event.judgeKinds {
                guard let kind = ChartSfxKind.kind(forOrdinal: kindOrdinal) else { continue }
                guard let sample = bank.sample(kind) else {
                    stats.eventsWithoutSamples += 1
                    continue
                }
                let looping = kind == .touchHold && event.hasTouchHoldLoop
                started = voicePool.start(
                    sample: sample,
                    layerIndex: 1,
                    monoGroupIndex: kind.isMono ? kindOrdinal : -1,
                    frame: event.frame,
                    offsetInBuffer: offset,
                    isLooping: looping,
                    loopEnd: looping ? event.touchHoldEndFrame : -1,
                    monoFadeFrames: monoFadeFrames
                ) || started
            }
            if started { stats.eventsMixed += 1 }
        }
    }

    private func reanchorCursor() {
        eventCursor = lowerBound(frame: currentFrame)
    }

    private func restoreTouchHold() {
        var hold: ChartMixEvent?
        for event in touchHoldEvents {
            if event.frame > currentFrame { break }
            if currentFrame < event.touchHoldEndFrame { hold = event }
        }
        guard let event = hold, let sample = bank.sample(.touchHold) else { return }
        _ = voicePool.start(
            sample: sample,
            layerIndex: 1,
            monoGroupIndex: ChartSfxKind.touchHold.ordinal,
            frame: currentFrame,
            offsetInBuffer: 0,
            isLooping: true,
            loopEnd: event.touchHoldEndFrame,
            monoFadeFrames: monoFadeFrames
        )
    }

    private func lowerBound(frame: Int64) -> Int {
        var low = 0
        var high = events.count
        while low < high {
            let mid = (low + high) / 2
            if events[mid].frame < frame { low = mid + 1 } else { high = mid }
        }
        return low
    }

    // MARK: - Music and output

    private func addMusic(bufferStart: Int64, frames: Int) {
        guard let source = musicSource else { return }
        let read = source.readFrames(startFrame: bufferStart, into: &musicBuffer, outOffset: 0, frames: frames)
        if read <= 0 {
            // The decoder had not caught up yet (streaming decode); count it so a silent gap is
            // detectable instead of being invisible.
            if !source.isComplete { stats.starvedBuffers += 1 }
            return
        }
        let volume = musicVolume
        for index in 0..<(read * 2) {
            mixBuffer[index] += Float(musicBuffer[index]) / 32_768 * volume
        }
    }

    private func writeOutput(_ out: inout [Int16], frames: Int) {
        for index in 0..<(frames * 2) {
            let limited = softLimit(mixBuffer[index])
            out[index] = Int16(clamping: Int(limited * 32_767))
        }
    }

    /// Soft knee limiter: transparent below 0.7, then bends smoothly towards 1.0 so summing many
    /// voices can never clip past full scale.
    private func softLimit(_ value: Float) -> Float {
        let magnitude = abs(value)
        if magnitude <= Self.softKnee { return value }
        let limited = Self.softKnee + (1 - Self.softKnee)
            * tanh((magnitude - Self.softKnee) / (1 - Self.softKnee))
        return value < 0 ? -limited : limited
    }
}
