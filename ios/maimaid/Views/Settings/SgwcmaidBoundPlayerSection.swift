import SwiftUI

/// The player remembered from the last SGWCMAID import.
///
/// Only the display name is shown; the bound user id stays inside the
/// Keychain-backed store and the import pipeline.
///
/// The whole block is a single list row on purpose: it keeps the list from
/// drawing its own inset separators between the identity and the two actions,
/// which used to leave a rule that started only after the avatar and another
/// rule between the buttons. One full rule is drawn between the identity and
/// the actions, and the actions sit directly next to each other.
struct SgwcmaidBoundPlayerSection: View {
  @Bindable var model: SgwcmaidImportViewModel
  let isEnabled: Bool
  let quickImport: () -> Void
  let unbind: () -> Void

  var body: some View {
    Section {
      VStack(alignment: .leading, spacing: 12) {
        HStack(spacing: 16) {
          Image(systemName: "person.crop.circle")
            .font(.largeTitle)
            .foregroundStyle(.tint)
            .frame(width: 48)
            .accessibilityHidden(true)
          Text(playerName)
            .font(.headline)
        }

        Divider()

        Button(action: quickImport) {
          HStack {
            Spacer()
            if model.isImporting {
              ProgressView()
            }
            Text("import.sgwcmaid.action.quick")
              .bold()
            Spacer()
          }
        }
        .buttonStyle(.borderedProminent)
        .controlSize(.large)
        .disabled(!isEnabled || model.isImporting)

        Button(role: .destructive, action: unbind) {
          Text("import.sgwcmaid.action.unbind")
            .bold()
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(.bordered)
        .controlSize(.large)
        .tint(.red)
        .disabled(model.isImporting)
      }
      .listRowInsets(EdgeInsets(top: 12, leading: 16, bottom: 12, trailing: 16))
      .listRowSeparator(.hidden)
    } header: {
      Text("import.sgwcmaid.bound.header")
    } footer: {
      Text("import.sgwcmaid.bound.notice")
    }
  }

  private var playerName: String {
    let name = model.binding?.playerName ?? ""
    return name.isEmpty ? String(localized: "import.sgwcmaid.bound.unknown") : name
  }
}
