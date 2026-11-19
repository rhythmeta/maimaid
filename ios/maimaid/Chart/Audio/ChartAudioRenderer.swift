import AVFoundation
import Foundation
import os

// MARK: - Host time

/// Converts the audio graph's render timestamps into the monotonic nanosecond clock the chart clock
/// runs on. `AudioTimeStamp.mHostTime` is `mach_absolute_time` ticks, and `CLOCK_UPTIME_RAW` is the
/// same time base expressed in nanoseconds, so both can be mixed without an extra epoch.
nonisolated enum ChartAudioHostTime {
    static func nanos(from timestamp: AudioTimeStamp) -> Int64 {
        guard timestamp.mFlags.contains(.hostTimeValid) else { return nowNanos() }
        var info = mach_timebase_info_data_t()
        guard mach_timebase_info(&info) == KERN_SUCCESS, info.denom != 0 else { return nowNanos() }
        let ticks = timestamp.mHostTime
        return Int64(ticks * UInt64(info.numer) / UInt64(info.denom))
    }

    static func nowNanos() -> Int64 {
        Int64(clock_gettime_nsec_np(CLOCK_UPTIME_RAW))
    }
}

// MARK: - Commands and control state

/// Everything that has to reach the mixer: only the audio render callback may mutate the mixer, so
/// the UI thread publishes commands instead of touching it.
nonisolated enum ChartAudioCommand: Sendable {
    case play
    case pause
    case seek(frame: Int64, playAfterSeek: Bool, token: UInt64)
    case setEvents([ChartMixEvent])
    case stopVoices
    case restoreLoop
}

/// Lock-guarded control block shared by the UI thread (writer) and the audio render callback
/// (reader). Values the mixer needs on every buffer live here so the callback takes the lock exactly
/// once per buffer, and the critical sections are field copies only — never blocking work.
nonisolated final class ChartAudioControl: @unchecked Sendable {
    struct Snapshot: Sendable {
        var musicVolume: Float = 1
        var answerVolume: Float = 1
        var judgeVolume: Float = 1
        var sfxEnabled = true
        var playbackSpeed: Float = 1
        var mixPlaying = false

        /// Chart clock, seeded by the render callback. Optional because the sample rate is only known
        /// once the track format has been decoded.
        var clock: ChartAudioClock?

        /// Chart position published the moment a seek is requested, so the UI never shows the old
        /// position while the render thread catches up (the reference engine does the same).
        var requestedSeekPositionMs: Double = 0
        var requestedSeekToken: UInt64 = 0
        var appliedSeekToken: UInt64 = 0
        var seekGeneration: UInt64 = 0

        var commands: [ChartAudioCommand] = []
    }

    private let lock = OSAllocatedUnfairLock(initialState: Snapshot())

    var snapshot: Snapshot { lock.withLockUnchecked { $0 } }

    func mutate<T>(_ body: (inout Snapshot) -> T) -> T { lock.withLockUnchecked(body) }

    /// Copies the control values and clears the command mailbox in one critical section. The drained
    /// array keeps its storage, so the callback never has to allocate for the hand-off itself.
    func beginBuffer() -> Snapshot {
        lock.withLockUnchecked { state in
            let drained = state
            if !state.commands.isEmpty { state.commands = [] }
            return drained
        }
    }

    /// Releases the clock and the mailbox but keeps the user's mix settings (volume, speed, mute).
    func resetTransport() {
        lock.withLockUnchecked { state in
            state.commands = []
            state.clock = nil
            state.mixPlaying = false
            state.requestedSeekPositionMs = 0
            state.requestedSeekToken = 0
            state.appliedSeekToken = 0
            state.seekGeneration = 0
        }
    }
}

// MARK: - Render-thread scratch

/// Mutable state owned exclusively by the audio render callback.
nonisolated final class ChartRenderThreadState: @unchecked Sendable {
    var timestampCounter = 0
    /// Set for the first rendered buffer after play/seek so the clock is seeded immediately instead of
    /// one poll interval into playback (which would leave the chart at 0 while the music already runs).
    var needsClockSeed = true
    var mixScratch: [Float]

    init(maxOutputFrames: Int) {
        mixScratch = [Float](repeating: 0, count: max(1, maxOutputFrames) * 2)
    }
}

