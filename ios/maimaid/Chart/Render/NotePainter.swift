import CoreGraphics
import Foundation
import SwiftUI

/// Tap-note rendering: approach arcs, chord connectors, EX rings and the tap ring itself.
///
/// 1:1 port of `NotePainter.kt`. The reference engine bakes the tap ring into an offscreen sprite as
/// a performance cache; both the Android port and this one draw the same artwork with vectors.
nonisolated final class NotePainter: BasePainter {
    /// Break-index label paint: bold sans-serif, centre aligned — mirrors the Kotlin `textPaint`.
    private var textPaint: ChartPaint = {
        var paint = ChartPaint()
        paint.bold = true
        paint.textAlign = .center
        return paint
    }()

    func positionOnRing(_ position: Int) -> ChartPoint { buttonPosition(position) }

    func angle(_ position: Int) -> Double { buttonAngle(position) }

    /// Current render position and scale of a note, or an invisible marker outside the window.
    func calculateNotePosition(_ note: ChartNote, currentTimeMs: Double) -> NoteRenderPosition {
        let position: Int
        switch note.kind {
        case .tap, .holdStart, .holdEnd, .slide:
            position = note.position
        case .touch, .touchHoldStart, .touchHoldEnd:
            return .invisible
        }
        let angle = buttonAngle(position)
        let timeDiff = note.timingMs - currentTimeMs
        let approachTime = noteApproachTimeMs(note.hiSpeed)

        // Hold bodies stay visible for their whole length after the head, every other note only for
        // the (normally zero) post-hit window.
        var holdWindow = ChartConstants.noteVisibilityAfterMs
        if note.kind == .holdStart {
            holdWindow = note.holdLengthMs
        }
        if timeDiff > approachTime || timeDiff < -holdWindow { return .invisible }

        let dir = noteApproachDir(note.hiSpeed)
        let halfApproach = approachTime / 2
        let distance: Double
        let scale: Double
        if timeDiff > halfApproach {
            distance = Double(context.radius) * (1 + dir * (ChartConstants.approachStartScale - 1))
            scale = 1 - (timeDiff - halfApproach) / halfApproach
        } else if timeDiff >= 0 {
            let progress = 1 - timeDiff / halfApproach
            distance = Double(context.radius)
                * (1 + dir * (ChartConstants.approachStartScale - 1 + 0.75 * progress))
            scale = 1.0
        } else if note.kind == .holdStart {
            // A hold head sits on the judgment line for the duration of its body.
            distance = Double(context.radius)
            scale = 1.0
        } else {
            let fadeProgress = 1 + -timeDiff / halfApproach
            distance = Double(context.radius)
                * (1 + dir * (ChartConstants.approachStartScale - 1 + 0.75 * fadeProgress))
            scale = 1.0
        }

        return NoteRenderPosition(
            x: Double(context.centerX) + cos(angle) * distance,
            y: Double(context.centerY) + sin(angle) * distance,
            scale: scale,
            visible: true
        )
    }

    /// One note's approach arc plus its four fading trails on either side.
    func renderApproachArc(position: Int, noteX: Double, noteY: Double, color: ChartColor) {
        let angle = buttonAngle(position)
        let distance = Float(distanceToCenter(noteX, noteY))
        let trailStep = Double.pi / 8 / 4
        let lineWidth = scaleByRadius(ChartConstants.noteStrokeWidthRatio)
        strokeSettings(lineWidth, .butt, .miter)
        let canvas = context.canvas
        for index in stride(from: 4, through: 0, by: -1) {
            let alpha = index == 0 ? 0.4 : 0.4 * (1 - Double(index) / 5.0)
            var path = Path()
            if index == 0 {
                path.move(to: CGPoint(
                    x: Double(context.centerX) + cos(angle - Double.pi / 8) * Double(distance),
                    y: Double(context.centerY) + sin(angle - Double.pi / 8) * Double(distance)
                ))
                appendArc(
                    &path,
                    cx: context.centerX,
                    cy: context.centerY,
                    radius: distance,
                    startDeg: Float(toDegrees(angle - Double.pi / 8)),
                    sweepDeg: Float(toDegrees(Double.pi / 4))
                )
            } else {
                let leftStart = angle - Double.pi / 8 - Double(index) * trailStep
                let leftEnd = angle - Double.pi / 8 - Double(index - 1) * trailStep
                path.move(to: CGPoint(
                    x: Double(context.centerX) + cos(leftStart) * Double(distance),
                    y: Double(context.centerY) + sin(leftStart) * Double(distance)
                ))
                appendArc(
                    &path,
                    cx: context.centerX,
                    cy: context.centerY,
                    radius: distance,
                    startDeg: Float(toDegrees(leftStart)),
                    sweepDeg: Float(toDegrees(leftEnd - leftStart))
                )
                let rightStart = angle + Double.pi / 8 + Double(index - 1) * trailStep
                let rightEnd = angle + Double.pi / 8 + Double(index) * trailStep
                path.move(to: CGPoint(
                    x: Double(context.centerX) + cos(rightStart) * Double(distance),
                    y: Double(context.centerY) + sin(rightStart) * Double(distance)
                ))
                appendArc(
                    &path,
                    cx: context.centerX,
                    cy: context.centerY,
                    radius: distance,
                    startDeg: Float(toDegrees(rightStart)),
                    sweepDeg: Float(toDegrees(rightEnd - rightStart))
                )
            }
            applyColor(&strokePaint, color, alpha: alpha)
            canvas.drawPath(path, strokePaint)
        }
    }

    /// Batched approach arcs, grouped by color so all arcs of one color share one stroke.
    func renderApproachArcsBatch(_ arcs: [ApproachArc]) {
        if arcs.isEmpty { return }
        let trailStep = Double.pi / 8 / 4
        let lineWidth = scaleByRadius(ChartConstants.noteStrokeWidthRatio)
        strokeSettings(lineWidth, .butt, .miter)
        let canvas = context.canvas

        // Kotlin uses a `LinkedHashMap`, so colors keep their first-seen order; that order decides
        // which approach-arc layer lands on top, so it has to be preserved here too.
        var colorOrder: [ChartColor] = []
        var grouped: [ChartColor: [ApproachArc]] = [:]
        for arc in arcs {
            if grouped[arc.color] == nil { colorOrder.append(arc.color) }
            grouped[arc.color, default: []].append(arc)
        }

        for color in colorOrder {
            let group = grouped[color] ?? []
            for index in stride(from: 4, through: 0, by: -1) {
                let alpha = index == 0 ? 0.4 : 0.4 * (1 - Double(index) / 5.0)
                var path = Path()
                for arc in group {
                    let angle = buttonAngle(arc.position)
                    let distance = Float(arc.distance)
                    if index == 0 {
                        path.move(to: CGPoint(
                            x: Double(context.centerX) + cos(angle - Double.pi / 8) * arc.distance,
                            y: Double(context.centerY) + sin(angle - Double.pi / 8) * arc.distance
                        ))
                        appendArc(
                            &path,
                            cx: context.centerX,
                            cy: context.centerY,
                            radius: distance,
                            startDeg: Float(toDegrees(angle - Double.pi / 8)),
                            sweepDeg: Float(toDegrees(Double.pi / 4))
                        )
                    } else {
                        let leftStart = angle - Double.pi / 8 - Double(index) * trailStep
                        let leftEnd = angle - Double.pi / 8 - Double(index - 1) * trailStep
                        path.move(to: CGPoint(
                            x: Double(context.centerX) + cos(leftStart) * arc.distance,
                            y: Double(context.centerY) + sin(leftStart) * arc.distance
                        ))
                        appendArc(
                            &path,
                            cx: context.centerX,
                            cy: context.centerY,
                            radius: distance,
                            startDeg: Float(toDegrees(leftStart)),
                            sweepDeg: Float(toDegrees(leftEnd - leftStart))
                        )
                        let rightStart = angle + Double.pi / 8 + Double(index - 1) * trailStep
                        let rightEnd = angle + Double.pi / 8 + Double(index) * trailStep
                        path.move(to: CGPoint(
                            x: Double(context.centerX) + cos(rightStart) * arc.distance,
                            y: Double(context.centerY) + sin(rightStart) * arc.distance
                        ))
                        appendArc(
                            &path,
                            cx: context.centerX,
                            cy: context.centerY,
                            radius: distance,
                            startDeg: Float(toDegrees(rightStart)),
                            sweepDeg: Float(toDegrees(rightEnd - rightStart))
                        )
                    }
                }
                applyColor(&strokePaint, color, alpha: alpha)
                canvas.drawPath(path, strokePaint)
            }
        }
    }

    /// Connects two simultaneous notes along the shorter arc; opposite buttons get a full circle.
    func renderSimultaneousConnector(from startPos: Int, to endPos: Int, distance: Double,
                                     color: ChartColor) {
        let startAngle = buttonAngle(startPos)
        let endAngle = buttonAngle(endPos)
        var angleDiff = endPos - startPos
        while angleDiff > 4 { angleDiff -= 8 }
        while angleDiff < -4 { angleDiff += 8 }
        let isFullCircle = abs(angleDiff) == 4

        strokeSettings(noteStrokeWidth(), .butt, .miter)
        applyColor(&strokePaint, color, alpha: 0.7)
        let path: Path
        if isFullCircle {
            path = arcPath(cx: context.centerX, cy: context.centerY, radius: Float(distance),
                           startDeg: 0, sweepDeg: 360)
        } else {
            let arcStart = angleDiff > 0 ? startAngle : endAngle
            let arcEnd = angleDiff > 0 ? endAngle : startAngle
            path = arcPath(cx: context.centerX, cy: context.centerY, radius: Float(distance),
                           startDeg: Float(toDegrees(arcStart)),
                           sweepDeg: Float(toDegrees(arcEnd - arcStart)))
        }
        context.canvas.drawPath(path, strokePaint)
    }

    /// Glowing EX ring drawn behind tap and star heads.
    func renderExRing(x: Double, y: Double, noteSize: Double, isBreak: Bool,
                      isSimultaneous: Bool, scaleFactor: Double) {
        let outerRadius = noteSize * 1.19 * scaleFactor
        let color: ChartColor
        if isBreak {
            color = ChartConstants.Colors.exOverlayBreak
        } else if isSimultaneous {
            color = ChartConstants.Colors.exOverlaySimultaneous
        } else {
            color = ChartConstants.Colors.exOverlayNormal
        }
        let path = ringPath(x: x, y: y, innerRadius: noteSize, outerRadius: outerRadius)
        applyColor(&fillPaint, color)
        // The Kotlin code sets `Path.FillType.EVEN_ODD` on the path; the shim carries that on the
        // paint instead, so it has to be cleared again for the following single-contour fills.
        fillPaint.evenOdd = true
        context.canvas.drawPath(path, fillPaint)
        fillPaint.evenOdd = false
    }

    /// Draws a tap note using vectors — the reference sprite bake is a cache, not a different look.
    func renderTapNote(x: Double, y: Double, scale: Double, position: Int, isBreak: Bool,
                       isSimultaneous: Bool, isEx: Bool, timing: Double, exScale: Double) {
        drawTapNoteVector(
            x: x,
            y: y,
            noteScale: scale,
            position: position,
            isBreak: isBreak,
            isSimultaneous: isSimultaneous,
            isEx: isEx,
            timing: timing,
            highlightExScale: exScale
        )
    }

    private func drawTapNoteVector(x: Double, y: Double, noteScale: Double, position: Int,
                                   isBreak: Bool, isSimultaneous: Bool, isEx: Bool,
                                   timing: Double, highlightExScale: Double) {
        let baseSize = Double(scaleByRadius(ChartConstants.noteSizeRatio)) * noteScale * 1.36
        let innerRadius = baseSize * ChartConstants.tapInnerRingRatio
        let canvas = context.canvas

        if isEx {
            renderExRing(x: x, y: y, noteSize: baseSize, isBreak: isBreak,
                         isSimultaneous: isSimultaneous, scaleFactor: highlightExScale)
        }

        let ddr = ddrColor(timing)
        let colors = gradientColors(ddr: ddr, isBreak: isBreak, isSimultaneous: isSimultaneous)
        renderDirectionalTapRing(x: x, y: y, innerRadius: innerRadius, outerRadius: baseSize,
                                 position: position, colors: colors)

        let strokeW = noteStrokeWidth()
        let strokeWD = Double(strokeW)
        if !isEx {
            strokeSettings(strokeW, .butt, .miter)
            applyColor(&strokePaint, ChartConstants.Colors.black)
            let outerPath = arcPath(
                cx: Float(x),
                cy: Float(y),
                radius: Float(baseSize + strokeWD / 2 + strokeWD / 2),
                startDeg: 0,
                sweepDeg: 360
            )
            canvas.drawPath(outerPath, strokePaint)
        }
        let innerBlackR = innerRadius - strokeWD / 2 - strokeWD / 2
        if innerBlackR > 0 {
            strokeSettings(strokeW, .butt, .miter)
            applyColor(&strokePaint, ChartConstants.Colors.black)
            canvas.drawPath(
                arcPath(cx: Float(x), cy: Float(y), radius: Float(innerBlackR),
                        startDeg: 0, sweepDeg: 360),
                strokePaint
            )
        }

        strokeSettings(strokeW, .butt, .miter)
        applyColor(&strokePaint, ChartConstants.Colors.white)
        canvas.drawPath(
            arcPath(cx: Float(x), cy: Float(y), radius: Float(baseSize), startDeg: 0, sweepDeg: 360),
            strokePaint
        )
        canvas.drawPath(
            arcPath(cx: Float(x), cy: Float(y), radius: Float(innerRadius), startDeg: 0, sweepDeg: 360),
            strokePaint
        )

        let centerSize = baseSize * 0.15
        let centerColor: ChartColor
        if let ddr {
            centerColor = ddr
        } else if isBreak {
            centerColor = ChartConstants.Colors.breakOrange
        } else if isSimultaneous {
            centerColor = ChartConstants.Colors.simultaneousGold
        } else {
            centerColor = ChartConstants.Colors.tapPink
        }
        applyColor(&fillPaint, centerColor)
        canvas.drawPath(
            arcPath(cx: Float(x), cy: Float(y), radius: Float(centerSize), startDeg: 0, sweepDeg: 360),
            fillPaint
        )
    }

    /// Four directional gradient sectors aligned with the button angle.
    private func renderDirectionalTapRing(x: Double, y: Double, innerRadius: Double,
                                          outerRadius: Double, position: Int,
                                          colors: [ChartColor]) {
        let canvas = context.canvas
        let startAngle = buttonAngle(position)
        let lightColor = mixColor(colors[0], ChartConstants.Colors.white,
                                  ChartConstants.noteLightenRatio)

        for index in 0..<4 {
            let sectorStart = startAngle + Double(index) * Double.pi / 2
            let sectorEnd = sectorStart + Double.pi / 2
            let midR = (innerRadius + outerRadius) / 2
            let x0 = x + cos(sectorStart) * midR
            let y0 = y + sin(sectorStart) * midR
            let x1 = x + cos(sectorEnd) * midR
            let y1 = y + sin(sectorEnd) * midR

            var path = Path()
            path.move(to: CGPoint(
                x: x + cos(sectorStart) * outerRadius,
                y: y + sin(sectorStart) * outerRadius
            ))
            appendArc(
                &path,
                cx: Float(x),
                cy: Float(y),
                radius: Float(outerRadius),
                startDeg: Float(toDegrees(sectorStart)),
                sweepDeg: Float(toDegrees(Double.pi / 2))
            )
            appendArc(
                &path,
                cx: Float(x),
                cy: Float(y),
                radius: Float(innerRadius),
                startDeg: Float(toDegrees(sectorEnd)),
                sweepDeg: Float(toDegrees(-Double.pi / 2))
            )
            path.closeSubpath()
            fillPaint.shading = .linear(
                start: CGPoint(x: x0, y: y0),
                end: CGPoint(x: x1, y: y1),
                colors: [lightColor, colors[1]],
                stops: nil
            )
            fillPaint.alpha = 1
            canvas.drawPath(path, fillPaint)
        }
        fillPaint.shading = nil
    }

    /// Break ordering number above a break note.
    func renderBreakIndex(x: Double, y: Double, scale: Double, index: Int) {
        // Kotlin: `(30f * radius / 300f * scale).roundToInt()` — the first two products stay Float
        // and only the `* scale` promotion happens in Double, so keep that split.
        let unitSize = 30 * context.radius / 300
        let fontSize = Float((Double(unitSize) * scale).rounded())
        textPaint.textSize = CGFloat(fontSize)
        textPaint.color = ChartConstants.Colors.white
        // Android's `FontMetrics.ascent` is negative and the label is centred on
        // `(ascent + descent) / 2`; the canvas shim reports a positive ascent, so negate it here.
        let ascent = -context.canvas.textAscent(textPaint)
        let descent = context.canvas.textDescent(textPaint)
        let baseline = CGFloat(y - (ascent + descent) / 2)
        textPaint.color = ChartColor(argb: 0xE600_0000)
        context.canvas.drawText(String(index), x: CGFloat(x + 1), y: baseline + 1, textPaint)
        textPaint.color = ChartConstants.Colors.white
        context.canvas.drawText(String(index), x: CGFloat(x), y: baseline, textPaint)
    }

    private func ringPath(x: Double, y: Double, innerRadius: Double, outerRadius: Double) -> Path {
        var path = Path()
        path.addEllipse(in: CGRect(
            x: x - outerRadius,
            y: y - outerRadius,
            width: outerRadius * 2,
            height: outerRadius * 2
        ))
        path.addEllipse(in: CGRect(
            x: x - innerRadius,
            y: y - innerRadius,
            width: innerRadius * 2,
            height: innerRadius * 2
        ))
        return path
    }

    /// `Math.toDegrees`, which the Kotlin painter uses to feed the degree-based path shims.
    private func toDegrees(_ radians: Double) -> Double {
        radians * 180.0 / Double.pi
    }
}

/// One batched approach-arc entry.
nonisolated struct ApproachArc: Sendable {
    var position: Int
    var distance: Double
    var color: ChartColor
}
