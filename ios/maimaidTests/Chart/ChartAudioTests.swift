import Foundation
import Testing
@testable import maimaid

// MARK: - Fixtures

/// Minimal SFX bank for the mixer tests. `nonisolated` because `ChartSfxSampleProviding` is a
/// nonisolated protocol and the mixer calls it from its own thread.
nonisolated private final class FakeSfxBank: ChartSfxSampleProviding {
    let sampleRate: Int
    private let samples: [ChartSfxKind: [Int16]]

    init(sampleRate: Int = 48_000, samples: [ChartSfxKind: [Int16]] = [:]) {
        self.sampleRate = sampleRate
        self.samples = samples
    }

    func sample(_ kind: ChartSfxKind) -> [Int16]? {
        samples[kind]
    }
}

/// Bank that returns a different sample on every call, used to prove that a monophonic layer fades
/// the previous voice instead of cutting it.
nonisolated private final class ScriptedSfxBank: ChartSfxSampleProviding {
    let sampleRate = 48_000
    private let first: [Int16]
    private let subsequent: [Int16]
    private var callCount = 0

    init(first: [Int16], subsequent: [Int16]) {
        self.first = first
        self.subsequent = subsequent
    }

    func sample(_ kind: ChartSfxKind) -> [Int16]? {
        guard kind == .breakSound else { return nil }
        defer { callCount += 1 }
        return callCount == 0 ? first : subsequent
    }
}

private func impulse(_ value: Int16 = 10_000) -> [Int16] {
    [value]
}

private func constant(_ value: Int16, _ length: Int = 4_800) -> [Int16] {
    [Int16](repeating: value, count: length)
}

private func tapEvent(_ frame: Int64) -> ChartMixEvent {
    ChartMixEvent(frame: frame, playsAnswer: false, judgeKinds: [ChartSfxKind.tap.ordinal], touchHoldEndFrame: -1)
}

private func render(_ mixer: ChartMixer, frames: Int) -> [Int16] {
    var out = [Int16](repeating: 0, count: frames * 2)
    mixer.render(&out, frames: frames)
    return out
}

private func peak(_ samples: [Int16]) -> Int {
    samples.reduce(0) { max($0, abs(Int($1))) }
}

private func shaKey(_ index: Int) -> String {
    let hex = String(index, radix: 16)
    return String(repeating: "0", count: max(0, 64 - hex.count)) + hex
}

private func makeTemporaryDirectory(_ label: String) throws -> URL {
    let url = FileManager.default.temporaryDirectory
        .appending(path: "chart-audio-\(label)-\(UUID().uuidString)", directoryHint: .isDirectory)
    try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
    return url
}

private func createGroup(in directory: URL, index: Int, modified: TimeInterval) throws {
    let key = shaKey(index)
    let names = [
        "\(key).mp3",
        "\(key).v1.48000.s16le.stereo.pcm",
        "\(key).v1.48000.s16le.stereo.meta"
    ]
    for name in names {
        let url = directory.appending(path: name)
        try Data("x".utf8).write(to: url)
        try FileManager.default.setAttributes(
            [.modificationDate: Date(timeIntervalSince1970: modified)],
            ofItemAtPath: url.path
        )
    }
}

private func existingGroups(in directory: URL) -> Set<String> {
    let names = (try? FileManager.default.contentsOfDirectory(atPath: directory.path)) ?? []
    var keys: Set<String> = []
    for name in names {
        let key = String(name.prefix { $0 != "." })
        if !key.isEmpty { keys.insert(key) }
    }
    return keys
}

private func littleEndian16(_ value: UInt16) -> Data {
    Data([UInt8(value & 0xff), UInt8((value >> 8) & 0xff)])
}

private func littleEndian32(_ value: UInt32) -> Data {
    Data([
        UInt8(value & 0xff),
        UInt8((value >> 8) & 0xff),
        UInt8((value >> 16) & 0xff),
        UInt8((value >> 24) & 0xff)
    ])
}

