import Foundation
import Testing
@testable import maimaid

/// The preview page must honour the persisted audio settings as soon as it opens, not only after the
/// user touches a control.
@MainActor
struct ChartPreviewAudioSettingsTests {

    @Test
    func appliesPersistedAudioSettingsOnEntry() throws {
        let fixture = try makeStore()
        defer { fixture.cleanup() }

        var custom = ChartPreviewSettings.default
        custom.musicVolume = 0.35
        custom.answerVolume = 0.4
        custom.judgeVolume = 0.45
        custom.musicOffsetMs = -120
        custom.sfxOffsetMs = 30
        custom.playbackSpeed = 1.25
        fixture.store.replace(with: custom)

        let viewModel = ChartPreviewViewModel(song: makeSong(), preferredSheetKey: nil,
                                              settingsStore: fixture.store)
        // The engine keeps its own defaults until the page publishes the stored values.
        #expect(viewModel.audioEngine.musicVolume == 1)
        #expect(viewModel.audioEngine.musicOffsetMs == 0)

        viewModel.applyStoredAudioSettings()

        #expect(viewModel.audioEngine.musicVolume == 0.35)
        #expect(viewModel.audioEngine.answerVolume == 0.4)
        #expect(viewModel.audioEngine.judgeVolume == 0.45)
        #expect(viewModel.audioEngine.musicOffsetMs == -120)
        #expect(viewModel.audioEngine.sfxOffsetMs == 30)
        #expect(viewModel.audioEngine.playbackSpeed == 1.25)
    }

    @Test
    func panelChangesPersistAndReachTheEngine() throws {
        let fixture = try makeStore()
        defer { fixture.cleanup() }

        let viewModel = ChartPreviewViewModel(song: makeSong(), preferredSheetKey: nil,
                                              settingsStore: fixture.store)
        viewModel.updateSettings { settings in
            settings.musicVolume = 0.2
            settings.judgeVolume = 0.6
            settings.sfxOffsetMs = -40
        }

        #expect(viewModel.audioEngine.musicVolume == 0.2)
        #expect(viewModel.audioEngine.judgeVolume == 0.6)
        #expect(viewModel.audioEngine.sfxOffsetMs == -40)

        // A new page (a new store over the same defaults suite) sees the same values.
        let reloaded = ChartPreviewSettingsStore(defaults: try #require(UserDefaults(suiteName: fixture.suiteName)))
        #expect(reloaded.settings.musicVolume == 0.2)
        #expect(reloaded.settings.judgeVolume == 0.6)
        #expect(reloaded.settings.sfxOffsetMs == -40)
    }

    // MARK: - Fixtures

    private struct StoreFixture {
        var store: ChartPreviewSettingsStore
        var suiteName: String
        var cleanup: () -> Void
    }

    private func makeStore() throws -> StoreFixture {
        let name = "chart.preview.settings.tests.\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: name))
        let store = ChartPreviewSettingsStore(defaults: defaults)
        store.reset()
        return StoreFixture(store: store, suiteName: name, cleanup: {
            defaults.removePersistentDomain(forName: name)
        })
    }

    private func makeSong() -> Song {
        Song(
            songIdentifier: "chart-preview-test-song",
            category: "POPS＆ANIME",
            title: "Test Song",
            artist: "Test Artist",
            imageName: "test.png",
            sortOrder: 1,
            bpm: 165,
            isNew: false,
            isLocked: false,
            aliases: []
        )
    }
}
