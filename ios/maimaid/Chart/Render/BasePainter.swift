import CoreGraphics
import Foundation
import SwiftUI

/// Shared helpers: position mapping, approach timing and color math.
nonisolated class BasePainter {
    let context: RenderContext

    var fillPaint = ChartPaint()
    var strokePaint: ChartPaint = {
        var paint = ChartPaint()
        paint.style = .stroke
        return paint
    }()

    init(_ context: RenderContext) {
        self.context = context
    }

    // MARK: - Positions

    /// Mirror transform for a 1-based button index.
    func mirrorPosition(_ position: Int) -> Int {
        let mode = context.config.mirrorMode
        guard mode != .none, (0...8).contains(position) else { return position }
        let mirrorH = [0, 8, 7, 6, 5, 4, 3, 2, 1]
        let mirrorV = [0, 4, 3, 2, 1, 8, 7, 6, 5]
        let rotate180 = [0, 5, 6, 7, 8, 1, 2, 3, 4]
        switch mode {
        case .horizontal: return mirrorH[position]
        case .vertical: return mirrorV[position]
        case .rotate180: return rotate180[position]
        case .none: return position
        }
    }

    func mirrorTouchPosition(_ position: String) -> String {
        TouchGeometry.mirrorTouchSensor(position, context.config.mirrorMode)
    }

    /// Canvas-space angle of a button (already mirrored).
    func buttonAngle(_ position: Int) -> Double {
        let mirrored = mirrorPosition(position)
        return ChartConstants.baseAngle + ChartConstants.buttonAngleOffset
            + Double(mirrored - 1) * ChartConstants.buttonAngleStep
    }

    func buttonPosition(_ position: Int) -> ChartPoint {
        let angle = buttonAngle(position)
        return ChartPoint(
            Double(context.centerX) + cos(angle) * Double(context.radius),
            Double(context.centerY) + sin(angle) * Double(context.radius)
        )
    }

    func touchPosition(_ position: String) -> ChartPoint {
        TouchGeometry.sensorPoint(
            position,
            centerX: Double(context.centerX),
            centerY: Double(context.centerY),
            radius: Double(context.radius),
            mirrorMode: context.config.mirrorMode
        )
    }

    // MARK: - Timing

    func approachTimeMs() -> Double {
        let config = context.config
        if config.alwaysKeepHiSpeed {
            return ChartConstants.baseApproachTimeMs / (config.hiSpeed / config.playbackSpeed)
        }
        return ChartConstants.baseApproachTimeMs / config.hiSpeed
    }

    func noteApproachTimeMs(_ hiSpeed: Double?) -> Double {
        let magnitude = abs(hiSpeed ?? 1.0)
        return approachTimeMs() / (magnitude > 0 ? magnitude : 1.0)
    }

    func noteApproachDir(_ hiSpeed: Double?) -> Double {
        (hiSpeed ?? 1.0) < 0 ? -1.0 : 1.0
    }

    func scaleByRadius(_ ratio: Double) -> Float {
        Float(ratio * Double(context.radius))
    }

    func distanceToCenter(_ x: Double, _ y: Double) -> Double {
        ((x - Double(context.centerX)) * (x - Double(context.centerX))
            + (y - Double(context.centerY)) * (y - Double(context.centerY))).squareRoot()
    }

    func durationToMs(_ duration: Double, bpm: Double) -> Double {
        60_000.0 * duration / bpm
    }

    // MARK: - Colors

    /// Beat-based coloring: 1/1, 1/2, 1/4 and (extended) 1/8, 1/6.
    func ddrColor(_ timing: Double) -> ChartColor? {
        guard context.config.ddrColorMode else { return nil }
        let epsilon = 0.001
        let fractional = abs(timing.truncatingRemainder(dividingBy: 1.0))
        if fractional < epsilon || fractional > 1 - epsilon { return ChartConstants.Colors.ddrRed }
        let half = abs(timing.truncatingRemainder(dividingBy: 0.5))
        if !(half >= epsilon && half <= 0.499) { return ChartConstants.Colors.ddrBlue }
        let quarter = abs(timing.truncatingRemainder(dividingBy: 0.25))
        if !(quarter >= epsilon && quarter <= 0.249) { return ChartConstants.Colors.ddrYellow }
        if context.config.ddrColorExtended {
            let eighth = abs(timing.truncatingRemainder(dividingBy: 0.125))
            if !(eighth >= epsilon && eighth <= 0.124) { return ChartConstants.Colors.ddrOrange }
            let sixth = 1.0 / 6.0
            let sixthRemainder = abs(timing.truncatingRemainder(dividingBy: sixth))
            if sixthRemainder < epsilon || sixthRemainder > sixth - epsilon {
                return ChartConstants.Colors.ddrCyan
            }
        }
        return ChartConstants.Colors.ddrGreen
    }

    /// Ring colors [light end, dark end] with priority DDR > break > simultaneous > tap.
    func gradientColors(ddr: ChartColor?, isBreak: Bool, isSimultaneous: Bool) -> [ChartColor] {
        if let ddr {
            return [ddr, mixColor(ddr, ChartConstants.Colors.black, ChartConstants.ddrDarkenRatio)]
        }
        if isBreak {
            return [ChartConstants.Colors.breakGradientStart, ChartConstants.Colors.breakGradientEnd]
        }
        if isSimultaneous {
            return [ChartConstants.Colors.simultaneousGradientStart, ChartConstants.Colors.simultaneousGradientEnd]
        }
        return [ChartConstants.Colors.tapGradientStart, ChartConstants.Colors.tapGradientEnd]
    }

    func mixColor(_ from: ChartColor, _ target: ChartColor, _ amount: Double) -> ChartColor {
        func mix(_ a: Double, _ b: Double) -> Double { a + (b - a) * amount }
        return ChartColor(
            red: mix(from.red, target.red),
            green: mix(from.green, target.green),
            blue: mix(from.blue, target.blue),
            alpha: from.alpha
        )
    }

    func scaleBrightness(_ color: ChartColor, _ factor: Double) -> ChartColor {
        ChartColor(
            red: (color.red * factor).clampedToUnit,
            green: (color.green * factor).clampedToUnit,
            blue: (color.blue * factor).clampedToUnit,
            alpha: color.alpha
        )
    }

    func applyColor(_ paint: inout ChartPaint, _ color: ChartColor, alpha: Double = 1) {
        paint.shading = nil
        paint.color = color
        paint.alpha = alpha.clampedToUnit
    }

    func strokeSettings(_ width: Float, _ cap: CGLineCap, _ join: CGLineJoin) {
        strokePaint.strokeWidth = CGFloat(width)
        strokePaint.strokeCap = cap
        strokePaint.strokeJoin = join
    }

    func noteStrokeWidth() -> Float {
        scaleByRadius(ChartConstants.noteStrokeWidthRatio)
    }

    // MARK: - Paths

    /// Appends a circular arc, connecting from the current path point like `ctx.arc`.
    func arcTo(_ path: inout Path, cx: Float, cy: Float, radius: Float,
               startDeg: Float, sweepDeg: Float) {
        path.addArc(
            center: CGPoint(x: Double(cx), y: Double(cy)),
            radius: Double(radius),
            startAngle: .degrees(Double(startDeg)),
            endAngle: .degrees(Double(startDeg + sweepDeg)),
            clockwise: sweepDeg < 0
        )
    }

    /// Circular path built from explicit line segments: hardware canvases tessellate large path
    /// arcs too coarsely (visible facets on tap rings), so circles are emitted as dense polygons at
    /// a fixed angular step instead.
    func circlePath(cx: Float, cy: Float, radius: Float) -> Path {
        var path = Path()
        let segments = max(32, Int(ceil(Double(radius))))
        for index in 0...segments {
            let angle = Double.pi * 2 * Double(index) / Double(segments)
            let x = Double(cx) + cos(angle) * Double(radius)
            let y = Double(cy) + sin(angle) * Double(radius)
            if index == 0 {
                path.move(to: CGPoint(x: x, y: y))
            } else {
                path.addLine(to: CGPoint(x: x, y: y))
            }
        }
        path.closeSubpath()
        return path
    }

    func arcPath(cx: Float, cy: Float, radius: Float, startDeg: Float, sweepDeg: Float) -> Path {
        if abs(sweepDeg) >= 359.9 {
            return circlePath(cx: cx, cy: cy, radius: radius)
        }
        var path = Path()
        let startRad = Double(startDeg) * Double.pi / 180
        path.move(to: CGPoint(
            x: Double(cx) + cos(startRad) * Double(radius),
            y: Double(cy) + sin(startRad) * Double(radius)
        ))
        appendArc(&path, cx: cx, cy: cy, radius: radius, startDeg: startDeg, sweepDeg: sweepDeg)
        // No close: a stroked arc must not gain a chord between its endpoints.
        return path
    }

    /// Appends a polygonal arc in degrees, connecting from the current point.
    func appendArc(_ path: inout Path, cx: Float, cy: Float, radius: Float,
                   startDeg: Float, sweepDeg: Float) {
        let steps = max(2, Int(ceil(abs(sweepDeg) / 7.5)))
        let start = Double(startDeg) * Double.pi / 180
        let sweep = Double(sweepDeg) * Double.pi / 180
        for index in 0...steps {
            let angle = start + sweep * Double(index) / Double(steps)
            let x = Double(cx) + cos(angle) * Double(radius)
            let y = Double(cy) + sin(angle) * Double(radius)
            path.addLine(to: CGPoint(x: x, y: y))
        }
    }

    /// Angle difference in (-π, π], matching the reference helper.
    func angleDelta(_ a: Double, _ b: Double) -> Double {
        var delta = (b - a).truncatingRemainder(dividingBy: Double.pi * 2)
        if delta > Double.pi { delta -= Double.pi * 2 }
        if delta < -Double.pi { delta += Double.pi * 2 }
        return delta
    }

    func lerpAngle(_ a: Double, _ b: Double, _ t: Double) -> Double {
        a + angleDelta(a, b) * t
    }

    func slideTrackAppearance(_ note: ChartNote, currentTimeMs: Double, isWifi: Bool) -> SlideTrackAppearance {
        SlideAppearance.trackAppearance(
            noteTimeMs: note.timingMs,
            approachTimeMs: noteApproachTimeMs(note.hiSpeed),
            slideDelay: context.config.slideDelay,
            currentTimeMs: currentTimeMs,
            isWifi: isWifi
        )
    }
}

extension Double {
    /// Clamp into 0...1 for color channels.
    nonisolated var clampedToUnit: Double { Swift.min(1, Swift.max(0, self)) }
}
