// The preview page owns the stage, transport and panel composition; splitting it further would
// scatter the shared @State that drives them.
import SwiftUI
import Observation

/// One previewable sheet: its catalog sheet, the simai slot and the published maidata it lives in.
@MainActor
struct PreviewChartOption: Identifiable {
    let sheet: Sheet
    let slot: Int
    let entry: ChartAssetIndex.Entry
    let maidata: String

    var id: String { ChartPreviewSheetKey.key(for: sheet) }

    /// Short label for the difficulty selector.
    var shortLabel: String { ChartPreviewSheetKey.shortDifficultyName(sheet.difficulty) }

    var displayType: String { ChartPreviewSheetKey.displayType(sheet.type) }
}

/// Sheet identity helpers shared by the preview page and the song-detail entry point.
nonisolated enum ChartPreviewSheetKey {
    /// Stable identity of a catalog sheet inside one song.
    @MainActor
    static func key(for sheet: Sheet) -> String {
        key(type: sheet.type, difficulty: sheet.difficulty)
    }

    static func key(type: String, difficulty: String) -> String {
        "\(type.lowercased()):\(difficulty.lowercased())"
    }

    /// Maps a catalog difficulty name to the simai `&inote_N` slot.
    static func difficultySlot(_ difficulty: String) -> Int {
        if difficulty.localizedCaseInsensitiveContains("utage") { return 7 }
        if difficulty.contains("宴") { return 7 }
        if difficulty.localizedCaseInsensitiveContains("remaster") { return 6 }
        if difficulty.localizedCaseInsensitiveContains("master") { return 5 }
        if difficulty.localizedCaseInsensitiveContains("expert") { return 4 }
        if difficulty.localizedCaseInsensitiveContains("advanced") { return 3 }
        if difficulty.localizedCaseInsensitiveContains("basic") { return 2 }
        return 5
    }

    /// `STD` / `DX` / upper-cased type, mirroring the Android display mapping.
    static func displayType(_ type: String) -> String {
        switch type.lowercased() {
        case "std", "standard": return "STD"
        default: return type.uppercased()
        }
    }

    /// Three-letter difficulty label used by the selector and the report header.
    static func shortDifficultyName(_ difficulty: String) -> String {
        if difficulty.localizedCaseInsensitiveContains("remaster") { return "ReM" }
        if difficulty.localizedCaseInsensitiveContains("master") { return "MAS" }
        if difficulty.localizedCaseInsensitiveContains("expert") { return "EXP" }
        if difficulty.localizedCaseInsensitiveContains("advanced") { return "ADV" }
        if difficulty.localizedCaseInsensitiveContains("basic") { return "BAS" }
        if difficulty.localizedCaseInsensitiveContains("utage") { return "宴" }
        return difficulty.uppercased()
    }
}

/// Loads every previewable chart of a song. The published maidata does not always contain all
/// difficulties, so only slots actually present in the asset become selectable.
@MainActor
@Observable
final class ChartPreviewViewModel {
    enum State: Equatable {
        case loading
        case ready
        case failed(String)
    }

    let song: Song
    private let preferredSheetKey: String?

    private(set) var state: State = .loading
    /// Incremented whenever the parsed chart changes, so the page can rebuild its scene graph.
    private(set) var chartRevision = 0
    private(set) var options: [PreviewChartOption] = []
    private(set) var selectedKey: String = ""
    private(set) var chart: Chart?
    private(set) var parseError: String?
    private(set) var hasAudioAsset = false
    private(set) var downloadProgress: TrackDownloadProgress?
    private(set) var coverImage: ChartImage?
    private(set) var sensorImage: ChartImage? = ChartImageLoader.sensorImage()

    let audioEngine = ChartAudioEngine()
    let settingsStore: ChartPreviewSettingsStore
    var settings: ChartPreviewSettings { settingsStore.settings }

    /// Audio asset shared by every chart type of this song (DX and STD use the same track).
    private var songAudioAsset: ChartAssetIndex.File?
    private var preparedAudioKey: String?
    private var audioPrepareTask: Task<Void, Never>?
    private var coverCache: (key: String, image: ChartImage?)?

    init(song: Song, preferredSheetKey: String?,
         settingsStore: ChartPreviewSettingsStore = .shared) {
        self.song = song
        self.preferredSheetKey = preferredSheetKey
        self.settingsStore = settingsStore
    }

    var selectedOption: PreviewChartOption? {
        options.first { $0.id == selectedKey } ?? options.first
    }

    /// True when this song has a shared audio track.
    var audioPending: Bool {
        guard hasAudioAsset else { return false }
        switch audioEngine.state {
        case .idle, .preparing: return true
        case .ready, .failed: return false
        }
    }

    // MARK: - Loading

