import CoreGraphics
import Foundation
import SwiftUI

/// Straight-alpha RGBA color used by the chart renderer.
///
/// The reference engine and the Android port thread packed ARGB integers through every painter.
/// This type keeps that math (alpha multiplication, lightening/darkening, lerping) in one place and
/// converts to `CGColor` only at draw time.
nonisolated struct ChartColor: Hashable, Sendable {
    var red: Double
    var green: Double
    var blue: Double
    var alpha: Double

    init(red: Double, green: Double, blue: Double, alpha: Double = 1) {
        self.red = red
        self.green = green
        self.blue = blue
        self.alpha = alpha
    }

    /// Builds a color from a packed `0xAARRGGBB` value, the format the reference palette uses.
    init(argb: UInt32) {
        self.init(
            red: Double((argb >> 16) & 0xFF) / 255.0,
            green: Double((argb >> 8) & 0xFF) / 255.0,
            blue: Double(argb & 0xFF) / 255.0,
            alpha: Double((argb >> 24) & 0xFF) / 255.0
        )
    }

    /// Builds a color from a packed `0xRRGGBB` value at full opacity.
    init(rgb: UInt32) {
        self.init(argb: 0xFF00_0000 | (rgb & 0x00FF_FFFF))
    }

    static let black = ChartColor(rgb: 0x000000)
    static let white = ChartColor(rgb: 0xFFFFFF)

    /// `0xAARRGGBB` representation, matching the reference palette constants.
    var argbValue: UInt32 {
        let a = UInt32((alpha.chartClamped01 * 255).rounded())
        let r = UInt32((red.chartClamped01 * 255).rounded())
        let g = UInt32((green.chartClamped01 * 255).rounded())
        let b = UInt32((blue.chartClamped01 * 255).rounded())
        return (a << 24) | (r << 16) | (g << 8) | b
    }

    var cgColor: CGColor {
        CGColor(
            srgbRed: red.chartClamped01,
            green: green.chartClamped01,
            blue: blue.chartClamped01,
            alpha: alpha.chartClamped01
        )
    }

    var color: Color {
        Color(.sRGB, red: red.chartClamped01, green: green.chartClamped01, blue: blue.chartClamped01, opacity: alpha.chartClamped01)
    }

    func withAlpha(_ alpha: Double) -> ChartColor {
        ChartColor(red: red, green: green, blue: blue, alpha: alpha)
    }

    /// Multiplies the existing alpha, the way `Paint.setAlpha` behaves on Android.
    func multiplyingAlpha(_ factor: Double) -> ChartColor {
        ChartColor(red: red, green: green, blue: blue, alpha: alpha * factor)
    }

    func lightened(_ ratio: Double) -> ChartColor {
        ChartColor(
            red: red + (1 - red) * ratio,
            green: green + (1 - green) * ratio,
            blue: blue + (1 - blue) * ratio,
            alpha: alpha
        )
    }

    func darkened(_ ratio: Double) -> ChartColor {
        ChartColor(
            red: red * (1 - ratio),
            green: green * (1 - ratio),
            blue: blue * (1 - ratio),
            alpha: alpha
        )
    }

    func mixed(with other: ChartColor, fraction: Double) -> ChartColor {
        let t = fraction
        return ChartColor(
            red: red + (other.red - red) * t,
            green: green + (other.green - green) * t,
            blue: blue + (other.blue - blue) * t,
            alpha: alpha + (other.alpha - alpha) * t
        )
    }

    /// Alpha-weighted blend of two paints, mirroring `alphaMix` in the reference engine.
    func blended(over other: ChartColor, alpha: Double) -> ChartColor {
        mixed(with: other, fraction: alpha)
    }
}

nonisolated fileprivate extension Double {
    var chartClamped01: Double { Swift.min(1, Swift.max(0, self)) }
}
