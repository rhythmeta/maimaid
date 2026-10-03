import SwiftUI

enum BadgeContrast {
    static func foreground(on backgrounds: [Color], in environment: EnvironmentValues) -> Color {
        guard let first = backgrounds.first?.resolve(in: environment) else { return .primary }
        let darkTint = Color(
            .sRGB,
            red: Double(first.red) * 0.2,
            green: Double(first.green) * 0.2,
            blue: Double(first.blue) * 0.2,
            opacity: 1
        )
        let luminances = backgrounds.map { luminance($0.resolve(in: environment)) }
        func minimumContrast(_ color: Color) -> Double {
            let foreground = luminance(color.resolve(in: environment))
            return luminances.map { (max($0, foreground) + 0.05) / (min($0, foreground) + 0.05) }
                .min() ?? 1
        }

        // Both halves of a split badge must remain readable.
        if minimumContrast(darkTint) >= 4.5 { return darkTint }
        return minimumContrast(.white) >= minimumContrast(.black) ? .white : .black
    }

    private static func luminance(_ color: Color.Resolved) -> Double {
        func linear(_ channel: Float) -> Double {
            let value = Double(channel)
            return value <= 0.04045 ? value / 12.92 : pow((value + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)
    }
}
