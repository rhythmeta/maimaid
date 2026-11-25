import SwiftUI

struct BestTableSettingsView: View {
    @Bindable var preferences: BestTablePreferences

    @Binding var constantMode: Best50ConstantMode
    @Binding var selectedVersion: String?
    @Binding var b35Count: Int
    @Binding var b15Count: Int

    let serverVersion: String?
    let availableVersions: [String]
    let categories: [String]

    let onCommitB35: () -> Void
    let onCommitB15: () -> Void
    let onReset: () -> Void

    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var colorScheme

    private let difficulties = [
        (value: "basic", label: "Basic"),
        (value: "advanced", label: "Advanced"),
        (value: "expert", label: "Expert"),
        (value: "master", label: "Master"),
        (value: "remaster", label: "Re:Master")
    ]
    private let types = ["std", "dx"]
    private let ranks = ["SSS+", "SSS", "SS+", "SS", "S+", "S", "AAA", "AA", "A", "BBB", "BB", "B", "C", "D"]
    private let fcOptions = ["AP+", "AP", "FC+", "FC"]
    private let fsOptions = ["FDX+", "FDX", "FS+", "FS"]

    private var autoVersionLabel: String {
        let auto = String(localized: "bestTable.settings.version.auto")
        guard let serverVersion else { return auto }
        return "\(auto) (\(ThemeUtils.versionAbbreviation(serverVersion)))"
    }

    private var selectedVersionLabel: String {
        guard let selectedVersion else { return autoVersionLabel }
        return ThemeUtils.versionAbbreviation(selectedVersion)
    }

    private var constantModeLabel: String {
        switch constantMode {
        case .server:
            return String(localized: "bestTable.settings.constants.default")
        case .fitted:
            return String(localized: "bestTable.settings.useFitDiff")
        case .version:
            return String(localized: "bestTable.settings.useVersionConstant")
        }
    }

    private var theoreticalBinding: Binding<Bool> {
        Binding(
            get: { preferences.theoretical },
            set: { newValue in
                preferences.theoretical = newValue
                if newValue {
                    constantMode = .version
                }
            }
        )
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Toggle("bestTable.settings.theoretical", isOn: theoreticalBinding)

                    LabeledContent("bestTable.settings.version") {
                        Menu {
                            Button(autoVersionLabel) {
                                selectedVersion = nil
                            }
                            Divider()
                            ForEach(availableVersions.reversed(), id: \.self) { version in
                                Button(ThemeUtils.versionAbbreviation(version)) {
                                    selectedVersion = version
                                }
                            }
                        } label: {
                            HStack(spacing: 4) {
                                Text(selectedVersionLabel)
                                Image(systemName: "chevron.up.chevron.down")
                                    .font(.caption2)
                            }
                            .contentShape(.rect)
                        }
                        .tint(.secondary)
                    }

                    LabeledContent("bestTable.settings.constants") {
                        Menu {
                            Button(String(localized: "bestTable.settings.constants.default")) {
                                constantMode = .server
                            }
                            Button(String(localized: "bestTable.settings.useFitDiff")) {
                                constantMode = .fitted
                            }
                            Button(String(localized: "bestTable.settings.useVersionConstant")) {
                                constantMode = .version
                            }
                        } label: {
                            HStack(spacing: 4) {
                                Text(constantModeLabel)
                                Image(systemName: "chevron.up.chevron.down")
                                    .font(.caption2)
                            }
                            .contentShape(.rect)
                        }
                        .tint(.secondary)
                        .disabled(preferences.theoretical)
                    }
                } header: {
                    Text("bestTable.settings.calculation")
                }

