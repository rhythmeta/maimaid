import Foundation

/// The scores read from the international title server.
nonisolated struct SdgaFetchedMusic: Sendable {
  let music: Data
  let userID: Int
  let playerName: String
}

/// The international (`SDGA`) title-server client.
///
/// Same `Maimai2Servlet` protocol as the China client — zlib + AES-CBC bodies, the same
/// preview/login/music/logout calls — but every deployment detail differs: the `MaimaiExp`
/// hash salt, a fixed `number` header, `Mai-Encoding` 1.65, no `urn` path segment, and a
/// login body carrying a real `accessCode` instead of a QR token.
nonisolated final class SdgaGameClient: Sendable {
  private let config: SdgaConfig
  private let transport: SgwcmaidHTTPTransport

  init(config: SdgaConfig = .current, transport: SgwcmaidHTTPTransport = .shared) {
    self.config = config
    self.transport = transport
  }

  /// Logs in with the resolved card account, reads every music page, then logs out.
  @concurrent func fetchMusic(account: SdgaAimeAccount, cabinet: SdgaCabinet) async throws
    -> SdgaFetchedMusic {
    guard config.isComplete else { throw ScoreImportError(code: "sdga_not_configured") }
    // The cookie jar lives for exactly one import, which keeps the client stateless.
    var cookies: [(name: String, value: String)] = []
    let preview = try await call(
      "GetUserPreviewApi",
      body: [
        "userId": account.userID, "segaIdAuthKey": account.authKey, "clientId": "",
      ], userID: account.userID, clientID: cabinet.keychip, cookies: &cookies)
    if let errorId = preview["errorId"] as? Int, errorId != 0 {
      throw ScoreImportError(code: "game_preview_error")
    }
    if (preview["isLogin"] as? Bool) == true {
      throw ScoreImportError(code: "game_account_already_logged_in")
    }
    let playerName = preview["userName"] as? String ?? ""

    let now = Int(Date().timeIntervalSince1970)
    let bootStamp = now - Int.random(in: 1_000...2_000)
    let login: [String: Any] = [
      "userId": account.userID,
      "accessCode": account.accessCode,
      "regionId": cabinet.regionID,
      "placeId": cabinet.placeID,
      "clientId": cabinet.keychip,
      "dateTime": bootStamp,
      "loginDateTime": now,
      "isContinue": false,
      "genericFlag": 0,
    ]
    let returnCode = (try await call(
      "UserLoginApi", body: login, userID: account.userID, clientID: cabinet.keychip,
      cookies: &cookies
    )["returnCode"] as? Int) ?? 0
    guard returnCode == 1 else { throw ScoreImportError(code: "game_login_\(returnCode)") }

    do {
      let records = try await fetchPages(userID: account.userID, clientID: cabinet.keychip, cookies: &cookies)
      await logout(account: account, cabinet: cabinet, loginStamp: now, cookies: &cookies)
      return SdgaFetchedMusic(
        music: try JSONSerialization.data(withJSONObject: records),
        userID: account.userID, playerName: playerName)
    } catch {
      await logout(account: account, cabinet: cabinet, loginStamp: now, cookies: &cookies)
      throw error
    }
  }

  private func fetchPages(
    userID: Int, clientID: String, cookies: inout [(name: String, value: String)]
  ) async throws -> [[String: Any]] {
    var records: [[String: Any]] = []
    var index = 0
    for _ in 0..<10_000 {
      let result = try await call(
        "GetUserMusicApi",
        body: ["userId": userID, "nextIndex": index, "maxCount": 999],
        userID: userID, clientID: clientID, cookies: &cookies)
      if let list = result["userMusicList"] as? [[String: Any]] {
        records.append(contentsOf: list)
      }
      let next = (result["nextIndex"] as? Int) ?? 0
      if next == 0 { return records }
      if next == index { throw ScoreImportError(code: "game_pagination_stalled") }
      index = next
    }
    throw ScoreImportError(code: "game_pagination_limit")
  }

  private func logout(
    account: SdgaAimeAccount, cabinet: SdgaCabinet, loginStamp: Int,
    cookies: inout [(name: String, value: String)]
  ) async {
    _ = try? await call(
      "UserLogoutApi",
      body: [
        "userId": account.userID,
        "accessCode": account.accessCode,
        "regionId": cabinet.regionID,
        "placeId": cabinet.placeID,
        "clientId": cabinet.keychip,
        "loginDateTime": loginStamp,
        // LogoutType.TestIn (4) uses the simple request VO.
        "type": 4,
      ], userID: account.userID, clientID: cabinet.keychip, cookies: &cookies)
  }

  private func call(
    _ api: String, body: [String: Any], userID: Int, clientID: String,
    cookies: inout [(name: String, value: String)]
  ) async throws -> [String: Any] {
    let digest = SgwcmaidCrypto.md5Hex("\(api)MaimaiExp\(config.obfuscateParam)")
    guard let url = URL(string: config.servletBase + digest) else {
      throw ScoreImportError(code: "game_url_invalid")
    }
    let plaintext = try JSONSerialization.data(withJSONObject: body)
    let ciphertext = try SgwcmaidCrypto.protect(plaintext, key: config.aesKey, iv: config.aesIV)
    var headers = [
      "user-agent": "\(digest)#\(userID)",
      "Accept": "*/*",
      "charset": "UTF-8",
      "Mai-Encoding": config.gameVersion,
      "Content-Encoding": "deflate",
      "number": "100",
    ]
    if !cookies.isEmpty {
      headers["Cookie"] = cookies.map { "\($0.name)=\($0.value)" }.joined(separator: "; ")
    }
    let response: SgwcmaidHTTPResponse
    do {
      response = try await transport.post(url: url, headers: headers, body: ciphertext)
    } catch {
      throw ScoreImportError(code: "game_network")
    }
    absorbCookies(response.values(forHeader: "set-cookie"), into: &cookies)
    guard (200..<300).contains(response.statusCode) else {
      throw ScoreImportError(code: "game_http_\(response.statusCode)")
    }
    guard !response.body.isEmpty else { throw ScoreImportError(code: "game_empty_response") }
    let text = try SgwcmaidCrypto.unprotect(
      response.body, key: config.aesKey, iv: config.aesIV)
    guard let root = try? JSONSerialization.jsonObject(with: text) as? [String: Any] else {
      throw ScoreImportError(code: "game_bad_json_\(api)_\(text.count)")
    }
    return root
  }

  private func absorbCookies(_ values: [String], into cookies: inout [(name: String, value: String)]) {
    for raw in values {
      let pair = raw.split(separator: ";", maxSplits: 1).first.map(String.init) ?? ""
      guard let separator = pair.firstIndex(of: "="), separator != pair.startIndex else {
        continue
      }
      let name = String(pair[..<separator]).trimmingCharacters(in: .whitespaces)
      let value = String(pair[pair.index(after: separator)...])
        .trimmingCharacters(in: .whitespaces)
      if let index = cookies.firstIndex(where: { $0.name == name }) {
        cookies[index].value = value
      } else {
        cookies.append((name, value))
      }
    }
  }
}
