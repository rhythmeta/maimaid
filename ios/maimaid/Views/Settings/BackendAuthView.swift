import AuthenticationServices
import SwiftData
import SwiftUI
import UIKit

@MainActor
private final class WebAuthPresentationProvider: NSObject,
  ASWebAuthenticationPresentationContextProviding {
  func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
    let windowScenes = UIApplication.shared.connectedScenes
      .compactMap { $0 as? UIWindowScene }
    let activeWindowScene =
      windowScenes.first(where: { $0.activationState == .foregroundActive })
      ?? windowScenes.first

    guard let activeWindowScene else {
      preconditionFailure("A connected window scene is required for web authentication")
    }

    return activeWindowScene.windows.first(where: \.isKeyWindow)
      ?? ASPresentationAnchor(windowScene: activeWindowScene)
  }
}

struct BackendAuthView: View {
  @Environment(\.modelContext) private var modelContext
  @State private var sessionManager = BackendSessionManager.shared
  @State private var backups = CloudBackupService.shared
  @State private var webAuthenticationSession: ASWebAuthenticationSession?
  @State private var isOpeningWebAuth = false
  @State private var message: String?
  @State private var selectedBackup: CloudBackup?
  private let presentationProvider = WebAuthPresentationProvider()

  var body: some View {
    List {
      Section("Rhythmeta") {
        if let user = sessionManager.currentUser {
          LabeledContent("settings.cloud.handle", value: user.handle)
          LabeledContent("settings.cloud.email", value: user.email)
          Button("settings.cloud.logout", role: .destructive) {
            Task { await sessionManager.logout() }
          }
        } else {
          Button("settings.cloud.login") { startWebAuth("login") }
          Button("settings.cloud.register") { startWebAuth("register") }
          Button("settings.cloud.forgotPassword") { startWebAuth("forgot") }
        }
      }
      if sessionManager.isAuthenticated {
        Section {
          Button("settings.cloud.backup", systemImage: "icloud.and.arrow.up") {
            Task { await perform { try await backups.backup(context: modelContext) } }
          }
          Button("settings.cloud.snapshots.refresh", systemImage: "arrow.clockwise") {
            Task { await perform { try await backups.reload() } }
          }
          if backups.isBusy { ProgressView() }
        } footer: {
          Text("settings.cloud.snapshot.hint")
        }
        Section("settings.cloud.snapshots.title") {
          if backups.backups.isEmpty {
            Text("settings.cloud.snapshots.empty").foregroundStyle(.secondary)
          }
          ForEach(backups.backups) { backup in
            Button {
              selectedBackup = backup
            } label: {
              VStack(alignment: .leading) {
                Text(backup.committedAt)
                Text(backup.deviceName).foregroundStyle(.secondary)
                Text("settings.cloud.snapshot.profiles \(backup.profileCount)").font(.caption)
              }
            }
          }
        }
      }
      if let message { Section { Text(message).textSelection(.enabled) } }
    }
    .navigationTitle("Rhythmeta")
    .disabled(backups.isBusy || isOpeningWebAuth)
    .task { await sessionManager.checkSession() }
    .task(id: sessionManager.currentUser?.id) {
      if sessionManager.isAuthenticated { await perform { try await backups.reload() } }
    }
    .onChange(of: sessionManager.pendingMessage) { _, value in
      if let value {
        message = String(localized: String.LocalizationValue(value))
        sessionManager.clearPendingMessage()
      }
    }
    .confirmationDialog(
      "settings.cloud.restore",
      isPresented: Binding(
        get: { selectedBackup != nil }, set: { if !$0 { selectedBackup = nil } }),
      titleVisibility: .visible
    ) {
      if let backup = selectedBackup {
        Button("settings.cloud.restore", role: .destructive) {
          Task {
            await perform {
              try await backups.restore(backup, context: modelContext)
              message = String(localized: "settings.cloud.message.restoreSuccess")
            }
          }
        }
      }
    } message: {
      Text("settings.cloud.restore.replaceHint")
    }
  }

  private func perform(_ operation: () async throws -> Void) async {
    do { try await operation() } catch { message = error.localizedDescription }
  }

  private func startWebAuth(_ mode: String) {
    guard let url = sessionManager.webAuthURL(mode: mode) else { return }
    isOpeningWebAuth = true
    let session = ASWebAuthenticationSession(url: url, callbackURLScheme: "maimaid") { callbackURL, error in
      Task { @MainActor in
        isOpeningWebAuth = false
        webAuthenticationSession = nil
        if let callbackURL {
          sessionManager.handleAuthRedirect(callbackURL)
        } else if (error as? ASWebAuthenticationSessionError)?.code != .canceledLogin {
          message = String(localized: "settings.cloud.message.authLinkFailed")
        }
      }
    }
    session.presentationContextProvider = presentationProvider
    session.prefersEphemeralWebBrowserSession = false
    webAuthenticationSession = session
    if !session.start() {
      isOpeningWebAuth = false
      webAuthenticationSession = nil
    }
  }
}
