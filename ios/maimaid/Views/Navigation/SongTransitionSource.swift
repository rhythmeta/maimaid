import SwiftUI

extension EnvironmentValues {
    /// Namespace hoisted to `MainTabView` so every screen can share the song-detail zoom transition.
    @Entry var songTransitionNamespace: Namespace.ID?
}

/// Marks the tapped row/tile as the zoom transition source for the global song detail cover.
struct SongTransitionSource: ViewModifier {
    @Environment(\.songTransitionNamespace) private var namespace

    let id: UUID

    func body(content: Content) -> some View {
        if let namespace {
            content.applyMatchedTransitionSource(id: id, namespace: namespace)
        } else {
            content
        }
    }
}

// MARK: - Zoom transition helpers

extension View {
    @ViewBuilder
    func applyMatchedTransitionSource(id: some Hashable, namespace: Namespace.ID) -> some View {
        if #available(iOS 18.0, *) {
            self.matchedTransitionSource(id: id, in: namespace)
        } else {
            self
        }
    }

    @ViewBuilder
    func applyZoomTransition(id: some Hashable, namespace: Namespace.ID) -> some View {
        if #available(iOS 18.0, *) {
            self.navigationTransition(.zoom(sourceID: id, in: namespace))
        } else {
            self
        }
    }
}
