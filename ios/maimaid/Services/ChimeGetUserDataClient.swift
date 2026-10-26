import Foundation

/// The scores fetched from the China server, plus the identity they belong to.
///
/// `playerName` is what the settings page shows for a remembered player;
/// `userID` never leaves the import pipeline.
nonisolated struct SgwcmaidFetchedMusic: Sendable {
  let data: Data
  let userID: Int
  let playerName: String
}

/// The user session returned by chime for one QR code, bound to a cabinet.
nonisolated struct SgwcmaidChimeSession: Sendable, Equatable {
  let userID: Int
  let token: String
}

/// Exchanges an `SGWCMAID` cabinet code for a China-server session and returns
/// the player's `music.json` records.
///
/// This is a direct port of the Android `ChimeGetUserDataClient`: the chime
/// service resolves the code into a user ID and session token, then the game
/// API is walked (preview, login, paginated music, logout) with AES-protected
/// zlib bodies. The returned data is the concatenated `userMusicList` array.
nonisolated final class ChimeGetUserDataClient: Sendable {
  private let config: SgwcmaidConfig
  private let transport: SgwcmaidHTTPTransport

  init(config: SgwcmaidConfig = .current, transport: SgwcmaidHTTPTransport = .shared) {
    self.config = config
    self.transport = transport
  }

  /// Exchanges the QR payload for a user session on one cabinet. This is the
  /// only stage that may be retried with a different keychip: once a session
  /// exists the QR code has been consumed, so the import must continue on the
  /// same cabinet.
  @concurrent func resolveSession(code: String, cabinet: SgwcmaidCabinet) async throws
    -> SgwcmaidChimeSession {
    do {
      return try await openSession(code: code, cabinet: cabinet)
    } catch let error as SgwcmaidHTTPError {
      throw Self.importError(for: error)
    }
  }

  private func openSession(code: String, cabinet: SgwcmaidCabinet) async throws
    -> SgwcmaidChimeSession {
    guard config.isComplete else {
      throw ScoreImportError(code: "sgwcmaid_not_configured")
    }
    let keychip = cabinet.keychip.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !keychip.isEmpty else {
      throw ScoreImportError(code: "chime_keychip_missing")
    }
    guard let payload = SgwcmaidCode.payload(of: code) else {
      throw ScoreImportError(code: "invalid_sgwcmaid")
    }
    guard let chimeURL = URL(string: config.chimeURL) else {
      throw ScoreImportError(code: "sgwcmaid_not_configured")
    }
    return try await resolveCredentials(
      payload: payload, keychip: keychip, chimeURL: chimeURL)
  }

  /// Walks the game API with an already-established session. Errors here are
  /// not retried elsewhere because the QR code is no longer reusable.
  @concurrent func fetchMusic(session: SgwcmaidChimeSession, cabinet: SgwcmaidCabinet) async throws
    -> SgwcmaidFetchedMusic {
    do {
      return try await fetch(session: session, cabinet: cabinet)
    } catch let error as SgwcmaidHTTPError {
      throw Self.importError(for: error)
    }
  }

  /// Reads the player's best scores using only the remembered user id.
  ///
  /// `GetUserRivalMusicApi` answers for a `rivalId` without opening a game
  /// session, so this path never logs in: no QR code, no token, no session lock
  /// and no logout to get wrong. Only the achievement and the best DX score come
  /// back; the cabinet-only fields stay unknown.
  @concurrent func fetchOwnMusic(userID: Int) async throws -> SgwcmaidFetchedMusic {
    guard config.isComplete else { throw ScoreImportError(code: "sgwcmaid_not_configured") }
    guard userID > 0 else { throw ScoreImportError(code: "sgwcmaid_binding_invalid") }
    // No cabinet is involved, so the client id is only needed to build the
    // settings request; the player id drives every other header.
    let clientID = String(config.keychip.replacingOccurrences(of: "-", with: "").prefix(11))
    var client = GameClient(
      config: config, clientID: clientID, placeID: config.placeID, regionID: config.regionID,
      transport: transport)
    do {
      return try await client.fetchOwnMusic(userID: userID)
    } catch let error as SgwcmaidHTTPError {
      throw Self.importError(for: error)
    }
  }

  private func fetch(session: SgwcmaidChimeSession, cabinet: SgwcmaidCabinet) async throws
    -> SgwcmaidFetchedMusic {
    let keychip = cabinet.keychip.trimmingCharacters(in: .whitespacesAndNewlines)
    let clientID = String(keychip.replacing("-", with: "").prefix(11))
    var client = GameClient(
      config: config, clientID: clientID, placeID: cabinet.placeID,
      regionID: cabinet.regionID, transport: transport)
    return try await client.fetch(userID: session.userID, token: session.token)
  }

  /// Exchanges the QR payload for a game session. Chime answers failures with
  /// {"errorID":1,"userID":-1,"token":""}; surface the real reason instead of
  /// reporting empty credentials.
  private func resolveCredentials(payload: String, keychip: String, chimeURL: URL) async throws
    -> SgwcmaidChimeSession {
    let timestamp = Self.utcStamp()
    let key = SgwcmaidCrypto.sha256HexUpper(keychip + timestamp + config.chimeSecret)
    let body: [String: Any] = [
      "chipID": keychip,
      "openGameID": "MAID",
      "key": key,
      "qrCode": payload,
      "timestamp": timestamp,
      "titlekey": config.titleKey
    ]
    let response = try await transport.post(
      url: chimeURL,
      headers: ["user-agent": "WC_AIME_LIB", "content-type": "application/json"],
      body: try JSONSerialization.data(withJSONObject: body))

    guard (200..<300).contains(response.statusCode) else {
      throw ScoreImportError(code: "chime_http_\(response.statusCode)")
    }
    guard !response.body.isEmpty else {
      throw ScoreImportError(code: "chime_empty_body")
    }
    guard let root = try? JSONSerialization.jsonObject(with: response.body) as? [String: Any] else {
      throw ScoreImportError(code: "chime_bad_json_\(response.body.count)")
    }
    let errorID = SgwcmaidJSON.contentInt(root["errorID"]) ?? -1
    guard errorID == 0 else {
      throw Self.chimeError(for: errorID)
    }
    guard let userID = SgwcmaidJSON.contentInt(root["userID"]), userID > 0 else {
      throw ScoreImportError(code: "chime_empty_credentials")
    }
    guard let token = (root["token"] as? String)?
      .trimmingCharacters(in: .whitespacesAndNewlines), !token.isEmpty
    else {
      throw ScoreImportError(code: "chime_empty_credentials")
    }
    return SgwcmaidChimeSession(userID: userID, token: token)
  }

  private static func chimeError(for errorID: Int) -> ScoreImportError {
    let code: String =
      switch errorID {
      case 1: "chime_code_invalid_or_expired"
      case 3: "chime_code_already_used"
      case 4: "chime_db_resolve_fail"
      case 5: "chime_db_access_timeout"
      case 6: "chime_db_access_fail"
      default: "chime_error_\(errorID)"
      }
    return ScoreImportError(code: code)
  }

  private static func importError(for error: SgwcmaidHTTPError) -> ScoreImportError {
    switch error {
    case .timeout:
      return ScoreImportError(code: "chime_timeout")
    case .invalidURL, .insecureScheme, .connection:
      return ScoreImportError(code: "chime_network")
    case .malformedResponse, .truncatedResponse, .responseTooLarge:
      return ScoreImportError(code: "chime_bad_response")
    }
  }

  private static func utcStamp() -> String {
    var calendar = Calendar(identifier: .gregorian)
    calendar.timeZone = TimeZone(secondsFromGMT: 0) ?? .gmt
    let components = calendar.dateComponents(
      [.year, .month, .day, .hour, .minute, .second], from: Date())
    func twoDigits(_ value: Int) -> String {
      let normalized = abs(value) % 100
      return normalized < 10 ? "0\(normalized)" : "\(normalized)"
    }
    return twoDigits(components.year ?? 0) + twoDigits(components.month ?? 0)
      + twoDigits(components.day ?? 0) + twoDigits(components.hour ?? 0)
      + twoDigits(components.minute ?? 0) + twoDigits(components.second ?? 0)
  }

}

