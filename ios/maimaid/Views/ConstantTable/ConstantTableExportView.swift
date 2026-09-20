import SwiftData
import SwiftUI
import UIKit

// Export loading, filtering, preview state, and image generation share one lifecycle.
// swiftlint:disable:next type_body_length
struct ConstantTableExportView: View {
    enum Mode: String, CaseIterable {
        case constantsOnly
        case withScores
    }

    struct Entry: Identifiable, Sendable {
        let id: String
        let songIdentifier: String
        let songTitle: String
        let imageName: String
        let difficulty: String
        let type: String
        let level: Double
        let category: String
        let version: String?
        let isFavorite: Bool
        let rank: String?
        let fc: String?
        let fs: String?
    }

    struct ExportSection: Identifiable, Sendable {
        let levelLabel: String
        let entries: [Entry]

        var id: String { levelLabel }
    }

    struct SharePayload: Identifiable {
        let id = UUID()
        let image: UIImage
    }

    private static var prefetchedSongsDescriptor: FetchDescriptor<Song> {
        var descriptor = FetchDescriptor<Song>()
        descriptor.relationshipKeyPathsForPrefetching = [\Song.sheets]
        return descriptor
    }

    @Environment(\.modelContext) private var modelContext
    @Environment(\.colorScheme) private var colorScheme
    @Query(Self.prefetchedSongsDescriptor) private var songs: [Song]
    @Query(filter: #Predicate<UserProfile> { $0.isActive == true }) private var activeProfiles: [UserProfile]

    @State private var allEntries: [Entry] = []
    @State private var displayedSections: [ExportSection] = []
    @State private var songMap: [String: Song] = [:]
    @State private var selectedBaseLevel = 14
    @State private var includesScores = false
    @State private var filterSettings = FilterSettings()
    @State private var showFilterSheet = false
    @State private var isLoading = true
    @State private var isExporting = false
    @State private var sharePayload: SharePayload?

    private var activeProfile: UserProfile? { activeProfiles.first }
    private var activeServer: GameServer {
        activeProfile.flatMap { GameServer(rawValue: $0.server) } ?? .jp
    }
    private var mode: Mode { includesScores ? .withScores : .constantsOnly }

    private var availableBaseLevels: [Int] {
        Array(Set(allEntries.map { exportBucketBaseLevel(for: $0.level) }))
            .sorted(by: >)
    }

    private var displayedEntryCount: Int {
        displayedSections.reduce(0) { $0 + $1.entries.count }
    }

    private var allCategories: [String] {
        Array(Set(songs.map(\.category))).sorted {
            ThemeUtils.categorySortOrder($0) < ThemeUtils.categorySortOrder($1)
        }
    }

    private var allVersions: [String] {
        Array(Set(songs.compactMap(\.version))).sorted {
            ThemeUtils.versionSortOrder($0) < ThemeUtils.versionSortOrder($1)
        }
    }

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading) {
                if isLoading {
                    ProgressView()
                        .frame(maxWidth: .infinity)
                        .padding()
                } else if availableBaseLevels.isEmpty {
                    ContentUnavailableView(
                        "scoreQuery.export.empty",
                        systemImage: "music.note.list",
                        description: Text("scoreQuery.export.empty.description")
                    )
                } else {
                    VStack {
                        HStack {
                            Label("scoreQuery.export.level", systemImage: "chart.bar.fill")

                            Spacer()

                            Picker("scoreQuery.export.level", selection: $selectedBaseLevel) {
                                ForEach(availableBaseLevels, id: \.self) { value in
                                    Text(exportBaseLevelLabel(for: value)).tag(value)
                                }
                            }
                            .labelsHidden()
                        }

                        Divider()

                        Toggle(isOn: $includesScores) {
                            Label(
                                "scoreQuery.export.mode.scores",
                                systemImage: includesScores ? "person.text.rectangle.fill" : "person.text.rectangle"
                            )
                        }
                    }
                    .padding()
                    .background(
                        Color(uiColor: .secondarySystemGroupedBackground),
                        in: .rect(cornerRadius: 16)
                    )

                    HStack {
                        Label("scoreQuery.export.regularOnly", systemImage: "music.note")

                        Spacer()

                        Text(
                            "\(displayedEntryCount.formatted()) \(String(localized: "scoreQuery.export.charts")) · "
                                + "\(displayedSections.count.formatted()) "
                                + String(localized: "scoreQuery.export.sections")
                        )
                        .foregroundStyle(.secondary)
                        .monospacedDigit()
                    }
                    .padding()
                    .background(
                        Color(uiColor: .secondarySystemGroupedBackground),
                        in: .rect(cornerRadius: 16)
                    )

                    Button {
                        exportConstantTable()
                    } label: {
                        HStack {
                            Spacer()
                            if isExporting {
                                ProgressView()
                                    .controlSize(.small)
                            } else {
                                Image(systemName: "photo.on.rectangle.angled")
                            }
                            Text(isExporting ? "scoreQuery.export.exporting" : "scoreQuery.export.button")
                            Spacer()
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
                    .disabled(displayedSections.isEmpty || isExporting)

                    ConstantTablePreviewView(
                        sections: displayedSections,
                        includesScores: includesScores,
                        songMap: songMap
                    )
                }
            }
            .padding()
        }
        .background(Color(uiColor: .systemGroupedBackground))
        .navigationTitle("scoreQuery.export.title")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    showFilterSheet = true
                } label: {
                    Image(systemName: "line.3.horizontal.decrease.circle")
                        .foregroundStyle(filterSettings.hasConstantTableFilters ? .blue : .primary)
                }
                .accessibilityLabel("filter.title")
            }
        }
        .sheet(isPresented: $showFilterSheet) {
            FilterView(
                settings: $filterSettings,
                allCategories: allCategories,
                allVersions: allVersions,
                showsDifficultyAndType: false
            )
        }
        .sheet(item: $sharePayload) { payload in
            ShareSheetView(items: [payload.image])
        }
        .task(id: activeProfile?.server) {
            await loadData()
        }
        .onChange(of: filterSettings) { _, _ in
            if let firstLevel = availableBaseLevels.first,
               !availableBaseLevels.contains(selectedBaseLevel) {
                selectedBaseLevel = firstLevel
            }
            rebuildDisplayedSections()
        }
        .onChange(of: selectedBaseLevel) { _, _ in
            rebuildDisplayedSections()
        }
        .onReceive(NotificationCenter.default.publisher(for: .maimaiScoresDidChange)) { notification in
            if let changedProfileID = notification.object as? UUID,
               changedProfileID != activeProfile?.id {
                return
            }
            Task { await loadData() }
        }
    }

    private func loadData() async {
        isLoading = true
        let scoreMap = ScoreService.shared.scoreMap(context: modelContext)
        var entries: [Entry] = []
        var songsByIdentifier: [String: Song] = [:]

        for (index, song) in songs.enumerated() {
            if index.isMultiple(of: 32) {
                await Task.yield()
            }
            if song.category.localizedStandardContains("utage") || song.category.contains("宴") {
                continue
            }
            songsByIdentifier[song.songIdentifier] = song

            for sheet in song.sheets {
                if sheet.type.localizedStandardContains("utage") {
                    continue
                }

                guard ServerChartPolicy.isPlayable(sheet, on: activeServer) else { continue }
                let metadata = ServerChartPolicy.metadata(for: sheet, on: activeServer)
                guard let level = metadata.ratingLevel else { continue }
                let score = scoreForSheet(sheet, in: scoreMap)

                entries.append(
                    Entry(
                        id: "\(sheet.songIdentifier)_\(sheet.type)_\(sheet.difficulty)",
                        songIdentifier: song.songIdentifier,
                        songTitle: song.title,
                        imageName: song.imageName,
                        difficulty: sheet.difficulty,
                        type: sheet.type,
                        level: level,
                        category: song.category,
                        version: song.version,
                        isFavorite: song.isFavorite,
                        rank: score.map { RatingUtils.calculateRank(achievement: $0.rate) },
                        fc: score?.fc,
                        fs: score?.fs
                    )
                )
            }
        }

        allEntries = entries
        songMap = songsByIdentifier
        if let firstLevel = availableBaseLevels.first, !availableBaseLevels.contains(selectedBaseLevel) {
            selectedBaseLevel = firstLevel
        }
        rebuildDisplayedSections()
        isLoading = false
    }

    private func rebuildDisplayedSections() {
        let filteredEntries = allEntries
            .filter { entry in
                if filterSettings.showFavoritesOnly && !entry.isFavorite { return false }
                if !filterSettings.selectedCategories.isEmpty,
                   !filterSettings.selectedCategories.contains(entry.category) { return false }
                if !filterSettings.selectedVersions.isEmpty,
                   entry.version.map({ !filterSettings.selectedVersions.contains($0) }) ?? true { return false }
                return true
            }
            .filter { exportBucketBaseLevel(for: $0.level) == selectedBaseLevel }
            .sorted(by: exportEntryComparator)
        let grouped = Dictionary(grouping: filteredEntries) { constantKey(for: $0.level) }

        displayedSections = grouped
            .map { levelKey, entries in
                ExportSection(levelLabel: levelKey, entries: entries)
            }
            .sorted { lhs, rhs in
                (Double(lhs.levelLabel) ?? 0) > (Double(rhs.levelLabel) ?? 0)
            }
    }

    private func scoreForSheet(_ sheet: Sheet, in map: [String: Score]) -> Score? {
        for candidate in candidateSheetIDs(for: sheet) {
            if let score = map[candidate] {
                return score
            }
        }
        return nil
    }

    private func candidateSheetIDs(for sheet: Sheet) -> [String] {
        let rawIdentifiers: [String?] = [
            sheet.songIdentifier,
            sheet.song.map { String($0.songId) },
            sheet.song?.songIdentifier,
            sheet.songId == 0 ? nil : String(sheet.songId)
        ]
        var candidates: [String] = []
        var seen = Set<String>()

        for raw in rawIdentifiers {
            guard let raw, !raw.isEmpty, raw != "0" else { continue }
            for separator in ["_", "-"] {
                let sheetID = "\(raw)\(separator)\(sheet.type)\(separator)\(sheet.difficulty)"
                if seen.insert(sheetID).inserted {
                    candidates.append(sheetID)
                }
            }
        }

        return candidates
    }

    private func constantKey(for level: Double) -> String {
        let normalized = (level * 10).rounded(.towardZero) / 10
        return normalized.formatted(.number.precision(.fractionLength(1)))
    }

    private func exportBucketBaseLevel(for level: Double) -> Int {
        level >= 15 ? 14 : Int(level.rounded(.down))
    }

    private func exportBaseLevelLabel(for level: Int) -> String {
        level == 14 ? "14~15" : level.formatted()
    }

    private func exportEntryComparator(_ lhs: Entry, _ rhs: Entry) -> Bool {
        if lhs.songTitle != rhs.songTitle {
            return lhs.songTitle.localizedStandardCompare(rhs.songTitle) == .orderedAscending
        }

        let lhsDifficulty = ThemeUtils.difficultyOrder(lhs.difficulty)
        let rhsDifficulty = ThemeUtils.difficultyOrder(rhs.difficulty)
        if lhsDifficulty != rhsDifficulty {
            return lhsDifficulty > rhsDifficulty
        }

        if lhs.type != rhs.type {
            return lhs.type.localizedStandardCompare(rhs.type) == .orderedAscending
        }

        return lhs.id < rhs.id
    }

    private func exportConstantTable() {
        guard !displayedSections.isEmpty else { return }
        isExporting = true

        Task { @MainActor in
            await Task.yield()
            let image = ConstantTableExportImageView.renderImage(
                baseLevel: selectedBaseLevel,
                sections: displayedSections,
                mode: mode,
                userName: activeProfile?.name,
                colorScheme: colorScheme
            )
            isExporting = false
            if let image {
                sharePayload = SharePayload(image: image)
            }
        }
    }
}
