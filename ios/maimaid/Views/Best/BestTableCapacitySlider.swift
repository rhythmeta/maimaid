import SwiftUI

struct BestTableCapacitySlider: View {
    let label: String
    @Binding var value: Int
    let snapValue: Int
    let onCommit: () -> Void

    @State private var snapTick = 0
    @State private var wasSnapped = false

    private let range = 1...99
    private let snapMagnet = 1

    private var unit: String {
        String(localized: "bestTable.capacity.unit")
    }

    private var sliderBinding: Binding<Double> {
        Binding(
            get: { Double(value) },
            set: { rawValue in
                let rounded = min(max(Int(rawValue.rounded()), range.lowerBound), range.upperBound)
                let snapped = abs(rounded - snapValue) <= snapMagnet ? snapValue : rounded

                if snapped != value {
                    value = snapped
                }

                let isSnapped = snapped == snapValue
                if isSnapped, !wasSnapped {
                    snapTick += 1
                }
                wasSnapped = isSnapped
            }
        )
    }

    var body: some View {
        VStack(spacing: 8) {
            HStack {
                Text(label)
                Spacer()
                Text("\(value) \(unit)")
                    .font(.body.monospacedDigit())
                    .foregroundStyle(.secondary)
            }

            Slider(value: sliderBinding, in: 1...99, step: 1) { editing in
                if !editing {
                    onCommit()
                }
            }
            .sensoryFeedback(.selection, trigger: snapTick)
            .accessibilityValue(Text("\(value) \(unit)"))
        }
        .padding(.vertical, 2)
    }
}
