import Foundation

/// Visual constants copied 1:1 from the reference engine's `utils/constants.ts`.
nonisolated enum ChartConstants {
    static let baseApproachTimeMs = 2250.0
    static let noteVisibilityAfterMs = 0.0
    static let noteHitEffectDurationMs = 450.0
    static let holdActiveCycleMs = 16_000.0 / 60.0

    static let buttonAngleStep = Double.pi / 4
    static let buttonAngleOffset = Double.pi / 8
    static let baseAngle = -Double.pi / 2

    static let panelRadiusUnits = 480.0
    static let noteSizeRatio = 1.0 / 12.5
    static let tapInnerRingRatio = 0.65
    static let approachStartScale = 0.25
    static let buttonMarkerRatio = 9.0 / 300.0
    static let noteStrokeWidthRatio = 2.0 / 300.0
    static let judgmentLineWidthRatio = 3.0 / 300.0
    static let slideArrowWidthRatio = 16.6 / 300.0
    static let slideArrowHeightRatio = 44.6 / 300.0
    static let slideArrowSpanRatio = 12.2 / 300.0
    static let slideArrowPaddingRatio = 9.5 / 300.0
    static let slideStarSizeRatio = 1.6 / 10.42
    static let slideStarWaitingMinScale = 0.5
    static let slideWifiLineWidthRatio = 19.2 / 300.0
    static let slideWifiCornerFracs: [Double] = [
        0.0, 0.094, 0.178, 0.266, 0.355, 0.462, 0.57, 0.674, 0.779, 0.884, 1.0,
    ]

    static let holdWidthRatio = 1.5
    static let holdInnerRatio = 0.62
    static let holdActiveBrightnessMin = 0.82
    static let holdActiveBrightnessMax = 1.0
    static let holdActiveGlowRatio = 0.06
    static let holdActiveBrightnessCurve: [Double] = [
        0.714, 0.51, 0.286, 0.095, 0.0, 0.01, 0.095, 0.293, 0.552, 0.762, 0.917, 0.976, 1.0,
        0.995, 0.969, 0.917,
    ]
    static let holdActiveGlowRamp: [[Double]] = [
        [0.0, 0.99, 1.11, 1.29],
        [0.1, 0.99, 0.98, 1.04],
        [0.2, 0.99, 0.79, 0.72],
        [0.3, 0.98, 0.63, 0.62],
        [0.4, 0.93, 0.49, 0.62],
        [0.5, 0.76, 0.28, 0.43],
        [0.6, 0.54, 0.08, 0.13],
        [0.7, 0.41, 0.02, 0.05],
        [0.8, 0.3, 0.01, 0.04],
        [0.9, 0.24, 0.02, 0.05],
    ]

    static let touchApproachMultiplier = 0.9
    static let touchCenterDotRatio = 1.0 / 37.5
    static let touchPetalOpenRatio = 1.0 / 6.25
    static let touchPetalClosedRatio = 1.0 / 12.5
    static let touchHoldCentreBurstAngle = -Double.pi / 6

    static let hiSpeedDefault = 6.0
    static let hiSpeedConversionFactor = (2.0 / 3.0) * 0.9
    static let noteLightenRatio = 0.18
    static let ddrDarkenRatio = 0.25
    static let rainbowSpeedDegPerSec = 60.0

    /// Colors lifted from the reference palette.
    enum Colors {
        static let black = ChartColor(argb: 0xFF00_0000)
        static let white = ChartColor(argb: 0xFFFF_FFFF)
        static let tapPink = ChartColor(argb: 0xFFFF_69B4)
        static let tapGradientStart = ChartColor(argb: 0xFFFD_197D)
        static let tapGradientEnd = ChartColor(argb: 0xFFFF_7DD5)
        static let simultaneousGold = ChartColor(argb: 0xFFFF_D700)
        static let simultaneousGradientStart = ChartColor(argb: 0xFFFC_B600)
        static let simultaneousGradientEnd = ChartColor(argb: 0xFFFF_FE00)
        static let breakOrange = ChartColor(argb: 0xFFFF_8C00)
        static let breakGradientStart = ChartColor(argb: 0xFFFF_B347)
        static let breakGradientEnd = ChartColor(argb: 0xFFFF_6600)

        static let slideCyan = ChartColor(argb: 0xFF01_FBFD)
        static let slideArrowRight = ChartColor(argb: 0xFFFF_9001)
        static let slidePink = ChartColor(argb: 0xFFFF_69B4)
        static let slideSimultaneous = ChartColor(argb: 0xFFFF_ED24)

        static let touchCyan = ChartColor(argb: 0xFF00_FFFF)
        static let touchBlue = ChartColor(argb: 0xFF00_80FF)
        static let touchSimultaneousYellow = ChartColor(argb: 0xFFFF_FF00)
        static let touchHoldRed = ChartColor(argb: 0xFFF7_4601)
        static let touchHoldYellow = ChartColor(argb: 0xFFF6_ED00)
        static let touchHoldGreen = ChartColor(argb: 0xFF12_A86A)
        static let touchHoldBlue = ChartColor(argb: 0xFF00_97F6)

        static let exOverlayBreak = ChartColor(argb: 0xCCFF_C878)
        static let exOverlaySimultaneous = ChartColor(argb: 0xCCFF_F596)
        static let exOverlayNormal = ChartColor(argb: 0xCCFF_B4D2)

        static let hitEffectGold = ChartColor(argb: 0xFFF5_EA72)

        static let ddrRed = ChartColor(argb: 0xFFFF_0000)
        static let ddrBlue = ChartColor(argb: 0xFF00_66FF)
        static let ddrYellow = ChartColor(argb: 0xFFFF_FF00)
        static let ddrGreen = ChartColor(argb: 0xFF00_FF00)
        static let ddrOrange = ChartColor(argb: 0xFFFF_8800)
        static let ddrCyan = ChartColor(argb: 0xFF00_DDFF)

        static let hudMuted = ChartColor(argb: 0xFF94_A3B8)
        static let fpsGood = ChartColor(argb: 0xFF22_C55E)
        static let fpsOk = ChartColor(argb: 0xFFEA_B308)
        static let fpsBad = ChartColor(argb: 0xFFEF_4444)
    }
}
