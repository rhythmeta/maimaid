import Foundation

// MARK: - Theoretical B50

// swiftlint:disable function_body_length
extension RatingUtils {
    /// The version's theoretical maximum B50: every available chart is assumed to be
    /// played at 101% with AP+, using the version constants already baked into
    /// `CalculationInput`'s `calculationLevel`.
    static func calculateTheoreticalB50(
        input: CalculationInput,
        b35Count: Int,
        b15Count: Int,
        latestVersion: String?
    ) -> (total: Int, b35: [RatingEntry], b15: [RatingEntry]) {
        var allEntries: [(entry: RatingEntry, isNew: Bool)] = []
        let afterCircle = isAfterCircle(version: latestVersion)
        let theoreticalAchievement = 101.0
        let theoreticalFC = "app"
        let theoreticalFS = "fsdp"

        for songData in input.songs {
            if songData.category.lowercased().contains("utage") || songData.category.contains("宴") {
                continue
            }

            for sheetData in songData.sheets {
                if sheetData.type.lowercased().contains("utage") { continue }

                let category = determineSheetCategory(
                    sheet: sheetData,
                    song: songData,
                    latestServerVersion: latestVersion,
                    server: input.server
                )
                guard category != .excluded else { continue }
                guard let internalLevel = sheetData.calculationLevel, internalLevel > 0 else { continue }

                let rating = calculateRating(
                    internalLevel: internalLevel,
                    achievement: theoreticalAchievement,
                    fc: theoreticalFC,
                    afterCircle: afterCircle
                )
                guard rating > 0 else { continue }

                let maxDxScore = max(sheetData.total ?? 0, 0) * 3
                let entry = RatingEntry(
                    songId: songData.songId,
                    songIdentifier: songData.songIdentifier,
                    songTitle: songData.title,
                    imageName: songData.imageName,
                    achievement: theoreticalAchievement,
                    rating: rating,
                    level: internalLevel,
                    diff: sheetData.difficulty,
                    type: sheetData.type,
                    dxScore: maxDxScore,
                    maxDxScore: maxDxScore,
                    fc: theoreticalFC,
                    fs: theoreticalFS
                )
                allEntries.append((entry: entry, isNew: category == .b15))
            }
        }

        let sorted = allEntries.sorted { lhs, rhs in
            if lhs.entry.rating != rhs.entry.rating { return lhs.entry.rating > rhs.entry.rating }
            if lhs.entry.songIdentifier != rhs.entry.songIdentifier {
                return lhs.entry.songIdentifier < rhs.entry.songIdentifier
            }
            if lhs.entry.type != rhs.entry.type { return lhs.entry.type < rhs.entry.type }
            return lhs.entry.diff < rhs.entry.diff
        }
        let b15Entries = sorted.filter { $0.isNew }.prefix(b15Count).map { $0.entry }
        let b35Entries = sorted.filter { !$0.isNew }.prefix(b35Count).map { $0.entry }

        return (
            total: b15Entries.reduce(0) { $0 + $1.rating } + b35Entries.reduce(0) { $0 + $1.rating },
            b35: Array(b35Entries),
            b15: Array(b15Entries)
        )
    }
}
// swiftlint:enable function_body_length
