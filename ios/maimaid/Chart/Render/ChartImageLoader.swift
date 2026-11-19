import CoreGraphics
import Foundation
import ImageIO

/// Loads the bitmaps the stage needs (catalog cover, MP3 embedded cover, sensor layout) as
/// `CGImage`s, without going through UIKit.
nonisolated enum ChartImageLoader {
    /// Maximum edge length of a decoded stage bitmap; the stage draws at most a few hundred points,
    /// so a larger decode only costs memory.
    private static let maximumEdge = 1024

    /// Decodes image data, downscaling anything larger than `maximumEdge`.
    static func image(from data: Data) -> ChartImage? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        return image(from: source)
    }

    /// Decodes an image file, downscaling anything larger than `maximumEdge`.
    static func image(contentsOf url: URL) -> ChartImage? {
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil) else { return nil }
        return image(from: source)
    }

    /// The cabinet sensor layout shipped with the chart preview assets.
    static func sensorImage() -> ChartImage? {
        for candidate in sensorResourceURLs() {
            if let image = image(contentsOf: candidate) { return image }
        }
        return nil
    }

    private static func image(from source: CGImageSource) -> ChartImage? {
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: maximumEdge,
        ]
        if let thumbnail = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) {
            return ChartImage(thumbnail)
        }
        return ChartImage(CGImageSourceCreateImageAtIndex(source, 0, nil))
    }

    /// Resolves the bundled sensor bitmap whether or not the resource folder is preserved in the
    /// built app.
    private static func sensorResourceURLs() -> [URL] {
        var urls: [URL] = []
        if let url = Bundle.main.url(forResource: "sensor", withExtension: "webp") { urls.append(url) }
        if let url = Bundle.main.resourceURL?
            .appending(path: "maimai/chart/sensor.webp"),
           FileManager.default.fileExists(atPath: url.path) {
            urls.append(url)
        }
        return urls
    }
}
