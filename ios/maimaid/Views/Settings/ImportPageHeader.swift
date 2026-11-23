import SwiftData
import SwiftUI

struct ImportPageHeader: View {
  let symbol: String
  let title: LocalizedStringKey
  let summary: LocalizedStringKey

  var body: some View {
    HStack(spacing: 16) {
      Image(systemName: symbol)
        .font(.system(size: 32, weight: .semibold))
        .foregroundStyle(.tint)
        .frame(width: 48, height: 48)
        .accessibilityHidden(true)

      VStack(alignment: .leading, spacing: 4) {
        Text(title)
          .font(.headline)
        Text(summary)
          .font(.subheadline)
          .foregroundStyle(.secondary)
          .fixedSize(horizontal: false, vertical: true)
      }
      .frame(maxWidth: .infinity, alignment: .leading)
    }
    .padding(20)
    .frame(maxWidth: .infinity, alignment: .leading)
    .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 20))
  }
}

struct ImportProfilePreview: View {
  @Environment(\.modelContext) private var modelContext
  @Query private var songs: [Song]

  let profile: UserProfile?
  @State private var rating = 0

  var body: some View {
    Section {
      ProfileCardView(profile: profile, rating: rating, action: nil)
        .listRowInsets(EdgeInsets(top: 0, leading: 0, bottom: 8, trailing: 0))
        .listRowBackground(Color.clear)
    } header: {
      Text("import.profile.header")
        .textCase(nil)
    }
    .listSectionSeparator(.hidden)
    .task(id: profile?.id) { await updateRating() }
  }

  private func updateRating() async {
    guard let profile else {
      rating = 0
      return
    }
    let scoreMap = await RatingUtils.fetchScoreMap(context: modelContext)
    let server = GameServer(rawValue: profile.server)
    let input = await songs.toCalculationInput(
      userProfileId: profile.id, server: server, preloadedScores: scoreMap)
    let latestVersion = server.map {
      ServerVersionService.shared.latestVersion(for: $0, songs: songs)
    } ?? nil
    let totals = await RatingUtils.calculateB50(
      input: input, b35Count: 35, b15Count: 15, latestVersion: latestVersion)
    guard !Task.isCancelled else { return }
    rating = max(totals.total, profile.playerRating)
  }
}