/// Builds a minimal RIFF/WAVE file so the parser can be tested without the bundled assets.
private func makeWav(sampleRate: Int, channels: Int, samples: [Int16]) -> Data {
    var payload = Data()
    for sample in samples {
        payload.append(littleEndian16(UInt16(bitPattern: sample)))
    }
    var data = Data()
    data.append(Data("RIFF".utf8))
    data.append(littleEndian32(UInt32(36 + payload.count)))
    data.append(Data("WAVE".utf8))
    data.append(Data("fmt ".utf8))
    data.append(littleEndian32(16))
    data.append(littleEndian16(1))
    data.append(littleEndian16(UInt16(channels)))
    data.append(littleEndian32(UInt32(sampleRate)))
    data.append(littleEndian32(UInt32(sampleRate * channels * 2)))
    data.append(littleEndian16(UInt16(channels * 2)))
    data.append(littleEndian16(16))
    data.append(Data("data".utf8))
    data.append(littleEndian32(UInt32(payload.count)))
    data.append(payload)
    return data
}

// MARK: - AudioTimeline

@MainActor
struct AudioTimelineTests {
    @Test("Music position and chart time are inverse")
    func musicPositionAndChartTimeAreInverse() {
        for offset in [-500, -100, -1, 0, 1, 100, 500] {
            let chart = 12_345.0
            let music = AudioTimeline.musicPositionMs(chartTimeMs: chart, leadInMs: 1_000, musicOffsetMs: offset)
            #expect(abs(AudioTimeline.chartTimeMs(musicPositionMs: music, leadInMs: 1_000, musicOffsetMs: offset) - chart) < 1e-9)
        }
    }

    @Test("A positive music offset delays the music")
    func positiveMusicOffsetDelaysTheMusic() {
        // Chart time 5000 with a +100 offset maps the music 100 ms earlier, i.e. the music lags.
        let chart = 5_000.0
        #expect(abs(AudioTimeline.musicPositionMs(chartTimeMs: chart, leadInMs: 1_000, musicOffsetMs: 100) - 3_900) < 1e-9)
        // Reading back the clock must return the chart time, not chart - 2 * offset.
        #expect(abs(AudioTimeline.chartTimeMs(musicPositionMs: 3_900, leadInMs: 1_000, musicOffsetMs: 100) - chart) < 1e-9)
    }

    @Test("The SFX frame uses the base -50 ms offset plus the user offset")
    func sfxFrameUsesBaseAndUserOffset() {
        // Event at 1550 ms, lead-in 1000 ms: audible at 1500 ms -> 500 ms of music -> 24000 frames.
        #expect(AudioTimeline.frameForSfx(
            eventTimeMs: 1_550, leadInMs: 1_000, musicOffsetMs: 0, sfxOffsetMs: 0, sampleRate: 48_000
        ) == 24_000)
        // +100 ms user offset delays the SFX.
        #expect(AudioTimeline.frameForSfx(
            eventTimeMs: 1_550, leadInMs: 1_000, musicOffsetMs: 0, sfxOffsetMs: 100, sampleRate: 48_000
        ) == 28_800)
        // +100 ms music offset makes the same chart time map 100 ms earlier in the music.
        #expect(AudioTimeline.frameForSfx(
            eventTimeMs: 1_550, leadInMs: 1_000, musicOffsetMs: 100, sfxOffsetMs: 0, sampleRate: 48_000
        ) == 19_200)
    }

    @Test("Events before the music start clamp to frame zero")
    func eventsBeforeTheMusicStartClampToFrameZero() {
        #expect(AudioTimeline.frameForSfx(
            eventTimeMs: 1_000, leadInMs: 1_000, musicOffsetMs: 0, sfxOffsetMs: 0, sampleRate: 48_000
        ) == 0)
    }

    @Test("Chart time for a frame round-trips")
    func chartTimeForFrameRoundTrips() {
        let frame: Int64 = 240_000
        let chart = AudioTimeline.chartTimeForFrame(frame, leadInMs: 1_000, musicOffsetMs: 100, sampleRate: 48_000)
        let back = AudioTimeline.frame(
            forMusicPositionMs: AudioTimeline.musicPositionMs(chartTimeMs: chart, leadInMs: 1_000, musicOffsetMs: 100),
            sampleRate: 48_000
        )
        #expect(back == frame)
        #expect(abs(chart - 6_100) < 1e-6)
    }
}

