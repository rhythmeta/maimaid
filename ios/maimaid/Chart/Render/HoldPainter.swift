import CoreGraphics
import Foundation
import SwiftUI

/// Hold bodies: hexagonal tube, EX frame, head glow and pressed breathing brightness.
///
/// 1:1 port of `HoldPainter.kt`.
nonisolated final class HoldPainter: BasePainter {
    /// Paint reused by the gradient-filled body segments and the radial head glow.
    private var gradientPaint: ChartPaint = {
        var paint = ChartPaint()
        paint.style = .fill
        return paint
    }()

    /// Breathing brightness multiplier while a hold is pressed; 1.0 when not pressed.
    private func activeBodyBrightness(_ elapsedMs: Double) -> Double {
        if elapsedMs < 0 { return 1.0 }
        let curve = ChartConstants.holdActiveBrightnessCurve
        let steps = curve.count
        let position = elapsedMs.truncatingRemainder(dividingBy: ChartConstants.holdActiveCycleMs)
            / ChartConstants.holdActiveCycleMs * Double(steps)
        let index = Int(position.rounded(.down))
        let t = position - Double(index)
        let a = curve[index % steps]
        let b = curve[(index + 1) % steps]
        return ChartConstants.holdActiveBrightnessMin
            + (a + (b - a) * t)
            * (ChartConstants.holdActiveBrightnessMax - ChartConstants.holdActiveBrightnessMin)
    }

    func renderHold(startPos: NoteRenderPosition, endPos: NoteRenderPosition, position: Int,
                    colors: [ChartColor], isEx: Bool, holdStart: ChartNote, holdEnd: ChartNote,
                    currentTimeMs: Double, isBreak: Bool, isSimultaneous: Bool, exScale: Double) {
        let canvas = context.canvas
        let angle = buttonAngle(position)
        let baseSize = Double(scaleByRadius(ChartConstants.noteSizeRatio))
            * ChartConstants.holdWidthRatio
        let holdWidth = baseSize * startPos.scale

        let startX = startPos.x
        let startY = startPos.y

        let endX: Double
        let endY: Double
        if endPos.visible {
            endX = endPos.x
            endY = endPos.y
        } else {
            // Outside the tail's window the body still has to reach the judgment line.
            let dir = noteApproachDir(holdStart.hiSpeed)
            let approachDist = (1 + dir * -0.75) * Double(context.radius)
            endX = Double(context.centerX) + cos(angle) * approachDist
            endY = Double(context.centerY) + sin(angle) * approachDist
        }

        let leftAngle = angle + Double.pi / 3
        let rightAngle = angle - Double.pi / 3
        let backAngle = angle + Double.pi
        let backLeftAngle = angle + Double.pi - Double.pi / 3
        let backRightAngle = angle + Double.pi + Double.pi / 3

        func point(_ fromX: Double, _ fromY: Double, _ angle: Double,
                   _ distance: Double) -> ChartPoint {
            ChartPoint(fromX + cos(angle) * distance, fromY + sin(angle) * distance)
        }

        let startTip = point(startX, startY, angle, holdWidth)
        let startLeft = point(startX, startY, leftAngle, holdWidth)
        let startRight = point(startX, startY, rightAngle, holdWidth)

        // The tail cap shrinks while the head is still approaching.
        var endScale = 1.0
        if currentTimeMs != 0.0 {
            let approachHalf = noteApproachTimeMs(holdStart.hiSpeed) / 2
            let timeDiff = holdStart.timingMs - currentTimeMs
            if timeDiff > approachHalf {
                endScale = max(0.0, 1 - (timeDiff - approachHalf) / approachHalf)
            }
        }
        let endWidth = baseSize * endScale

        let endBack = point(endX, endY, backAngle, endWidth)
        let endBackLeft = point(endX, endY, backLeftAngle, endWidth)
        let endBackRight = point(endX, endY, backRightAngle, endWidth)

        let innerRatio = ChartConstants.holdInnerRatio
        let innerStartTip = point(startX, startY, angle, holdWidth * innerRatio)
        let innerStartLeft = point(startX, startY, leftAngle, holdWidth * innerRatio)
        let innerStartRight = point(startX, startY, rightAngle, holdWidth * innerRatio)
        let innerEndBack = point(endX, endY, backAngle, endWidth * innerRatio)
        let innerEndBackLeft = point(endX, endY, backLeftAngle, endWidth * innerRatio)
        let innerEndBackRight = point(endX, endY, backRightAngle, endWidth * innerRatio)

        var outerPath = Path()
        outerPath.move(to: CGPoint(x: startTip.x, y: startTip.y))
        outerPath.addLine(to: CGPoint(x: startLeft.x, y: startLeft.y))
        outerPath.addLine(to: CGPoint(x: endBackLeft.x, y: endBackLeft.y))
        outerPath.addLine(to: CGPoint(x: endBack.x, y: endBack.y))
        outerPath.addLine(to: CGPoint(x: endBackRight.x, y: endBackRight.y))
        outerPath.addLine(to: CGPoint(x: startRight.x, y: startRight.y))
        outerPath.closeSubpath()

        var innerPath = Path()
        innerPath.move(to: CGPoint(x: innerStartTip.x, y: innerStartTip.y))
        innerPath.addLine(to: CGPoint(x: innerStartLeft.x, y: innerStartLeft.y))
        innerPath.addLine(to: CGPoint(x: innerEndBackLeft.x, y: innerEndBackLeft.y))
        innerPath.addLine(to: CGPoint(x: innerEndBack.x, y: innerEndBack.y))
        innerPath.addLine(to: CGPoint(x: innerEndBackRight.x, y: innerEndBackRight.y))
        innerPath.addLine(to: CGPoint(x: innerStartRight.x, y: innerStartRight.y))
        innerPath.closeSubpath()

        if isEx {
            let exFrameScale = 1.19 * exScale
            let exColor: ChartColor
            if isBreak {
                exColor = ChartConstants.Colors.exOverlayBreak
            } else if isSimultaneous {
                exColor = ChartConstants.Colors.exOverlaySimultaneous
            } else {
                exColor = ChartConstants.Colors.exOverlayNormal
            }
            func scaled(_ centerX: Double, _ centerY: Double, _ p: ChartPoint) -> ChartPoint {
                ChartPoint(
                    centerX + (p.x - centerX) * exFrameScale,
                    centerY + (p.y - centerY) * exFrameScale
                )
            }
            let exStartTip = scaled(startX, startY, startTip)
            let exStartLeft = scaled(startX, startY, startLeft)
            let exStartRight = scaled(startX, startY, startRight)
            let exEndBack = scaled(endX, endY, endBack)
            let exEndBackLeft = scaled(endX, endY, endBackLeft)
            let exEndBackRight = scaled(endX, endY, endBackRight)

            // Outer frame minus the body outline, then the body hole — EVEN_ODD on the paint.
            var path = Path()
            path.move(to: CGPoint(x: exStartTip.x, y: exStartTip.y))
            path.addLine(to: CGPoint(x: exStartLeft.x, y: exStartLeft.y))
            path.addLine(to: CGPoint(x: exEndBackLeft.x, y: exEndBackLeft.y))
            path.addLine(to: CGPoint(x: exEndBack.x, y: exEndBack.y))
            path.addLine(to: CGPoint(x: exEndBackRight.x, y: exEndBackRight.y))
            path.addLine(to: CGPoint(x: exStartRight.x, y: exStartRight.y))
            path.closeSubpath()
            path.move(to: CGPoint(x: startTip.x, y: startTip.y))
            path.addLine(to: CGPoint(x: startRight.x, y: startRight.y))
            path.addLine(to: CGPoint(x: endBackRight.x, y: endBackRight.y))
            path.addLine(to: CGPoint(x: endBack.x, y: endBack.y))
            path.addLine(to: CGPoint(x: endBackLeft.x, y: endBackLeft.y))
            path.addLine(to: CGPoint(x: startLeft.x, y: startLeft.y))
            path.closeSubpath()
            applyColor(&fillPaint, exColor)
            fillPaint.evenOdd = true
            canvas.drawPath(path, fillPaint)
            fillPaint.evenOdd = false
        }

        let isPressed = currentTimeMs >= holdStart.timingMs && currentTimeMs < holdEnd.timingMs
        let brightness = isPressed
            ? activeBodyBrightness(currentTimeMs - holdStart.timingMs)
            : 1.0
        func tint(_ color: ChartColor) -> ChartColor {
            brightness == 1 ? color : scaleBrightness(color, brightness)
        }

        let outlineColor = tint(ChartConstants.Colors.white)
        let strokeWidth = scaleByRadius(ChartConstants.noteStrokeWidthRatio)

        strokeSettings(strokeWidth * 3, .butt, .miter)
        if !isEx {
            applyColor(&strokePaint, ChartConstants.Colors.black)
            canvas.drawPath(outerPath, strokePaint)
        }
        applyColor(&strokePaint, ChartConstants.Colors.black)
        canvas.drawPath(innerPath, strokePaint)

        let lightColor = tint(mixColor(colors[0], ChartConstants.Colors.white,
                                       ChartConstants.noteLightenRatio))
        let bodyColor = tint(colors[1])
        let segments = [
            HoldSegment(outerStart: startTip, outerEnd: startLeft,
                        innerStart: innerStartTip, innerEnd: innerStartLeft),
            HoldSegment(outerStart: startLeft, outerEnd: endBackLeft,
                        innerStart: innerStartLeft, innerEnd: innerEndBackLeft),
            HoldSegment(outerStart: endBackLeft, outerEnd: endBack,
                        innerStart: innerEndBackLeft, innerEnd: innerEndBack),
            HoldSegment(outerStart: endBack, outerEnd: endBackRight,
                        innerStart: innerEndBack, innerEnd: innerEndBackRight),
            HoldSegment(outerStart: endBackRight, outerEnd: startRight,
                        innerStart: innerEndBackRight, innerEnd: innerStartRight),
            HoldSegment(outerStart: startRight, outerEnd: startTip,
                        innerStart: innerStartRight, innerEnd: innerStartTip),
        ]
        for segment in segments {
            gradientPaint.shading = .linear(
                start: CGPoint(x: segment.outerStart.x, y: segment.outerStart.y),
                end: CGPoint(x: segment.outerEnd.x, y: segment.outerEnd.y),
                colors: [lightColor, bodyColor],
                stops: nil
            )
            gradientPaint.alpha = 1
            var path = Path()
            path.move(to: CGPoint(x: segment.outerStart.x, y: segment.outerStart.y))
            path.addLine(to: CGPoint(x: segment.outerEnd.x, y: segment.outerEnd.y))
            path.addLine(to: CGPoint(x: segment.innerEnd.x, y: segment.innerEnd.y))
            path.addLine(to: CGPoint(x: segment.innerStart.x, y: segment.innerStart.y))
            path.closeSubpath()
            canvas.drawPath(path, gradientPaint)
        }

        strokeSettings(strokeWidth, .butt, .miter)
        applyColor(&strokePaint, outlineColor)
        canvas.drawPath(outerPath, strokePaint)
        canvas.drawPath(innerPath, strokePaint)

        if isPressed {
            drawActiveHeadGlow(x: startX, y: startY, noteColor: colors[0])
        } else {
            let centerSize = holdWidth * 0.15
            applyColor(&fillPaint, colors[0])
            canvas.drawCircle(centerX: startX, centerY: startY, radius: centerSize, fillPaint)
        }

        if endPos.visible && currentTimeMs != 0.0 {
            let approachHalf = noteApproachTimeMs(holdEnd.hiSpeed) / 2
            let endTimeDiff = holdEnd.timingMs - currentTimeMs
            if endTimeDiff <= approachHalf {
                let endCenterSize = endWidth * 0.15
                applyColor(&fillPaint, tint(colors[0]))
                canvas.drawCircle(centerX: endX, centerY: endY, radius: endCenterSize, fillPaint)
            }
        }
    }

    /// Radial head glow while a hold is pressed. Constant brightness, no breathing.
    private func drawActiveHeadGlow(x: Double, y: Double, noteColor: ChartColor) {
        let outer = scaleByRadius(ChartConstants.holdActiveGlowRatio)
        if outer < 1 { return }
        let packed = noteColor.argbValue
        let base = [Int((packed >> 16) & 0xFF), Int((packed >> 8) & 0xFF), Int(packed & 0xFF)]
        let ramp = ChartConstants.holdActiveGlowRamp

        func channel(_ value: Int, _ multiplier: Double) -> Double {
            Double(min(255, max(0, Int((Double(value) * multiplier).rounded())))) / 255.0
        }

        var colors: [ChartColor] = []
        colors.reserveCapacity(ramp.count + 1)
        var positions: [CGFloat] = []
        positions.reserveCapacity(ramp.count + 1)
        for stop in ramp {
            colors.append(ChartColor(
                red: channel(base[0], stop[1]),
                green: channel(base[1], stop[2]),
                blue: channel(base[2], stop[3]),
                alpha: 1
            ))
            positions.append(CGFloat(stop[0]))
        }
        // The ramp ends on a fully transparent stop at radius 1.
        colors.append(ChartColor(red: 0, green: 0, blue: 0, alpha: 0))
        positions.append(1)

        gradientPaint.shading = .radial(
            center: CGPoint(x: x, y: y),
            radius: CGFloat(outer),
            colors: colors,
            stops: positions
        )
        gradientPaint.alpha = 1
        context.canvas.drawCircle(centerX: x, centerY: y, radius: Double(outer), gradientPaint)
    }

    /// One trapezium of the tube surface. Mirrors the Kotlin `Segment` (outer/inner start & end).
    private struct HoldSegment {
        var outerStart: ChartPoint
        var outerEnd: ChartPoint
        var innerStart: ChartPoint
        var innerEnd: ChartPoint
    }
}
