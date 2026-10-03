import SwiftUI

struct SongDetailPlayHistorySection: View {
    let records: [PlayRecord]
    let maxDxScore: Int
    let diffColor: Color
    @Binding var isExpanded: Bool
    @Binding var historySortByDate: Bool
    @Binding var historyPage: Int
    let onDeleteRequested: (PlayRecord) -> Void

    private let itemsPerPage = 5

    private var sortedRecords: [PlayRecord] {
        records.sorted { lhs, rhs in
            if historySortByDate {
                return lhs.playDate > rhs.playDate
            }
            return lhs.rate > rhs.rate
        }
    }

    private var totalPages: Int {
        max(1, Int(ceil(Double(sortedRecords.count) / Double(itemsPerPage))))
    }

    private var validPage: Int {
        max(1, min(historyPage, totalPages))
    }

    private var displayRecords: [PlayRecord] {
        let startIndex = (validPage - 1) * itemsPerPage
        let endIndex = min(startIndex + itemsPerPage, sortedRecords.count)
        guard startIndex < endIndex else { return [] }
        return Array(sortedRecords[startIndex..<endIndex])
    }

    var body: some View {
        let bestRecordId = records.max(by: { $0.rate < $1.rate })?.id

        return VStack(spacing: 0) {
            HStack {
                Button {
                    withAnimation(.spring(response: 0.35, dampingFraction: 0.8)) {
                        isExpanded.toggle()
                    }
                } label: {
                    Text("song.detail.section.history")
                        .font(.subheadline.bold())
                        .foregroundStyle(.primary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .contentShape(.rect)
                }
                SongDetailHistorySortOption(
                    title: "song.detail.sort.time", selected: historySortByDate, tint: diffColor
                ) {
                    historySortByDate = true
                    historyPage = 1
                }
                SongDetailHistorySortOption(
                    title: "song.detail.sort.rate", selected: !historySortByDate, tint: diffColor
                ) {
                    historySortByDate = false
                    historyPage = 1
                }
                Button {
                    withAnimation(.spring(response: 0.35, dampingFraction: 0.8)) {
                        isExpanded.toggle()
                    }
                } label: {
                    Label("song.detail.section.history", systemImage: "chevron.right")
                        .labelStyle(.iconOnly)
                        .font(.caption.bold())
                        .foregroundStyle(.secondary.opacity(0.4))
                        .rotationEffect(.degrees(isExpanded ? 90 : 0))
                }
            }
            .buttonStyle(.plain)
            .padding(.horizontal, 20)
            .padding(.bottom, isExpanded ? 8 : 0)

            if isExpanded {
                VStack(spacing: 0) {
                    ForEach(displayRecords.indices, id: \.self) { index in
                        let record = displayRecords[index]
                        HStack(spacing: 12) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(
                                    record.playDate.formatted(
                                        .dateTime.year(.twoDigits).month(.defaultDigits).day(.defaultDigits))
                                )
                                    .font(.caption.bold())
                                    .foregroundStyle(.primary)
                                Text(record.playDate, style: .time)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            .frame(width: 70, alignment: .leading)

                            VStack(alignment: .leading, spacing: 2) {
                                HStack(spacing: 4) {
                                    Text(record.rank)
                                        .font(.caption.bold())
                                        .fontDesign(.rounded)
                                        .foregroundStyle(RatingUtils.colorForRank(record.rank))
                                    Text("\(record.rate, format: .number.precision(.fractionLength(4)))%")
                                        .font(.caption.monospaced().bold())
                                        .foregroundStyle(.primary)
                                }

                                .lineLimit(1)
                                .minimumScaleFactor(0.75)

                                SongDetailScoreBadges(
                                    dxScore: record.dxScore, maxDxScore: maxDxScore,
                                    fc: record.fc, fs: record.fs
                                )
                            }

                            .layoutPriority(1)

                            Spacer(minLength: 0)

                            if record.dxScore > 0 {
                                Text(record.dxScore, format: .number.grouping(.never))
                                    .font(.caption)
                                    .monospacedDigit()
                                    .foregroundStyle(.secondary)
                                    .lineLimit(1)
                                    .minimumScaleFactor(0.75)
                            }
                            Button {
                                onDeleteRequested(record)
                            } label: {
                                Label("song.detail.history.delete.confirm", systemImage: "trash")
                                    .labelStyle(.iconOnly)
                                    .font(.caption)
                                    .foregroundStyle(.red.opacity(0.6))
                            }
                            .buttonStyle(.plain)
                        }
                        .padding(.horizontal, 20)
                        .padding(.vertical, 8)
                        .background(
                            Group {
                                if record.id == bestRecordId {
                                    RoundedRectangle(cornerRadius: 8)
                                        .fill(diffColor.opacity(0.1))
                                        .overlay(
                                            RoundedRectangle(cornerRadius: 8)
                                                .strokeBorder(diffColor, lineWidth: 1.5)
                                        )
                                        .padding(.horizontal, 10)
                                        .padding(.vertical, 2)
                                } else if index % 2 == 0 {
                                    Color.primary.opacity(0.02)
                                }
                            }
                        )
                    }

                    if totalPages > 1 {
                        HStack(spacing: 12) {
                            Button {
                                if historyPage > 1 {
                                    historyPage -= 1
                                }
                            } label: {
                                Image(systemName: "chevron.left")
                                    .font(.subheadline.bold())
                                    .foregroundStyle(
                                        historyPage > 1
                                            ? AnyShapeStyle(diffColor)
                                            : AnyShapeStyle(.secondary.opacity(0.3))
                                    )
                                    .padding(8)
                            }
                            .disabled(historyPage <= 1)

                            Menu {
                                Picker("song.detail.history.pagePicker", selection: $historyPage) {
                                    ForEach(1...totalPages, id: \.self) { page in
                                        Text(String(localized: "song.detail.page \(page)")).tag(page)
                                    }
                                }
                            } label: {
                                Text("\(validPage) / \(totalPages)")
                                    .font(.caption.monospaced().bold())
                                    .foregroundStyle(.primary)
                                    .padding(.horizontal, 16)
                                    .padding(.vertical, 6)
                                    .background(Color.primary.opacity(0.05), in: Capsule())
                            }

                            Button {
                                if historyPage < totalPages {
                                    historyPage += 1
                                }
                            } label: {
                                Image(systemName: "chevron.right")
                                    .font(.subheadline.bold())
                                    .foregroundStyle(
                                        historyPage < totalPages
                                            ? AnyShapeStyle(diffColor)
                                            : AnyShapeStyle(.secondary.opacity(0.3))
                                    )
                                    .padding(8)
                            }
                            .disabled(historyPage >= totalPages)
                        }
                        .padding(.vertical, 12)
                    }
                }
                .transition(.opacity.combined(with: .move(edge: .top)))
            }
        }
    }
}