// MARK: - ChartMixEvents

@MainActor
struct ChartMixEventsTests {
    @Test("Maps every layer of a tap")
    func mapsEveryLayerOfATap() {
        let event = ChartAudioEvent(timeMs: 1_000)
        event.hasBaseSound = true
        event.hasTapJudgeSound = true
        let mixEvents = ChartMixEvents.build(
            events: [event],
            leadInMs: 1_000,
            musicOffsetMs: 0,
            sfxOffsetMs: 0,
            sampleRate: 48_000
        )
        #expect(mixEvents.count == 1)
        #expect(mixEvents.first?.frame == 0)
        #expect(mixEvents.first?.playsAnswer == true)
        #expect(mixEvents.first?.judgeKinds == [ChartSfxKind.tap.ordinal])
        #expect(mixEvents.first?.touchHoldEndFrame == -1)
        #expect(mixEvents.first?.hasTouchHoldLoop == false)
    }

    @Test("A break plays break and cheer")
    func breakPlaysBreakAndCheer() {
        let event = ChartAudioEvent(timeMs: 2_000)
        event.hasBreakJudgeSound = true
        #expect(ChartMixEvents.judgeKinds(event) == [ChartSfxKind.breakSound.ordinal, ChartSfxKind.cheer.ordinal])
    }

    @Test("Judge layers keep the reference engine's order")
    func judgeLayersFollowTheReferenceOrder() {
        let event = ChartAudioEvent(timeMs: 0)
        event.hasTapJudgeSound = true
        event.hasTouchSound = true
        event.hasBreakJudgeSound = true
        event.hasExJudgeSound = true
        event.hasSlideSound = true
        event.hasBreakSlideSound = true
        event.hasBreakSlideCheerSound = true
        event.hasFireworkSound = true
        event.hasTouchHoldSound = true
        #expect(ChartMixEvents.judgeKinds(event) == [
            ChartSfxKind.tap.ordinal,
            ChartSfxKind.touch.ordinal,
            ChartSfxKind.breakSound.ordinal,
            ChartSfxKind.cheer.ordinal,
            ChartSfxKind.ex.ordinal,
            ChartSfxKind.slide.ordinal,
            ChartSfxKind.breakSlide.ordinal,
            ChartSfxKind.breakSlideCheer.ordinal,
            ChartSfxKind.firework.ordinal,
            ChartSfxKind.touchHold.ordinal
        ])
    }

    @Test("Touch-hold carries its loop end frame")
    func touchHoldCarriesItsLoopEndFrame() {
        let event = ChartAudioEvent(timeMs: 2_000)
        event.hasTouchSound = true
        event.hasTouchHoldSound = true
        event.touchHoldDurationMs = 500
        let mixEvents = ChartMixEvents.build(
            events: [event],
            leadInMs: 1_000,
            musicOffsetMs: 0,
            sfxOffsetMs: 0,
            sampleRate: 48_000
        )
        #expect(mixEvents.count == 1)
        #expect(mixEvents.first?.frame == 45_600) // audible at 1950 ms -> 950 ms of music
        #expect(mixEvents.first?.touchHoldEndFrame == 69_600) // 2450 ms
        #expect(mixEvents.first?.judgeKinds == [ChartSfxKind.touch.ordinal, ChartSfxKind.touchHold.ordinal])
    }
}

// MARK: - ChartMixer

@MainActor
struct ChartMixerTests {
    private let rate = 48_000

