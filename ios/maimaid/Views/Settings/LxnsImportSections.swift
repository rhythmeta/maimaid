import SwiftUI

struct LxnsImportSections: View {
  @Bindable var model: LocalScoreImportViewModel
  let authorize: () -> Void
  let quickImport: () -> Void
  let disconnect: () -> Void
  let exchange: () -> Void

  var body: some View {
    Section {
      LxnsImportSummary(isConnected: model.isConnected)
    }
    .listRowInsets(EdgeInsets(top: 8, leading: 0, bottom: 8, trailing: 0))
    .listSectionSeparator(.hidden)

    if model.isConnected {
      Section("import.lxns.bound.header") {
        HStack {
          ScoreImportIcon(symbol: "checkmark.shield.fill", tint: .green)
          Text("import.lxns.status")
          Spacer()
          Text("import.lxns.status.connected")
            .foregroundStyle(.green)
        }
        ScoreImportActionRow(
          title: model.isBusy ? "import.status.syncing" : "import.lxns.action.quickSync",
          symbol: "arrow.triangle.2.circlepath.circle.fill", tint: .cyan, action: quickImport
        )
        .disabled(model.isBusy)
        .opacity(model.isBusy ? 0.6 : 1)
      }
      Section("import.lxns.manage.header") {
        Button("import.lxns.action.relogin", role: .destructive, action: disconnect)
          .disabled(model.isBusy)
      }
    } else {
      Section {
        ScoreImportActionRow(
          title: "import.lxns.action.openBrowser", symbol: "safari.fill",
          tint: .indigo, action: authorize
        )
        .disabled(model.isBusy)
        .opacity(model.isBusy ? 0.6 : 1)
      } header: {
        Text("import.lxns.step1.header")
      } footer: {
        Text("import.lxns.step1.footer")
      }
      Section {
        VStack {
          HStack {
            ScoreImportIcon(symbol: "key.fill", tint: .gray)
            TextField("import.lxns.code.placeholder", text: $model.authorizationCode)
              .textInputAutocapitalization(.never)
              .autocorrectionDisabled()
              .disabled(model.isBusy)
          }
          Button(action: exchange) {
            HStack {
              Spacer()
              if model.isBusy {
                ProgressView()
              }
              Text(model.isBusy ? "import.status.syncing" : "import.lxns.action.startImport")
                .bold()
              Spacer()
            }
          }
          .buttonStyle(.borderedProminent)
          .controlSize(.large)
          .disabled(
            model.isBusy
              || model.authorizationCode.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
          )
        }
        .padding(.vertical, 4)
        .listRowSeparator(.hidden)
      } header: {
        Text("import.lxns.step2.header")
      } footer: {
        Text("import.lxns.step2.footer")
      }
      .listSectionSeparator(.hidden)
    }
  }
}
