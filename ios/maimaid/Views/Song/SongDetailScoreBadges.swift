import SwiftUI

struct SongDetailScoreBadges: View {
    let dxScore: Int
    let maxDxScore: Int
    let fc: String?
    let fs: String?
    var showStars = true

    var body: some View {
        let stars = DXScoreStars.count(score: dxScore, maximum: maxDxScore)
        HStack(spacing: 4) {
            if showStars && stars > 0 {
                ScoreTintBadge(text: "\(stars) ★", tint: .orange)
            }
            if let fc, !fc.isEmpty {
                ScoreTintBadge(text: ThemeUtils.normalizeFC(fc), tint: ThemeUtils.fcColor(fc))
            }
            if let fs, !fs.isEmpty {
                ScoreTintBadge(text: ThemeUtils.normalizeFS(fs), tint: ThemeUtils.fsColor(fs))
            }
        }
    }
}