// MARK: - Graph

/// One prepared audio graph. Everything except `engine`/`sourceNode` (touched from the UI thread while
/// the graph is being created or torn down) is driven exclusively by the render callback.
nonisolated private final class ChartAudioGraph: @unchecked Sendable {
    let engine: AVAudioEngine
    let sourceNode: AVAudioSourceNode
    let mixer: ChartMixer
    let stream: ChartPcmStream
    let resampler: ChartPlaybackResampler
    let bank: ChartSfxSampleBank
    let sampleRate: Int

    init(
        engine: AVAudioEngine,
        sourceNode: AVAudioSourceNode,
        mixer: ChartMixer,
        stream: ChartPcmStream,
        resampler: ChartPlaybackResampler,
        bank: ChartSfxSampleBank,
        sampleRate: Int
    ) {
        self.engine = engine
        self.sourceNode = sourceNode
        self.mixer = mixer
        self.stream = stream
        self.resampler = resampler
        self.bank = bank
        self.sampleRate = sampleRate
    }

    var isRunning: Bool { engine.isRunning }

    func start() throws {
        guard !engine.isRunning else { return }
        try engine.start()
    }

    func pause() {
        if engine.isRunning { engine.pause() }
    }

    func teardown() {
        if engine.isRunning { engine.pause() }
        engine.stop()
        engine.disconnectNodeOutput(sourceNode)
        engine.detach(sourceNode)
        mixer.release()
        stream.close()
    }
}

// MARK: - Renderer

