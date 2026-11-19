import CoreGraphics
import Foundation

/// Canvas geometry for one frame. Mirrors the reference `RenderContext`: the judgment ring radius
/// is the single scale unit, so every ratio in `ChartConstants` multiplies this value.
nonisolated final class RenderContext {
    var canvas: ChartCanvas
    var logicalSize: Float
    var centerX: Float
    var centerY: Float
    var radius: Float
    var config: ChartRenderConfig

    init(canvas: ChartCanvas, logicalSize: Float, centerX: Float, centerY: Float, radius: Float,
         config: ChartRenderConfig) {
        self.canvas = canvas
        self.logicalSize = logicalSize
        self.centerX = centerX
        self.centerY = centerY
        self.radius = radius
        self.config = config
    }
}
