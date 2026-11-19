import CoreGraphics
import Foundation
import SwiftUI

/// Time-driven effects: tap hit stamps, hold press ripples, touch hit bursts and touch fireworks.
///
/// 1:1 port of `EffectsPainter.kt`. The reference engine builds blurred sprites offscreen; both the
/// Android port and this one approximate the blur with stacked strokes/gradients so the result stays
/// visually equivalent. The firework hole punch is the one place that genuinely needs offscreen
/// compositing, and it is the only spot where this port's draw calls are structured differently from
/// the Kotlin file (see `renderTouchFireworks`).
nonisolated final class EffectsPainter: BasePainter {
    private let notePainter: NotePainter
    private let touchPainter: TouchPainter

    /// Fill paint for the effect shapes (the Kotlin `effectPaint`).
    private var effectPaint: ChartPaint = {
        var paint = ChartPaint()
        paint.style = .fill
        return paint
    }()

    /// Stroke paint for hit stamps and sparkle outlines (the Kotlin `effectStroke`).
    private var effectStroke: ChartPaint = {
        var paint = ChartPaint()
        paint.style = .stroke
        return paint
    }()

    // Per-frame geometry is cached by size/radius so drawing a frame allocates nothing here.
    private var hexagonPaths: [Int: Path] = [:]
    private var starPaths: [Int: Path] = [:]
    private var petalPaths = [Path?](repeating: nil, count: EffectsPainter.fireworkPetalColors.count)
    private var petalShaders = [ChartShading?](repeating: nil,
                                               count: EffectsPainter.fireworkPetalColors.count)
    private var petalShaderAlpha = Double.nan
    private let unitBallShaderBig: ChartShading = .radial(
        center: .zero,
        radius: 1,
        colors: [ChartColor(argb: 0xFFFF_EBC8), ChartColor(argb: 0x00FF_B464)],
        stops: [0, 1]
    )
    private let unitBallShaderSmall: ChartShading = .radial(
        center: .zero,
        radius: 1,
        colors: [ChartColor(argb: 0xFFFF_F5DC), ChartColor(argb: 0x00FF_DC9F)],
        stops: [0, 1]
    )
    /// Last hit timestamp per button (1...8), reused instead of a per-frame map.
    private var lastHitByPosition = [Double](repeating: -.infinity, count: 9)

    init(_ context: RenderContext, _ notePainter: NotePainter, _ touchPainter: TouchPainter) {
        self.notePainter = notePainter
        self.touchPainter = touchPainter
        super.init(context)
    }

    // MARK: - Tap hit effects

    /// Renders the hit stamp of every button note that entered the 450 ms effect window.
    func renderTapHitEffects(_ prepared: PreparedChart, currentTimeMs: Double) {
        let notes = prepared.hitEffectNotes
        if notes.isEmpty { return }
        let windowStart = currentTimeMs - ChartConstants.noteHitEffectDurationMs
        var lo = 0
        var hi = notes.count
        while lo < hi {
            let mid = (lo + hi) >> 1
            if notes[mid].timingMs < windowStart { lo = mid + 1 } else { hi = mid }
        }
        if lo >= notes.count { return }

        // A newer hit on the same button truncates the previous effect. Buttons are 1...8, so a
        // fixed array replaces a per-frame map.
        for index in lastHitByPosition.indices { lastHitByPosition[index] = -.infinity }
        for index in lo..<notes.count {
            let note = notes[index]
            if note.timingMs > currentTimeMs { break }
            guard let position = buttonPositionOf(note), (1...8).contains(position) else { continue }
            if note.timingMs > lastHitByPosition[position] {
                lastHitByPosition[position] = note.timingMs
            }
        }

        for index in lo..<notes.count {
            let note = notes[index]
            if note.timingMs > currentTimeMs { break }
            guard let position = buttonPositionOf(note), (1...8).contains(position) else { continue }
            if lastHitByPosition[position] > note.timingMs { continue }
            let timeDiff = currentTimeMs - note.timingMs
            if timeDiff < 0 || timeDiff > ChartConstants.noteHitEffectDurationMs { continue }
            let progress = timeDiff / ChartConstants.noteHitEffectDurationMs
            let angle = notePainter.angle(position)
            let isStar = (note.kind == .tap && (note.isBreak || note.isStar))
                || (note.kind == .holdEnd && note.isBreakHold)
                || (note.kind == .slide && note.isStartBreak)
            renderHitEffectAt(
                x: Double(context.centerX) + cos(angle) * Double(context.radius),
                y: Double(context.centerY) + sin(angle) * Double(context.radius),
                angle: angle,
                color: ChartConstants.Colors.hitEffectGold,
                progress: progress,
                type: isStar ? .star : .hexagon
            )
        }
    }

    /// Button position of a note class the hit stamp covers; touch notes have none.
    private func buttonPositionOf(_ note: ChartNote) -> Int? {
        switch note.kind {
        case .tap, .holdEnd, .holdStart, .slide: return note.position
        case .touch, .touchHoldStart, .touchHoldEnd: return nil
        }
    }

    /// Draws one hit stamp cluster at a given progress: a main stamp plus four trailing copies.
    private func renderHitEffectAt(x: Double, y: Double, angle: Double, color: ChartColor,
                                   progress: Double, type: StampShape) {
        let scale = 1 - 0.75 * (progress - 1) * (progress - 1)
        let alpha = 1 - 4 * (progress - 0.5) * (progress - 0.5)
        if alpha <= 0 || scale <= 0 { return }
        let subAng = 1 - (progress - 1) * (progress - 1)
        let subRad = max(0.0, min(1.0, 1 - (8.0 / 9.0) * progress * progress))
        let baseR = Double(scaleByRadius(ChartConstants.noteSizeRatio)) * 1.36 * 1.5
        if baseR <= 0 { return }

        let r0 = baseR * scale
        let rSmall = r0 * 0.7
        let rBig = r0 * 0.8
        let offset = baseR * subRad * 0.7

        let canvas = context.canvas
        canvas.save()
        canvas.translate(CGFloat(x), CGFloat(y))
        // `Canvas.rotate(degrees)` turns about the current origin, which the translate moved to
        // (x, y); the shim takes an explicit pivot of (0, 0) for the same result.
        canvas.rotate(degrees: CGFloat(toDegrees(angle)), pivotX: 0, pivotY: 0)

        drawStamp(x: 0, y: 0, radius: r0, color: color, alpha: alpha, type: type)
        let sub1 = Double.pi / 6
        let sub2 = -Double.pi / 6
        let a1a = sub1 + Double.pi * subAng
        drawStamp(x: cos(a1a) * offset, y: sin(a1a) * offset * 0.7, radius: rSmall, color: color,
                  alpha: alpha, type: type)
        let a1b = sub1 + Double.pi * (1 + subAng)
        drawStamp(x: cos(a1b) * offset, y: sin(a1b) * offset, radius: rSmall, color: color,
                  alpha: alpha, type: type)
        let a2a = sub2 - Double.pi * subAng
        drawStamp(x: cos(a2a) * offset, y: sin(a2a) * offset, radius: rBig, color: color,
                  alpha: alpha, type: type)
        let a2b = sub2 + Double.pi * (1 - subAng)
        drawStamp(x: cos(a2b) * offset, y: sin(a2b) * offset, radius: rBig, color: color,
                  alpha: alpha, type: type)
        canvas.restore()
    }

    /// One stamp outline as three concentric strokes: two soft halos plus the core.
    private func drawStamp(x: Double, y: Double, radius: Double, color: ChartColor, alpha: Double,
                           type: StampShape) {
        if radius <= 0 { return }
        let path = stampPath(type, radius)
        let core = CGFloat(scaleByRadius(ChartConstants.noteStrokeWidthRatio)) * 2
        let canvas = context.canvas
        canvas.withTranslation(CGFloat(x), CGFloat(y)) { inner in
            effectStroke.strokeJoin = .round
            effectStroke.strokeCap = .round
            // Soft halo: wide, low-alpha strokes that do not thicken the core stroke.
            effectStroke.strokeWidth = core + CGFloat(scaleByRadius(10.0 / 300.0))
            effectStroke.color = color
            effectStroke.alpha = byteAlpha(alpha * 0.10)
            inner.drawPath(path, effectStroke)
            effectStroke.strokeWidth = core + CGFloat(scaleByRadius(5.0 / 300.0))
            effectStroke.alpha = byteAlpha(alpha * 0.22)
            inner.drawPath(path, effectStroke)
            effectStroke.strokeWidth = core
            effectStroke.alpha = byteAlpha(alpha)
            inner.drawPath(path, effectStroke)
        }
    }

    /// Stamp outline at the origin, cached per type and half-pixel radius bucket.
    private func stampPath(_ type: StampShape, _ radius: Double) -> Path {
        // `kotlin.math.round` rounds half up; the radius is always positive here.
        let bucket = Int((radius * 2).rounded())
        switch type {
        case .star:
            if let cached = starPaths[bucket] { return cached }
            let path = starOutline(Double(bucket) / 2.0)
            if starPaths.count >= Self.stampPathCacheLimit { starPaths.removeAll(keepingCapacity: true) }
            starPaths[bucket] = path
            return path
        case .hexagon:
            if let cached = hexagonPaths[bucket] { return cached }
            let path = hexagonOutline(Double(bucket) / 2.0)
            if hexagonPaths.count >= Self.stampPathCacheLimit {
                hexagonPaths.removeAll(keepingCapacity: true)
            }
            hexagonPaths[bucket] = path
            return path
        }
    }

    private func hexagonOutline(_ radius: Double) -> Path {
        var path = Path()
        for index in 0..<6 {
            let angle = Double(index) * Double.pi / 3
            let point = CGPoint(x: radius * cos(angle), y: radius * sin(angle))
            if index == 0 { path.move(to: point) } else { path.addLine(to: point) }
        }
        path.closeSubpath()
        return path
    }

    /// Five-pointed star built from ten alternating tips; the Kotlin version uses one subpath per
    /// spike, which only becomes visible once the outline is stroked.
    private func starOutline(_ radius: Double) -> Path {
        var path = Path()
        let inner = radius * 0.5
        var angle = (Double.pi / 2) * 3 + Double.pi
        for _ in 0..<5 {
            path.move(to: CGPoint(x: cos(angle) * radius, y: sin(angle) * radius))
            angle += Double.pi / 5
            path.addLine(to: CGPoint(x: cos(angle) * inner, y: sin(angle) * inner))
            angle += Double.pi / 5
        }
        path.closeSubpath()
        return path
    }

    // MARK: - Hold ripples

    /// Expanding ring ripples while a button hold is pressed.
    func renderHoldPressEffects(_ notes: [ChartNote], holdEndMap: [String: ChartNote],
                                currentTimeMs: Double, range: Range<Int>) {
        for index in range {
            guard notes.indices.contains(index) else { continue }
            let hold = notes[index]
            guard let end = holdEndMap["\(hold.position):\(hold.timing)"] else { continue }
            if currentTimeMs < hold.timingMs || currentTimeMs >= end.timingMs { continue }
            let origin = notePainter.positionOnRing(hold.position)
            renderPressRipple(origin: origin, startMs: hold.timingMs, endMs: end.timingMs,
                              currentTimeMs: currentTimeMs)
        }
    }

    /// One emitted ripple ring: peak outer radius and its (already faded) alpha.
    private nonisolated struct RippleWave {
        var peakRadius: Double
        var alpha: Double
    }

    /// Ripple rings at an arbitrary sensor/button origin (shared by touch holds).
    private func renderPressRipple(origin: ChartPoint, startMs: Double, endMs: Double,
                                   currentTimeMs: Double) {
        // Kotlin spells this `currentTimeMs !in startMs..<endMs`; comparing directly also keeps a
        // reversed window from trapping on the Swift range.
        if currentTimeMs < startMs || currentTimeMs >= endMs { return }
        if context.radius <= 0 { return }

        // Paint blend mode is not canvas state, so the Kotlin `withSave` wrapper around this block
        // only has to be mirrored by resetting the paint afterwards.
        effectPaint.blendMode = .screen
        let elapsedSec = (currentTimeMs - startMs) / 1000.0
        let firstCycle = Int(((elapsedSec - Self.rippleLifetime) / Self.rippleCycle).rounded(.down))
        let lastCycle = Int((elapsedSec / Self.rippleCycle).rounded(.down))
        let radiusPx = Double(context.radius)

        var waves: [RippleWave] = []
        waves.reserveCapacity(4)
        if firstCycle <= lastCycle {
            for cycle in firstCycle...lastCycle {
                for emitOffset in Self.rippleEmitOffsets {
                    let age = elapsedSec - (Double(cycle) * Self.rippleCycle + emitOffset)
                    if age < 0 || age >= Self.rippleLifetime { continue }
                    let peakRadius = (Self.rippleBirthRadiusRatio
                        + Self.rippleGrowthRatioPerSec * age) * radiusPx
                    let alpha = min(1.0, (Self.rippleLifetime - age)
                        / (Self.rippleLifetime - Self.rippleAttack))
                    if peakRadius < 0.75 || alpha <= 0.02 { continue }
                    waves.append(RippleWave(peakRadius: peakRadius, alpha: alpha))
                }
            }
        }
        // Largest ring first so younger, smaller rings sit on top, like the reference sort.
        for wave in stableSorted(waves, by: { $0.peakRadius > $1.peakRadius }) {
            drawRippleRing(origin: origin, peakRadius: wave.peakRadius, alpha: wave.alpha)
        }
        effectPaint.blendMode = .normal
    }

    /// One ring: a radial gradient band from `RIPPLE_INNER_SCALE` to `RIPPLE_OUTER_SCALE`.
    private func drawRippleRing(origin: ChartPoint, peakRadius: Double, alpha: Double) {
        let inner = peakRadius * Self.rippleInnerScale
        let outer = peakRadius * Self.rippleOuterScale
        if outer - inner < 0.5 || alpha <= 0.02 { return }
        let bandStart = CGFloat(inner / outer)
        var colors: [ChartColor] = []
        var positions: [CGFloat] = []
        colors.reserveCapacity(Self.rippleRingProfile.count + 2)
        positions.reserveCapacity(Self.rippleRingProfile.count + 2)
        // Transparent centre, and transparent up to the inner edge of the band.
        colors.append(ChartColor(argb: 0))
        positions.append(0)
        colors.append(ChartColor(argb: 0))
        positions.append(bandStart)
        let brightness = alpha / Self.ripplePeakAlpha
        for stop in Self.rippleRingProfile {
            let profileValue = stop[1]
            let whiten = Self.rippleCoreWhiten * profileValue * brightness * brightness
            let red = Self.rippleEdgeR + (1 - Self.rippleEdgeR) * whiten
            let green = Self.rippleEdgeG + (1 - Self.rippleEdgeG) * whiten
            let blue = Self.rippleEdgeB + (1 - Self.rippleEdgeB) * whiten
            colors.append(argbColor(alpha: profileValue * alpha, red: red, green: green, blue: blue))
            positions.append(bandStart + (1 - bandStart) * CGFloat(stop[0]))
        }
        effectPaint.shading = .radial(
            center: CGPoint(x: origin.x, y: origin.y),
            radius: CGFloat(outer),
            colors: colors,
            stops: positions
        )
        effectPaint.alpha = 1
        context.canvas.drawCircle(centerX: origin.x, centerY: origin.y, radius: outer, effectPaint)
        effectPaint.shading = nil
    }

    // MARK: - Touch hold release

    /// Ripples while a touch hold is held, then one hexagon stamp when it is released.
    func renderTouchHoldEffects(_ notes: [ChartNote], currentTimeMs: Double, range: Range<Int>) {
        for index in range {
            guard notes.indices.contains(index) else { continue }
            let note = notes[index]
            let endMs = note.timingMs + note.durationMs
            if currentTimeMs >= note.timingMs && currentTimeMs < endMs {
                let origin = touchPainter.touchPosition(note.touchPosition)
                renderPressRipple(origin: origin, startMs: note.timingMs, endMs: endMs,
                                  currentTimeMs: currentTimeMs)
            }
            if currentTimeMs >= endMs
                && currentTimeMs < endMs + ChartConstants.noteHitEffectDurationMs {
                let origin = touchPainter.touchPosition(note.touchPosition)
                // The centre sensor has no meaningful outward direction, so it uses a fixed angle.
                let angle = note.touchPosition == "C"
                    ? ChartConstants.touchHoldCentreBurstAngle
                    : atan2(Double(context.centerY) - origin.y, Double(context.centerX) - origin.x)
                renderHitEffectAt(
                    x: origin.x,
                    y: origin.y,
                    angle: angle,
                    color: ChartConstants.Colors.hitEffectGold,
                    progress: (currentTimeMs - endMs) / ChartConstants.noteHitEffectDurationMs,
                    type: .hexagon
                )
            }
        }
    }

    // MARK: - Touch hit burst

    /// Center ring plus star sparks for a touch hit; additive blending.
    func renderTouchHitEffects(_ touches: [ChartNote], currentTimeMs: Double) {
        if touches.isEmpty { return }
        // `PANEL_RADIUS_UNITS.toFloat()`: the unit stays Float in Kotlin, so keep the same rounding.
        let unitPx = context.radius / Float(ChartConstants.panelRadiusUnits)
        if unitPx <= 0 { return }
        let lifeMs = 500.0
        let windowStart = currentTimeMs - lifeMs
        var lo = 0
        var hi = touches.count
        while lo < hi {
            let mid = (lo + hi) >> 1
            if touches[mid].timingMs < windowStart { lo = mid + 1 } else { hi = mid }
        }
        if lo >= touches.count { return }

        var lastByPos: [String: Double] = [:]
        for index in lo..<touches.count {
            let note = touches[index]
            if note.timingMs > currentTimeMs { break }
            if note.kind != .touch { continue }
            if let previous = lastByPos[note.touchPosition] {
                if note.timingMs > previous { lastByPos[note.touchPosition] = note.timingMs }
            } else {
                lastByPos[note.touchPosition] = note.timingMs
            }
        }

        // Kotlin sets `effectPaint.blendMode = PLUS` for the whole loop. The stroke paint is
        // deliberately left on normal blending: the reference never sets it either, so the
        // frame-3 sparkle outlines are composited source-over.
        effectPaint.blendMode = .plusLighter
        for index in lo..<touches.count {
            let note = touches[index]
            if note.timingMs > currentTimeMs { break }
            if note.kind != .touch { continue }
            if let latest = lastByPos[note.touchPosition], latest > note.timingMs { continue }
            let age = (currentTimeMs - note.timingMs) / 1000.0
            if age < 0 || age >= 0.5 { continue }
            let origin = touchPainter.touchPosition(note.touchPosition)
            // Per-note deterministic seed: timestamp in microseconds, mixed with the sensor name.
            // There is no unseeded `Random()` anywhere in the Kotlin painter, and this LCG/xor hash
            // has to wrap on 32 bits to stay bit-identical.
            var seed = roundToInt32(note.timingMs * 1000)
            for (charIndex, unit) in note.touchPosition.utf16.enumerated() {
                seed ^= Int32(bitPattern: UInt32(unit) &* UInt32(charIndex + 1) &* 73_856_093)
            }
            drawTouchCenterRing(origin: origin, age: age, unitPx: unitPx)
            drawStarBurst(origin: origin, age: age, unitPx: unitPx, seedBase: seed,
                          count: Self.smallCount, shapeR: Self.smallShapeR,
                          startSize: Self.smallSize, startSpeed: 0, clampSpeed: 0)
            drawStarBurst(origin: origin, age: age, unitPx: unitPx,
                          seedBase: seed ^ Int32(bitPattern: 0x85EB_CA77),
                          count: Self.roundCount, shapeR: Self.roundShapeR,
                          startSize: Self.roundSize, startSpeed: Self.roundStartSpeed,
                          clampSpeed: Self.roundClampSpeed)
        }
        effectPaint.blendMode = .normal
    }

    private func drawTouchCenterRing(origin: ChartPoint, age: Double, unitPx: Float) {
        let life = age / 0.5
        let sizeMul = max(0.0, evalHermite(Self.ringSizeKeys, life))
        var diameter = Self.ringStartSize * sizeMul
        if diameter > Self.ringMaxSize { diameter = Self.ringMaxSize }
        let radius = diameter * Double(unitPx) / 2
        if radius < 0.5 { return }
        let alpha = evalKeys(Self.ringAlphaKeys, life)
        if alpha <= 0.01 { return }

        var colors: [ChartColor] = []
        var positions: [CGFloat] = []
        let last = Self.touchRingAlphaProfile.count - 1
        colors.reserveCapacity(last + 2)
        positions.reserveCapacity(last + 2)
        for index in 0...last {
            let stopAlpha = min(1.0, max(0.0, Self.touchRingAlphaProfile[index] * alpha))
            colors.append(Self.touchHitYellow.withAlpha(byteAlpha(stopAlpha)))
            positions.append((CGFloat(index) / CGFloat(last)) * 0.96)
        }
        // The profile ends on a fully transparent ring edge at radius 1.
        colors.append(Self.touchHitYellow.withAlpha(0))
        positions.append(1)

        effectPaint.shading = .radial(
            center: CGPoint(x: origin.x, y: origin.y),
            radius: CGFloat(radius),
            colors: colors,
            stops: positions
        )
        effectPaint.alpha = 1
        context.canvas.drawCircle(centerX: origin.x, centerY: origin.y, radius: radius, effectPaint)
        effectPaint.shading = nil
    }

    private func drawStarBurst(origin: ChartPoint, age: Double, unitPx: Float, seedBase: Int32,
                               count: Int, shapeR: Double, startSize: Double, startSpeed: Double,
                               clampSpeed: Double) {
        let life = age / 0.5
        let sizeMul = max(0.0, evalHermite(Self.starSizeKeys, life))
        if sizeMul <= 0.02 { return }
        let alpha = min(1.0, sizeMul * 1.05)
        let travel = (startSpeed > 0 && clampSpeed > 0)
            ? clampedTravel(age: age, startSpeed: startSpeed, clampSpeed: clampSpeed)
            : 0.0

        for index in 0..<count {
            let base = seedBase &+ Int32(index * 4)
            let h1 = hash01(base &+ 1)
            let h2 = hash01(base &+ 2)
            let h3 = hash01(base &+ 3)
            let h4 = hash01(base &+ 4)
            let angle = h1 * Double.pi * 2
            let cosA = cos(angle)
            let sinA = sin(angle)
            let px = origin.x + (cosA * shapeR + cosA * travel) * Double(unitPx)
            let py = origin.y + (sinA * shapeR + sinA * travel) * Double(unitPx)
            let size = startSize * sizeMul * Double(unitPx) * (0.9 + 0.2 * h2)
            let outerR = size * 0.55
            if outerR < 0.5 { continue }
            let frame = min(3, Int((h4 * 4).rounded(.down)))
            drawSparkle(x: px, y: py, outerR: outerR, rotation: h3 * Double.pi * 2, alpha: alpha,
                        frame: frame)
        }
    }

    /// One burst sparkle: a four-point star for frames 0...2, a double outline for frame 3.
    private func drawSparkle(x: Double, y: Double, outerR: Double, rotation: Double, alpha: Double,
                             frame: Int) {
        let canvas = context.canvas
        canvas.withTranslation(CGFloat(x), CGFloat(y)) { inner in
            inner.rotate(degrees: CGFloat(toDegrees(rotation)), pivotX: 0, pivotY: 0)
            if frame <= 2 {
                let tipR: Double
                switch frame {
                case 0: tipR = outerR * 1.08
                case 1: tipR = outerR
                default: tipR = outerR * 0.94
                }
                let waistR = frame <= 1 ? outerR * 0.23 : outerR * 0.19
                let path = sparklePath(tipR: tipR, waistR: waistR)
                effectPaint.shading = .radial(
                    center: .zero,
                    radius: CGFloat(tipR),
                    colors: [
                        Self.touchHitYellow.withAlpha(byteAlpha(alpha * 0.95)),
                        Self.touchHitYellow.withAlpha(byteAlpha(alpha * 0.72)),
                        Self.touchHitYellow.withAlpha(0),
                    ],
                    stops: [0, 0.82, 1]
                )
                effectPaint.alpha = 1
                inner.drawPath(path, effectPaint)
                effectPaint.shading = nil
            } else {
                effectStroke.strokeJoin = .round
                effectStroke.strokeCap = .round
                effectStroke.strokeWidth = max(1, CGFloat(outerR * 0.13))
                effectStroke.color = ChartConstants.Colors.hitEffectGold
                effectStroke.alpha = byteAlpha(alpha * 0.22)
                inner.drawPath(sparklePath(tipR: outerR * 1.08, waistR: outerR * 0.2 * 1.08),
                               effectStroke)
                effectStroke.strokeWidth = max(0.8, CGFloat(outerR * 0.055))
                effectStroke.color = ChartColor(argb: 0xFFFF_FEF0)
                effectStroke.alpha = byteAlpha(alpha * 0.88)
                inner.drawPath(sparklePath(tipR: outerR, waistR: outerR * 0.2), effectStroke)
            }
        }
    }

    /// Four-petal star with concave waists, built from four quadratic corners.
    private func sparklePath(tipR: Double, waistR: Double) -> Path {
        var path = Path()
        for index in 0..<4 {
            let aTip = Double(index) * Double.pi / 2 - Double.pi / 2
            let aWaist = aTip + Double.pi / 4
            let aNext = aTip + Double.pi / 2
            let tip = CGPoint(x: cos(aTip) * tipR, y: sin(aTip) * tipR)
            if index == 0 { path.move(to: tip) } else { path.addLine(to: tip) }
            path.addQuadCurve(
                to: CGPoint(x: cos(aNext) * tipR, y: sin(aNext) * tipR),
                control: CGPoint(x: cos(aWaist) * waistR, y: sin(aWaist) * waistR)
            )
        }
        path.closeSubpath()
        return path
    }

    // MARK: - Fireworks

    /// Petal burst for the most recent firework touch, with the shrinking hole punched through it.
    func renderTouchFireworks(_ touches: [ChartNote], currentTimeMs: Double) {
        if touches.isEmpty { return }
        var lo = 0
        var hi = touches.count
        while lo < hi {
            let mid = (lo + hi) >> 1
            if fireworkTrigger(touches[mid]) <= currentTimeMs { lo = mid + 1 } else { hi = mid }
        }
        guard lo > 0 else { return }
        let latest = touches[lo - 1]
        let ageMs = currentTimeMs - fireworkTrigger(latest)
        if ageMs >= Self.fireworkDurationMs { return }
        let tSec = ageMs / 1000.0
        if tSec >= Self.fireworkEndSec { return }

        let scale: Double
        if tSec < 0.1 {
            scale = 0
        } else if tSec < 0.5 {
            let u = (tSec - 0.1) / 0.4
            scale = (1 - pow(1 - u, 3.0)) * Self.fireworkScalePeak
        } else {
            scale = Self.fireworkScalePeak
        }
        if scale <= 0 { return }

        let position: ChartPoint
        switch latest.kind {
        case .touch, .touchHoldStart: position = touchPainter.touchPosition(latest.touchPosition)
        default: return
        }
        let rotation = (ageMs / Self.fireworkDurationMs) * (72 * Double.pi / 180)

        if tSec > Self.fireworkHoleStartSec {
            let outerR = Double(context.radius) / 4.5 * scale
            let linearU = min(1.0, (tSec - Self.fireworkHoleStartSec)
                / (Self.fireworkEndSec - Self.fireworkHoleStartSec))
            let u = linearU * linearU * (3 - 2 * linearU)
            let solidR = outerR * (0.05 + u * 0.95)
            let totalR = solidR + outerR * 0.5
            let maskAlpha = min(1.0, linearU / 0.2)
            if totalR <= 0 || maskAlpha <= 0 { return }

            // The Kotlin painter composites the burst into a scratch bitmap and punches a growing
            // feathered hole through it with `PorterDuff.Mode.DST_OUT`, so the hole only erases the
            // burst and never the stage behind it. iOS gets the same isolation from layers: the
            // burst goes into a transparent layer (clipped to the scratch bitmap's square), and the
            // hole is composited into that layer with `.destinationOut`.
            let fireworkHalf = Double(
                Int(Double(context.radius) / 4.5 * Self.fireworkScalePeak * 1.05) + 2
            )
            let canvas = context.canvas
            canvas.drawLayer { burstCanvas in
                burstCanvas.clipRect(CGRect(
                    x: position.x - fireworkHalf,
                    y: position.y - fireworkHalf,
                    width: fireworkHalf * 2,
                    height: fireworkHalf * 2
                ))
                drawFirework(burstCanvas, position: position, scale: scale, rotation: rotation,
                             tSec: tSec, alpha: 1)
                burstCanvas.drawLayer(blendMode: .destinationOut) { maskCanvas in
                    var mask = ChartPaint()
                    mask.style = .fill
                    mask.shading = .radial(
                        center: CGPoint(x: position.x, y: position.y),
                        radius: CGFloat(totalR),
                        colors: [
                            ChartConstants.Colors.black,
                            ChartConstants.Colors.black,
                            ChartConstants.Colors.black.withAlpha(0),
                        ],
                        stops: [0, CGFloat(solidR / totalR), 1]
                    )
                    mask.alpha = maskAlpha
                    maskCanvas.drawCircle(centerX: position.x, centerY: position.y,
                                          radius: totalR, mask)
                }
            }
        } else {
            drawFirework(context.canvas, position: position, scale: scale, rotation: rotation,
                         tSec: tSec, alpha: 1)
        }
    }

    /// Cached petal outlines and gradients at radius 1, keyed by the burst alpha.
    private func ensurePetalCaches(alpha: Double) {
        if petalPaths[0] == nil {
            let count = Self.fireworkPetalColors.count
            let halfWidth = Double.pi / Double(count) * 0.45
            for index in 0..<count {
                let angle = Double(index) / Double(count) * Double.pi * 2
                var path = Path()
                path.move(to: .zero)
                path.addLine(to: CGPoint(x: cos(angle - halfWidth), y: sin(angle - halfWidth)))
                path.addLine(to: CGPoint(x: cos(angle + halfWidth), y: sin(angle + halfWidth)))
                path.closeSubpath()
                petalPaths[index] = path
            }
        }
        if petalShaderAlpha == alpha { return }
        let baseAlpha = min(255, max(0, Int(0x96 * alpha)))
        let shadeAlpha = Double(baseAlpha) / 255.0
        for index in Self.fireworkPetalColors.indices {
            let start = Self.fireworkPetalColors[index]
            petalShaders[index] = .radial(
                center: .zero,
                radius: 1,
                colors: [start.withAlpha(shadeAlpha), start.withAlpha(shadeAlpha), start.withAlpha(0)],
                stops: [0, 0.9, 1]
            )
        }
        petalShaderAlpha = alpha
    }

    private func fireworkTrigger(_ note: ChartNote) -> Double {
        note.kind == .touchHoldStart ? note.timingMs + note.durationMs : note.timingMs
    }

    private func drawFirework(_ canvas: ChartCanvas, position: ChartPoint, scale: Double,
                              rotation: Double, tSec: Double, alpha: Double) {
        let outerR = Double(context.radius) / 4.5 * scale
        if outerR <= 0 { return }
        canvas.withTranslation(CGFloat(position.x), CGFloat(position.y)) { inner in
            inner.rotate(degrees: CGFloat(toDegrees(rotation)), pivotX: 0, pivotY: 0)

            ensurePetalCaches(alpha: alpha)
            // Petal outlines and gradients are cached at radius 1; the canvas scale maps them up, so
            // a frame does not build a dozen paths and shaders. `Canvas.scale` has no shim method,
            // so the transform is applied to the wrapped `GraphicsContext` directly.
            inner.graphics.scaleBy(x: CGFloat(outerR), y: CGFloat(outerR))
            for index in Self.fireworkPetalColors.indices {
                guard let path = petalPaths[index] else { continue }
                effectPaint.shading = petalShaders[index]
                effectPaint.alpha = 1
                inner.drawPath(path, effectPaint)
            }
            effectPaint.shading = nil
        }

        drawFireworkBalls(canvas, position: position, growT: tSec - 0.1, alpha: alpha)
    }

    /// The two additive glow balls at the centre of the burst.
    private func drawFireworkBalls(_ canvas: ChartCanvas, position: ChartPoint, growT: Double,
                                   alpha: Double) {
        if growT < 0 { return }
        let baseRadius = Double(context.radius) / 4.5
        let unitRadius: Double = 1
        canvas.withSave { inner in
            effectPaint.blendMode = .plusLighter

            let bigGrow = 0.06
            let bigRise = 0.04
            let bigRMax = baseRadius * 1.3
            let bigDecayEnd = Self.fireworkDurationMs / 1000.0 - 0.1
            let bigPeakAlpha = 0.7
            let big: (radius: Double, alpha: Double)
            if growT < bigGrow {
                big = (growT / bigGrow * bigRMax, bigPeakAlpha * min(1.0, growT / bigRise))
            } else {
                let d = (growT - bigGrow) / (bigDecayEnd - bigGrow)
                big = (bigRMax * (1 - d), bigPeakAlpha * (1 - d))
            }
            if big.alpha > 0 && big.radius > 0 {
                effectPaint.shading = unitBallShaderBig
                effectPaint.alpha = byteAlpha(big.alpha * alpha)
                inner.withTranslation(CGFloat(position.x), CGFloat(position.y)) { ball in
                    ball.graphics.scaleBy(x: CGFloat(big.radius), y: CGFloat(big.radius))
                    ball.drawCircle(centerX: 0, centerY: 0, radius: unitRadius, effectPaint)
                }
            }

            let smallGrow = 0.04
            let smallRise = 0.02
            let smallRMax = baseRadius * 0.55
            let smallDecayEnd = 0.55
            let smallPeakAlpha = 0.6
            let small: (radius: Double, alpha: Double)
            if growT < smallGrow {
                small = (growT / smallGrow * smallRMax, smallPeakAlpha * min(1.0, growT / smallRise))
            } else if growT < smallDecayEnd {
                let d = (growT - smallGrow) / (smallDecayEnd - smallGrow)
                small = (smallRMax * (1 - d), smallPeakAlpha * (1 - d))
            } else {
                small = (0, 0)
            }
            if small.alpha > 0 && small.radius > 0 {
                effectPaint.shading = unitBallShaderSmall
                effectPaint.alpha = byteAlpha(small.alpha * alpha)
                inner.withTranslation(CGFloat(position.x), CGFloat(position.y)) { ball in
                    ball.graphics.scaleBy(x: CGFloat(small.radius), y: CGFloat(small.radius))
                    ball.drawCircle(centerX: 0, centerY: 0, radius: unitRadius, effectPaint)
                }
            }

            effectPaint.shading = nil
            effectPaint.blendMode = .normal
        }
    }

    // MARK: - Interpolation

    private nonisolated struct HermiteKey {
        var t: Double
        var v: Double
        var slope: Double
    }

    private nonisolated struct Keyframe {
        var t: Double
        var v: Double
    }

    /// Cubic Hermite spline through the key list, mirroring the reference curve evaluation.
    private func evalHermite(_ keys: [HermiteKey], _ t: Double) -> Double {
        guard let first = keys.first, let last = keys.last else { return 0 }
        if t <= first.t { return first.v }
        if t >= last.t { return last.v }
        for index in 0..<(keys.count - 1) {
            let a = keys[index]
            let b = keys[index + 1]
            if t < a.t || t > b.t { continue }
            let span = b.t - a.t
            let dt = span == 0 ? 1.0 : span
            let u = (t - a.t) / dt
            let u2 = u * u
            let u3 = u2 * u
            return (2 * u3 - 3 * u2 + 1) * a.v
                + (u3 - 2 * u2 + u) * a.slope * dt
                + (-2 * u3 + 3 * u2) * b.v
                + (u3 - u2) * b.slope * dt
        }
        return last.v
    }

    /// Piecewise linear keyframes.
    private func evalKeys(_ keys: [Keyframe], _ t: Double) -> Double {
        guard let first = keys.first, let last = keys.last else { return 0 }
        if t <= first.t { return first.v }
        if t >= last.t { return last.v }
        for index in 0..<(keys.count - 1) {
            let a = keys[index]
            let b = keys[index + 1]
            if t >= a.t && t <= b.t {
                let span = b.t - a.t
                let u = (t - a.t) / (span == 0 ? 1.0 : span)
                return a.v + (b.v - a.v) * u
            }
        }
        return last.v
    }

    /// Distance a sparkle travels once its speed has been clamped by the magnitude curve.
    private func clampedTravel(age: Double, startSpeed: Double, clampSpeed: Double) -> Double {
        if age <= 0 { return 0 }
        let steps = max(6, Int((age * 60).rounded(.up)))
        let dt = age / Double(steps)
        var speed = startSpeed
        var distance = 0.0
        for index in 0..<steps {
            let life = min(1.0, (Double(index) + 0.5) * dt / 0.5)
            let maxSpeed = max(0.0, evalHermite(Self.clampMagKeys, life) * clampSpeed)
            if speed > maxSpeed { speed = maxSpeed }
            distance += speed * dt
        }
        return distance
    }

    /// 32-bit LCG + xor-shift hash in 0..<1, bit-identical to the Kotlin `hash01`.
    ///
    /// Kotlin's `Int` arithmetic wraps silently; Swift traps on overflow, so every step uses the
    /// wrapping operators to keep the same bit pattern.
    private func hash01(_ seed: Int32) -> Double {
        var x = seed &* 1_664_525 &+ 1_013_904_223
        x ^= Int32(bitPattern: UInt32(bitPattern: x) >> 16)
        return Double(UInt32(bitPattern: x)) / 4_294_967_296.0
    }

    // MARK: - Small math / paint helpers

    /// `Math.toDegrees`, which the Kotlin painter uses to feed the degree-based canvas shims.
    private func toDegrees(_ radians: Double) -> Double { radians * 180.0 / Double.pi }

    /// `Double.roundToInt()`: half up (towards positive infinity) with saturation.
    private func roundToInt32(_ value: Double) -> Int32 {
        if value > Double(Int32.max) { return Int32.max }
        if value < Double(Int32.min) { return Int32.min }
        return Int32((value + 0.5).rounded(.down))
    }

    /// `Paint.setAlpha` byte: the Kotlin painters truncate `255 * alpha` to an int.
    private func byteAlpha(_ alpha: Double) -> Double {
        Double(min(255, max(0, Int(alpha * 255)))) / 255.0
    }

    /// One channel of `Color.argb(...)`, truncating like the Kotlin integer conversion.
    private func rgbByte(_ channel: Double) -> Double {
        Double(min(255, max(0, Int(channel * 255)))) / 255.0
    }

    private func argbColor(alpha: Double, red: Double, green: Double, blue: Double) -> ChartColor {
        ChartColor(red: rgbByte(red), green: rgbByte(green), blue: rgbByte(blue),
                   alpha: byteAlpha(alpha))
    }

    // MARK: - Constants

    private nonisolated enum StampShape {
        case star
        case hexagon
    }

    private static let touchHitYellow = ChartColor(argb: 0xFFFF_FF00)

    /// Cached stamp outlines are bounded by the radius range; this rarely fills.
    private static let stampPathCacheLimit = 256

    private static let rippleCycle = (16_000.0 / 60.0) / 1000.0
    private static let rippleEmitOffsets = [0.0, 4.0 / 60.0, 8.0 / 60.0]
    private static let rippleLifetime = 11.0 / 60.0
    private static let rippleAttack = 1.0 / 60.0
    private static let ripplePeakAlpha = 1.0
    private static let rippleBirthRadiusRatio = 0.1355
    private static let rippleGrowthRatioPerSec = 0.735
    private static let rippleInnerScale = 0.8
    private static let rippleOuterScale = 1.2
    private static let rippleCoreWhiten = 0.58
    private static let rippleEdgeR = 1.0
    private static let rippleEdgeG = 0.889
    private static let rippleEdgeB = 0.213

    /// Ring profile as `[position within the band, intensity]`.
    private static let rippleRingProfile: [[Double]] = [
        [0.0, 0.0],
        [0.063, 0.01],
        [0.125, 0.08],
        [0.188, 0.17],
        [0.25, 0.33],
        [0.313, 0.52],
        [0.375, 0.68],
        [0.438, 0.85],
        [0.5, 1.0],
        [0.563, 0.88],
        [0.625, 0.71],
        [0.688, 0.56],
        [0.75, 0.37],
        [0.813, 0.2],
        [0.875, 0.09],
        [0.938, 0.03],
        [1.0, 0.01],
    ]

    /// Touch-hit burst geometry is expressed in units of `ROOT_SCALE` (the reference's 70 px panel
    /// unit), then multiplied by the current pixels-per-unit so the burst scales with the stage.
    private static let rootScale = 70.0
    private static let ringStartSize = 2.5 * rootScale
    private static let ringMaxSize = 540.0
    private static let smallCount = 8
    private static let smallShapeR = 0.5 * rootScale
    private static let smallSize = 0.3 * rootScale
    private static let roundCount = 8
    private static let roundShapeR = 0.6 * rootScale
    private static let roundSize = 0.6 * rootScale
    private static let roundStartSpeed = 10 * rootScale
    private static let roundClampSpeed = 2.0 * rootScale

    private static let ringSizeKeys: [HermiteKey] = [
        HermiteKey(t: 0.0, v: 0.0, slope: 10.02783),
        HermiteKey(t: 0.0734887, v: 0.482223, slope: 2.93804),
        HermiteKey(t: 0.3209003, v: 0.834577, slope: 0.592962),
        HermiteKey(t: 1.0, v: 1.0, slope: 0.180534),
    ]

    private static let ringAlphaKeys: [Keyframe] = [
        Keyframe(t: 0.0, v: 1.0),
        Keyframe(t: 5484 / 65535.0, v: 1.0),
        Keyframe(t: 18591 / 65535.0, v: 0.141176),
        Keyframe(t: 31029 / 65535.0, v: 0.031373),
        Keyframe(t: 45607 / 65535.0, v: 0.0),
        Keyframe(t: 1.0, v: 0.0),
    ]

    private static let starSizeKeys: [HermiteKey] = [
        HermiteKey(t: 0.0, v: 0.755245, slope: 2.38315),
        HermiteKey(t: 0.127286, v: 1.0, slope: 0.061337),
        HermiteKey(t: 0.272332, v: 0.892134, slope: -0.927502),
        HermiteKey(t: 0.540943, v: 0.811183, slope: -0.114026),
        HermiteKey(t: 1.0, v: 0.0, slope: 0.036313),
    ]

    private static let clampMagKeys: [HermiteKey] = [
        HermiteKey(t: 0.0, v: 1.0, slope: -2.726415),
        HermiteKey(t: 1.0, v: 0.0, slope: 0.0),
    ]

    private static let touchRingAlphaProfile: [Double] = [
        0.666, 0.667, 0.671, 0.679, 0.685, 0.693, 0.7, 0.709, 0.718, 0.727, 0.736, 0.744,
        0.753, 0.763, 0.772, 0.781, 0.791, 0.8, 0.809, 0.818, 0.826, 0.835, 0.843, 0.851,
        0.858, 0.866, 0.872, 0.879, 0.887, 0.896, 0.905, 0.913, 0.922, 0.93, 0.938, 0.946,
        0.953, 0.959, 0.964, 0.969, 0.974, 0.977, 0.981, 0.984, 0.986, 0.987, 0.989, 0.993,
        0.998, 1.0, 0.896, 0.584, 0.374, 0.248, 0.155, 0.087, 0.038, 0.008, 0.002, 0.0,
        0.0, 0.0,
    ]

    private static let fireworkDurationMs = 1333.0
    private static let fireworkScalePeak = 5.0
    private static let fireworkHoleStartSec = 0.6
    private static let fireworkEndSec = 1.1

    private static let fireworkPetalColors: [ChartColor] = [
        ChartColor(argb: 0xFFFF_B3BA), ChartColor(argb: 0xFFFF_D0A8), ChartColor(argb: 0xFFFF_FAB8),
        ChartColor(argb: 0xFFD8_F0A8), ChartColor(argb: 0xFFB8_E0B0), ChartColor(argb: 0xFFA8_E0CC),
        ChartColor(argb: 0xFFA8_C8E8), ChartColor(argb: 0xFFB8_B0E8), ChartColor(argb: 0xFFD0_B0E8),
        ChartColor(argb: 0xFFF0_B0D8), ChartColor(argb: 0xFFFF_C9A8), ChartColor(argb: 0xFFFF_EFA8),
        ChartColor(argb: 0xFFC8_E8A8), ChartColor(argb: 0xFFA8_E0BC), ChartColor(argb: 0xFFA8_CCE8),
    ]
}
