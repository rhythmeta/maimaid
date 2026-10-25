import SwiftUI

@MainActor
struct RemoteImage<Placeholder: View, Failure: View>: View {
    let urls: [URL]
    let contentMode: ContentMode
    @ViewBuilder let placeholder: Placeholder
    @ViewBuilder let failure: Failure

    @State private var image: Image?
    @State private var didFail = false

    init(
        urls: [URL],
        contentMode: ContentMode = .fill,
        @ViewBuilder placeholder: () -> Placeholder,
        @ViewBuilder failure: () -> Failure
    ) {
        self.urls = urls
        self.contentMode = contentMode
        self.placeholder = placeholder()
        self.failure = failure()
    }

    init(
        url: URL?,
        contentMode: ContentMode = .fill,
        @ViewBuilder placeholder: () -> Placeholder,
        @ViewBuilder failure: () -> Failure
    ) {
        self.init(
            urls: url.map { [$0] } ?? [],
            contentMode: contentMode,
            placeholder: placeholder,
            failure: failure
        )
    }

    var body: some View {
        Group {
            if let image {
                image
                    .resizable()
                    .aspectRatio(contentMode: contentMode)
            } else if didFail {
                failure
            } else {
                placeholder
            }
        }
        .task(id: urls) {
            image = nil
            didFail = false
            guard !urls.isEmpty else { return }

            for url in urls {
                do {
                    let fetchedImage = try await ImageDownloader.shared.fetchImage(from: url)
                    try Task.checkCancellation()
                    image = Image(uiImage: fetchedImage)
                    return
                } catch is CancellationError {
                    return
                } catch {
                    // Try the next candidate before falling back to the
                    // placeholder; configured CDN transforms may reject an
                    // origin that the direct asset path still serves.
                    continue
                }
            }
            didFail = true
        }
    }
}

extension RemoteImage where Failure == Placeholder {
    init(
        urls: [URL],
        contentMode: ContentMode = .fill,
        @ViewBuilder placeholder: () -> Placeholder
    ) {
        let placeholder = placeholder()
        self.init(
            urls: urls,
            contentMode: contentMode,
            placeholder: { placeholder },
            failure: { placeholder }
        )
    }

    init(
        url: URL?,
        contentMode: ContentMode = .fill,
        @ViewBuilder placeholder: () -> Placeholder
    ) {
        self.init(
            urls: url.map { [$0] } ?? [],
            contentMode: contentMode,
            placeholder: placeholder
        )
    }
}
