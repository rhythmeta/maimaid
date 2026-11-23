import SwiftUI

struct ProfileCardView: View {
  let profile: UserProfile?
  let rating: Int
  var action: (() -> Void)?

  var body: some View {
    Group {
      if let action {
        Button(action: action) { card }
          .buttonStyle(.plain)
      } else {
        card
      }
    }
  }

  private var card: some View {
    HStack(spacing: 16) {
      ZStack(alignment: .bottomTrailing) {
        AvatarImageView(
          imageData: profile?.avatarData,
          avatarURL: profile?.avatarUrl,
          size: 60,
          placeholderSystemName: "person.circle.fill",
          placeholderTint: .blue.opacity(0.6),
          placeholderBackground: Color.blue.opacity(0.08)
        )
        Text("\(rating)")
          .font(.system(size: 10, weight: .black, design: .rounded))
          .foregroundStyle(.white)
          .padding(.horizontal, 6)
          .padding(.vertical, 2)
          .background(ThemeUtils.ratingColor(rating), in: Capsule())
          .overlay(Capsule().stroke(Color.white, lineWidth: 1))
          .offset(x: 4, y: 4)
      }
      .frame(width: 60, height: 60)

      VStack(alignment: .leading, spacing: 4) {
        HStack(spacing: 6) {
          Text(profile?.name ?? String(localized: "home.profile.unbound"))
            .font(.system(size: 18, weight: .bold))
            .foregroundStyle(.primary)
          if let profile, let server = GameServer(rawValue: profile.server) {
            ScoreTintBadge(text: server.displayName, tint: serverColor(server))
          }
        }
        if let plate = profile?.plate, !plate.isEmpty {
          Text(plate)
            .font(.system(size: 12))
            .foregroundStyle(.secondary)
        } else {
          Text("home.profile.editHint")
            .font(.system(size: 12))
            .foregroundStyle(.secondary)
        }
      }

      Spacer()
      if action != nil {
        Image(systemName: "chevron.right")
          .font(.system(size: 14, weight: .bold))
          .foregroundStyle(.secondary.opacity(0.3))
          .accessibilityHidden(true)
      }
    }
    .padding(16)
    .frame(maxWidth: .infinity, alignment: .leading)
    .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 28))
    .overlay(
      RoundedRectangle(cornerRadius: 28)
        .stroke(Color.primary.opacity(0.05), lineWidth: 1)
    )
    .shadow(color: .black.opacity(0.02), radius: 8, x: 0, y: 4)
  }

  private func serverColor(_ server: GameServer) -> Color {
    switch server {
    case .jp: .red
    case .intl: .blue
    case .cn: .orange
    }
  }
}
