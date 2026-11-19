import SwiftUI

/// One published alias: its approval time and a marker when the signed-in account submitted it.
struct CommunityAliasApprovedRow: View {
    let alias: CommunityAliasApprovedItem
    let isMine: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .center, spacing: 8) {
                Text(alias.aliasText)
                    .font(.system(size: 16, weight: .semibold))

                Spacer(minLength: 8)

                if isMine {
                    Text("community.alias.board.approved.mine")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(Color.accentColor)
                        .padding(.horizontal, 7)
                        .padding(.vertical, 3)
                        .background(Capsule().fill(Color.accentColor.opacity(0.14)))
                }
            }

            if let approvedAt = alias.approvedAt {
                Text(String(localized: "community.alias.board.approved.at \(formatApprovedAt(approvedAt))"))
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 6)
    }

    private func formatApprovedAt(_ date: Date) -> String {
        date.formatted(date: .abbreviated, time: .shortened)
    }
}
