import SwiftUI
import SwiftData
import CoreData

// Best-list calculation state and export presentation are coordinated by this screen.
// swiftlint:disable:next type_body_length
struct BestTableView: View {
    private enum CapacityField {
        case old
        case new
    }

    @Environment(\.modelContext) private var modelContext
    @Environment(\.colorScheme) private var colorScheme
    @Query private var songs: [Song]
    @Query private var configs: [SyncConfig]
    @Query(filter: #Predicate<UserProfile> { $0.isActive == true }) private var activeProfiles: [UserProfile]

    @State private var cache = B50CacheService.shared
    @State private var preferences = BestTablePreferences()

    @State private var showSettings = false
    @State private var isExporting = false

    // MARK: - 临时版本覆盖 (退出页面即失效)
    @State private var overriddenVersion: String?
    @State private var cachedServerVersion: String?
    @AppStorage(AppStorageKeys.best50ConstantMode) private var constantMode: Best50ConstantMode = .server
    @AppStorage(AppStorageKeys.useFitDiff) private var legacyUseFitDiff = false

    // MARK: - Performance / Lifecycle
    @State private var calculationTask: Task<Void, Never>?
    @State private var hasAppeared = false
    @State private var isVisible = false
    @State private var b35CountDraft = 35
    @State private var b15CountDraft = 15

    private var activeProfile: UserProfile? { activeProfiles.first }

    private var availableVersions: [String] {
        UserDefaults.app.maimaiVersionSequence
    }

    private var serverVersion: String? {
        cachedServerVersion
    }

    private var currentB35Count: Int {
        activeProfile?.b35Count ?? configs.first?.b35Count ?? 35
    }

    private var currentB15Count: Int {
        activeProfile?.b15Count ?? configs.first?.b15Count ?? 15
    }

    private var newCapacity: Int {
        preferences.theoretical ? 15 : currentB15Count
    }

    private var oldCapacity: Int {
        preferences.theoretical ? 35 : currentB35Count
    }

    private var filterCategories: [String] {
        Set(songs.map(\.category))
            .filter { !$0.lowercased().contains("utage") && !$0.contains("宴") }
            .sorted { ThemeUtils.categorySortOrder($0) < ThemeUtils.categorySortOrder($1) }
    }

    private var isFilterActive: Bool {
        !preferences.theoretical && !preferences.filters.isEmpty
    }

    private var isSettingsActive: Bool {
        preferences.theoretical || isFilterActive || overriddenVersion != nil
    }

    private var displayedB35: [RatingUtils.RatingEntry] {
        filtered(cache.b50Result.b35)
    }

    private var displayedB15: [RatingUtils.RatingEntry] {
        filtered(cache.b50Result.b15)
    }

    private var summaryData: BestTableSummaryData {
        BestTableSummaryData(b35: displayedB35, b15: displayedB15)
    }

    var body: some View {
        List {
            Section {
                BestTableSummaryCard(data: summaryData)
                    .listRowInsets(EdgeInsets(top: 8, leading: 0, bottom: 8, trailing: 0))
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
            }

            Section(String(localized: "bestTable.section.new \(newCapacity)")) {
                if cache.isLoading && cache.isFirstLoad {
                    ForEach(0..<min(5, newCapacity), id: \.self) { _ in
                        RatingRowSkeletonView()
                            .listRowSeparator(.hidden)
                    }
                } else if displayedB15.isEmpty {
                    Text("bestTable.empty")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(displayedB15) { entry in
                        SongDetailLink(
                            song: cache.getSong(identifier: entry.songIdentifier),
                            preferredType: entry.type
                        ) {
                            BestTableEntryRow(entry: entry)
                        }
                    }
                }
            }

            Section(String(localized: "bestTable.section.old \(oldCapacity)")) {
                if cache.isLoading && cache.isFirstLoad {
                    ForEach(0..<min(8, oldCapacity), id: \.self) { _ in
                        RatingRowSkeletonView()
                            .listRowSeparator(.hidden)
                    }
                } else if displayedB35.isEmpty {
                    Text("bestTable.empty")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(displayedB35) { entry in
                        SongDetailLink(
                            song: cache.getSong(identifier: entry.songIdentifier),
                            preferredType: entry.type
                        ) {
                            BestTableEntryRow(entry: entry)
                        }
                    }
                }
            }
        }
        .navigationTitle("bestTable.title")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    showSettings = true
                } label: {
                    Image(systemName: "slider.horizontal.3")
                }
                .tint(isSettingsActive ? .orange : .primary)
                .accessibilityLabel(Text("bestTable.settings.title"))
            }

            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    exportImage()
                } label: {
                    if isExporting {
                        ProgressView()
                            .controlSize(.small)
                    } else {
                        Label("bestTable.action.export", systemImage: "square.and.arrow.up")
                            .labelStyle(.iconOnly)
                    }
                }
                .disabled(
                    cache.isLoading
                        || isExporting
                        || (summaryData.b15.isEmpty && summaryData.b35.isEmpty)
                )
            }
        }
        .sheet(isPresented: $showSettings) {
            BestTableSettingsView(
                preferences: preferences,
                constantMode: $constantMode,
                selectedVersion: $overriddenVersion,
                b35Count: $b35CountDraft,
                b15Count: $b15CountDraft,
                serverVersion: cachedServerVersion,
                availableVersions: availableVersions,
                categories: filterCategories,
                onCommitB35: { commitCapacity(.old) },
                onCommitB15: { commitCapacity(.new) },
                onReset: resetSettings
            )
        }
        .onAppear {
            migrateLegacyConstantModeIfNeeded()
            isVisible = true
            cache.updateSongs(songs)
            synchronizeCapacityDrafts()

            guard !hasAppeared else { return }
            hasAppeared = true

            scheduleCalculation(delay: .milliseconds(180))
        }
        .task(id: activeProfile?.server) {
            guard let server = activeProfile.flatMap({ GameServer(rawValue: $0.server) }) else {
                cachedServerVersion = nil
                return
            }
            await Task.yield()
            guard !Task.isCancelled else { return }
            cachedServerVersion = ServerVersionService.shared.latestVersion(for: server, songs: songs)
        }
        .onDisappear {
            isVisible = false
            calculationTask?.cancel()
            calculationTask = nil
        }
        .onChange(of: songs.count) { _, _ in
            cache.updateSongs(songs)
            scheduleCalculation()
        }
        .onChange(of: activeProfile?.id) { _, _ in
            synchronizeCapacityDrafts()
            scheduleCalculation()
        }
        .onChange(of: activeProfile?.server) { _, _ in
            scheduleCalculation()
        }
        .onChange(of: currentB35Count) { _, newValue in
            b35CountDraft = newValue
            scheduleCalculation()
        }
        .onChange(of: currentB15Count) { _, newValue in
            b15CountDraft = newValue
            scheduleCalculation()
        }
        .onChange(of: overriddenVersion) { _, _ in
            scheduleCalculation()
        }
        .onChange(of: constantMode) { _, _ in
            scheduleCalculation()
        }
        .onChange(of: preferences.theoretical) { _, newValue in
            if newValue, constantMode != .version {
                constantMode = .version
            }
            scheduleCalculation()
        }
        .onReceive(NotificationCenter.default.publisher(for: .maimaiScoresDidChange)) { notification in
            guard isVisible else { return }
            if let changedProfileID = notification.object as? UUID,
               changedProfileID != activeProfile?.id {
                return
            }
            scheduleCalculation(delay: .milliseconds(180))
        }
        .onReceive(NotificationCenter.default.publisher(for: .maimaiCatalogDidChange)) { _ in
            cache.updateSongs(songs)
            scheduleCalculation()
        }
    }

    // MARK: - Scheduling

    private func scheduleCalculation(delay: Duration = .milliseconds(120)) {
        calculationTask?.cancel()
        calculationTask = Task {
            try? await Task.sleep(for: delay)
            guard !Task.isCancelled, isVisible else { return }
            await performCalculation()
        }
    }

    private func synchronizeCapacityDrafts() {
        b35CountDraft = currentB35Count
        b15CountDraft = currentB15Count
    }

    private func filtered(_ entries: [RatingUtils.RatingEntry]) -> [RatingUtils.RatingEntry] {
        guard !preferences.theoretical, !preferences.filters.isEmpty else { return entries }
        let settings = preferences.filters
        return entries.filter { entry in
            settings.matches(
                entry: entry,
                songCategory: cache.getSong(identifier: entry.songIdentifier)?.category
            )
        }
    }

    // MARK: - Export

    private func exportImage() {
        let b35 = displayedB35
        let b15 = displayedB15
        let totalRating = b35.reduce(0) { $0 + $1.rating } + b15.reduce(0) { $0 + $1.rating }
        guard !isExporting, !(b35.isEmpty && b15.isEmpty) else { return }

        isExporting = true

        Task {
            let image = await MainActor.run {
                B50ExportView.renderImage(
                    b35: b35,
                    b15: b15,
                    totalRating: totalRating,
                    userName: activeProfile?.name,
                    currentVersion: overriddenVersion ?? serverVersion,
                    constantMode: preferences.theoretical ? .version : constantMode,
                    colorScheme: colorScheme
                )
            }

            isExporting = false

            guard let image else { return }

            guard let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
                  let rootVC = scene.windows.first?.rootViewController else {
                return
            }

            var topVC = rootVC
            while let presented = topVC.presentedViewController {
                topVC = presented
            }

            let activityVC = UIActivityViewController(activityItems: [image], applicationActivities: nil)

            if let popover = activityVC.popoverPresentationController {
                popover.sourceView = topVC.view
                popover.sourceRect = CGRect(x: topVC.view.bounds.midX, y: 0, width: 0, height: 0)
                popover.permittedArrowDirections = []
            }

            topVC.present(activityVC, animated: true)
        }
    }

    // MARK: - Capacity

    private func commitCapacity(_ field: CapacityField) {
        switch field {
        case .old:
            let value = min(max(1, b35CountDraft), 99)
            b35CountDraft = value
            if let activeProfile {
                if activeProfile.b35Count != value {
                    activeProfile.b35Count = value
                }
            } else if configs.first?.b35Count != value {
                configs.first?.b35Count = value
            }
        case .new:
            let value = min(max(1, b15CountDraft), 99)
            b15CountDraft = value
            if let activeProfile {
                if activeProfile.b15Count != value {
                    activeProfile.b15Count = value
                }
            } else if configs.first?.b15Count != value {
                configs.first?.b15Count = value
            }
        }
    }

    private func resetSettings() {
        overriddenVersion = nil
        constantMode = .server
        b35CountDraft = 35
        b15CountDraft = 15
        preferences.filters = BestTableFilterSettings()
        commitCapacity(.old)
        commitCapacity(.new)
    }

    // MARK: - Calculation

    private func performCalculation() async {
        if !preferences.theoretical, constantMode == .fitted {
            await ChartStatsService.shared.fetchStats()
        }

        guard isVisible else { return }

        _ = await cache.calculateIfNeeded(
            modelContext: modelContext,
            activeProfile: activeProfile,
            configs: configs,
            overriddenVersion: overriddenVersion,
            constantMode: constantMode,
            theoretical: preferences.theoretical
        )
    }

    private func migrateLegacyConstantModeIfNeeded() {
        guard legacyUseFitDiff else { return }
        if constantMode == .server {
            constantMode = .fitted
        }
        legacyUseFitDiff = false
    }
}