/// One session against the game API. Cookies are kept in insertion order, like
/// the reference client's dictionary keyed by cookie name.
fileprivate nonisolated struct GameClient {  // swiftlint:disable:this private_over_fileprivate
  let config: SgwcmaidConfig
  let clientID: String
  let placeID: Int
  let regionID: Int
  let transport: SgwcmaidHTTPTransport

  private var urn = ""
  private var cookies: [(name: String, value: String)] = []
  private var pingMsec = 0

  fileprivate init(
    config: SgwcmaidConfig, clientID: String, placeID: Int, regionID: Int,
    transport: SgwcmaidHTTPTransport
  ) {
    self.config = config
    self.clientID = clientID
    self.placeID = placeID
    self.regionID = regionID
    self.transport = transport
  }

  mutating func fetch(userID: Int, token: String) async throws -> SgwcmaidFetchedMusic {
    try await prepare()
    let preview = try await call(
      "GetUserPreviewApi",
      body: ["userId": userID, "segaIdAuthKey": "", "token": token, "clientId": clientID],
      userID: userID)
    guard (SgwcmaidJSON.contentInt(preview["errorId"]) ?? 0) == 0 else {
      throw ScoreImportError(code: "game_preview_error")
    }
    // isLogin=true means the account is still logged in elsewhere; the server
    // then rejects UserLoginApi and applies a ~15 minute penalty, so stop here.
    guard SgwcmaidJSON.bool(preview["isLogin"]) != true else {
      throw ScoreImportError(code: "game_account_already_logged_in")
    }

    // dateTime is plain Unix time, but loginDateTime carries the local UTC
    // offset (a MAI2.DateTime/DateTimeOffset quirk). The server reuses this
    // exact value for UserLogoutApi; a mismatch leaves the session open.
    let now = Int(Date().timeIntervalSince1970)
    let loginStamp = now + TimeZone.current.secondsFromGMT()
    let login: [String: Any] = [
      "userId": userID,
      "accessCode": "",
      "regionId": regionID,
      "placeId": placeID,
      "clientId": clientID,
      "dateTime": now,
      "loginDateTime": loginStamp,
      "isContinue": false,
      "genericFlag": 0,
      "token": token
    ]
    let returnCode = loginReturnCode(of: try await call("UserLoginApi", body: login, userID: userID))
    guard returnCode == 1 else {
      throw ScoreImportError(code: "game_login_\(returnCode)")
    }

    let playerName = preview["userName"] as? String ?? ""
    do {
      let records = try await fetchPages(userID: userID)
      await logout(userID: userID, loginStamp: loginStamp)
      return SgwcmaidFetchedMusic(
        data: try JSONSerialization.data(withJSONObject: records), userID: userID,
        playerName: playerName)
    } catch {
      await logout(userID: userID, loginStamp: loginStamp)
      throw error
    }
  }

  /// Reads the best scores of `userID` without opening a game session.
  ///
  /// Pointing `GetUserRivalMusicApi` at the player itself returns that player's
  /// own chart table, which is the only way to read scores with just a user id.
  mutating func fetchOwnMusic(userID: Int) async throws -> SgwcmaidFetchedMusic {
    try await prepare()
    let identity = try await call(
      "GetUserRivalDataApi", body: ["userId": userID, "rivalId": userID], userID: userID)
    guard let data = identity["userRivalData"] as? [String: Any] else {
      throw ScoreImportError(code: "sgwcmaid_binding_invalid")
    }
    let playerName = data["rivalName"] as? String ?? ""
    let records = try await fetchRivalPages(userID: userID)
    return SgwcmaidFetchedMusic(
      data: try JSONSerialization.data(withJSONObject: records), userID: userID,
      playerName: playerName)
  }

  private mutating func prepare() async throws {
    let setting = try await call(
      "GetGameSettingApi",
      body: ["placeId": placeID, "clientId": clientID])
    if let gameSetting = setting["gameSetting"] as? [String: Any],
      let movieServerURI = gameSetting["movieServerUri"] as? String {
      urn = movieServerURI
    }
    if !urn.isEmpty && !urn.hasSuffix("/") { urn += "/" }

    // The real client sends the Ping round-trip as the `number` header on every
    // subsequent request. Treat a failed heartbeat as number=0 (the pre-heartbeat
    // state) so the preview request still gets a chance to report the real error.
    let pingStarted = Date()
    if (try? await call("Ping", body: [:], userID: 0)) != nil {
      pingMsec = max(0, Int(Date().timeIntervalSince(pingStarted) * 1000))
    }
  }

  private mutating func fetchPages(userID: Int) async throws -> [[String: Any]] {
    var records: [[String: Any]] = []
    var index = 0
    for _ in 0..<10_000 {
      let result = try await call(
        "GetUserMusicApi", body: ["userId": userID, "nextIndex": index], userID: userID)
      if let list = result["userMusicList"] as? [[String: Any]] {
        records.append(contentsOf: list)
      }
      let next = SgwcmaidJSON.contentInt(result["nextIndex"]) ?? 0
      if next == 0 { return records }
      if next == index { throw ScoreImportError(code: "game_pagination_stalled") }
      index = next
    }
    throw ScoreImportError(code: "game_pagination_limit")
  }

  private mutating func fetchRivalPages(userID: Int) async throws -> [[String: Any]] {
    var songs: [[String: Any]] = []
    var index = 0
    for _ in 0..<100 {
      let result = try await call(
        "GetUserRivalMusicApi",
        body: [
          "userId": userID, "rivalId": userID, "nextIndex": index,
          "userRivalMusicLevelList": [0, 1, 2, 3, 4, 10].map { ["level": $0] },
        ], userID: userID)
      if let list = result["userRivalMusicList"] as? [[String: Any]] {
        songs.append(contentsOf: list)
      }
      let next = SgwcmaidJSON.contentInt(result["nextIndex"]) ?? 0
      if next == 0 { break }
      if next == index { throw ScoreImportError(code: "game_pagination_stalled") }
      index = next
    }
    return SgwcmaidRivalMusic.musicJSON(from: songs)
  }

  private mutating func logout(userID: Int, loginStamp: Int) async {
    _ = try? await call(
      "UserLogoutApi",
      body: [
        "userId": userID,
        "accessCode": "",
        "regionId": regionID,
        "placeId": placeID,
        "clientId": clientID,
        "loginDateTime": loginStamp,
        // LogoutType.TestIn (4); type 1 requires a 26-slot delayLog, which
        // would not close the session if omitted.
        "type": 4
      ],
      userID: userID)
  }

  private mutating func call(_ api: String, body: [String: Any], userID: Int = 0) async throws
    -> [String: Any] {
    let digest = SgwcmaidCrypto.md5Hex("\(api)MaimaiChn\(config.apiSalt)")
    guard
      let url = URL(
        string: "\(config.gameServer)/Maimai2Servlet/\(urn)\(digest)")
    else {
      throw ScoreImportError(code: "game_url_invalid")
    }

    let plaintext = try JSONSerialization.data(withJSONObject: body)
    let ciphertext = try SgwcmaidCrypto.protect(
      plaintext, key: config.aesKey, iv: config.aesIV)
    var headers = [
      "user-agent": "\(digest)#\(userID == 0 ? clientID : String(userID))",
      "Accept": "*/*",
      "charset": "UTF-8",
      "Mai-Encoding": "1.55",
      "Content-Encoding": "deflate",
      "content-type": "application/json"
    ]
    if api != "Ping" {
      headers["number"] = String(pingMsec)
    }
    if !cookies.isEmpty {
      headers["Cookie"] = cookies.map { "\($0.name)=\($0.value)" }.joined(separator: "; ")
    }

    let response = try await transport.post(url: url, headers: headers, body: ciphertext)
    absorbCookies(response.values(forHeader: "set-cookie"))
    guard (200..<300).contains(response.statusCode) else {
      throw ScoreImportError(code: "game_http_\(response.statusCode)")
    }
    guard !response.body.isEmpty else {
      throw ScoreImportError(code: "game_empty_response")
    }
    let text = try SgwcmaidCrypto.unprotect(response.body, key: config.aesKey, iv: config.aesIV)
    guard let string = String(data: text, encoding: .utf8),
      !string.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    else {
      throw ScoreImportError(code: "game_empty_text_\(api)")
    }
    guard let root = try? JSONSerialization.jsonObject(with: text) as? [String: Any] else {
      throw ScoreImportError(code: "game_bad_json_\(api)_\(text.count)")
    }
    return root
  }

  private mutating func absorbCookies(_ values: [String]) {
    for raw in values {
      let pair = (raw.split(separator: ";", maxSplits: 1).first.map(String.init) ?? "")
        .trimmingCharacters(in: .whitespaces)
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

  private func loginReturnCode(of response: [String: Any]) -> Int {
    SgwcmaidJSON.contentInt(response["returnCode"]) ?? 0
  }
}