    func load() async {
        state = .loading
        parseError = nil
        do {
            let index = try await ChartAssetClient.fetchIndex()
            var maidataByEntry: [Int: String?] = [:]
            var loaded: [PreviewChartOption] = []

            for sheet in song.sheets {
                guard sheet.songId > 0 else { continue }
                let candidates = ChartAssetIdResolver.candidates(providerSongId: sheet.songId,
                                                                 chartType: sheet.type)
                guard let entry = candidates.compactMap({ index.entry(for: $0) }).first else { continue }

                let maidata: String
                if let cached = maidataByEntry[entry.id] {
                    guard let cached else { continue }
                    maidata = cached
                } else {
                    let fetched = try? await fetchMaidata(entry)
                    maidataByEntry[entry.id] = fetched
                    guard let fetched else { continue }
                    maidata = fetched
                }

                let availableSlots = SimaiParser.availableDifficulties(maidata)
                let slot: Int?
                if sheet.type.localizedCaseInsensitiveContains("utage") {
                    // UTAGE sheets carry a marker (e.g. 「協」) instead of a difficulty name and always
                    // live in slot 7; fall back to the only published slot if needed.
                    slot = availableSlots.contains(7) ? 7 : availableSlots.max()
                } else {
                    slot = ChartPreviewSheetKey.difficultySlot(sheet.difficulty)
                }
                guard let slot, availableSlots.contains(slot) else { continue }
                loaded.append(PreviewChartOption(sheet: sheet, slot: slot, entry: entry, maidata: maidata))
            }

            guard !loaded.isEmpty else {
                state = .failed(String(localized: "chart.preview.error.noChart"))
                return
            }

            // DX and STD reference separate chart entries but the same music. Pick one canonical
            // audio asset for the song (the base/STD entry where available) so switching chart types
            // reuses the downloaded MP3 and the decoded mix instead of downloading again.
            songAudioAsset = loaded
                .sorted { $0.entry.id < $1.entry.id }
                .lazy
                .compactMap { $0.entry.files.audio }
                .first
            hasAudioAsset = songAudioAsset != nil

            let ordered = loaded.sorted { lhs, rhs in
                let lhsDX = lhs.sheet.type.caseInsensitiveCompare("dx") == .orderedSame ? 0 : 1
                let rhsDX = rhs.sheet.type.caseInsensitiveCompare("dx") == .orderedSame ? 0 : 1
                if lhsDX != rhsDX { return lhsDX < rhsDX }
                let lhsOrder = ThemeUtils.difficultyOrder(lhs.sheet.difficulty)
                let rhsOrder = ThemeUtils.difficultyOrder(rhs.sheet.difficulty)
                if lhsOrder != rhsOrder { return lhsOrder < rhsOrder }
                return lhs.slot < rhs.slot
            }
            let requested = preferredSheetKey.flatMap { key in ordered.first { $0.id == key } }
            // When the preview is opened from a song-detail card, only the difficulties of the
            // selected chart type are shown; otherwise fall back to every type.
            let scoped: [PreviewChartOption]
            if let requested {
                let type = requested.displayType
                scoped = ordered.filter { $0.displayType == type }
            } else {
                scoped = ordered
            }
            // Always start on the hardest difficulty of the selected type; the requested sheet is
            // only used to scope the type.
            let selected = scoped.max { $0.slot < $1.slot }?.sheet
            options = scoped
            selectedKey = selected.map { ChartPreviewSheetKey.key(for: $0) } ?? scoped.last?.id ?? ""
            state = .ready
            rebuildChart()
            applyStoredAudioSettings()
            refreshCover()
            prepareAudio()
        } catch is CancellationError {
            return
        } catch {
            state = .failed(error.localizedDescription)
        }
    }

    private func fetchMaidata(_ entry: ChartAssetIndex.Entry) async throws -> String {
        guard let file = entry.files.maidata else {
            throw ChartAssetDownloadError(message: "Chart asset has no maidata")
        }
        return try await ChartAssetClient.downloadMaidata(file)
    }

    /// Switches the previewed difficulty without re-downloading the maidata.
    func selectSheet(_ key: String) {
        guard key != selectedKey, options.contains(where: { $0.id == key }) else { return }
        selectedKey = key
        rebuildChart()
    }

    private func rebuildChart() {
        parseError = nil
        chart = nil
        chartRevision += 1
        guard let option = selectedOption else { return }
        do {
            let parsed = try SimaiParser.parse(option.maidata, difficulty: option.slot)
            chart = parsed
        } catch {
            parseError = error.localizedDescription
        }
        prepareAudio()
    }

    // MARK: - Audio

