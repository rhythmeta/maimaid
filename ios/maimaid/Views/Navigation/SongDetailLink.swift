import SwiftUI

/// Entry point for opening a song's detail screen from anywhere in the app.
///
/// It only records the request on `SongNavigation`; the single `fullScreenCover` in
/// `MainTabView` presents the detail view and zooms from this row's transition source.
struct SongDetailLink<Content: View>: View {
    /// Accepts an optional song so call sites with a missing model simply stay inert.
    let song: Song?
    var preferredType: String?
    var isEnabled = true
    var onDismiss: (() -> Void)?
    @ViewBuilder let content: () -> Content

    @Environment(SongNavigation.self) private var navigation
    @State private var identity = UUID()

    var body: some View {
        Button {
            guard isEnabled, let song else { return }
            navigation.open(
                song,
                preferredType: preferredType,
                sourceID: identity,
                onDismiss: onDismiss
            )
        } label: {
            content()
        }
        .buttonStyle(.plain)
        .disabled(!isEnabled || song == nil)
        .modifier(SongTransitionSource(id: identity))
    }
}
