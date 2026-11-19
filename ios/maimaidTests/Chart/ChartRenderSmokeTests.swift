import CoreGraphics
import Foundation
import ImageIO
import SwiftUI
import Testing
@testable import maimaid

/// Renders real frames of the ported stage renderer and checks that the expected palette reaches the
/// canvas. The dump path in the log is a PNG the developer can eyeball next to the Android build.
@MainActor
struct ChartRenderSmokeTests {

    @Test
    func rendersStageFramesWithTheReferencePalette() throws {
        let chart = try SimaiParser.parse(parityMaidata(), difficulty: 5)
        let scene = ChartSceneGraph(chart: chart)

        // One frame just before the first note's judgment: the break head and the hold head that
        // follow it are already on the ring.
        let frame = try renderFrame(scene: scene, positionMs: 1_500, config: ChartRenderConfig())
        let pixels = try Pixels(image: frame)

        #expect(pixels.width == 600 && pixels.height == 600)
        // The stage starts black; every visible note adds its own palette.
        #expect(pixels.fraction { $0.isBright } > 0.01)
        #expect(pixels.fraction { $0.matches(ChartConstants.Colors.breakOrange) } > 0.0001)
        #expect(pixels.fraction { $0.matches(ChartConstants.Colors.tapPink) } > 0.0001)

        // Slide tracks only appear once a slide approaches, so the palette is sampled across the
        // whole chart instead of a single frame.
        var sawSlide = false
        var sawTouch = false
        var position = 2_000.0
        while position < scene.playbackDurationMs {
            let sampled = try Pixels(image: renderFrame(scene: scene, positionMs: position,
                                                       config: ChartRenderConfig()))
            if sampled.fraction({ $0.matches(ChartConstants.Colors.slideCyan) }) > 0.0002 { sawSlide = true }
            if sampled.fraction({ $0.matches(ChartConstants.Colors.touchCyan) }) > 0.0002 { sawTouch = true }
            if sawSlide && sawTouch { break }
            position += 200
        }
        #expect(sawSlide)
        #expect(sawTouch)

        try dump(frame, name: "chart-stage-1500")
    }

    @Test
    func rendersJudgmentLineDesignsAndMirrorModes() throws {
        let chart = try SimaiParser.parse(parityMaidata(), difficulty: 5)
        let scene = ChartSceneGraph(chart: chart)

        for design in JudgmentLineDesign.allCases {
            for mirror in MirrorMode.allCases {
                var config = ChartRenderConfig()
                config.judgmentLineDesign = design
                config.mirrorMode = mirror
                let frame = try renderFrame(scene: scene, positionMs: 3_000, config: config)
                #expect(frame.width == 600)
            }
        }

        var sensor = ChartRenderConfig()
        sensor.judgmentLineDesign = .sensor
        sensor.highlightExNotes = true
        sensor.ddrColorMode = false
        let frame = try renderFrame(scene: scene, positionMs: 5_000, config: sensor)
        try dump(frame, name: "chart-stage-sensor")
    }

    @Test
    func rendersEverySectionOfTheChart() throws {
        let chart = try SimaiParser.parse(parityMaidata(), difficulty: 5)
        let scene = ChartSceneGraph(chart: chart)
        var position = 0.0
        while position < scene.playbackDurationMs {
            let frame = try renderFrame(scene: scene, positionMs: position, config: ChartRenderConfig())
            #expect(frame.width == 600)
            position += 250
        }
    }

    // MARK: - Helpers

    private func renderFrame(scene: ChartSceneGraph, positionMs: Double,
                             config: ChartRenderConfig) throws -> CGImage {
        let renderer = ChartRenderer()
        let beats = scene.timeline.beatFromMs(positionMs)
        let view = Canvas(opaque: true, colorMode: .extendedLinear) { context, size in
            let side = Float(min(size.width, size.height))
            let canvas = ChartCanvas(graphics: context, logicalSize: CGFloat(side))
            renderer.render(
                canvas: canvas,
                sizePx: side,
                scene: scene,
                currentBeats: beats,
                config: config,
                isPlaying: true,
                background: nil,
                sensorImage: nil
            )
        }
        .frame(width: 300, height: 300)

        let output = ImageRenderer(content: view)
        output.scale = 2
        return try #require(output.cgImage)
    }

    private func parityMaidata() throws -> String {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .appending(path: "../Fixtures/Chart/parity-maidata.txt")
        return try String(contentsOf: url, encoding: .utf8)
    }

    /// Writes the frame next to the test artifacts so it can be compared with the Android build.
    private func dump(_ image: CGImage, name: String) throws {
        let directory = FileManager.default.temporaryDirectory
        let url = directory.appending(path: "\(name).png")
        guard let destination = CGImageDestinationCreateWithURL(
            url as CFURL, "public.png" as CFString, 1, nil
        ) else { return }
        CGImageDestinationAddImage(destination, image, nil)
        CGImageDestinationFinalize(destination)
        print("chart render dump: \(url.path)")
    }
}

/// Minimal RGBA pixel buffer used by the render assertions.
private struct Pixels {
    struct Pixel {
        var r: Double
        var g: Double
        var b: Double

        var isBright: Bool { max(r, max(g, b)) > 0.15 }

        /// Palette match with a generous tolerance: the renderer composites gradients, antialiasing
        /// and EX overlays on top of the exact palette entries.
        func matches(_ color: ChartColor, tolerance: Double = 0.12) -> Bool {
            abs(r - color.red) < tolerance && abs(g - color.green) < tolerance
                && abs(b - color.blue) < tolerance
        }
    }

    let width: Int
    let height: Int
    private let pixels: [Pixel]

    init(image: CGImage) throws {
        let width = image.width
        let height = image.height
        var buffer = [UInt8](repeating: 0, count: width * height * 4)
        let colorSpace = CGColorSpaceCreateDeviceRGB()
        let context = try #require(CGContext(
            data: &buffer,
            width: width,
            height: height,
            bitsPerComponent: 8,
            bytesPerRow: width * 4,
            space: colorSpace,
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ))
        context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
        self.width = width
        self.height = height
        self.pixels = stride(from: 0, to: buffer.count, by: 4).map { index in
            Pixel(
                r: Double(buffer[index]) / 255,
                g: Double(buffer[index + 1]) / 255,
                b: Double(buffer[index + 2]) / 255
            )
        }
    }

    func fraction(_ predicate: (Pixel) -> Bool) -> Double {
        guard !pixels.isEmpty else { return 0 }
        let hits = pixels.reduce(0) { $0 + (predicate($1) ? 1 : 0) }
        return Double(hits) / Double(pixels.count)
    }
}
