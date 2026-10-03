import SwiftUI

struct DivingFishImportSections: View {
  let model: LocalScoreImportViewModel
  let authorize: () -> Void
  let quickImport: () -> Void
  let disconnect: () -> Void

  var body: some View {
    Section {
      Label {
        VStack(alignment: .leading) {
          Text(model.isConnected ? "import.df.oauth.connected" : "import.df.oauth.title")
            .bold()
          Text(
            model.isConnected
              ? "import.df.oauth.connectedAccount" : "import.df.oauth.description"
          )
          .foregroundStyle(.secondary)
        }
      } icon: {
        Image(systemName: model.isConnected ? "checkmark.shield.fill" : "person.badge.key.fill")
          .foregroundStyle(model.isConnected ? .green : .blue)
      }
    }

    Section {
      if model.isConnected {
        ScoreImportActionRow(
          title: "import.df.action.quickSync", symbol: "arrow.triangle.2.circlepath",
          tint: .blue, action: quickImport
        )
        ScoreImportActionRow(
          title: "import.df.oauth.reconnect", symbol: "person.badge.key",
          tint: .orange, action: authorize
        )
        ScoreImportActionRow(
          title: "import.df.oauth.disconnect", symbol: "personalhotspot.slash",
          tint: .red, action: disconnect
        )
      } else {
        ScoreImportActionRow(
          title: "import.df.oauth.connectImport", symbol: "person.badge.key",
          tint: .blue, action: authorize
        )
      }
    } header: {
      Text("import.df.oauth.actions")
    } footer: {
      Text("import.df.oauth.footer")
    }
    .disabled(model.isBusy)
    .opacity(model.isBusy ? 0.6 : 1)
  }
}
