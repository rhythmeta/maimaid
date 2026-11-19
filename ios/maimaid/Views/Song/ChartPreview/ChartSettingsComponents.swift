import SwiftUI

/// Layout tokens shared by the chart-preview settings panels.
///
/// The reference (Android) panel is built from Miuix cards: 16dp card padding, a 14dp header block
/// and a 6dp gap between a group title and its controls. Keeping the same numbers preserves that
/// rhythm now that the Miuix card is a native material card.
nonisolated enum ChartSettingsLayout {
    static let cardCornerRadius: CGFloat = 16
    /// Gap between two controls inside an expanded card (reference: 14–16dp blocks).
    static let contentSpacing: CGFloat = 16
    /// Gap between a group title and the controls it introduces (reference: 6dp).
    static let groupSpacing: CGFloat = 6
    /// Width of the editable audio-offset field; the reference uses 72dp, enough for "−500".
    static let offsetFieldWidth: CGFloat = 72
    /// Audio offset editing range and increment, copied from the reference offset row.
    static let offsetRange = -500...500
    static let offsetStep = 10
}

/// Applies the panels' "mutate a copy, hand the copy back" contract to `ChartPreviewSettings`.
///
/// The panels never mutate the settings they are given; every control builds a modified copy and
/// passes it to `onChange`, like the reference panel's `settings.copy(...)` calls. Redundant copies
/// are dropped so a control cannot spam the owner, mirroring the settings store's equality guard.
struct ChartSettingsBinder {
    let settings: ChartPreviewSettings
    let onChange: (ChartPreviewSettings) -> Void

    func mutate(_ transform: (inout ChartPreviewSettings) -> Void) {
        var next = settings
        transform(&next)
        guard next != settings else { return }
        onChange(next)
    }

    /// Two-way binding for a single settings field, routed through `mutate`.
    func binding<Value>(_ keyPath: WritableKeyPath<ChartPreviewSettings, Value>) -> Binding<Value> {
        Binding(
            get: { settings[keyPath: keyPath] },
            set: { newValue in mutate { $0[keyPath: keyPath] = newValue } }
        )
    }
}

/// Collapsible settings card mirroring the reference `SettingsCard`: an icon + title header that
/// toggles the body, collapsed by default like the reference panel.
struct ChartSettingsCard<Content: View>: View {
    let title: String
    let systemImage: String
    @Binding var isExpanded: Bool
    private let content: Content

    init(title: String, systemImage: String, isExpanded: Binding<Bool>, @ViewBuilder content: () -> Content) {
        self.title = title
        self.systemImage = systemImage
        self._isExpanded = isExpanded
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ChartSettingsCardHeader(title: title, systemImage: systemImage, isExpanded: $isExpanded)
            if isExpanded {
                VStack(alignment: .leading, spacing: ChartSettingsLayout.contentSpacing) {
                    content
                }
                .padding(.top, ChartSettingsLayout.contentSpacing)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding()
        .background(.ultraThinMaterial, in: .rect(cornerRadius: ChartSettingsLayout.cardCornerRadius))
        .overlay {
            RoundedRectangle(cornerRadius: ChartSettingsLayout.cardCornerRadius)
                .strokeBorder(Color.primary.opacity(0.06))
        }
    }
}

/// Header button of a settings card; the chevron flips while the body is expanded.
struct ChartSettingsCardHeader: View {
    let title: String
    let systemImage: String
    @Binding var isExpanded: Bool

    var body: some View {
        Button {
            withAnimation(.snappy) { isExpanded.toggle() }
        } label: {
            HStack {
                Image(systemName: systemImage)
                    .foregroundStyle(.secondary)
                    .accessibilityHidden(true)
                Text(title)
                    .font(.headline)
                Spacer(minLength: 0)
                Image(systemName: "chevron.down")
                    .font(.footnote.bold())
                    .foregroundStyle(.secondary)
                    .rotationEffect(.degrees(isExpanded ? 180 : 0))
                    .accessibilityHidden(true)
            }
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
    }
}

/// Title + controls block inside a card (the reference panel's chip and toggle groups).
struct ChartSettingsGroup<Content: View>: View {
    let title: String
    private let content: Content

