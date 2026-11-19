import CoreGraphics
import Foundation
import SwiftUI

/// Gradient shading for a paint, mirroring `android.graphics.Shader` usage in the renderers.
nonisolated enum ChartShading {
    case linear(start: CGPoint, end: CGPoint, colors: [ChartColor], stops: [CGFloat]?)
    case radial(center: CGPoint, radius: CGFloat, colors: [ChartColor], stops: [CGFloat]?)

    func scaledColors(alpha: Double) -> Gradient {
        func gradientColors(_ colors: [ChartColor]) -> [Color] {
            colors.map { $0.multiplyingAlpha(alpha).color }
        }
        switch self {
        case .linear(_, _, let colors, let stops):
            if let stops, stops.count == colors.count {
                return Gradient(stops: zip(stops, gradientColors(colors)).map {
                    Gradient.Stop(color: $0.1, location: $0.0)
                })
            }
            return Gradient(colors: gradientColors(colors))
        case .radial(_, _, let colors, let stops):
            if let stops, stops.count == colors.count {
                return Gradient(stops: zip(stops, gradientColors(colors)).map {
                    Gradient.Stop(color: $0.1, location: $0.0)
                })
            }
            return Gradient(colors: gradientColors(colors))
        }
    }
}

/// Horizontal text alignment, mirroring `Paint.Align`.
nonisolated enum ChartTextAlign: Sendable {
    case left
    case center
    case right
}

/// Paint state for one draw call: fill/stroke style, color or gradient, stroke geometry and text
/// metrics. The Android renderers mutate a small set of `Paint` instances between draws; this struct
/// keeps the same ergonomics.
nonisolated struct ChartPaint {
    enum Style: Sendable {
        case fill
        case stroke
    }

    var style: Style = .fill
    var color: ChartColor = .white
    /// Extra alpha multiplier applied on top of the color's own alpha (`Paint.setAlpha`).
    var alpha: Double = 1
    var strokeWidth: CGFloat = 1
    var strokeCap: CGLineCap = .butt
    var strokeJoin: CGLineJoin = .miter
    var shading: ChartShading?
    var blendMode: GraphicsContext.BlendMode = .normal
    var dash: [CGFloat] = []
    /// Even-odd fill rule, mirroring `Path.FillType.EVEN_ODD` on Android.
    var evenOdd = false

    // Text state.
    var textSize: CGFloat = 12
    var bold = false
    var textAlign: ChartTextAlign = .left

    /// Effective color after the paint alpha is applied.
    var effectiveColor: ChartColor {
        color.multiplyingAlpha(alpha)
    }

    /// Resolves the effective shading for a draw, applying the paint alpha.
    func resolvedShading() -> GraphicsContext.Shading {
        if let shading {
            switch shading {
            case .linear(let start, let end, _, _):
                return .linearGradient(shading.scaledColors(alpha: alpha), startPoint: start, endPoint: end)
            case .radial(let center, let radius, _, _):
                return .radialGradient(shading.scaledColors(alpha: alpha), center: center,
                                       startRadius: 0, endRadius: radius)
            }
        }
        return .color(effectiveColor.color)
    }
}

/// A decoded bitmap the renderer can draw, cached across frames.
nonisolated struct ChartImage: @unchecked Sendable {
    let cgImage: CGImage
    var width: Double { Double(cgImage.width) }
    var height: Double { Double(cgImage.height) }

    init?(_ cgImage: CGImage?) {
        guard let cgImage else { return nil }
        self.cgImage = cgImage
    }
}
