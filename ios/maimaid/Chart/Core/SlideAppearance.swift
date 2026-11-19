import Foundation

/// Track appearance (alpha and whether it is still fading in) for a slide segment.
nonisolated struct SlideTrackAppearance: Sendable {
    var alpha: Double
    var isFading: Bool
}

/// Slide track fade timing, ported from `core/timing/slideAppearance.ts`.
nonisolated enum SlideAppearance {
    private static let slideJudgeAdjustMs = 50.0
    private static let trackFadeRampMs = 200.0
    private static let wifiTrackAlpha = 120.0 / 255.0

    static func appearanceStartMs(noteTimeMs: Double, approachTimeMs: Double, slideDelay: Double) -> Double {
        let optionIndex = ((slideDelay + 1.0) * 10.0).rounded()
        return noteTimeMs - approachTimeMs + (approachTimeMs * optionIndex) / 21.0
    }

    private static func isWifiTrackHalfAlpha(noteTimeMs: Double, currentTimeMs: Double) -> Bool {
        noteTimeMs - currentTimeMs + trackFadeRampMs <= currentTimeMs
    }

    static func trackAppearance(noteTimeMs: Double, approachTimeMs: Double, slideDelay: Double,
                                currentTimeMs: Double, isWifi: Bool) -> SlideTrackAppearance {
        let appearanceStart = appearanceStartMs(
            noteTimeMs: noteTimeMs, approachTimeMs: approachTimeMs, slideDelay: slideDelay)
        if isWifi && currentTimeMs <= appearanceStart {
            return SlideTrackAppearance(alpha: 0, isFading: false)
        }

        if currentTimeMs + slideJudgeAdjustMs >= noteTimeMs {
            return SlideTrackAppearance(alpha: isWifi ? wifiTrackAlpha : 1.0, isFading: false)
        }

        let elapsed = currentTimeMs - appearanceStart
        if elapsed <= 0 {
            return SlideTrackAppearance(alpha: 0, isFading: false)
        }

        if isWifi && elapsed > trackFadeRampMs {
            let alpha = isWifiTrackHalfAlpha(noteTimeMs: noteTimeMs, currentTimeMs: currentTimeMs)
                ? 0.5 : wifiTrackAlpha
            return SlideTrackAppearance(alpha: alpha, isFading: false)
        }

        let fadeWindow = noteTimeMs - appearanceStart
        let alpha: Double
        if isWifi || fadeWindow >= trackFadeRampMs {
            alpha = min(0.5, (0.5 * elapsed) / trackFadeRampMs)
        } else {
            alpha = max(0, min(0.5, 0.5 * (1.0 - (noteTimeMs - currentTimeMs) / elapsed)))
        }
        return SlideTrackAppearance(alpha: alpha, isFading: alpha > 0 && alpha < 0.5)
    }
}
