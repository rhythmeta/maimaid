import Foundation
import Observation

/// PCM music + SFX engine for the chart preview.
///
/// One `AVAudioEngine` source node carries the whole mix: the decoded music stream and every SFX
/// voice are rendered by `ChartMixer` on the audio thread, so both paths share the same output
/// latency and SFX start on the exact sample frame of their event. The chart clock is derived from
/// the frames the mixer has produced, which makes the visible chart follow the audible mix.
///
/// The UI thread only publishes commands (play/pause/seek/settings) and reads `chartPositionMs`; it
/// never touches voices or buffers.
@MainActor
@Observable
final class ChartAudioEngine {
    private(set) var state: ChartAudioState = .idle
    private(set) var preparing = false
    private(set) var isReady = false
    private(set) var usesPCM = false
    private(set) var isPlaying = false

    /// Length of the loaded track in milliseconds, once known.
    private(set) var durationMs: Double = 0

    // Read-only mirrors of the mixer settings, so the preview page (and its tests) can tell which
    // values the audible mix is actually using.
    private(set) var playbackSpeed: Float = 1
    private(set) var musicVolume: Float = 1
    private(set) var answerVolume: Float = 1
    private(set) var judgeVolume: Float = 1
    private(set) var sfxEnabled = true
    private(set) var musicOffsetMs = 0
    private(set) var sfxOffsetMs = 0

    /// Current chart position in milliseconds.
    var chartPositionMs: Double { renderer.chartPositionMs }

    /// Mixer implementation, owned by the audio layer.
    private let renderer: ChartAudioRenderer

    init() {
        renderer = ChartAudioRenderer()
    }

    /// Prepares a previously downloaded MP3 and starts the mix once its first frames are decoded.
    /// Returns false when the track cannot be played at all; the chart then runs silently.
    @discardableResult
    func prepare(fileURL: URL, key: String) async -> Bool {
        preparing = true
        state = .preparing
        let ready = await renderer.prepare(fileURL: fileURL, key: key)
        isReady = ready
        usesPCM = ready
        preparing = false
        durationMs = renderer.durationMs
        state = ready ? .ready(durationMillis: Int(renderer.durationMs.rounded())) : .failed(message: "Unable to decode the chart track")
        return ready
    }

    /// Publishes the frame-addressed event timeline to the mixer. Safe before the track is ready;
    /// the configuration is kept for the moment it becomes ready.
    func applyConfiguration(events: [ChartAudioEvent], leadInMs: Double, durationMs: Double,
                            musicOffsetMs: Int, sfxOffsetMs: Int) {
        renderer.configure(events: events, leadInMs: leadInMs, durationMs: durationMs,
                           musicOffsetMs: musicOffsetMs, sfxOffsetMs: sfxOffsetMs)
    }

    func play() {
        renderer.play()
        isPlaying = true
    }

    func pause() {
        renderer.pause()
        isPlaying = false
    }

    func stopSfx() {
        renderer.stopSfx()
    }

    /// Seeks to a chart position; the engine applies the lead-in/offset mapping itself.
    func seek(toChartTimeMs chartTimeMs: Double, playAfterSeek: Bool) {
        renderer.seek(toChartTimeMs: chartTimeMs, playAfterSeek: playAfterSeek)
        isPlaying = playAfterSeek
    }

    func setPlaybackSpeed(_ speed: Float) {
        playbackSpeed = speed
        renderer.setPlaybackSpeed(speed)
    }

    func setMusicVolume(_ volume: Float) {
        musicVolume = volume
        renderer.setMusicVolume(volume)
    }

    func setAnswerVolume(_ volume: Float) {
        answerVolume = volume
        renderer.setAnswerVolume(volume)
    }

    func setJudgeVolume(_ volume: Float) {
        judgeVolume = volume
        renderer.setJudgeVolume(volume)
    }

    func setSfxEnabled(_ enabled: Bool) {
        sfxEnabled = enabled
        renderer.setSfxEnabled(enabled)
    }

    func setMusicOffsetMs(_ offset: Int) {
        musicOffsetMs = offset
        renderer.setMusicOffsetMs(offset)
    }

    func setSfxOffsetMs(_ offset: Int) {
        sfxOffsetMs = offset
        renderer.setSfxOffsetMs(offset)
    }

    /// Tears the audio graph down; called when the preview page goes away.
    func release() {
        renderer.release()
        state = .idle
        preparing = false
        isReady = false
        usesPCM = false
        isPlaying = false
        playbackSpeed = 1
        musicVolume = 1
        answerVolume = 1
        judgeVolume = 1
        sfxEnabled = true
        musicOffsetMs = 0
        sfxOffsetMs = 0
    }

    /// One-line diagnostics for debug logging.
    var debugSummary: String { renderer.debugSummary }
}