    @Test("Passes music through with volume")
    func passesMusicThroughWithVolume() {
        let source = ChartMemoryPcmSource([Int16](repeating: 16_384, count: 1_024))
        let mixer = ChartMixer(sampleRate: rate, bank: FakeSfxBank())
        mixer.setMusicSource(source)
        mixer.musicVolume = 0.5
        let out = render(mixer, frames: 256)
        #expect(abs(Double(out[0]) - 8_191) <= 2)
        #expect(abs(Double(out[1]) - 8_191) <= 2)
    }

    @Test("Starts a voice at the exact sample frame")
    func startsVoiceAtExactSampleFrame() {
        let mixer = ChartMixer(sampleRate: rate, bank: FakeSfxBank(samples: [.tap: impulse()]))
        mixer.setEvents([tapEvent(100)])
        let out = render(mixer, frames: 512)
        #expect(out[198] == 0)
        #expect(out[199] == 0)
        #expect(out[200] != 0)
        #expect(out[201] != 0)
        #expect(out[202] == 0)
    }

    @Test("Seek skips past events but keeps the one at the seek frame")
    func seekSkipsPastEventsButKeepsTheOneAtTheSeekFrame() {
        let mixer = ChartMixer(sampleRate: rate, bank: FakeSfxBank(samples: [.tap: impulse()]))
        mixer.setEvents([tapEvent(100), tapEvent(200), tapEvent(300)])
        mixer.seekToFrame(200)
        let out = render(mixer, frames: 256)
        #expect(out[0] != 0) // event at 200
        #expect(out[2] == 0) // event at 100 must not replay
        #expect(out[(300 - 200) * 2] != 0) // event at 300
        let second = render(mixer, frames: 256)
        #expect(peak(second) == 0)
    }

    @Test("Replacing events does not replay the past")
    func replacingEventsDoesNotReplayThePast() {
        let mixer = ChartMixer(sampleRate: rate, bank: FakeSfxBank(samples: [.tap: impulse()]))
        mixer.setEvents([tapEvent(100)])
        _ = render(mixer, frames: 512)
        mixer.setEvents([tapEvent(100), tapEvent(600)])
        let out = render(mixer, frames: 256)
        #expect(out[0] == 0)
        #expect(out[(600 - 512) * 2] != 0)
        let next = render(mixer, frames: 256)
        #expect(peak(next) == 0)
    }

    @Test("A full pool evicts the oldest voice but never drops new events")
    func fullPoolEvictsOldestButNeverDropsNewEvents() {
        let mixer = ChartMixer(
            sampleRate: rate,
            bank: FakeSfxBank(samples: [.tap: constant(1_000, 48_000)]),
            maxVoices: 2,
            reserve: 2
        )
        mixer.setEvents((0..<40).map { tapEvent(Int64($0) * 128) })
        for _ in 0..<20 { _ = render(mixer, frames: 256) }
        #expect(mixer.stats.eventsDue == 40)
        #expect(mixer.stats.eventsMixed == 40)
        #expect(mixer.stats.maxVoices >= 2)
        #expect(mixer.stats.voiceEvictions > 0)
    }

    @Test("Gain scales with the number of voices")
    func gainScalesWithTheNumberOfVoices() {
        let one = ChartMixer(sampleRate: rate, bank: FakeSfxBank(samples: [.tap: constant(1_000, 4_800)]))
        one.setEvents([tapEvent(0)])
        let single = render(one, frames: 256)[0]

        let two = ChartMixer(sampleRate: rate, bank: FakeSfxBank(samples: [.tap: constant(1_000, 4_800)]))
        two.setEvents([tapEvent(0), tapEvent(0)])
        let pair = render(two, frames: 256)[0]
        // Two equal voices at 1/sqrt(2) each sum to ~1.414x a single voice.
        #expect(abs(Double(pair) - Double(single) * 1.414) <= Double(single) * 0.05)
    }

