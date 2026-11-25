import SwiftUI

struct CommunityAliasReviewButtonStyle: PrimitiveButtonStyle {
    let decision: CommunityAliasModeration
    let alias: String
    let onModerate: (CommunityAliasModeration) -> Void

    @Environment(\.isEnabled) private var isEnabled
    @State private var isConfirming = false

    func makeBody(configuration: Configuration) -> some View {
        let title: LocalizedStringKey = decision == .approved
            ? "community.alias.board.confirmApproval" : "community.alias.board.confirmRejection"
        let action: LocalizedStringKey = decision == .approved
            ? "community.alias.board.setApproved" : "community.alias.board.setRejected"

        configuration.label
            .contentShape(.capsule)
            .gesture(
                LongPressGesture().exclusively(before: TapGesture())
                    .onEnded { gesture in
                        guard isEnabled else { return }
                        switch gesture {
                        case .first: isConfirming = true
                        case .second: configuration.trigger()
                        }
                    }
            )
            .accessibilityAddTraits(.isButton)
            .accessibilityAction { configuration.trigger() }
            .accessibilityAction(named: Text(action)) { isConfirming = true }
            .sensoryFeedback(.impact(weight: .light), trigger: isConfirming) { _, isPresented in
                isPresented
            }
            .alert(title, isPresented: $isConfirming) {
                Button(action, role: decision == .rejected ? .destructive : nil) {
                    if isEnabled { onModerate(decision) }
                }
                Button("userProfile.cancel", role: .cancel) {}
            } message: {
                Text(verbatim: alias)
            }
    }
}
