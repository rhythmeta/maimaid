import CoreGraphics
import Foundation
import SwiftUI

/// Background, judgment ring and the in-canvas HUD.
///
/// 1:1 port of the Android `StagePainter` (itself a port of the reference engine's stage painter):
/// the stage is always black, the optional cover art is clipped to a disc of `logicalSize` diameter
/// behind a `0x99000000` dim overlay, and every HUD string is measured in multiples of `radius / 300`
/// so the readouts scale with the stage.
nonisolated final class StagePainter: BasePainter {
    /// HUD text paint. The reference builds it with `Typeface.SANS_SERIF` + `BOLD` and never changes
    /// the weight, so bold lives on the paint for its whole lifetime.
    private var textPaint: ChartPaint = {
        var paint = ChartPaint()
        paint.bold = true
        return paint
    }()

    /// Cached disc clip and cover destination, mirroring the Kotlin fields: both only change with the
    /// stage size or the cover bitmap size, so rebuilding them for every frame at 120 Hz is wasted
    /// work.
    private var coverClip = Path()
    private var coverClipRect = CGRect.zero
    private var coverDest = CGRect.zero
    private var coverClipSize: CGFloat = -1
    private var coverDestKey = -1.0

    // HUD palette entries the reference spells inline (`0xBF000000`, `0xFFFFD700`, `0xFFFFA500`).
    private static let textShadowColor = ChartColor(argb: 0xBF00_0000)
    private static let coverDimOverlay = ChartColor(argb: 0x9900_0000)
    private static let speedBoostColor = ChartColor(argb: 0xFFFF_D700)
    private static let breakNoExColor = ChartColor(argb: 0xFFFF_A500)

    override init(_ context: RenderContext) {
        super.init(context)
    }

    // MARK: - Stage

    /// Fills the stage and draws the optional cover-art disc background.
    func clear(_ background: ChartImage?) {
        let canvas = context.canvas
        let size = CGFloat(context.logicalSize)
        let centerX = CGFloat(context.centerX)
        let centerY = CGFloat(context.centerY)
        canvas.drawColor(ChartConstants.Colors.black)
        guard let background, background.width > 0, background.height > 0 else { return }

        canvas.withSave { canvas in
            if coverClipSize != size {
                coverClipRect = CGRect(
                    x: centerX - size / 2,
                    y: centerY - size / 2,
                    width: size,
                    height: size
                )
                coverClip = Path(ellipseIn: coverClipRect)
                coverClipSize = size
            }
            canvas.clipPath(coverClip)

            // Cover-fit the artwork: uniform scale, centred, then clipped to the disc above.
            let scale = min(size / CGFloat(background.width), size / CGFloat(background.height))
            let dw = CGFloat(background.width) * scale
            let dh = CGFloat(background.height) * scale
            let destinationKey = Double(dw) * 10_000.0 + Double(dh)
            if coverDestKey != destinationKey {
                coverDest = CGRect(x: (size - dw) / 2, y: (size - dh) / 2, width: dw, height: dh)
                coverDestKey = destinationKey
            }
            canvas.drawImage(background, in: coverDest, alpha: 1)

            // The dim overlay carries its alpha in the colour; forcing the paint alpha would make it
            // opaque and hide the artwork.
            fillPaint.shading = nil
            fillPaint.color = Self.coverDimOverlay
            fillPaint.alpha = 1
            canvas.drawRect(CGRect(x: 0, y: 0, width: size, height: size), fillPaint)
        }
    }

    // MARK: - Judgment line

    /// Judgment ring and the eight button markers.
    func renderJudgmentLine(_ sensorImage: ChartImage?) {
        let design = context.config.judgmentLineDesign
        if design == .blind { return }
        let canvas = context.canvas
        let centerX = Double(context.centerX)
        let centerY = Double(context.centerY)
        let radius = Double(context.radius)

        // Sensor design swaps the ring for the arcade sensor-layout artwork, inset by 10 pt and
        // nudged 8 pt down like the reference.
        if design == .sensor, let sensorImage {
            let imageSize = Double(context.logicalSize) - 10
            let left = centerX - imageSize / 2
            let top = centerY - imageSize / 2 + 8
            canvas.drawImage(
                sensorImage,
                in: CGRect(x: left, y: top, width: imageSize, height: imageSize),
                alpha: 1
            )
        }

        if design == .simple || design == .sensor {
            strokeSettings(scaleByRadius(ChartConstants.judgmentLineWidthRatio), .butt, .miter)
            applyColor(&strokePaint, ChartConstants.Colors.white)
            canvas.drawCircle(centerX: centerX, centerY: centerY, radius: radius, strokePaint)
        }

        // The marker dots exist for every design except `blind`, including `noLine`.
        let markerSize = Double(scaleByRadius(ChartConstants.buttonMarkerRatio))
        applyColor(&fillPaint, ChartConstants.Colors.white)
        for position in 1...8 {
            let angle = buttonAngle(position)
            canvas.drawCircle(
                centerX: centerX + cos(angle) * radius,
                centerY: centerY + sin(angle) * radius,
                radius: markerSize,
                fillPaint
            )
        }
    }

    // MARK: - HUD

    /// BPM readout plus the measure/beat/divisor and KPS lines.
    func renderBpmDisplay(currentBpm: Double, currentBeats: Double, divisor: Double,
                          keysPerSecond: Int, bpmFlashColor: ChartColor?) {
        let radius = Double(context.radius)
        let fontSize = CGFloat((22 * radius / 300).rounded())
        let smallFontSize = CGFloat((16 * radius / 300).rounded())
        let padding = CGFloat((20 * radius / 300).rounded())
        let lineGap = CGFloat((4 * radius / 300).rounded())
        let shadowOffset = CGFloat(2 * radius / 300)
        let canvas = context.canvas

        textPaint.textAlign = .left
        textPaint.textSize = fontSize
        textPaint.color = ChartConstants.Colors.white
        let bpmText = "BPM: \(Self.kotlinInt(floor(currentBpm)))"
        // Kotlin reads `fontMetrics.ascent`, which is negative; the shim reports the ascent magnitude,
        // so a baseline below the top padding is `padding + ascent`.
        let baseline = padding + canvas.textAscent(textPaint)

        // A tempo change flashes the readout (red up, green down) and fades back to white.
        drawShadowedText(
            bpmText,
            x: padding,
            baseline: baseline,
            shadowOffset: shadowOffset,
            color: bpmFlashColor ?? ChartConstants.Colors.white,
            textSize: fontSize
        )

        let measure = Self.kotlinInt(floor(currentBeats / 4))
        let beatInMeasure = currentBeats - Double(measure) * 4
        let beat = Self.kotlinInt(floor(beatInMeasure)) + 1
        let fraction = beatInMeasure - floor(beatInMeasure)
        let currentY = padding + fontSize + lineGap
        textPaint.textSize = smallFontSize
        let fractionText = Self.padded(Self.kotlinInt(floor(fraction * 100)), width: 2)
        let beatText = "\(measure):\(beat).\(fractionText) [1/\(formatDivisor(divisor))]"
        textPaint.textAlign = .left
        textPaint.color = ChartConstants.Colors.hudMuted
        // Kotlin writes `y - fontMetrics.ascent` (ascent is negative there), so the Swift baseline
        // adds the ascent magnitude the shim reports.
        canvas.drawText(beatText, x: padding, y: currentY + canvas.textAscent(textPaint),
                        textPaint)

        // Key density sits under the measure readout: presses inside the trailing second.
        let kpsY = currentY + smallFontSize + lineGap
        canvas.drawText("KPS: \(keysPerSecond)", x: padding,
                        y: kpsY + canvas.textAscent(textPaint), textPaint)
    }

    /// Note / break counters around the ring.
    func renderNoteCounts(totalNotes: Int, completedNotes: Int, totalBreaks: Int,
                          completedBreaks: Int, totalBreaksNoEx: Int, completedBreaksNoEx: Int,
                          speedLabel: String?) {
        let config = context.config
        let radius = Double(context.radius)
        let size = CGFloat(context.logicalSize)
        let fontSize = CGFloat((22 * radius / 300).rounded())
        let smallFontSize = CGFloat((18 * radius / 300).rounded())
        let padding = CGFloat((20 * radius / 300).rounded())
        let lineGap = CGFloat((4 * radius / 300).rounded())
        let shadowOffset = CGFloat(2 * radius / 300)

        textPaint.textSize = fontSize
        if config.showNoteTotal {
            textPaint.textAlign = .right
            let text = "\(config.hudComboLabel): \(completedNotes) / \(totalNotes)"
            let baseline = padding + context.canvas.textAscent(textPaint)
            drawShadowedText(
                text,
                x: size - padding,
                baseline: baseline,
                shadowOffset: shadowOffset,
                color: ChartConstants.Colors.white,
                textSize: fontSize
            )
        }

        // Speed multiplier badge, drawn under the combo counter in gold.
        if let speedLabel {
            textPaint.textAlign = .right
            textPaint.textSize = smallFontSize
            let badgeBaseline = padding + fontSize + lineGap
                + context.canvas.textAscent(textPaint)
            drawShadowedText(
                speedLabel,
                x: size - padding,
                baseline: badgeBaseline,
                shadowOffset: shadowOffset,
                color: Self.speedBoostColor,
                textSize: smallFontSize
            )
        }

        if config.showBreakCount {
            textPaint.textAlign = .left
            textPaint.textSize = fontSize
            let bottomBaseline = size - padding - context.canvas.textDescent(textPaint)
            drawShadowedText(
                "BREAK: \(completedBreaks) / \(totalBreaks)",
                x: padding,
                baseline: bottomBaseline,
                shadowOffset: shadowOffset,
                color: ChartConstants.Colors.breakOrange,
                textSize: fontSize
            )

            textPaint.textAlign = .right
            textPaint.textSize = fontSize
            drawShadowedText(
                "BREAK: \(completedBreaksNoEx) / \(totalBreaksNoEx)",
                x: size - padding,
                baseline: bottomBaseline,
                shadowOffset: shadowOffset,
                color: Self.breakNoExColor,
                textSize: fontSize
            )

            textPaint.textAlign = .right
            textPaint.textSize = smallFontSize
            let labelBaseline = bottomBaseline - fontSize - lineGap
            drawShadowedText(
                config.hudBreakNoExLabel,
                x: size - padding,
                baseline: labelBaseline,
                shadowOffset: shadowOffset,
                color: Self.breakNoExColor,
                textSize: smallFontSize
            )
        }
    }

    // MARK: - HUD helpers

    /// Draws `text` twice: a 75 % black copy offset by `shadowOffset`, then the coloured original.
    /// Alignment is whatever the caller last set on `textPaint`, exactly like the reference helper.
    private func drawShadowedText(_ text: String, x: CGFloat, baseline: CGFloat,
                                  shadowOffset: CGFloat, color: ChartColor, textSize: CGFloat) {
        let canvas = context.canvas
        textPaint.textSize = textSize
        textPaint.color = Self.textShadowColor
        canvas.drawText(text, x: x + shadowOffset, y: baseline + shadowOffset, textPaint)
        textPaint.color = color
        canvas.drawText(text, x: x, y: baseline, textPaint)
    }

    /// Integral divisors print without a fractional part (the reference uses `Double.toString` for
    /// the fractional case, which Swift's shortest round-trip description matches for divisors).
    private func formatDivisor(_ divisor: Double) -> String {
        if divisor == floor(divisor) { return String(Self.kotlinInt(divisor)) }
        return "\(divisor)"
    }

    /// Mirrors Kotlin's `Double.toInt()`: NaN becomes 0 and out-of-range values saturate, so a
    /// non-finite tempo or beat can never trap the render loop.
    private static func kotlinInt(_ value: Double) -> Int {
        if value.isNaN { return 0 }
        if value >= Double(Int.max) { return Int.max }
        if value <= Double(Int.min) { return Int.min }
        return Int(value)
    }

    /// Mirrors Kotlin `padStart(width, '0')` for the beat fraction.
    private static func padded(_ value: Int, width: Int) -> String {
        var text = String(value)
        while text.count < width { text = "0" + text }
        return text
    }
}
