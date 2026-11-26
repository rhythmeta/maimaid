import SwiftUI

/// The card credential remembered from the last successful import.
///
/// Mirrors `SgwcmaidBoundPlayerSection`: the identity and its two actions share a single
/// list row so the list draws one separator, and the card number is shown masked.
struct SdgaSavedCredentialSection: View {
  let saved: SdgaSavedCredential
  let isImporting: Bool
  let isEnabled: Bool
  let quickImport: () -> Void
  let forget: () -> Void

  var body: some View {
    Section {
      VStack(alignment: .leading, spacing: 12) {
        HStack(spacing: 16) {
          Image(systemName: "person.crop.circle")
            .font(.largeTitle)
            .foregroundStyle(.tint)
            .frame(width: 48)
            .accessibilityHidden(true)
          VStack(alignment: .leading, spacing: 2) {
            Text(playerName)
              .font(.headline)
            Text(saved.masked)
              .font(.footnote.monospaced())
              .foregroundStyle(.secondary)
          }
        }

        Divider()

        Button(action: quickImport) {
          HStack {
            Spacer()
            if isImporting {
              ProgressView()
            }
            Text("import.sdga.action.quick")
              .bold()
            Spacer()
          }
        }
        .buttonStyle(.borderedProminent)
        .controlSize(.large)
        .disabled(!isEnabled || isImporting)

        Button(role: .destructive, action: forget) {
          Text("import.sdga.action.forget")
            .bold()
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(.bordered)
        .controlSize(.large)
        .tint(.red)
        .disabled(isImporting)
      }
      .listRowInsets(EdgeInsets(top: 12, leading: 16, bottom: 12, trailing: 16))
      .listRowSeparator(.hidden)
    } header: {
      Text("import.sdga.bound.header")
    } footer: {
      Text("import.sdga.bound.notice")
    }
  }

  private var playerName: String {
    saved.playerName.isEmpty
      ? String(localized: "import.sdga.bound.unknown")
      : saved.playerName
  }
}