    @Test("The soft limiter keeps the mix inside full scale")
    func limiterKeepsTheMixInsideFullScale() {
        let mixer = ChartMixer(sampleRate: rate, bank: FakeSfxBank(samples: [.tap: constant(32_767, 4_800)]))
        mixer.setEvents((0..<12).map { tapEvent(Int64($0)) })
        let out = render(mixer, frames: 256)
        let maximum = peak(out)
        #expect(maximum <= 32_767)
        #expect(maximum > 30_000)
    }

    @Test("A monophonic layer fades the previous voice instead of cutting it")
    func monoLayerFadesThePreviousVoiceInsteadOfCuttingIt() {
        let bank = ScriptedSfxBank(first: constant(5_000, 4_800), subsequent: constant(0, 4_800))
        let mixer = ChartMixer(sampleRate: rate, bank: bank)
        let breakKind = [ChartSfxKind.breakSound.ordinal]
        mixer.setEvents([
            ChartMixEvent(frame: 0, playsAnswer: false, judgeKinds: breakKind, touchHoldEndFrame: -1),
            ChartMixEvent(frame: 1_000, playsAnswer: false, judgeKinds: breakKind, touchHoldEndFrame: -1)
        ])
        let out = render(mixer, frames: 2_048)
        let atStart = abs(Int(out[100 * 2]))
        let duringFade = abs(Int(out[1_100 * 2]))
        let afterFade = abs(Int(out[1_900 * 2]))
        #expect(atStart > 0)
        #expect(duringFade > 0)
        #expect(duringFade <= atStart)
        #expect(afterFade == 0)
    }

    @Test("A touch-hold loop stops at its end frame")
    func touchHoldLoopStopsAtItsEndFrame() {
        let mixer = ChartMixer(sampleRate: rate, bank: FakeSfxBank(samples: [.touchHold: constant(1_000, 100)]))
        mixer.setEvents([
            ChartMixEvent(
                frame: 0,
                playsAnswer: false,
                judgeKinds: [ChartSfxKind.touchHold.ordinal],
                touchHoldEndFrame: 500
            )
        ])
        var blocks: [[Int16]] = []
        for _ in 0..<12 { blocks.append(render(mixer, frames: 256)) }
        // Still sounding shortly after the loop start, silent long after the end + fade.
        #expect(peak(blocks[0]) > 0)
        #expect(peak(blocks[11]) == 0)
    }

    @Test("Muted output still consumes events")
    func mutedOutputStillConsumesEvents() {
        let mixer = ChartMixer(sampleRate: rate, bank: FakeSfxBank(samples: [.tap: impulse()]))
        mixer.setEvents([tapEvent(100)])
        mixer.sfxEnabled = false
        let muted = render(mixer, frames: 512)
        #expect(peak(muted) == 0)
        #expect(mixer.stats.eventsMuted == 1)
        mixer.sfxEnabled = true
        let after = render(mixer, frames: 512)
        #expect(peak(after) == 0)
    }

    @Test("Seeking into a touch-hold restores its loop")
    func seekIntoATouchHoldRestoresItsLoop() {
        let mixer = ChartMixer(sampleRate: rate, bank: FakeSfxBank(samples: [.touchHold: constant(1_000, 100)]))
        mixer.setEvents([
            ChartMixEvent(
                frame: 0,
                playsAnswer: false,
                judgeKinds: [ChartSfxKind.touchHold.ordinal],
                touchHoldEndFrame: 5_000
            )
        ])
        mixer.seekToFrame(2_000)
        let out = render(mixer, frames: 256)
        #expect(peak(out) > 0)
    }

    @Test("Missing samples are counted instead of crashing")
    func missingSamplesAreCountedInsteadOfCrashing() {
        let mixer = ChartMixer(sampleRate: rate, bank: FakeSfxBank())
        mixer.setEvents([tapEvent(10)])
        _ = render(mixer, frames: 256)
        #expect(mixer.stats.eventsDue == 1)
        #expect(mixer.stats.eventsMixed == 0)
        #expect(mixer.stats.eventsWithoutSamples == 1)
    }