// MARK: - Skeleton View

struct RatingRowSkeletonView: View {
    var body: some View {
        HStack(spacing: 14) {
            RoundedRectangle(cornerRadius: 10)
                .fill(Color.gray.opacity(0.15))
                .frame(width: 56, height: 56)
                .skeleton()

            VStack(alignment: .leading, spacing: 8) {
                RoundedRectangle(cornerRadius: 4)
                    .fill(Color.gray.opacity(0.15))
                    .frame(height: 16)
                    .frame(maxWidth: 160)
                    .skeleton()

                HStack(spacing: 6) {
                    RoundedRectangle(cornerRadius: 4)
                        .fill(Color.gray.opacity(0.15))
                        .frame(width: 30, height: 14)
                        .skeleton()

                    RoundedRectangle(cornerRadius: 4)
                        .fill(Color.gray.opacity(0.15))
                        .frame(width: 50, height: 14)
                        .skeleton()
                }

                HStack(spacing: 4) {
                    RoundedRectangle(cornerRadius: 2)
                        .fill(Color.gray.opacity(0.15))
                        .frame(width: 24, height: 12)
                        .skeleton()

                    RoundedRectangle(cornerRadius: 2)
                        .fill(Color.gray.opacity(0.15))
                        .frame(width: 36, height: 12)
                        .skeleton()
                }
            }
            .frame(minHeight: 56, alignment: .leading)

            Spacer()

            VStack(alignment: .trailing, spacing: 6) {
                RoundedRectangle(cornerRadius: 4)
                    .fill(Color.gray.opacity(0.15))
                    .frame(width: 40, height: 20)
                    .skeleton()

                RoundedRectangle(cornerRadius: 4)
                    .fill(Color.gray.opacity(0.15))
                    .frame(width: 48, height: 12)
                    .skeleton()
            }
        }
        .padding(.vertical, 6)
    }
}
