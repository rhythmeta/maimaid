import Foundation

/// Everything the render loop needs that is derived once per chart: the parsed notes, the prepared
/// category lists and culling windows, the beat timeline and the key-press times.
///
/// The Android renderer keyed these caches on object identity; Swift `Chart` is a value type, so the
/// stage view creates one scene per parsed chart and the renderer compares `===`.
nonisolated final class ChartSceneGraph {
    let chart: Chart
    let prepared: PreparedChart
    let timeline: TimingTimeline
    let keyPressTimes: [Double]

    /// Total preview length: the reference stops at `measures × beatsPerMeasure + 500 ms`; the chart
    /// already carries one measure of padding on each side, so the last effects get to finish. A
    /// floor of 1.5 s after the final note keeps very short charts watchable.
    let playbackDurationMs: Double

    init(chart: Chart) {
        self.chart = chart
        self.prepared = PreparedChart.build(chart: chart)
        self.timeline = TimingTimeline.fromChart(chart)
        self.keyPressTimes = ChartKeyDensity.pressTimes(chart.notes)
        self.playbackDurationMs = max(
            timeline.msFromBeat(Double(chart.measures) * 4.0) + 500.0,
            chart.endTimeMs + 1_500.0
        )
    }
}