    @Test("No event is lost at high density")
    func noEventIsLostAtHighDensity() {
        let mixer = ChartMixer(
            sampleRate: rate,
            bank: FakeSfxBank(samples: [.tap: constant(500, 200)]),
            maxVoices: 4,
            reserve: 4
        )
        mixer.setEvents((0..<400).map { tapEvent(Int64($0) * 24) })
        for _ in 0..<40 { _ = render(mixer, frames: 256) }
        #expect(mixer.stats.eventsDue == 400)
        #expect(mixer.stats.eventsMixed == 400)
        #expect(mixer.stats.eventsWithoutSamples == 0)
    }

    @Test("Playback speed consumes music frames faster without losing the timeline")
    func playbackSpeedConsumesMusicFramesFaster() {
        let source = ChartMemoryPcmSource([Int16](repeating: 16_384, count: 48_000 * 4))
        let mixer = ChartMixer(sampleRate: rate, bank: FakeSfxBank(), maxFramesPerBuffer: 2_056)
        mixer.setMusicSource(source)
        let resampler = ChartPlaybackResampler(maxOutputFrames: 256)
        resampler.reset(atFrame: 0)
        var output = [Float](repeating: 0, count: 512)
        let first = resampler.render(into: &output, frames: 256, mixer: mixer)
        #expect(first == 0)
        #expect(output[0] > 0)
        resampler.setStep(2)
        let second = resampler.render(into: &output, frames: 256, mixer: mixer)
        #expect(second == 256)
        let third = resampler.render(into: &output, frames: 256, mixer: mixer)
        #expect(third == 768)
    }
}

// MARK: - ChartAudioClock

@MainActor
struct ChartAudioClockTests {
    private let rate = 48_000
    private let t0: Int64 = 1_000_000_000

    @Test("Extrapolates at the playback speed")
    func extrapolatesAtPlaybackSpeed() {
        var clock = ChartAudioClock(sampleRate: rate)
        clock.reset(frame: 1_000, nanoTime: t0, playing: true)
        #expect(abs(clock.positionFrameAt(t0 + 1_000_000_000) - (1_000 + 48_000)) < 1e-6)
        clock.setSpeed(1.5, nanoTime: t0 + 1_000_000_000)
        #expect(abs(clock.positionFrameAt(t0 + 2_000_000_000) - (1_000 + 48_000 + 72_000)) < 1e-3)
    }

    @Test("Pause freezes the clock")
    func pauseFreezesTheClock() {
        var clock = ChartAudioClock(sampleRate: rate)
        clock.reset(frame: 0, nanoTime: t0, playing: true)
        clock.setPlaying(false, nanoTime: t0 + 500_000_000)
        #expect(abs(clock.positionFrameAt(t0 + 5_000_000_000) - 24_000) < 1e-6)
        clock.setPlaying(true, nanoTime: t0 + 5_000_000_000)
        #expect(abs(clock.positionFrameAt(t0 + 5_500_000_000) - 48_000) < 1e-3)
    }

    @Test("A small timestamp error is corrected by nudging the rate")
    func smallTimestampErrorIsCorrectedByNudgingTheRate() {
        var clock = ChartAudioClock(sampleRate: rate)
        clock.reset(frame: 0, nanoTime: t0, playing: true)
        // At 1 s the clock projected 48000 frames; the graph reports 48480 (+10 ms).
        clock.onTimestamp(frame: 48_480, nanoTime: t0 + 1_000_000_000)
        // The visual clock keeps running from the projected position; the rate is nudged by at most
        // +3% for the next interval.
        let at1100 = clock.positionFrameAt(t0 + 1_100_000_000)
        #expect(at1100 > 48_000 + 4_800)
        #expect(at1100 <= 48_000 + 4_800 * 1.031)
        #expect(clock.playbackSpeed == 1.0)
    }