    private func prepareAudio() {
        guard let audio = songAudioAsset else {
            audioPrepareTask?.cancel()
            audioPrepareTask = nil
            preparedAudioKey = nil
            audioEngine.release()
            return
        }
        let key = "\(audio.sha256.lowercased()):\(audio.size)"
        if preparedAudioKey == key && audioEngine.isReady { return }
        preparedAudioKey = key
        // A superseded download/decode must not keep preparing a track that is no longer selected.
        audioPrepareTask?.cancel()
        audioEngine.pause()
        audioEngine.stopSfx()
        audioPrepareTask = Task { [weak self] in
            guard let self else { return }
            do {
                let file = try await ChartTrackCache.shared.trackFile(for: audio) { [weak self] progress in
                    Task { @MainActor [weak self] in
                        self?.downloadProgress = progress
                    }
                }
                self.downloadProgress = nil
                guard !Task.isCancelled else { return }
                let prepared = await self.audioEngine.prepare(fileURL: file, key: key)
                if !prepared {
                    self.preparedAudioKey = nil
                }
                self.refreshCover()
            } catch is CancellationError {
                self.downloadProgress = nil
            } catch {
                self.downloadProgress = nil
                self.preparedAudioKey = nil
                self.audioEngine.release()
            }
        }
    }

    /// Community MP3s carry a sharper cover than the catalog image; prefer it when present.
    private func refreshCover() {
        let embedded = cachedAudioURL().flatMap { url -> ChartImage? in
            guard let data = Mp3CoverArt.read(fileURL: url) else { return nil }
            return ChartImageLoader.image(from: data)
        }
        if let embedded {
            coverImage = embedded
            return
        }
        let imageName = song.imageName
        if let cached = coverCache, cached.key == imageName {
            coverImage = cached.image
            return
        }
        let url = ImageDownloader.shared.getCoverUrl(for: imageName)
        let image = ChartImageLoader.image(contentsOf: url)
        coverCache = (imageName, image)
        coverImage = image
    }

    private func cachedAudioURL() -> URL? {
        guard let audio = songAudioAsset else { return nil }
        return ChartTrackCache.shared.cachedFile(for: audio)
    }

    // MARK: - Settings

    func updateSettings(_ transform: (inout ChartPreviewSettings) -> Void) {
        settingsStore.update(transform)
        applyStoredAudioSettings()
    }

    /// Pushes every persisted audio-related setting into the engine.
    ///
    /// The engine starts from its own defaults (100 % volumes, no offsets), so entering the page must
    /// re-apply the stored values — otherwise the first play only honours the settings once the user
    /// touches one of the controls.
    func applyStoredAudioSettings() {
        let settings = settingsStore.settings
        audioEngine.setMusicVolume(settings.musicVolume)
        audioEngine.setAnswerVolume(settings.answerVolume)
        audioEngine.setJudgeVolume(settings.judgeVolume)
        audioEngine.setMusicOffsetMs(settings.musicOffsetMs)
        audioEngine.setSfxOffsetMs(settings.sfxOffsetMs)
        audioEngine.setPlaybackSpeed(Float(settings.playbackSpeed))
        if chart != nil, sceneDurationMs > 0 {
            configureEngineAudio(durationMs: sceneDurationMs)
        }
    }

    /// Publishes the frame-addressed SFX timeline to the mixer. Safe before the track is ready.
    func configureEngineAudio(durationMs: Double? = nil) {
        guard let chart else { return }
        audioEngine.applyConfiguration(
            events: ChartAudioEvents.prepare(chart.notes),
            leadInMs: chart.leadInMs,
            durationMs: durationMs ?? sceneDurationMs,
            musicOffsetMs: settings.musicOffsetMs,
            sfxOffsetMs: settings.sfxOffsetMs
        )
    }

    private(set) var sceneDurationMs: Double = 0

    func updateSceneDuration(_ durationMs: Double) {
        guard durationMs != sceneDurationMs else { return }
        sceneDurationMs = durationMs
        configureEngineAudio(durationMs: durationMs)
    }

    func release() {
        audioPrepareTask?.cancel()
        audioPrepareTask = nil
        audioEngine.release()
    }

    // MARK: - Export

    /// Builds a zip package with `maidata.txt`, `track.mp3` and `bg.png` for sharing.
    func exportChartZip() async throws -> URL {
        guard let option = selectedOption else {
            throw ChartAssetDownloadError(message: "Chart is not ready")
        }
        guard let chart else {
            throw ChartAssetDownloadError(message: "Chart is not ready")
        }
        var audioURL: URL?
        if let audio = songAudioAsset {
            audioURL = try await ChartTrackCache.shared.trackFile(for: audio) { _ in }
        }
        let coverData: Data?
        if let audioURL, let embedded = Mp3CoverArt.read(fileURL: audioURL) {
            coverData = embedded
        } else {
            let imageURL = ImageDownloader.shared.getCoverUrl(for: song.imageName)
            coverData = try? Data(contentsOf: imageURL)
        }
        let title = song.title
        let difficulty = option.sheet.difficulty
        return try ChartZipExporter.export(
            maidata: option.maidata,
            title: title,
            difficulty: difficulty,
            audioURL: audioURL,
            coverData: coverData,
            noteDesigner: chart.sheetDesigner
        )
    }
}
