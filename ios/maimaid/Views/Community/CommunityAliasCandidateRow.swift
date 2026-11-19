import SwiftUI

/// One candidate on the voting board: its alias, the vote deadline and the two vote buttons.
struct CommunityAliasCandidateRow: View {
    let item: CommunityAliasVotingBoardItem
    let isVoting: Bool
    let onVote: (Bool) -> Void

    private struct VotePresentation {
        let iconName: String
        let title: String
        let accentColor: Color
        let isSelected: Bool
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top) {
                Text(item.aliasText)
                    .font(.system(size: 16, weight: .semibold))

                Spacer()

                Text(String(localized: "community.alias.board.deadline \(formatDeadline(item.voteCloseAt))"))
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(.secondary)
            }

            HStack(spacing: 10) {
                voteButton(support: true)
                voteButton(support: false)
                Spacer()
            }
        }
        .padding(.vertical, 6)
    }

    private func voteButton(support: Bool) -> some View {
        let presentation = votePresentation(support: support)

        return Button {
            onVote(support)
        } label: {
            HStack(spacing: 6) {
                if isVoting {
                    ProgressView()
                        .controlSize(.mini)
                }
                Image(systemName: presentation.iconName)
                Text(presentation.title)
            }
            .font(.system(size: 12, weight: .semibold))
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .foregroundStyle(presentation.isSelected ? .white : presentation.accentColor)
            .background(
                Capsule()
                    .fill(presentation.isSelected ? presentation.accentColor : .clear)
            )
            .overlay(
                Capsule()
                    .strokeBorder(presentation.accentColor.opacity(0.35), lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
        .disabled(isVoting)
    }

    private func votePresentation(support: Bool) -> VotePresentation {
        let isSelected = support ? (item.myVote == 1) : (item.myVote == -1)
        let count = support ? item.supportCount : item.opposeCount
        let accentColor: Color = support ? .green : .red

        let iconName: String
        if support {
            iconName = isSelected ? "hand.thumbsup.fill" : "hand.thumbsup"
        } else {
            iconName = isSelected ? "hand.thumbsdown.fill" : "hand.thumbsdown"
        }

        let title: String
        if support {
            title = isSelected
                ? String(localized: "community.alias.vote.cancelSupport \(count)")
                : String(localized: "community.alias.vote.support \(count)")
        } else {
            title = isSelected
                ? String(localized: "community.alias.vote.cancelOppose \(count)")
                : String(localized: "community.alias.vote.oppose \(count)")
        }

        return VotePresentation(
            iconName: iconName,
            title: title,
            accentColor: accentColor,
            isSelected: isSelected
        )
    }

    private func formatDeadline(_ date: Date?) -> String {
        guard let date else { return "--" }
        return date.formatted(date: .abbreviated, time: .shortened)
    }
}