    @Test("A large timestamp error snaps")
    func largeTimestampErrorSnaps() {
        var clock = ChartAudioClock(sampleRate: rate)
        clock.reset(frame: 0, nanoTime: t0, playing: true)
        clock.onTimestamp(frame: 96_000, nanoTime: t0 + 1_000_000_000) // +1 s discontinuity (seek/flush)
        #expect(abs(clock.positionFrameAt(t0 + 1_000_000_000) - 96_000) < 1e-6)
        #expect(abs(clock.positionFrameAt(t0 + 2_000_000_000) - (96_000 + 48_000)) < 1e-6)
    }

    @Test("A backward timestamp never moves the clock backwards")
    func aBackwardTimestampNeverMovesTheClockBackwards() {
        var clock = ChartAudioClock(sampleRate: rate)
        clock.reset(frame: 0, nanoTime: t0, playing: true)
        let before = clock.positionFrameAt(t0 + 1_000_000_000)
        // The graph reports a playhead far behind the projection (typical right after resume).
        clock.onTimestamp(frame: 0, nanoTime: t0 + 1_000_000_000)
        let after = clock.positionFrameAt(t0 + 1_000_000_000)
        #expect(after >= before)
        // It still converges instead of ignoring the sample: the rate is nudged down.
        let later = clock.positionFrameAt(t0 + 1_500_000_000)
        #expect(later > after)
        #expect(later < after + 0.5 * Double(rate))
    }

    @Test("A timestamp while paused is ignored")
    func timestampWhilePausedIsIgnored() {
        var clock = ChartAudioClock(sampleRate: rate)
        clock.reset(frame: 0, nanoTime: t0, playing: false)
        #expect(clock.isPlaying == false)
        clock.onTimestamp(frame: 48_000, nanoTime: t0 + 1_000_000_000)
        #expect(abs(clock.positionFrameAt(t0 + 2_000_000_000)) < 1e-6)
    }
}

// MARK: - ChartSfxSampleBank

@MainActor
struct ChartSfxSampleBankTests {
    @Test("Parses a 48 kHz mono 16-bit WAV")
    func parsesMonoWav() throws {
        let samples: [Int16] = [0, 1_000, -1_000, 32_767, -32_768]
        let parsed = try ChartSfxSampleBank.decodeWav(makeWav(sampleRate: 48_000, channels: 1, samples: samples))
        #expect(parsed.sampleRate == 48_000)
        #expect(parsed.samples == samples)
    }

    @Test("Averages multi-channel WAVs down to mono")
    func downmixesStereoWav() throws {
        // Interleaved L/R pairs: (1000, 3000), (-2000, 2000)
        let parsed = try ChartSfxSampleBank.decodeWav(
            makeWav(sampleRate: 44_100, channels: 2, samples: [1_000, 3_000, -2_000, 2_000])
        )
        #expect(parsed.sampleRate == 44_100)
        #expect(parsed.samples == [2_000, 0])
    }

    @Test("Linear resampling matches the reference fallback")
    func resamplesLinearly() {
        let resampled = ChartSfxSampleBank.resampleLinear([0, 1_000, 2_000, 3_000], from: 48_000, to: 24_000)
        #expect(resampled == [0, 2_000])
    }

    @Test("Resampling to the same rate is a no-op")
    func resamplingSameRateKeepsSamples() {
        let samples: [Int16] = [1, 2, 3]
        #expect(ChartSfxSampleBank.resampleLinear(samples, from: 48_000, to: 48_000) == samples)
    }

    @Test("A partially loaded bank reports the missing kinds")
    func partialBankReportsMissingKinds() {
        let bank = ChartSfxSampleBank(
            sampleRate: 48_000,
            samples: [.tap: ChartSfxSample(sampleRate: 48_000, samples: [1, 2, 3])]
        )
        #expect(bank.sample(.tap) == [1, 2, 3])
        #expect(bank.sample(.answer) == nil)
        #expect(bank.loadedKinds == [.tap])
        #expect(bank.missingKinds.count == ChartSfxKind.allCases.count - 1)
        #expect(bank.isEmpty == false)
    }
}

