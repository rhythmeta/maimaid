import CoreGraphics
import Foundation
import SwiftUI

/// Touch notes (petal diamonds), touch holds (progress ring), multi-tap borders.
///
/// Touch parts cannot be drawn note by note: the reference queues every visible touch and resolves
/// one global z-order across sensors (`TouchOrder`) before any petal is painted, so a touch that is
/// closer to the centre covers the outer rings. `renderTouch` only registers work; `flush` paints.
nonisolated final class TouchPainter: BasePainter {
    private var gradientPaint: ChartPaint = {
        var paint = ChartPaint()
        paint.style = .fill
        return paint
    }()

    private var borderPaint: ChartPaint = {
        var paint = ChartPaint()
        paint.style = .stroke
        return paint
    }()

    /// One queued touch draw command; parts are emitted in global z-order by `flush`.
    nonisolated struct TouchDrawCommand {
        var sort: TouchOrder.TouchSortNote
        var draw: (TouchOrder.Part) -> Void
    }

    /// Precomputed geometry of the four petals of one touch note.
    private nonisolated struct PetalGeometry {
        var index: Int
        var tip: ChartPoint
        var left: ChartPoint
        var right: ChartPoint
        var petalX: Double
        var petalY: Double
        var innerTip: ChartPoint
        var innerLeft: ChartPoint
        var innerRight: ChartPoint
    }

    func touchApproachTimeMs(_ hiSpeed: Double?) -> Double {
        noteApproachTimeMs(hiSpeed) * ChartConstants.touchApproachMultiplier
    }

    /// Queues one touch note; call `flush` after all visible touches are collected.
    func renderTouch(_ note: ChartNote, currentTimeMs: Double, isSimultaneous: Bool,
                     queue: inout [TouchDrawCommand], sourceNoteIndex: Int?) {
        // The reference switches on the note class: touch and touch-hold-start only.
        guard note.kind == .touch || note.kind == .touchHoldStart else { return }
        let positionName = note.touchPosition
        let isHold = note.kind == .touchHoldStart
        let timeDiff = note.timingMs - currentTimeMs
        let approachTime = touchApproachTimeMs(note.hiSpeed)

        var visibilityWindow = 50.0
        if isHold { visibilityWindow = note.durationMs + 50.0 }
        if timeDiff > approachTime || timeDiff < -visibilityWindow { return }

        // Fade in over the last 5 % of the approach window.
        var alpha = 1.0
        if timeDiff > approachTime * 0.95 {
            alpha = 1 - (timeDiff - approachTime * 0.95) / (approachTime * 0.05)
        }

        // Petals fade out over the final 150 ms before the hit.
        var petalAlpha = 1.0
        if timeDiff > 0 && timeDiff < approachTime {
            let remaining = approachTime - timeDiff
            if remaining < 150 { petalAlpha = remaining / 150 }
        }

        let openDist = Double(scaleByRadius(ChartConstants.touchPetalOpenRatio)) * 1.1
        let closedDist = Double(scaleByRadius(ChartConstants.touchPetalClosedRatio)) * 1.3
        var petalDist = openDist

        if timeDiff > 0 && timeDiff <= approachTime {
            let progress = 1 - timeDiff / approachTime
            let eased = progress * progress * progress * progress
            petalDist = openDist - (openDist - closedDist) * eased
        } else if timeDiff <= 0 {
            petalDist = closedDist
        }

        let origin = touchPosition(positionName)
        let isHoldActive = isHold && timeDiff < 0
        let canvas = context.canvas

        let cornerRadius = Double(scaleByRadius(8.0 / 300.0))
        let innerCornerRadius = cornerRadius * 0.4
        let strokeWidth = noteStrokeWidth()
        let ddr = ddrColor(note.timing)
        let petalBaseAngles = [-Double.pi / 4, Double.pi / 4, 3 * Double.pi / 4,
                               -3 * Double.pi / 4]
        let angleOffset = isHold ? 0.0 : -Double.pi / 4
        let combinedAlpha = alpha * petalAlpha

        var petals: [PetalGeometry] = []
        petals.reserveCapacity(4)
        for index in 0..<4 {
            let petalAngle = petalBaseAngles[index] + angleOffset
            let petalX = origin.x + cos(petalAngle) * petalDist
            let petalY = origin.y + sin(petalAngle) * petalDist
            let tipAngle = petalAngle + Double.pi
            let leftAngle = petalAngle + Double.pi / 2
            let rightAngle = petalAngle - Double.pi / 2

            let tip = ChartPoint(
                petalX + cos(tipAngle) * closedDist,
                petalY + sin(tipAngle) * closedDist
            )
            let left = ChartPoint(
                petalX + cos(leftAngle) * closedDist,
                petalY + sin(leftAngle) * closedDist
            )
            let right = ChartPoint(
                petalX + cos(rightAngle) * closedDist,
                petalY + sin(rightAngle) * closedDist
            )
            // Inner diamond is the outer triangle scaled towards its centroid.
            let innerRatio = 0.4
            let cx = (tip.x + left.x + right.x) / 3
            let cy = (tip.y + left.y + right.y) / 3
            petals.append(PetalGeometry(
                index: index,
                tip: tip,
                left: left,
                right: right,
                petalX: petalX,
                petalY: petalY,
                innerTip: ChartPoint(cx + (tip.x - cx) * innerRatio, cy + (tip.y - cy) * innerRatio),
                innerLeft: ChartPoint(cx + (left.x - cx) * innerRatio,
                                      cy + (left.y - cy) * innerRatio),
                innerRight: ChartPoint(cx + (right.x - cx) * innerRatio,
                                       cy + (right.y - cy) * innerRatio)
            ))
        }

        func drawPart(_ part: TouchOrder.Part) {
            switch part {
            case .gauge:
                // Hold progress ring: only while the hold is being held down.
                if isHoldActive && note.durationMs > 0 {
                    let elapsed = -timeDiff
                    drawHoldProgress(origin: origin, progress: min(elapsed / note.durationMs, 1.0),
                                     alpha: alpha)
                }
            case .petal0, .petal1, .petal2, .petal3:
                guard let index = part.petalIndex else { return }
                drawPetal(petal: petals[index], isHold: isHold, isSimultaneous: isSimultaneous,
                          ddr: ddr, cornerRadius: cornerRadius,
                          innerCornerRadius: innerCornerRadius, strokeWidth: strokeWidth,
                          alpha: combinedAlpha)
            case .center:
                let centerSize = Double(scaleByRadius(ChartConstants.touchCenterDotRatio)) * 0.8
                strokeSettings(strokeWidth * 3, .butt, .miter)
                applyColor(&strokePaint, ChartConstants.Colors.black, alpha: alpha)
                canvas.drawCircle(centerX: origin.x, centerY: origin.y, radius: centerSize,
                                  strokePaint)
                // The centre dot is the one place that does not use `TOUCH_BLUE`.
                applyColor(
                    &fillPaint,
                    isSimultaneous ? ChartConstants.Colors.touchSimultaneousYellow
                        : ChartColor(argb: 0xFF00_BFFF),
                    alpha: alpha
                )
                canvas.drawCircle(centerX: origin.x, centerY: origin.y, radius: centerSize,
                                  fillPaint)
                strokeSettings(strokeWidth, .butt, .miter)
                applyColor(&strokePaint, ChartConstants.Colors.white, alpha: alpha)
                canvas.drawCircle(centerX: origin.x, centerY: origin.y, radius: centerSize,
                                  strokePaint)
            }
        }

        // The reference falls back to the queue length when the caller has no source index.
        let index = sourceNoteIndex ?? queue.count
        let sortNote = TouchOrder.TouchSortNote(
            sourceIndex: index,
            position: mirrorTouchPosition(positionName),
            isHold: isHold,
            registeredAtMs: note.timingMs - approachTime
        )
        queue.append(TouchDrawCommand(sort: sortNote, draw: { part in drawPart(part) }))
    }

    /// Emits queued touch parts in arcade z-order.
    func flush(_ queue: [TouchDrawCommand]) {
        if queue.isEmpty { return }
        var commands: [Int: TouchDrawCommand] = [:]
        commands.reserveCapacity(queue.count)
        for command in queue {
            // `associateBy` keeps the last command for a duplicated source index.
            commands[command.sort.sourceIndex] = command
        }
        for ranked in TouchOrder.resolve(queue.map(\.sort)) {
            commands[ranked.sourceIndex]?.draw(ranked.part)
        }
    }

    // MARK: - Petals

    private func drawPetal(petal: PetalGeometry, isHold: Bool, isSimultaneous: Bool,
                           ddr: ChartColor?, cornerRadius: Double, innerCornerRadius: Double,
                           strokeWidth: Float, alpha: Double) {
        let canvas = context.canvas
        let shadowOffset = scaleByRadius(2.0 / 300.0)
        let outer = buildRoundedTriangle(tip: petal.tip, left: petal.left, right: petal.right,
                                         cornerRadius: cornerRadius)
        let inner = buildRoundedTriangle(tip: petal.innerTip, left: petal.innerLeft,
                                         right: petal.innerRight, cornerRadius: innerCornerRadius)

        // Soft drop shadow approximated by layered offset copies (three flat fills, no blur).
        canvas.withTranslation(CGFloat(shadowOffset), CGFloat(shadowOffset)) { shadowCanvas in
            applyColor(&fillPaint, ChartConstants.Colors.black, alpha: alpha * 0.18)
            shadowCanvas.drawPath(outer, fillPaint)
            shadowCanvas.drawPath(offsetPath(outer, -1, 0), fillPaint)
            shadowCanvas.drawPath(offsetPath(outer, 1, 0), fillPaint)
        }

        strokeSettings(strokeWidth * 3, .butt, .miter)
        applyColor(&strokePaint, ChartConstants.Colors.black, alpha: alpha)
        canvas.drawPath(outer, strokePaint)
        if !isHold { canvas.drawPath(inner, strokePaint) }

        let shading = petalShading(petal: petal, isHold: isHold, isSimultaneous: isSimultaneous,
                                   ddr: ddr)

        var fillPath = Path()
        fillPath.addPath(outer)
        if !isHold { fillPath.addPath(inner) }
        gradientPaint.evenOdd = true
        gradientPaint.shading = shading
        gradientPaint.alpha = alpha
        canvas.drawPath(fillPath, gradientPaint)
        gradientPaint.shading = nil

        strokeSettings(strokeWidth, .butt, .miter)
        applyColor(&strokePaint, ChartConstants.Colors.white, alpha: alpha)
        canvas.drawPath(outer, strokePaint)
        if !isHold { canvas.drawPath(inner, strokePaint) }
    }

    /// Gradient of one petal: DDR colors win, then hold palettes, then touch cyan / simultaneous
    /// gold. The 4-stop ramps run from the petal origin towards its tip.
    private func petalShading(petal: PetalGeometry, isHold: Bool, isSimultaneous: Bool,
                              ddr: ChartColor?) -> ChartShading {
        let start = CGPoint(x: petal.petalX, y: petal.petalY)
        let end = CGPoint(x: petal.tip.x, y: petal.tip.y)
        let stops: [CGFloat] = [0, 0.35, 0.72, 1]
        if let ddr {
            if isHold {
                return .linear(
                    start: start,
                    end: end,
                    colors: [
                        mixColor(ddr, ChartConstants.Colors.white, 0.24),
                        ddr,
                        mixColor(ddr, ChartConstants.Colors.black, 0.22),
                        mixColor(ddr, ChartConstants.Colors.white, 0.5),
                    ],
                    stops: stops
                )
            }
            return .linear(start: start, end: end, colors: [ddr, ddr], stops: nil)
        }
        if isHold {
            return .linear(start: start, end: end, colors: Self.holdPalettes[petal.index],
                           stops: stops)
        }
        return .linear(
            start: start,
            end: end,
            colors: isSimultaneous
                ? [ChartConstants.Colors.touchSimultaneousYellow,
                   ChartConstants.Colors.simultaneousGold]
                : [ChartConstants.Colors.touchCyan, ChartConstants.Colors.touchBlue],
            stops: nil
        )
    }

    /// Rounded triangle path used by every petal layer.
    private func buildRoundedTriangle(tip: ChartPoint, left: ChartPoint, right: ChartPoint,
                                      cornerRadius: Double) -> Path {
        var path = Path()
        if cornerRadius <= 0 {
            path.move(to: CGPoint(x: tip.x, y: tip.y))
            path.addLine(to: CGPoint(x: left.x, y: left.y))
            path.addLine(to: CGPoint(x: right.x, y: right.y))
            path.closeSubpath()
            return path
        }
        let vertices = [tip, left, right]
        for index in 0..<3 {
            let corner = vertices[index]
            let previous = vertices[(index + 2) % 3]
            let next = vertices[(index + 1) % 3]
            let dx1 = corner.x - previous.x
            let dy1 = corner.y - previous.y
            let len1 = (dx1 * dx1 + dy1 * dy1).squareRoot()
            let dx2 = next.x - corner.x
            let dy2 = next.y - corner.y
            let len2 = (dx2 * dx2 + dy2 * dy2).squareRoot()
            if len1 == 0.0 || len2 == 0.0 { continue }
            let nx1 = dx1 / len1
            let ny1 = dy1 / len1
            let nx2 = dx2 / len2
            let ny2 = dy2 / len2
            let radius = min(cornerRadius, min(len1 / 2, len2 / 2))
            let beforeX = corner.x - nx1 * radius
            let beforeY = corner.y - ny1 * radius
            let afterX = corner.x + nx2 * radius
            let afterY = corner.y + ny2 * radius
            if index == 0 {
                path.move(to: CGPoint(x: beforeX, y: beforeY))
            } else {
                path.addLine(to: CGPoint(x: beforeX, y: beforeY))
            }
            path.addQuadCurve(to: CGPoint(x: afterX, y: afterY),
                              control: CGPoint(x: corner.x, y: corner.y))
        }
        path.closeSubpath()
        return path
    }

    private func offsetPath(_ path: Path, _ dx: CGFloat, _ dy: CGFloat) -> Path {
        path.applying(CGAffineTransform(translationX: dx, y: dy))
    }

    // MARK: - Touch hold gauge

    /// Progress ring of a held touch: a pie clip intersected with the diamond-shaped gauge ring,
    /// filled by four directional gradients.
    private func drawHoldProgress(origin: ChartPoint, progress: Double, alpha: Double) {
        let unit = context.radius / Float(ChartConstants.panelRadiusUnits)
        let half = unit * Float(Self.touchHoldCanvasHalf)
        if half <= 0 { return }
        let canvas = context.canvas
        canvas.save()
        var pie = Path()
        pie.move(to: CGPoint(x: origin.x, y: origin.y))
        arcTo(&pie, cx: Float(origin.x), cy: Float(origin.y), radius: half, startDeg: -90,
              sweepDeg: Float(progress * 360))
        pie.closeSubpath()
        canvas.clipPath(pie)

        // The diamond helper is centered on (0,0); the gauge has to sit on the note.
        func diamondAt(_ axis: Double, _ diagonal: Double) -> Path {
            roundedDiamond(axisRadius: axis, diagonalRadius: diagonal, unit: unit)
                .applying(CGAffineTransform(translationX: CGFloat(origin.x),
                                            y: CGFloat(origin.y)))
        }
        var ring = Path()
        ring.addPath(diamondAt(Self.touchHoldOuterAxis, Self.touchHoldOuterDiagonal))
        ring.addPath(diamondAt(Self.touchHoldInnerAxis, Self.touchHoldInnerDiagonal))
        canvas.save()
        canvas.clipPath(ring, evenOdd: true)
        for index in 0..<4 {
            let angle = -Double.pi / 4 + Double(index) * Double.pi / 2
            let dx = cos(angle)
            let dy = sin(angle)
            let shading = ChartShading.linear(
                start: CGPoint(x: origin.x + dx * Self.touchHoldInnerDiagonal * Double(unit),
                               y: origin.y + dy * Self.touchHoldInnerDiagonal * Double(unit)),
                end: CGPoint(x: origin.x + dx * Self.touchHoldOuterDiagonal * Double(unit),
                             y: origin.y + dy * Self.touchHoldOuterDiagonal * Double(unit)),
                colors: [
                    mixColor(Self.progressColors[index], ChartConstants.Colors.black, 0.28),
                    Self.progressColors[index],
                    mixColor(Self.progressColors[index], ChartConstants.Colors.white, 0.32),
                    mixColor(Self.progressColors[index], ChartConstants.Colors.white, 0.08),
                ],
                stops: [0, 0.35, 0.72, 1]
            )
            gradientPaint.shading = shading
            gradientPaint.alpha = alpha
            let left = dx > 0 ? origin.x : origin.x - Double(half)
            let top = dy > 0 ? origin.y : origin.y - Double(half)
            canvas.drawRect(
                CGRect(x: left, y: top, width: Double(half), height: Double(half)),
                gradientPaint
            )
        }
        gradientPaint.shading = nil
        canvas.restore()
        canvas.restore()
    }

    /// Rounded diamond centered on the origin; the gauge ring is the even-odd difference of two of
    /// these.
    private func roundedDiamond(axisRadius: Double, diagonalRadius: Double, unit: Float) -> Path {
        let radius = diagonalRadius * 2.0.squareRoot() * Double(unit)
        let offset = 2 * (radius - axisRadius * Double(unit))
        var path = Path()
        path.move(to: CGPoint(x: -offset, y: -radius + offset))
        path.addQuadCurve(to: CGPoint(x: offset, y: -radius + offset),
                          control: CGPoint(x: 0, y: -radius))
        path.addLine(to: CGPoint(x: radius - offset, y: -offset))
        path.addQuadCurve(to: CGPoint(x: radius - offset, y: offset),
                          control: CGPoint(x: radius, y: 0))
        path.addLine(to: CGPoint(x: offset, y: radius - offset))
        path.addQuadCurve(to: CGPoint(x: -offset, y: radius - offset),
                          control: CGPoint(x: 0, y: radius))
        path.addLine(to: CGPoint(x: -radius + offset, y: offset))
        path.addQuadCurve(to: CGPoint(x: -radius + offset, y: -offset),
                          control: CGPoint(x: -radius, y: 0))
        path.closeSubpath()
        return path
    }

    // MARK: - Multi-touch border

    /// Faint sensor outlines around the sensors that carry more than one visible touch.
    func renderTouchBorder(_ position: ChartPoint, isSimultaneous: Bool, visibleCount: Int) {
        if visibleCount < 2 { return }
        let boxSize = scaleByRadius(1.0 / 4.46) * 1.1 * 1.2
        let cornerRadius = scaleByRadius(ChartConstants.noteSizeRatio)
        let color = isSimultaneous
            ? ChartConstants.Colors.simultaneousGold
            : ChartConstants.Colors.touchCyan
        // Three or more touches get a second, wider frame.
        if visibleCount >= 3 {
            drawTouchBorderBox(position, size: boxSize * 1.2, cornerRadius: cornerRadius,
                               color: color, lineWidth: 3)
        }
        drawTouchBorderBox(position, size: boxSize, cornerRadius: cornerRadius, color: color,
                           lineWidth: 3)
    }

    private func drawTouchBorderBox(_ position: ChartPoint, size: Float, cornerRadius: Float,
                                    color: ChartColor, lineWidth: Int) {
        let half = size / 2
        let left = Float(position.x) - half
        let top = Float(position.y) - half
        let gap = size * 0.3
        var path = Path()
        path.move(to: CGPoint(x: Double(left + cornerRadius), y: Double(top)))
        path.addLine(to: CGPoint(x: Double(left + size / 2 - gap / 2), y: Double(top)))
        path.move(to: CGPoint(x: Double(left + size / 2 + gap / 2), y: Double(top)))
        path.addLine(to: CGPoint(x: Double(left + size - cornerRadius), y: Double(top)))
        // Corner arcs are centered on (left+size-r, top+r) and friends; using the box edges as the
        // arc rect made the corners bulge outside the box.
        arcTo(&path, cx: left + size - cornerRadius, cy: top + cornerRadius,
              radius: cornerRadius, startDeg: -90, sweepDeg: 90)

        path.addLine(to: CGPoint(x: Double(left + size), y: Double(top + size / 2 - gap / 2)))
        path.move(to: CGPoint(x: Double(left + size), y: Double(top + size / 2 + gap / 2)))
        path.addLine(to: CGPoint(x: Double(left + size), y: Double(top + size - cornerRadius)))
        arcTo(&path, cx: left + size - cornerRadius, cy: top + size - cornerRadius,
              radius: cornerRadius, startDeg: 0, sweepDeg: 90)

        path.addLine(to: CGPoint(x: Double(left + size / 2 + gap / 2), y: Double(top + size)))
        path.move(to: CGPoint(x: Double(left + size / 2 - gap / 2), y: Double(top + size)))
        path.addLine(to: CGPoint(x: Double(left + cornerRadius), y: Double(top + size)))
        arcTo(&path, cx: left + cornerRadius, cy: top + size - cornerRadius,
              radius: cornerRadius, startDeg: 90, sweepDeg: 90)

        path.addLine(to: CGPoint(x: Double(left), y: Double(top + size / 2 + gap / 2)))
        path.move(to: CGPoint(x: Double(left), y: Double(top + size / 2 - gap / 2)))
        path.addLine(to: CGPoint(x: Double(left), y: Double(top + cornerRadius)))
        arcTo(&path, cx: left + cornerRadius, cy: top + cornerRadius,
              radius: cornerRadius, startDeg: 180, sweepDeg: 90)

        borderPaint.color = color
        borderPaint.strokeWidth = CGFloat(Float(lineWidth) * context.radius / 300)
        borderPaint.strokeCap = .butt
        borderPaint.strokeJoin = .round
        context.canvas.drawPath(path, borderPaint)
    }

    // MARK: - Hold gauge geometry

    private static let touchHoldCanvasHalf = 99.75
    private static let touchHoldInnerAxis = 64.0
    private static let touchHoldInnerDiagonal = 50.5
    private static let touchHoldOuterAxis = 97.75
    private static let touchHoldOuterDiagonal = 82.75

    private static let progressColors: [ChartColor] = [
        ChartColor(argb: 0xFFE9_5513),
        ChartColor(argb: 0xFFFA_ED00),
        ChartColor(argb: 0xFF0D_AC67),
        ChartColor(argb: 0xFF2C_A6E0),
    ]

    /// Per-petal hold palettes: light rim, body, shaded body, highlight tip.
    private static let holdPalettes: [[ChartColor]] = [
        [
            ChartColor(argb: 0xFFFF_5511),
            ChartColor(argb: 0xFFF7_4601),
            ChartColor(argb: 0xFFE7_4201),
            ChartColor(argb: 0xFFFF_F6F2),
        ],
        [
            ChartColor(argb: 0xFFF8_DE00),
            ChartColor(argb: 0xFFF6_ED00),
            ChartColor(argb: 0xFFEC_F402),
            ChartColor(argb: 0xFFFF_FBEF),
        ],
        [
            ChartColor(argb: 0xFF1E_B476),
            ChartColor(argb: 0xFF12_A86A),
            ChartColor(argb: 0xFF0A_A062),
            ChartColor(argb: 0xFFEC_FFF8),
        ],
        [
            ChartColor(argb: 0xFF00_AAF8),
            ChartColor(argb: 0xFF00_97F6),
            ChartColor(argb: 0xFF02_89F4),
            ChartColor(argb: 0xFFEF_FCFF),
        ],
    ]
}
