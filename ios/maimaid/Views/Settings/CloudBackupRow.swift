import SwiftUI

struct CloudBackupRow: View {
    let backup: CloudBackup

    private var date: Date? {
        (try? Date.ISO8601FormatStyle(includingFractionalSeconds: true).parse(backup.committedAt))
            ?? (try? Date.ISO8601FormatStyle().parse(backup.committedAt))
    }

    var body: some View {
        HStack {
            Image(systemName: "icloud.and.arrow.down.fill")
                .foregroundStyle(.green)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 4) {
                if let date {
                    Text(date, format: .dateTime.year().month().day().hour().minute())
                        .foregroundStyle(.primary)
                } else {
                    Text(backup.committedAt).foregroundStyle(.primary)
                }
                Text(backup.deviceName).font(.subheadline).foregroundStyle(.secondary)
                Text("settings.cloud.snapshot.profiles \(backup.profileCount)")
                    .font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            Image(systemName: "chevron.right")
                .font(.footnote.weight(.semibold))
                .foregroundStyle(.tertiary)
                .accessibilityHidden(true)
        }
        .frame(minHeight: 44)
        .contentShape(.rect)
        .accessibilityElement(children: .combine)
        .accessibilityHint(Text("settings.cloud.restore"))
    }
}
