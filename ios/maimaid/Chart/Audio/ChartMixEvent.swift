import Foundation

/// One SFX event converted to mixer frames. Immutable, so it can be built on the UI thread and read
/// by the audio thread without further synchronisation.
nonisolated struct ChartMixEvent: Sendable, Equatable {
    /// Mixer frame at which the event must start.
    let frame: Int64
    /// Whether the answer layer plays.
    let playsAnswer: Bool
    /// Judge-layer sounds started by this event, in the reference engine's order.
    let judgeKinds: [Int]
    /// End frame of the touch-hold loop, or -1 when this event starts no loop.
    let touchHoldEndFrame: Int64

    var hasTouchHoldLoop: Bool { touchHoldEndFrame >= 0 }
}

/// Builds the immutable, frame-addressed SFX event list for the mixer.
nonisolated enum ChartMixEvents {
    static func build(
        events: [ChartAudioEvent],
        leadInMs: Double,
        musicOffsetMs: Int,
        sfxOffsetMs: Double,
        sampleRate: Int
    ) -> [ChartMixEvent] {
        var result: [ChartMixEvent] = []
        result.reserveCapacity(events.count)
        for event in events {
            let frame = AudioTimeline.frameForSfx(
                eventTimeMs: event.timeMs,
                leadInMs: leadInMs,
                musicOffsetMs: musicOffsetMs,
                sfxOffsetMs: sfxOffsetMs,
                sampleRate: sampleRate
            )
            let holdEnd: Int64 = event.hasTouchHoldSound
                ? AudioTimeline.frameForSfx(
                    eventTimeMs: event.timeMs + event.touchHoldDurationMs,
                    leadInMs: leadInMs,
                    musicOffsetMs: musicOffsetMs,
                    sfxOffsetMs: sfxOffsetMs,
                    sampleRate: sampleRate
                )
                : -1
            result.append(
                ChartMixEvent(
                    frame: frame,
                    playsAnswer: event.playsAnswerSound,
                    judgeKinds: judgeKinds(event),
                    touchHoldEndFrame: holdEnd
                )
            )
        }
        return result
    }

    /// Mirrors the reference SoundPool implementation's `playEvent` layer selection. The order of the
    /// checks is part of the contract: it decides which layer starts first inside one sample frame
    /// and therefore *is* the audible layer order of a stacked event.
    static func judgeKinds(_ event: ChartAudioEvent) -> [Int] {
        var kinds: [ChartSfxKind] = []
        kinds.reserveCapacity(4)
        if event.hasTapJudgeSound || event.hasHoldEndSound { kinds.append(.tap) }
        if event.hasTouchSound || event.hasTouchHoldEndSound { kinds.append(.touch) }
        if event.hasBreakJudgeSound {
            kinds.append(.breakSound)
            kinds.append(.cheer)
        }
        if event.hasExJudgeSound { kinds.append(.ex) }
        if event.hasSlideSound { kinds.append(.slide) }
        if event.hasBreakSlideSound { kinds.append(.breakSlide) }
        if event.hasBreakSlideCheerSound { kinds.append(.breakSlideCheer) }
        if event.hasFireworkSound || event.hasTouchHoldEndFireworkSound { kinds.append(.firework) }
        if event.hasTouchHoldSound { kinds.append(.touchHold) }
        return kinds.map(\.ordinal)
    }
}
