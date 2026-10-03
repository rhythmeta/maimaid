import Foundation
import Observation
import SwiftData

@MainActor
@Observable
final class LocalScoreImportViewModel {
  let provider: ScoreImportProvider
  var authorizationCode = ""
  var userCode = ""
  var isBusy = false
  var isConnected = false
  var status = ""
  private(set) var hasError = false
  private var verifier = ""
  private var authorizationProfileID: UUID?
  private var operation: Task<Void, Never>?

  init(provider: ScoreImportProvider) { self.provider = provider }

  func reload(profileID: UUID?) {
    cancel()
    verifier = ""
    authorizationProfileID = nil
    authorizationCode = ""
    status = ""
    isConnected =
      profileID.map {
        !ScoreImportAPI.shared.refreshToken(provider: provider, profileID: $0).isEmpty
      } ?? false
  }

  func authorize(profileID: UUID, context: ModelContext, open: @escaping (URL) -> Void) {
    guard !isBusy else { return }
    if provider == .lxns {
      verifier = AuthUtils.generateCodeVerifier()
      authorizationProfileID = profileID
      var components = URLComponents(string: "https://maimai.lxns.net/oauth/authorize")
      components?.queryItems = [
        URLQueryItem(name: "response_type", value: "code"),
        URLQueryItem(name: "client_id", value: provider.clientID),
        URLQueryItem(name: "redirect_uri", value: LxnsOAuthConfiguration.redirectUri),
        URLQueryItem(name: "scope", value: "read_player write_player"),
        URLQueryItem(
          name: "code_challenge", value: AuthUtils.generateCodeChallenge(verifier: verifier)),
        URLQueryItem(name: "code_challenge_method", value: "S256"),
        URLQueryItem(name: "state", value: UUID().uuidString)
      ]
      if let url = components?.url { open(url) }
      return
    }
    perform(profileID: profileID) {
      let authorization = try await ScoreImportAPI.shared.startDivingFish()
      try Task.checkCancellation()
      self.userCode = authorization.userCode
      open(authorization.verificationUriComplete ?? authorization.verificationUri)
      try await ScoreImportAPI.shared.finishDivingFish(authorization, profileID: profileID)
      try Task.checkCancellation()
      try await self.importScores(profileID: profileID, context: context)
    }
  }

  func exchange(profileID: UUID, context: ModelContext) {
    guard authorizationProfileID == profileID, !verifier.isEmpty else {
      hasError = true
      status = String(localized: "import.lxns.error.security")
      return
    }
    let code = authorizationCode.trimmingCharacters(in: .whitespacesAndNewlines)
    let codeVerifier = verifier
    perform(profileID: profileID) {
      try await ScoreImportAPI.shared.exchangeLxns(
        code: code, verifier: codeVerifier, profileID: profileID)
      self.verifier = ""
      self.authorizationCode = ""
      try Task.checkCancellation()
      try await self.importScores(profileID: profileID, context: context)
    }
  }

  func quickImport(profileID: UUID, context: ModelContext) {
    perform(profileID: profileID) {
      try await self.importScores(profileID: profileID, context: context)
    }
  }

  func disconnect(profileID: UUID) {
    guard !isBusy else { return }
    do {
      try ScoreImportAPI.shared.store("", provider: provider, profileID: profileID)
      isConnected = false
      status = ""
      hasError = false
    } catch {
      hasError = true
      status = error.localizedDescription
    }
  }

  func cancel() {
    operation?.cancel()
    operation = nil
    isBusy = false
    userCode = ""
    status = ""
    hasError = false
  }

  private func perform(profileID: UUID, body: @escaping @MainActor () async throws -> Void) {
    guard !isBusy else { return }
    isBusy = true
    status = ""
    hasError = false
    operation = Task { @MainActor in
      do { try await body() } catch is CancellationError { return } catch {
        guard !Task.isCancelled else { return }
        self.hasError = true
        self.status = error.localizedDescription
      }
      guard !Task.isCancelled else { return }
      self.isConnected = !ScoreImportAPI.shared.refreshToken(
        provider: self.provider, profileID: profileID
      ).isEmpty
      self.isBusy = false
      self.userCode = ""
    }
  }

  private func importScores(profileID: UUID, context: ModelContext) async throws {
    let result = try await LocalScoreImportService.run(
      provider: provider, profileID: profileID, context: context)
    status = String(
      localized:
        "import.local.result \(result.fetchedCount) \(result.updatedCount) \(result.skippedCount)")
  }
}
