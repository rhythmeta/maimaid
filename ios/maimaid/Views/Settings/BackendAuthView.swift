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
  @State private var isSigningOut = false
  @State private var message: String?
  @State private var isErrorMessage = false
  @State private var selectedBackup: CloudBackup?
  private let presentationProvider = WebAuthPresentationProvider()

  var body: some View {
    List {
      Section {
        CloudAccountSummary(user: sessionManager.currentUser)
      }
      .listRowInsets(EdgeInsets(top: 8, leading: 0, bottom: 8, trailing: 0))
      .listRowBackground(Color.clear)
      .listSectionSeparator(.hidden)
      if let user = sessionManager.currentUser {
        Section("settings.cloud.account.section") {
          LabeledContent("settings.cloud.account.handle", value: user.handle)
          LabeledContent("settings.cloud.account.email", value: user.email)
          LabeledContent("settings.cloud.account.status") {
            Text("settings.cloud.status.loggedIn").foregroundStyle(.secondary)
          }
        }
      } else {
        Section {
          CloudAccountAction(title: "settings.cloud.login.button",
                             icon: "person.crop.circle.badge.checkmark", tint: .blue) { startWebAuth("login") }
          CloudAccountAction(title: "settings.cloud.signup.button",
                             icon: "person.badge.plus.fill", tint: .green) { startWebAuth("register") }
          CloudAccountAction(title: "settings.cloud.forgotPassword",
                             icon: "key.fill", tint: .orange) { startWebAuth("forgot") }
        }
      }
      if sessionManager.isAuthenticated {
        Section {
          CloudAccountAction(title: "settings.cloud.backup", icon: "icloud.and.arrow.up.fill", tint: .blue) {
            Task {
              await perform {
                try await backups.backup(context: modelContext)
                message = String(localized: "settings.cloud.message.backupSuccess")
              }
            }
          }
          CloudAccountAction(title: "settings.cloud.snapshots.refresh", icon: "arrow.clockwise", tint: .green) {
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
              CloudBackupRow(backup: backup)
            }
            .buttonStyle(.plain)
          }
        }
      }
      if sessionManager.isAuthenticated {
        Section {
          Button("settings.cloud.logout", role: .destructive) {
            Task {
              isSigningOut = true
              await sessionManager.logout()
              isSigningOut = false
            }
          }
        }
      }
    }
    .listStyle(.insetGrouped)
    .overlay(alignment: .bottom) {
      if let message {
        CloudAccountNotice(message: message, isError: isErrorMessage)
          .padding(20)
      }
    }
    .task(id: message) {
      guard message != nil else { return }
      do { try await Task.sleep(for: .seconds(4)) } catch { return }
      message = nil
    }
    .navigationTitle("Rhythmeta")
    .navigationBarTitleDisplayMode(.inline)
    .disabled(backups.isBusy || isOpeningWebAuth || isSigningOut)
    .task { await sessionManager.checkSession() }
    .task(id: sessionManager.currentUser?.id) {
      if sessionManager.isAuthenticated { await perform { try await backups.reload() } }
    }
    .onChange(of: sessionManager.pendingMessage) { _, value in
      if let value {
        isErrorMessage = value != "settings.cloud.message.loginSuccess"
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
    isErrorMessage = false
    do { try await operation() } catch {
      isErrorMessage = true
      message = error.localizedDescription
    }
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
          isErrorMessage = true
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
