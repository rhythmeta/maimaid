import Foundation

@MainActor
final class ScoreImportAPI {
  static let shared = ScoreImportAPI()
  private var refreshes: [String: Task<String, Error>] = [:]

  func request(_ address: String, form: [String: String]? = nil, token: String? = nil) async throws
    -> Data {
    guard let url = URL(string: address) else { throw URLError(.badURL) }
    var request = URLRequest(url: url)
    request.timeoutInterval = 30
    request.cachePolicy = .reloadIgnoringLocalCacheData
    request.setValue("application/json", forHTTPHeaderField: "Accept")
    if let token { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
    if let form {
      request.httpMethod = "POST"
      request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
      let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-._~"))
      request.httpBody = Data(
        form.sorted { $0.key < $1.key }.map {
          "\($0.key)=\($0.value.addingPercentEncoding(withAllowedCharacters: allowed) ?? "")"
        }.joined(separator: "&").utf8)
    }
    let (data, response) = try await URLSession.shared.data(for: request)
    guard let response = response as? HTTPURLResponse else { throw URLError(.badServerResponse) }
    guard (200..<300).contains(response.statusCode) else {
      let payload = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
      throw ScoreImportError(
        code: payload?["error"] as? String
          ?? (response.statusCode == 401 ? "unauthorized" : "HTTP \(response.statusCode)"))
    }
    return data
  }

  func startDivingFish() async throws -> DivingFishDeviceAuthorization {
    let data = try await request(
      "https://auth.diving-fish.com/oauth/device_authorization",
      form: [
        "client_id": ScoreImportProvider.divingFish.clientID, "scope": "prober.records.read"
      ])
    return try JSONDecoder().decode(DivingFishDeviceAuthorization.self, from: data)
  }

  func finishDivingFish(_ authorization: DivingFishDeviceAuthorization, profileID: UUID)
    async throws {
    let deadline = ContinuousClock.now.advanced(by: .seconds(authorization.expiresIn))
    var interval = max(authorization.interval ?? 5, 5)
    while ContinuousClock.now < deadline {
      try await Task.sleep(for: .seconds(interval))
      try Task.checkCancellation()
      do {
        let data = try await request(
          ScoreImportProvider.divingFish.tokenURL,
          form: [
            "grant_type": "urn:ietf:params:oauth:grant-type:device_code",
            "device_code": authorization.deviceCode,
            "client_id": ScoreImportProvider.divingFish.clientID
          ])
        let token = try JSONDecoder().decode(ScoreImportToken.self, from: data)
        try store(token.refreshToken, provider: .divingFish, profileID: profileID)
        return
      } catch let error as ScoreImportError where error.code == "authorization_pending" {
        continue
      } catch let error as ScoreImportError where error.code == "slow_down" {
        interval += 5
      }
    }
    throw ScoreImportError(code: "expired_token")
  }

  func exchangeLxns(code: String, verifier: String, profileID: UUID) async throws {
    guard !verifier.isEmpty else { throw ScoreImportError(code: "missing_pkce") }
    let data = try await request(
      ScoreImportProvider.lxns.tokenURL,
      form: [
        "grant_type": "authorization_code", "client_id": ScoreImportProvider.lxns.clientID,
        "redirect_uri": LxnsOAuthConfiguration.redirectUri, "code": code,
        "code_verifier": verifier
      ])
    let token = try JSONDecoder().decode(ScoreImportToken.self, from: data)
    try store(token.refreshToken, provider: .lxns, profileID: profileID)
  }

  func accessToken(provider: ScoreImportProvider, profileID: UUID) async throws -> String {
    let key = "\(profileID)-\(provider.rawValue)"
    if let pending = refreshes[key] { return try await pending.value }
    let task = Task { @MainActor in
      let previous = self.refreshToken(provider: provider, profileID: profileID)
      guard !previous.isEmpty else { throw ScoreImportError(code: "invalid_grant") }
      do {
        let data = try await self.request(
          provider.tokenURL,
          form: [
            "grant_type": "refresh_token", "client_id": provider.clientID,
            "refresh_token": previous
          ])
        let token = try JSONDecoder().decode(ScoreImportToken.self, from: data)
        guard self.refreshToken(provider: provider, profileID: profileID) == previous else {
          throw CancellationError()
        }
        // Persist the rotated token before exposing the access token to any caller.
        try self.store(token.refreshToken, provider: provider, profileID: profileID)
        return token.accessToken
      } catch let error as ScoreImportError where error.code == "invalid_grant" {
        if self.refreshToken(provider: provider, profileID: profileID) == previous {
          try self.store("", provider: provider, profileID: profileID)
        }
        throw error
      }
    }
    refreshes[key] = task
    defer { refreshes[key] = nil }
    return try await task.value
  }

  func refreshToken(provider: ScoreImportProvider, profileID: UUID) -> String {
    let credentials = ProfileCredentialStore.shared.credentials(for: profileID)
    return provider == .lxns ? credentials.lxnsRefreshToken : credentials.divingFishRefreshToken
  }

  func store(_ token: String, provider: ScoreImportProvider, profileID: UUID) throws {
    var credentials = ProfileCredentialStore.shared.credentials(for: profileID)
    if provider == .lxns {
      credentials.lxnsRefreshToken = token
    } else {
      credentials.divingFishRefreshToken = token
    }
    try ProfileCredentialStore.shared.saveCredentials(credentials, for: profileID)
  }
}
