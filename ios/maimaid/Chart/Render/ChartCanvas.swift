import CoreGraphics
import Foundation
import SwiftUI

/// Drawing surface for the chart stage.
///
/// The renderers are a 1:1 port of the reference engine's canvas painters, which are written against
/// `android.graphics.Canvas` + `Paint`. This type keeps that call shape on top of SwiftUI's
/// `GraphicsContext`: it owns the current context (whose transform and clip state can be saved and
/// restored), and translates paint state into `GraphicsContext` draw calls.
nonisolated final class ChartCanvas {
    var graphics: GraphicsContext
    /// Side length of the square stage in points, used by `drawColor` and by painters that need the
    /// full-stage extent.
    var logicalSize: CGFloat

    private var saveStack: [GraphicsContext] = []

    init(graphics: GraphicsContext, logicalSize: CGFloat) {
        self.graphics = graphics
        self.logicalSize = logicalSize
    }

    // MARK: - State

    func save() {
        saveStack.append(graphics)
    }

    func restore() {
        guard let previous = saveStack.popLast() else { return }
        graphics = previous
    }

    func withSave(_ body: (ChartCanvas) -> Void) {
        save()
        body(self)
        restore()
    }

    func translate(_ dx: CGFloat, _ dy: CGFloat) {
        graphics.translateBy(x: dx, y: dy)
    }

    func withTranslation(_ dx: CGFloat, _ dy: CGFloat, _ body: (ChartCanvas) -> Void) {
        withSave { canvas in
            canvas.translate(dx, dy)
            body(canvas)
        }
    }

    /// Rotates around a pivot, matching `Canvas.rotate(degrees, px, py)`.
    func rotate(degrees: CGFloat, pivotX: CGFloat, pivotY: CGFloat) {
        graphics.translateBy(x: pivotX, y: pivotY)
        graphics.rotate(by: .degrees(degrees))
        graphics.translateBy(x: -pivotX, y: -pivotY)
    }

    func withRotation(degrees: CGFloat, pivotX: CGFloat, pivotY: CGFloat, _ body: (ChartCanvas) -> Void) {
        withSave { canvas in
            canvas.rotate(degrees: degrees, pivotX: pivotX, pivotY: pivotY)
            body(canvas)
        }
    }

    func clipPath(_ path: Path, evenOdd: Bool = false) {
        graphics.clip(to: path, style: FillStyle(eoFill: evenOdd))
    }

    func clipRect(_ rect: CGRect) {
        graphics.clip(to: Path(rect))
    }

    /// Renders `body` into an offscreen layer that is composited with `blendMode`.
    func drawLayer(blendMode: GraphicsContext.BlendMode = .normal, _ body: (ChartCanvas) -> Void) {
        let previous = graphics.blendMode
        graphics.blendMode = blendMode
        let size = logicalSize
        graphics.drawLayer { layerContext in
            let layerCanvas = ChartCanvas(graphics: layerContext, logicalSize: size)
            layerCanvas.saveStack = []
            body(layerCanvas)
        }
        graphics.blendMode = previous
    }

    // MARK: - Primitive draws

    func drawColor(_ color: ChartColor) {
        graphics.fill(Path(CGRect(x: 0, y: 0, width: logicalSize, height: logicalSize)),
                      with: .color(color.color))
    }

    func drawPath(_ path: Path, _ paint: ChartPaint) {
        let previousBlend = graphics.blendMode
        if paint.blendMode != .normal { graphics.blendMode = paint.blendMode }
        switch paint.style {
        case .fill:
            graphics.fill(path, with: paint.resolvedShading(), style: FillStyle(eoFill: paint.evenOdd))
        case .stroke:
            graphics.stroke(path, with: paint.resolvedShading(), style: paint.strokeStyle)
        }
        graphics.blendMode = previousBlend
    }

    func drawRect(_ rect: CGRect, _ paint: ChartPaint) {
        drawPath(Path(rect), paint)
    }

    func drawOval(_ rect: CGRect, _ paint: ChartPaint) {
        drawPath(Path(ellipseIn: rect), paint)
    }

    func drawCircle(centerX: CGFloat, centerY: CGFloat, radius: CGFloat, _ paint: ChartPaint) {
        let rect = CGRect(x: centerX - radius, y: centerY - radius, width: radius * 2, height: radius * 2)
        drawPath(Path(ellipseIn: rect), paint)
    }

    func drawCircle(centerX: Double, centerY: Double, radius: Double, _ paint: ChartPaint) {
        drawCircle(centerX: CGFloat(centerX), centerY: CGFloat(centerY), radius: CGFloat(radius), paint)
    }

    func drawImage(_ image: ChartImage, in rect: CGRect, alpha: Double = 1) {
        guard alpha > 0 else { return }
        let previous = graphics.opacity
        graphics.opacity = alpha
        let resolved = graphics.resolve(Image(decorative: image.cgImage, scale: 1, orientation: .up))
        graphics.draw(resolved, in: rect)
        graphics.opacity = previous
    }

    // MARK: - Text

    func measureText(_ text: String, _ paint: ChartPaint) -> CGSize {
        let resolved = graphics.resolve(makeText(text, paint))
        return resolved.measure(in: CGSize(width: CGFloat.infinity, height: CGFloat.infinity))
    }

    /// Approximate ascent of the platform font at the paint's text size.
    func textAscent(_ paint: ChartPaint) -> CGFloat { paint.textSize * 0.78 }
    func textDescent(_ paint: ChartPaint) -> CGFloat { paint.textSize * 0.22 }

    /// Draws text with its baseline at `y`, honoring the paint's alignment.
    func drawText(_ text: String, x: CGFloat, y: CGFloat, _ paint: ChartPaint) {
        guard !text.isEmpty else { return }
        let size = measureText(text, paint)
        let originX: CGFloat
        switch paint.textAlign {
        case .left: originX = x
        case .center: originX = x - size.width / 2
        case .right: originX = x - size.width
        }
        let previous = graphics.opacity
        graphics.opacity = paint.alpha
        var resolved = graphics.resolve(makeText(text, paint))
        resolved.shading = .color(paint.color.color)
        graphics.draw(resolved, at: CGPoint(x: originX, y: y - textAscent(paint)), anchor: .topLeading)
        graphics.opacity = previous
    }

    private func makeText(_ text: String, _ paint: ChartPaint) -> Text {
        Text(text).font(.system(size: paint.textSize, weight: paint.bold ? .bold : .regular))
    }
}

nonisolated extension ChartPaint {
    /// Stroke configuration translated from the paint state.
    var strokeStyle: StrokeStyle {
        StrokeStyle(
            lineWidth: strokeWidth,
            lineCap: strokeCap == .round ? .round : (strokeCap == .square ? .square : .butt),
            lineJoin: strokeJoin == .round ? .round : (strokeJoin == .bevel ? .bevel : .miter),
            miterLimit: 10,
            dash: dash
        )
    }
}
