import Foundation

enum DXScoreStars {
    static func count(score: Int, maximum: Int) -> Int {
        guard maximum > 0 else { return 0 }
        let ratio = Double(score) / Double(maximum)
        if ratio >= 0.97 { return 5 }
        if ratio >= 0.95 { return 4 }
        if ratio >= 0.93 { return 3 }
        if ratio >= 0.90 { return 2 }
        if ratio >= 0.85 { return 1 }
        return 0
    }
}
