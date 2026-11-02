import Observation
import SwiftUI

/// App-wide song detail presentation. A single `fullScreenCover` in `MainTabView` renders
/// `song`, so every entry point (catalog, best table, score query, scanner, …) focuses on
/// providing the song plus a zoom-transition source instead of owning its own presentation.
@MainActor
@Observable
final class SongNavigation {
    /// Song currently presented, or `nil` when the detail cover is dismissed.
    var song: Song?

    /// Chart type the detail screen should focus on first.
    private(set) var preferredType: String?

    /// Identity of the tapped item, used as the zoom transition source.
    private(set) var sourceID = UUID()

    /// Work to run once the detail cover is dismissed.
    private var onDismiss: (() -> Void)?

    var isPresenting: Bool { song != nil }

    func open(
        _ song: Song,
        preferredType: String? = nil,
        sourceID: UUID,
        onDismiss: (() -> Void)? = nil
    ) {
        guard self.song == nil else { return }
        self.preferredType = preferredType
        self.sourceID = sourceID
        self.onDismiss = onDismiss
        self.song = song
    }

    /// Dismisses the cover programmatically; `completeDismissal()` still runs the pending work.
    func dismiss() {
        song = nil
    }

    /// Runs the dismissal callbacks exactly once, after the cover has fully gone away.
    func completeDismissal() {
        guard song == nil else { return }
        let pending = onDismiss
        onDismiss = nil
        preferredType = nil
        pending?()
    }
}
