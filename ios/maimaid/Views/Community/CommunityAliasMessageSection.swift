import SwiftUI

/// Hint or empty-state card used by the community alias board.
struct CommunityAliasMessageSection: View {
    let title: LocalizedStringKey
    let message: LocalizedStringKey
    var actionTitle: LocalizedStringKey?
    var onAction: (() -> Void)?

    var body: some View {
        Section {
            VStack(alignment: .leading, spacing: actionTitle == nil ? 8 : 10) {
                Text(title)
                    .font(.headline)
                Text(message)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)

                if let actionTitle, let onAction {
                    Button(action: onAction) {
                        Text(actionTitle)
                            .font(.system(size: 14, weight: .semibold))
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                }
            }
            .padding(.vertical, 4)
        }
    }
}