                if !preferences.theoretical {
                    Section {
                        BestTableCapacitySlider(
                            label: String(localized: "bestTable.settings.old"),
                            value: $b35Count,
                            snapValue: 35,
                            onCommit: onCommitB35
                        )
                        BestTableCapacitySlider(
                            label: String(localized: "bestTable.settings.new"),
                            value: $b15Count,
                            snapValue: 15,
                            onCommit: onCommitB15
                        )
                    } header: {
                        Text("bestTable.settings.capacity")
                    } footer: {
                        Text("bestTable.settings.totalValue \(b35Count + b15Count)")
                    }

                    filterSections
                }
            }
            .navigationTitle("bestTable.settings.title")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button {
                        withAnimation(.spring(response: 0.3)) {
                            onReset()
                        }
                    } label: {
                        Image(systemName: "arrow.counterclockwise")
                    }
                    .accessibilityLabel(Text("filter.reset"))
                    .disabled(preferences.theoretical)
                }

                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        dismiss()
                    } label: {
                        Image(systemName: "checkmark")
                            .fontWeight(.semibold)
                    }
                    .accessibilityLabel(Text("filter.done"))
                }
            }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }

    @ViewBuilder
    private var filterSections: some View {
        Section("filter.difficulty") {
            FlowLayout(spacing: 10) {
                ForEach(difficulties, id: \.value) { difficulty in
                    FilterChip(
                        title: difficulty.label,
                        isSelected: preferences.filters.selectedDifficulties.contains(difficulty.value),
                        color: ThemeUtils.colorForDifficulty(difficulty.value, nil, colorScheme)
                    ) {
                        toggle(difficulty.value, keyPath: \.selectedDifficulties)
                    }
                }
            }
        }

        Section("filter.category") {
            FlowLayout(spacing: 10) {
                ForEach(categories, id: \.self) { category in
                    FilterChip(
                        title: category,
                        isSelected: preferences.filters.selectedCategories.contains(category),
                        color: .blue
                    ) {
                        toggle(category, keyPath: \.selectedCategories)
                    }
                }
            }
        }

        Section("filter.type") {
            FlowLayout(spacing: 10) {
                ForEach(types, id: \.self) { type in
                    FilterChip(
                        title: type.uppercased(),
                        isSelected: preferences.filters.selectedTypes.contains(type),
                        color: type == "dx" ? .orange : .blue
                    ) {
                        toggle(type, keyPath: \.selectedTypes)
                    }
                }
            }
        }

        Section("scoreQuery.badge.rank") {
            FlowLayout(spacing: 10) {
                ForEach(ranks, id: \.self) { rank in
                    FilterChip(
                        title: rank,
                        isSelected: preferences.filters.selectedRanks.contains(rank),
                        color: RatingUtils.colorForRank(rank)
                    ) {
                        toggle(rank, keyPath: \.selectedRanks)
                    }
                }
            }
        }

        BestTableDXStarFilterSection(selection: $preferences.filters.selectedDXStars)

        Section("scoreQuery.badge.fc") {
            FlowLayout(spacing: 10) {
                ForEach(fcOptions, id: \.self) { fc in
                    FilterChip(
                        title: fc,
                        isSelected: preferences.filters.selectedFC.contains(fc),
                        color: ThemeUtils.fcColor(fc)
                    ) {
                        toggle(fc, keyPath: \.selectedFC)
                    }
                }
            }
        }

        Section("scoreQuery.badge.fs") {
            FlowLayout(spacing: 10) {
                ForEach(fsOptions, id: \.self) { fs in
                    FilterChip(
                        title: fs,
                        isSelected: preferences.filters.selectedFS.contains(fs),
                        color: ThemeUtils.fsColor(fs)
                    ) {
                        toggle(fs, keyPath: \.selectedFS)
                    }
                }
            }
        }
    }

    private func toggle(
        _ value: String,
        keyPath: WritableKeyPath<BestTableFilterSettings, Set<String>>
    ) {
        var selection = preferences.filters[keyPath: keyPath]
        if selection.contains(value) {
            selection.remove(value)
        } else {
            selection.insert(value)
        }
        preferences.filters[keyPath: keyPath] = selection
    }
}
