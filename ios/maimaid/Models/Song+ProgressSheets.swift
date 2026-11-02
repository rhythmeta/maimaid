import Foundation

@MainActor
extension Song {
    /// Sheets whose progress dots appear in the catalog: DX charts when the song has them,
    /// otherwise standard charts, otherwise 宴 (utage) charts, each ordered hardest first.
    var progressDotSheets: [Sheet] {
        func hardestFirst(_ sheets: [Sheet]) -> [Sheet] {
            sheets.sorted {
                ThemeUtils.difficultyOrder($0.difficulty) > ThemeUtils.difficultyOrder($1.difficulty)
            }
        }

        let dxSheets = sheets.filter { $0.type.lowercased() == "dx" }
        if !dxSheets.isEmpty { return hardestFirst(dxSheets) }

        let standardSheets = sheets.filter { $0.type.lowercased() == "std" }
        if !standardSheets.isEmpty { return hardestFirst(standardSheets) }

        return hardestFirst(sheets.filter { $0.type.lowercased().contains("utage") })
    }
}
