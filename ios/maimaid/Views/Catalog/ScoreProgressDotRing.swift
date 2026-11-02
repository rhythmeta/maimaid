import SwiftUI

/// Difficulty progress ring shown next to a sheet in the catalog.
///
/// The hollow ring fills from `ringStartRate` (97%) to `ringEndRate` (100%), then the inner
/// dot fills from 100% to 101%. Both stages use the same difficulty accent color, so a sheet
/// at 98% shows one third of the ring in accent color and a sheet at 101% shows a solid dot.
struct ScoreProgressDotRing: View {
    let rate: Double
    let color: Color
    var ringLineWidth: Double = 1.2

    /// Achievement rate where the hollow ring starts filling.
    static let ringStartRate = 97.0
    /// Achievement rate where the hollow ring is complete and the inner dot starts filling.
    static let ringEndRate = 100.0

    private var ringProgress: Double {
        min(1, max(0, (rate - Self.ringStartRate) / (Self.ringEndRate - Self.ringStartRate)))
    }

    private var dotProgress: Double {
        min(1, max(0, rate - Self.ringEndRate))
    }

    var body: some View {
        ZStack {
            Circle()
                .stroke(color.opacity(0.3), lineWidth: ringLineWidth)
                .frame(width: 8, height: 8)

            if ringProgress > 0 {
                Circle()
                    .trim(from: 0, to: ringProgress)
                    .stroke(color, style: StrokeStyle(lineWidth: ringLineWidth, lineCap: .butt))
                    .frame(width: 8, height: 8)
                    .rotationEffect(.degrees(-90))
            }

            if dotProgress > 0 {
                Circle()
                    .trim(from: 0, to: dotProgress)
                    .stroke(color, style: StrokeStyle(lineWidth: 4, lineCap: .butt))
                    .frame(width: 4, height: 4)
                    .rotationEffect(.degrees(-90))
            }
        }
    }
}