// MARK: - ChartAudioCacheLru

@MainActor
struct ChartAudioCacheLruTests {
    @Test("Keeps only the five most recent songs and deletes their PCM")
    func keepsOnlyTheFiveMostRecentSongs() throws {
        let directory = try makeTemporaryDirectory("lru")
        defer { try? FileManager.default.removeItem(at: directory) }
        for index in 0..<7 { try createGroup(in: directory, index: index, modified: 1_000 * Double(index)) }
        ChartAudioCacheLru.prune(directory: directory, keepKeys: [shaKey(6)])
        #expect(existingGroups(in: directory) == Set((2...6).map(shaKey)))
        // An evicted song must not leave its PCM or meta behind.
        #expect(FileManager.default.fileExists(atPath: directory.appending(path: "\(shaKey(0)).v1.48000.s16le.stereo.pcm").path) == false)
        #expect(FileManager.default.fileExists(atPath: directory.appending(path: "\(shaKey(0)).mp3").path) == false)
        #expect(FileManager.default.fileExists(atPath: directory.appending(path: "\(shaKey(6)).v1.48000.s16le.stereo.pcm").path))
    }

    @Test("Never deletes the song being prepared even if it is the oldest")
    func neverDeletesTheSongBeingPrepared() throws {
        let directory = try makeTemporaryDirectory("lru-keep")
        defer { try? FileManager.default.removeItem(at: directory) }
        for index in 0..<6 { try createGroup(in: directory, index: index, modified: 1_000 * Double(index)) }
        ChartAudioCacheLru.prune(directory: directory, keepKeys: [shaKey(0)])
        let remaining = existingGroups(in: directory)
        #expect(remaining.count == 5)
        #expect(remaining.contains(shaKey(0)))
        #expect(remaining.contains(shaKey(1)) == false)
        #expect(remaining.contains(shaKey(5)))
    }

    @Test("Purges PCM files left behind by older versions")
    func purgesLegacyPcmFiles() throws {
        let directory = try makeTemporaryDirectory("legacy")
        defer { try? FileManager.default.removeItem(at: directory) }
        try createGroup(in: directory, index: 0, modified: 1_000)
        let before = ((try? FileManager.default.contentsOfDirectory(atPath: directory.path)) ?? []).count
        let removed = ChartAudioCacheLru.purgeLegacyPcmFiles(directory: directory)
        let remaining = (try? FileManager.default.contentsOfDirectory(atPath: directory.path)) ?? []
        // Only the MP3 survives: PCM is decoded into memory now.
        #expect(remaining == ["\(shaKey(0)).mp3"])
        #expect(removed == 2)
        #expect(before - remaining.count == removed)
    }

    @Test("Does nothing when at or below the limit")
    func doesNothingWhenAtOrBelowTheLimit() throws {
        let directory = try makeTemporaryDirectory("lru-limit")
        defer { try? FileManager.default.removeItem(at: directory) }
        for index in 0..<5 { try createGroup(in: directory, index: index, modified: 1_000 * Double(index)) }
        ChartAudioCacheLru.prune(directory: directory)
        #expect(existingGroups(in: directory).count == 5)
    }

    @Test("Ignores files without a content-addressed prefix")
    func ignoresUnprefixedFiles() throws {
        let directory = try makeTemporaryDirectory("lru-unprefixed")
        defer { try? FileManager.default.removeItem(at: directory) }
        for index in 0..<6 { try createGroup(in: directory, index: index, modified: 1_000 * Double(index)) }
        try Data("x".utf8).write(to: directory.appending(path: ".DS_Store"))
        ChartAudioCacheLru.prune(directory: directory)
        #expect(existingGroups(in: directory).count == 5)
        #expect(FileManager.default.fileExists(atPath: directory.appending(path: ".DS_Store").path))
    }
}