/// PCM music + SFX renderer for the chart preview.
///
/// The whole mix is produced by `ChartMixer` inside one `AVAudioSourceNode` render callback: the
/// decoded music PCM and every SFX voice share the same output latency, and an SFX event starts on
/// the exact sample frame of its event. The chart clock is extrapolated from the render timestamps
/// the graph reports, which makes the visible chart follow the audible mix instead of a coarsely
/// reported player position.
///
/// Threading: the UI thread only publishes commands (`play`/`pause`/`seek`/settings) through
/// `ChartAudioControl` and reads `chartPositionMs`; it never touches the mixer. The render callback
/// drains that mailbox and is the single writer of all mixer, resampler and clock state.
nonisolated final class ChartAudioRenderer: @unchecked Sendable {
    private static let fallbackSampleRate = 48_000
    private static let outputChunkFrames = 1_024
    private static let timestampPollBuffers = 4

    /// Main-thread state. Guarded because `prepare` is asynchronous and may resume off the main
    /// actor while the UI keeps calling `play`/`pause`/`seek`.
    ///
    /// `@unchecked Sendable` is deliberate: `ChartAudioEvent` is the reference engine's mutable event
    /// class, and every access to this state happens inside `stateLock`.
    private struct RendererState: @unchecked Sendable {
        var graph: ChartAudioGraph?
        var isReady = false
        var preparedKey: String?
        var generation: UInt64 = 0
        var trackDurationMs: Double = 0

        /// Last published configuration, kept even when no mixer exists yet (chart loaded before the
        /// track finished preparing, or a settings change while preparing).
        var rawEvents: [ChartAudioEvent] = []
        var leadInMs: Double = 0
        var chartDurationMs: Double = 0
        var musicOffsetMs = 0
        var sfxOffsetMs = 0

        /// Play/pause/seek requests made before the track was ready, honoured on the first ready mixer.
        var desiredPlaying = false
        var pendingSeek: (chartTimeMs: Double, playAfterSeek: Bool)?
    }

    private struct SessionState {
        var isActivated = false
        var category: AVAudioSession.Category = .soloAmbient
        var mode: AVAudioSession.Mode = .default
        var options: AVAudioSession.CategoryOptions = []
    }

    private let control = ChartAudioControl()
    private let stateLock = OSAllocatedUnfairLock(initialState: RendererState())
    private let sessionLock = NSLock()
    private var session = SessionState()
    private var lastErrorDescription: String?

    // MARK: - Lifecycle

    /// Prepares a previously downloaded MP3 and starts the mix once its first frames are decoded.
    /// Returns false when the track cannot be played at all; the chart then runs silently.
    func prepare(fileURL: URL, key: String) async -> Bool {
        let current = stateLock.withLockUnchecked { $0 }
        if current.isReady, current.preparedKey == key, current.graph != nil { return true }

        let stale = stateLock.withLockUnchecked { state -> ChartAudioGraph? in
            let previous = state.graph
            state.graph = nil
            state.isReady = false
            state.preparedKey = nil
            state.trackDurationMs = 0
            state.generation &+= 1
            return previous
        }
        // A different track replaces the current one: tear the old graph down first so it cannot keep
        // playing (or leak) while the new track is being decoded.
        stale?.teardown()
        control.resetTransport()
        let generation = stateLock.withLockUnchecked { $0.generation }

        let stream = ChartPcmStream(url: fileURL)
        stream.start()
        let ready = await stream.awaitReady()
        guard ready, stream.sampleRate > 0 else {
            stream.close()
            lastErrorDescription = stream.failureDescription ?? "Unable to decode the chart track"
            return false
        }
        guard stateLock.withLockUnchecked({ $0.generation }) == generation else {
            stream.close()
            return false
        }
        let sampleRate = Self.validSampleRate(stream.sampleRate)
        let bank = await Task.detached(priority: .userInitiated) {
            ChartSfxSampleBank.load(sampleRate: sampleRate, bundle: .main)
        }.value
        guard stateLock.withLockUnchecked({ $0.generation }) == generation else {
            stream.close()
            return false
        }
        guard let graph = makeGraph(stream: stream, bank: bank, sampleRate: sampleRate) else {
            stream.close()
            lastErrorDescription = "Unable to start the audio graph for the chart track"
            return false
        }
        let duration = stream.durationMs > 0 ? stream.durationMs : 0
        let committed = stateLock.withLockUnchecked { state -> Bool in
            guard state.generation == generation else { return false }
            state.graph = graph
            state.isReady = true
            state.preparedKey = key
            state.trackDurationMs = duration
            return true
        }
        guard committed else {
            // A newer preparation (or a release) won the race: drop this graph instead of leaking it.
            graph.teardown()
            return false
        }
        let initialSpeed = control.snapshot.playbackSpeed
        control.mutate { state in
            var clock = ChartAudioClock(sampleRate: sampleRate)
            // The speed may have been set while the track was still decoding; the clock has to start
            // at that rate or the chart would run at 1x against a faster music stream.
            clock.setSpeed(initialSpeed, nanoTime: ChartAudioHostTime.nowNanos())
            state.clock = clock
            // `play()` may already have started the graph (it is latched before the track is ready);
            // seed the clock as playing so the first timestamp anchors a running clock.
            if state.mixPlaying {
                state.clock?.setPlaying(true, nanoTime: ChartAudioHostTime.nowNanos())
            }
        }
        publishEvents()
        flushLatchedRequests()
        lastErrorDescription = nil
        return true
    }

    /// Publishes the frame-addressed event timeline to the mixer. Safe before the track is ready; the
    /// configuration is kept for the moment the mixer appears, and re-published on every change.
    func configure(events: [ChartAudioEvent], leadInMs: Double, durationMs: Double,
                   musicOffsetMs: Int, sfxOffsetMs: Int) {
        stateLock.withLockUnchecked { state in
            state.rawEvents = events
            state.leadInMs = leadInMs
            state.chartDurationMs = durationMs
            state.musicOffsetMs = musicOffsetMs
            state.sfxOffsetMs = sfxOffsetMs
        }
        publishEvents()
    }

    /// Tears the audio graph down; called when the preview page goes away.
    func release() {
        let graph = stateLock.withLockUnchecked { state -> ChartAudioGraph? in
            let stale = state.graph
            state.graph = nil
            state.isReady = false
            state.preparedKey = nil
            state.trackDurationMs = 0
            state.desiredPlaying = false
            state.pendingSeek = nil
            state.generation &+= 1
            return stale
        }
        graph?.teardown()
        control.resetTransport()
        deactivateSession()
    }

    // MARK: - Transport

    func play() {
        let snapshot = stateLock.withLockUnchecked { state in
            state.desiredPlaying = true
            return (state.graph, state.isReady)
        }
        guard let graph = snapshot.0, snapshot.1 else { return }
        activateSessionIfNeeded()
        control.mutate { $0.commands.append(.play) }
        do {
            try graph.start()
            lastErrorDescription = nil
        } catch {
            lastErrorDescription = "Unable to start audio playback: \(error.localizedDescription)"
        }
    }

    func pause() {
        let graph = stateLock.withLockUnchecked { state -> ChartAudioGraph? in
            state.desiredPlaying = false
            return state.graph
        }
        control.mutate { state in
            state.mixPlaying = false
            state.clock?.setPlaying(false, nanoTime: ChartAudioHostTime.nowNanos())
        }
        guard let graph else { return }
        // Stopping the engine stops the render callback, so the clock is frozen here and the queued
        // commands are applied by the first callback after the next start. Nothing is audible while
        // paused, so deferring them is equivalent to the reference engine's idle mix thread.
        graph.pause()
        control.mutate { $0.commands.append(.pause) }
    }

    func stopSfx() {
        control.mutate { $0.commands.append(.stopVoices) }
    }

    /// Seeks to a chart position; the frame mapping goes through `AudioTimeline` so the music offset is
    /// applied exactly once.
    func seek(toChartTimeMs chartTimeMs: Double, playAfterSeek: Bool) {
        let configuration = stateLock.withLockUnchecked { state in
            (state.graph, state.isReady, state.leadInMs, state.musicOffsetMs, state.chartDurationMs)
        }
        guard let graph = configuration.0, configuration.1 else {
            stateLock.withLockUnchecked { state in
                state.pendingSeek = (chartTimeMs, playAfterSeek)
                state.desiredPlaying = playAfterSeek
            }
            return
        }
        let targetFrame = max(0, AudioTimeline.frame(
            forMusicPositionMs: AudioTimeline.musicPositionMs(
                chartTimeMs: chartTimeMs,
                leadInMs: configuration.2,
                musicOffsetMs: configuration.3
            ),
            sampleRate: graph.sampleRate
        ))
        let limit = configuration.4 > 0 ? configuration.4 : Double.greatestFiniteMagnitude
        control.mutate { state in
            state.seekGeneration &+= 1
            state.requestedSeekPositionMs = min(max(0, chartTimeMs), limit)
            state.requestedSeekToken = state.seekGeneration
            state.commands.append(
                .seek(frame: targetFrame, playAfterSeek: playAfterSeek, token: state.seekGeneration)
            )
        }
        if playAfterSeek {
            stateLock.withLockUnchecked { $0.desiredPlaying = true }
            activateSessionIfNeeded()
            do {
                try graph.start()
            } catch {
                lastErrorDescription = "Unable to start audio playback: \(error.localizedDescription)"
            }
        }
    }

    // MARK: - Settings

    func setPlaybackSpeed(_ speed: Float) {
        let clamped = min(2, max(0.1, speed))
        control.mutate { $0.playbackSpeed = clamped }
    }

    func setMusicVolume(_ volume: Float) {
        let clamped = min(1, max(0, volume))
        control.mutate { $0.musicVolume = clamped }
    }

    func setAnswerVolume(_ volume: Float) {
        let clamped = min(1, max(0, volume))
        control.mutate { $0.answerVolume = clamped }
    }

    func setJudgeVolume(_ volume: Float) {
        let clamped = min(1, max(0, volume))
        control.mutate { $0.judgeVolume = clamped }
    }

    func setSfxEnabled(_ enabled: Bool) {
        control.mutate { $0.sfxEnabled = enabled }
    }

    /// Rebuilds the event frames while keeping the current chart position.
    func setSfxOffsetMs(_ offsetMs: Int) {
        let changed = stateLock.withLockUnchecked { state -> Bool in
            guard state.sfxOffsetMs != offsetMs else { return false }
            state.sfxOffsetMs = offsetMs
            return true
        }
        if changed { publishEvents() }
    }

    /// Changes the music offset and re-seeks so the chart position does not jump. The mapping and its
    /// inverse live in `AudioTimeline`, so a non-zero offset never doubles.
    func setMusicOffsetMs(_ offsetMs: Int) {
        let currentOffset = stateLock.withLockUnchecked { $0.musicOffsetMs }
        guard currentOffset != offsetMs else { return }
        // Capture the audible position with the *old* mapping before the offset changes; reading it
        // afterwards would mix the two timelines.
        let position = chartPositionMs
        let wasPlaying = control.snapshot.mixPlaying
        stateLock.withLockUnchecked { $0.musicOffsetMs = offsetMs }
        seek(toChartTimeMs: position, playAfterSeek: wasPlaying)
        publishEvents()
    }

    // MARK: - Introspection

    var durationMs: Double {
        stateLock.withLockUnchecked { $0.trackDurationMs }
    }

    /// Audible chart position in milliseconds, extrapolated from the last render timestamp.
    var chartPositionMs: Double {
        let state = stateLock.withLockUnchecked { $0 }
        guard state.isReady else { return 0 }
        let live = control.snapshot
        if live.requestedSeekToken > live.appliedSeekToken { return live.requestedSeekPositionMs }
        guard let clock = live.clock else { return 0 }
        let frame = Int64(max(0, clock.positionFrameAt(ChartAudioHostTime.nowNanos()).rounded(.down)))
        let chart = AudioTimeline.chartTimeForFrame(
            frame,
            leadInMs: state.leadInMs,
            musicOffsetMs: state.musicOffsetMs,
            sampleRate: clock.sampleRate
        )
        let limit = state.chartDurationMs > 0 ? state.chartDurationMs : Double.greatestFiniteMagnitude
        return min(max(0, chart), limit)
    }

    /// One-line diagnostics for debug logging.
    var debugSummary: String {
        let state = stateLock.withLockUnchecked { $0 }
        guard let graph = state.graph else {
            let error = lastErrorDescription.map { " error=\($0)" } ?? ""
            return "pcm=not-ready\(error)"
        }
        let stats = graph.mixer.stats
        let live = control.snapshot
        let speed = live.playbackSpeed
        return "pcm sr=\(graph.sampleRate) due=\(stats.eventsDue) mixed=\(stats.eventsMixed) "
            + "starved=\(stats.starvedBuffers) decodeDone=\(graph.stream.isComplete) "
            + "muted=\(stats.eventsMuted) noSample=\(stats.eventsWithoutSamples) "
            + "maxFrameError=\(stats.maxFrameError) maxVoices=\(stats.maxVoices) "
            + "evictions=\(stats.voiceEvictions) hardStops=\(stats.voiceHardStops) "
            + "sfxSamples=\(graph.bank.loadedKinds.count)/\(ChartSfxKind.allCases.count) "
            + "speed=\(speed) seekLatched=\(live.requestedSeekToken > live.appliedSeekToken)"
    }

    // MARK: - Configuration plumbing

    private func publishEvents() {
        let configuration = stateLock.withLockUnchecked { state in
            (state.graph, state.isReady, state.rawEvents, state.leadInMs,
             state.musicOffsetMs, state.sfxOffsetMs)
        }
        guard let graph = configuration.0, configuration.1 else { return }
        let events = ChartMixEvents.build(
            events: configuration.2,
            leadInMs: configuration.3,
            musicOffsetMs: configuration.4,
            sfxOffsetMs: Double(configuration.5),
            sampleRate: graph.sampleRate
        )
        control.mutate { $0.commands.append(.setEvents(events)) }
    }

    /// Applies a seek or a play request that arrived before the track was ready.
    private func flushLatchedRequests() {
        let latched = stateLock.withLockUnchecked { state -> (seek: (Double, Bool)?, playing: Bool) in
            let seek = state.pendingSeek
            state.pendingSeek = nil
            return (seek, state.desiredPlaying)
        }
        if let pending = latched.seek {
            seek(toChartTimeMs: pending.0, playAfterSeek: pending.1)
        }
        if latched.playing {
            play()
        }
    }

    // MARK: - Graph construction

    private func makeGraph(stream: ChartPcmStream, bank: ChartSfxSampleBank, sampleRate: Int) -> ChartAudioGraph? {
        guard let format = AVAudioFormat(standardFormatWithSampleRate: Double(sampleRate), channels: 2) else {
            return nil
        }
        let mixerFrames = Self.outputChunkFrames * Int(ChartPlaybackResampler.maximumStep) + 8
        let mixer = ChartMixer(
            sampleRate: sampleRate,
            bank: bank,
            maxFramesPerBuffer: mixerFrames
        )
        mixer.setMusicSource(stream)
        let live = control.snapshot
        mixer.musicVolume = live.musicVolume
        mixer.answerVolume = live.answerVolume
        mixer.judgeVolume = live.judgeVolume
        mixer.sfxEnabled = live.sfxEnabled

        let resampler = ChartPlaybackResampler(maxOutputFrames: Self.outputChunkFrames)
        resampler.setStep(Double(live.playbackSpeed))
        let renderState = ChartRenderThreadState(maxOutputFrames: Self.outputChunkFrames)
        let chunkFrames = Self.outputChunkFrames
        let pollBuffers = Self.timestampPollBuffers
        let engine = AVAudioEngine()
        // Capture the control block, not `self`: the engine keeps the block alive for the lifetime of
        // the graph, and a `self` capture would create a retain cycle with the renderer.
        let control = self.control

        let renderBlock: AVAudioSourceNodeRenderBlock = { isSilence, timestamp, frameCount, audioBufferList in
            let buffers = UnsafeMutableAudioBufferListPointer(audioBufferList)
            let hostNanos = ChartAudioHostTime.nanos(from: timestamp.pointee)
            let live = control.beginBuffer()
            var playing = live.mixPlaying
            for command in live.commands {
                switch command {
                case .play:
                    playing = true
                    renderState.needsClockSeed = true
                    mixer.restoreAtCurrentFrame()
                    control.mutate { state in
                        state.mixPlaying = true
                        state.clock?.setPlaying(true, nanoTime: hostNanos)
                    }
                case .pause:
                    playing = false
                    mixer.stopAllVoices()
                    control.mutate { state in
                        state.mixPlaying = false
                        state.clock?.setPlaying(false, nanoTime: hostNanos)
                    }
                case let .seek(frame, playAfterSeek, token):
                    mixer.seekToFrame(frame)
                    // If the seek lands outside what the background decoder has produced, restart the
                    // decoder there instead of leaving the music silent until it catches up.
                    if !stream.isComplete,
                       frame >= stream.decodedFrames || frame < stream.baseFrame {
                        stream.requestSeek(frame: frame)
                    }
                    resampler.reset(atFrame: frame)
                    renderState.needsClockSeed = true
                    playing = playAfterSeek || playing
                    control.mutate { state in
                        state.mixPlaying = playing
                        state.appliedSeekToken = token
                        state.clock?.reset(frame: Double(frame), nanoTime: hostNanos, playing: playing)
                    }
                case let .setEvents(events):
                    mixer.setEvents(events)
                case .stopVoices:
                    mixer.stopAllVoices()
                case .restoreLoop:
                    mixer.restoreAtCurrentFrame()
                }
            }

            let requestedSpeed = min(
                Double(ChartPlaybackResampler.maximumStep),
                max(Double(ChartPlaybackResampler.minimumStep), Double(live.playbackSpeed))
            )
            if abs(resampler.step - requestedSpeed) > 0.000_001 {
                resampler.setStep(requestedSpeed)
            }
            if let clock = live.clock, abs(clock.playbackSpeed - requestedSpeed) > 0.000_001 {
                control.mutate { state in
                    state.clock?.setSpeed(live.playbackSpeed, nanoTime: hostNanos)
                }
            }

            guard playing, frameCount > 0 else {
                for index in 0..<buffers.count {
                    if let data = buffers[index].mData {
                        memset(data, 0, Int(buffers[index].mDataByteSize))
                    }
                }
                isSilence.pointee = true
                return noErr
            }

            mixer.musicVolume = live.musicVolume
            mixer.answerVolume = live.answerVolume
            mixer.judgeVolume = live.judgeVolume
            mixer.sfxEnabled = live.sfxEnabled

            var produced = 0
            var firstMusicFrame: Int64 = 0
            var chunkIndex = 0
            while produced < Int(frameCount) {
                let chunk = min(chunkFrames, Int(frameCount) - produced)
                let musicFrame = resampler.render(
                    into: &renderState.mixScratch,
                    frames: chunk,
                    mixer: mixer
                )
                if chunkIndex == 0 { firstMusicFrame = musicFrame }
                if buffers.count >= 2,
                   let left = buffers[0].mData?.assumingMemoryBound(to: Float.self),
                   let right = buffers[1].mData?.assumingMemoryBound(to: Float.self) {
                    let mix = renderState.mixScratch
                    for index in 0..<chunk {
                        left[produced + index] = mix[index * 2]
                        right[produced + index] = mix[index * 2 + 1]
                    }
                } else if buffers.count >= 1,
                          let interleaved = buffers[0].mData?.assumingMemoryBound(to: Float.self) {
                    let mix = renderState.mixScratch
                    for index in 0..<(chunk * 2) {
                        interleaved[produced * 2 + index] = mix[index]
                    }
                }
                produced += chunk
                chunkIndex += 1
            }

            // Publish a (frame, host time) pair the way `AudioTrack.getTimestamp` does; the chart clock
            // extrapolates between polls.
            renderState.timestampCounter += 1
            if renderState.needsClockSeed || renderState.timestampCounter >= pollBuffers {
                renderState.needsClockSeed = false
                renderState.timestampCounter = 0
                control.mutate { state in
                    state.clock?.onTimestamp(frame: firstMusicFrame, nanoTime: hostNanos)
                }
            }
            isSilence.pointee = false
            return noErr
        }

        let sourceNode = AVAudioSourceNode(format: format, renderBlock: renderBlock)
        engine.attach(sourceNode)
        engine.connect(sourceNode, to: engine.mainMixerNode, format: format)
        engine.prepare()
        return ChartAudioGraph(
            engine: engine,
            sourceNode: sourceNode,
            mixer: mixer,
            stream: stream,
            resampler: resampler,
            bank: bank,
            sampleRate: sampleRate
        )
    }

    private static func validSampleRate(_ sampleRate: Int) -> Int {
        guard sampleRate >= 8_000, sampleRate <= 192_000 else { return fallbackSampleRate }
        return sampleRate
    }

    // MARK: - Audio session

    private func activateSessionIfNeeded() {
        sessionLock.lock()
        let alreadyActive = session.isActivated
        sessionLock.unlock()
        guard !alreadyActive else { return }
        let shared = AVAudioSession.sharedInstance()
        sessionLock.lock()
        session.category = shared.category
        session.mode = shared.mode
        session.options = shared.categoryOptions
        sessionLock.unlock()
        do {
            try shared.setCategory(.playback, mode: .default, options: [])
            try shared.setActive(true)
            sessionLock.lock()
            session.isActivated = true
            sessionLock.unlock()
        } catch {
            // Keep the app's previous session configuration when activation fails; otherwise every
            // later preview would inherit a half-applied category.
            sessionLock.lock()
            let previous = session
            sessionLock.unlock()
            try? shared.setCategory(previous.category, mode: previous.mode, options: previous.options)
            lastErrorDescription = "Unable to activate the audio session: \(error.localizedDescription)"
        }
    }

    private func deactivateSession() {
        sessionLock.lock()
        let active = session.isActivated
        let previous = session
        session.isActivated = false
        sessionLock.unlock()
        guard active else { return }
        let shared = AVAudioSession.sharedInstance()
        try? shared.setActive(false, options: .notifyOthersOnDeactivation)
        try? shared.setCategory(previous.category, mode: previous.mode, options: previous.options)
    }
}