    init(title: String, @ViewBuilder content: () -> Content) {
        self.title = title
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: ChartSettingsLayout.groupSpacing) {
            Text(title)
                .font(.footnote)
                .foregroundStyle(.secondary)
            content
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Exclusive choice rendered as a native segmented control.
///
/// The reference draws wrapping chips for these; on iOS an exclusive choice with up to four short
/// options is a segmented `Picker`, which brings the platform's selection animation and
/// accessibility for free.
struct ChartSettingsSegmentedPicker<Value: Hashable>: View {
    let title: String
    let selection: Binding<Value>
    let options: [(label: String, value: Value)]

    var body: some View {
        ChartSettingsGroup(title: title) {
            Picker(title, selection: selection) {
                ForEach(options.indices, id: \.self) { index in
                    Text(options[index].label)
                        .tag(options[index].value)
                }
            }
            .pickerStyle(.segmented)
        }
    }
}

/// Number presentation for a settings slider, ported from the reference panel's format strings so
/// the labels keep the same precision and units.
nonisolated enum ChartSettingsValueStyle: Hashable {
    /// `%.2f` / `%.1f` — a plain decimal with a fixed number of fraction digits.
    case fractionDigits(Int)
    /// `%.2fx` — a multiplier, e.g. the playback speed.
    case multiplier(fractionDigits: Int)
    /// `100%` — a `0…1` fraction shown as a percentage.
    case percent

    func string(for value: Double) -> String {
        switch self {
        case let .fractionDigits(fractionDigits):
            value.formatted(.number.precision(.fractionLength(fractionDigits)))
        case let .multiplier(fractionDigits):
            value.formatted(.number.precision(.fractionLength(fractionDigits))) + "x"
        case .percent:
            value.formatted(.percent.precision(.fractionLength(0)))
        }
    }
}

/// Label + slider row ported from the reference `SettingSlider`.
///
/// The reference keeps the handle in local state while dragging and commits once when the drag ends
/// (`onValueChangeFinished`); the same happens here so a live chart preview is not rebuilt on every
/// frame. Snap points, the commit-time rounding and the snap-point selection haptic all follow the
/// Kotlin implementation.
struct ChartSettingsSlider: View {
    let title: String
    /// The committed value owned by `ChartPreviewSettings`.
    let value: Double
    let range: ClosedRange<Double>
    let valueStyle: ChartSettingsValueStyle
    /// Values the handle sticks to, e.g. exactly 1x playback speed.
    let snapPoints: [Double]
    /// Fraction digits the value is rounded to when committed; `nil` keeps the raw handle value.
    let roundingDigits: Int?
    let onChange: (Double) -> Void

    @State private var sliderValue: Double
    @State private var isDragging = false
    @State private var snapFeedbackTrigger = 0

    /// Snap window around a snap point (`snapThreshold` in the reference).
    private static let snapThreshold = 0.04
    /// Tolerance used to decide that the handle sits exactly on a snap point (`SNAP_EPSILON`).
    private static let snapEpsilon = 1e-4

    init(
        title: String,
        value: Double,
        range: ClosedRange<Double>,
        valueStyle: ChartSettingsValueStyle = .fractionDigits(2),
        snapPoints: [Double] = [],
        roundingDigits: Int? = nil,
        onChange: @escaping (Double) -> Void
    ) {
        self.title = title
        self.value = value
        self.range = range
        self.valueStyle = valueStyle
        self.snapPoints = snapPoints
        self.roundingDigits = roundingDigits
        self.onChange = onChange
        _sliderValue = State(initialValue: value)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: ChartSettingsLayout.groupSpacing) {
            HStack {
                Text(title)
                    .font(.body)
                Spacer(minLength: ChartSettingsLayout.groupSpacing)
                Text(valueStyle.string(for: sliderValue))
                    .font(.body.monospacedDigit())
                    .foregroundStyle(.secondary)
            }
            Slider(value: sliderBinding, in: range) { editing in
                isDragging = editing
                if !editing { commit() }
            }
            .sensoryFeedback(.selection, trigger: snapFeedbackTrigger)
            .accessibilityValue(Text(valueStyle.string(for: sliderValue)))
        }
        .onChange(of: value) { _, newValue in
            // Re-sync when another control (e.g. the audio reset) changes this value.
            if !isDragging { sliderValue = newValue }
        }
    }

    /// Drag binding that applies snap points where the reference does, inside `onValueChange`.
    private var sliderBinding: Binding<Double> {
        Binding(
            get: { sliderValue },
            set: { rawValue in
                let snapped = Self.snappedValue(rawValue, snapPoints: snapPoints)
                let entersSnapPoint = Self.isOnSnapPoint(snapped, snapPoints: snapPoints)
                    && !Self.isOnSnapPoint(sliderValue, snapPoints: snapPoints)
                if entersSnapPoint { snapFeedbackTrigger += 1 }
                sliderValue = snapped
            }
        )
    }

    private func commit() {
        var committed = sliderValue
        if let roundingDigits {
            let scale = pow(10, Double(roundingDigits))
            committed = (committed * scale).rounded() / scale
        }
        // Keep the handle on the committed value so the label matches what the owner received.
        sliderValue = committed
        guard committed != value else { return }
        onChange(committed)
    }

    /// Pulls a slider value onto the nearest snap point when it is within `snapThreshold`, ported
    /// from the reference `snapSliderValue` so a speed of exactly 1x stays easy to hit.
    private static func snappedValue(_ value: Double, snapPoints: [Double]) -> Double {
        guard let nearest = snapPoints.min(by: { abs($0 - value) < abs($1 - value) }) else {
            return value
        }
        return abs(nearest - value) <= snapThreshold ? nearest : value
    }

    private static func isOnSnapPoint(_ value: Double, snapPoints: [Double]) -> Bool {
        snapPoints.contains { abs($0 - value) < snapEpsilon }
    }
}

/// Offset row ported from the reference `SettingOffsetRow`: label, −/+ buttons in 10 ms steps that
/// clamp to ±500 ms, an editable field for typing an exact offset, and a per-row reset.
struct ChartSettingsOffsetRow: View {
    let title: String
    let value: Int
    let range: ClosedRange<Int>
    let step: Int
    let onChange: (Int) -> Void

