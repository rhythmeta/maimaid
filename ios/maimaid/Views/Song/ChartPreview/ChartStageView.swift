import SwiftUI

/// The arcade stage: renders one frame of the chart with `ChartRenderer` inside a 1:1 square.
struct ChartStageView: View {
    let scene: ChartSceneGraph
    let renderConfig: ChartRenderConfig
    let controller: ChartPlaybackController
    let coverImage: ChartImage?
    let sensorImage: ChartImage?
    let speedBoostActive: Bool

    @State private var renderer = ChartRenderer()

    var body: some View {
        // Reading the observable position here keeps the stage in sync when it is paused; while it
        // plays, the timeline schedule re-renders every display frame and the controller interpolates.
        let pausedPosition = controller.positionMs
        TimelineView(.animation(paused: !controller.isPlaying)) { _ in
            Canvas(opaque: true) { context, size in
                let side = Float(min(size.width, size.height))
                guard side > 0 else { return }
                let chartCanvas = ChartCanvas(graphics: context, logicalSize: CGFloat(side))
                let positionMs = controller.isPlaying ? controller.currentPositionMs() : pausedPosition
                renderer.render(
                    canvas: chartCanvas,
                    sizePx: side,
                    scene: scene,
                    currentBeats: scene.timeline.beatFromMs(positionMs),
                    config: renderConfig,
                    isPlaying: controller.isPlaying,
                    background: coverImage,
                    sensorImage: sensorImage,
                    speedMultiplier: speedBoostActive ? ChartPreviewTiming.boostSpeed : 1
                )
            }
        }
        .background(.black)
    }
}

/// Timing constants shared by the preview page and its stage.
nonisolated enum ChartPreviewTiming {
    /// Holding the stage plays at 1.5x the user's speed; releasing returns to the setting.
    static let boostSpeed = 1.5
}
