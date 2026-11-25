import SwiftUI

struct SongDetailDXScore: View {
    let dxScore: Int
    let maxDxScore: Int

    var body: some View {
        VStack(alignment: .trailing) {
            Text(maxDxScore > 0 ? "\(dxScore) / \(maxDxScore)" : "\(dxScore)")
                .font(.subheadline)

            if let progress = DXScoreStars.progress(score: dxScore, maximum: maxDxScore) {
                let current = "\(progress.stars)★ +\(progress.pointsAboveMinimum)"
                Group {
                    if let remaining = progress.pointsToNextStar {
                        Text("\(current) · \(progress.stars + 1)★ -\(remaining)")
                    } else {
                        Text(current)
                    }
                }
                .font(.caption)
                .foregroundStyle(.secondary)
            }
        }
        .monospacedDigit()
        .lineLimit(1)
        .minimumScaleFactor(0.75)
    }
}