    @State private var text: String
    @FocusState private var isFocused: Bool

    init(
        title: String,
        value: Int,
        range: ClosedRange<Int> = ChartSettingsLayout.offsetRange,
        step: Int = ChartSettingsLayout.offsetStep,
        onChange: @escaping (Int) -> Void
    ) {
        self.title = title
        self.value = value
        self.range = range
        self.step = step
        self.onChange = onChange
        _text = State(initialValue: String(value))
    }

    var body: some View {
        HStack(spacing: ChartSettingsLayout.groupSpacing) {
            Text(title)
                .font(.body)
            Spacer(minLength: ChartSettingsLayout.groupSpacing)

            Button { apply(value - step) } label: { Image(systemName: "minus") }
                .buttonStyle(.borderless)
                .accessibilityLabel(Text(verbatim: "−\(step)"))

            TextField(title, text: $text)
                .textFieldStyle(.roundedBorder)
                .multilineTextAlignment(.center)
                .font(.body.monospacedDigit())
                .keyboardType(.numbersAndPunctuation)
                .submitLabel(.done)
                .focused($isFocused)
                .frame(width: ChartSettingsLayout.offsetFieldWidth)
                .onSubmit(commit)

            Button { apply(value + step) } label: { Image(systemName: "plus") }
                .buttonStyle(.borderless)
                .accessibilityLabel(Text(verbatim: "+\(step)"))

            Button { apply(0) } label: { Image(systemName: "arrow.counterclockwise") }
                .buttonStyle(.borderless)
                .accessibilityLabel(Text(String(localized: "chart.preview.settings.reset")))
        }
        .onChange(of: text) { _, newValue in
            let filtered = Self.filtered(newValue)
            if filtered != newValue { text = filtered }
        }
        .onChange(of: isFocused) { _, focused in
            if !focused { commit() }
        }
        .onChange(of: value) { _, newValue in
            // Re-sync after −/+, the per-row reset or the panel reset.
            if !isFocused, text != String(newValue) { text = String(newValue) }
        }
    }

    private func commit() {
        guard let parsed = Int(text) else {
            text = String(value)
            return
        }
        apply(parsed)
    }

    private func apply(_ newValue: Int) {
        let clamped = min(max(newValue, range.lowerBound), range.upperBound)
        text = String(clamped)
        guard clamped != value else { return }
        onChange(clamped)
    }

    /// Keeps only digits and a minus sign, capped at four characters — the reference input filter
    /// ("−500" is the widest legal value).
    private static func filtered(_ input: String) -> String {
        String(input.filter { $0.isASCII && ($0.isNumber || $0 == "-") }.prefix(4))
    }
}
