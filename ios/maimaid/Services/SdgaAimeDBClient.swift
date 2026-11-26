import CommonCrypto
import Foundation
import Network
import OSLog

/// Runs `operation` and fails with `sdga_timeout` if it does not finish in time.
nonisolated func withTimeout<T: Sendable>(
  _ duration: Duration, _ operation: @escaping @Sendable () async throws -> T
) async throws -> T {
  try await withThrowingTaskGroup(of: T.self) { group in
    group.addTask { try await operation() }
    group.addTask {
      try await Task.sleep(for: duration)
      throw ScoreImportError(code: "sdga_timeout")
    }
    guard let result = try await group.next() else {
      throw ScoreImportError(code: "sdga_timeout")
    }
    group.cancelAll()
    return result
  }
}

/// The account AiMeDB resolves for one credential.
nonisolated struct SdgaAimeAccount: Sendable {
  let userID: Int
  let accessCode: String
  let authKey: String
  let portal: Int
}

/// Resolves an `IDm`/`AccessCode` into a game account over Sega's binary AiMeDB protocol.
///
/// A request is a 32-byte little-endian header plus a command body, zero padded to 16 bytes
/// and encrypted with AES-128-ECB — a different protocol from the China server's HTTP
/// AimeDB. The card can be resolved again at any time, so nothing here is one-shot.
nonisolated final class SdgaAimeDBClient: Sendable {
  private let config: SdgaConfig
  private let timeout: Duration = .seconds(8)

  init(config: SdgaConfig = .current) {
    self.config = config
  }

  func resolve(_ credential: SdgaCredential, cabinet: SdgaCabinet) async throws -> SdgaAimeAccount {
    switch credential {
    case .idm(let idm, let pmm):
      return try await resolveIDM(idm, pmm: pmm, cabinet: cabinet)
    case .accessCode(let code, let uid):
      return try await resolveAccessCode(code, uid: uid, cabinet: cabinet)
    }
  }

  // MARK: - Flows

  private func resolveIDM(_ idm: String, pmm: String?, cabinet: SdgaCabinet) async throws
    -> SdgaAimeAccount {
    let pmm = try SdgaCredential.pmm(for: idm, provided: pmm)
    guard let idmBytes = Self.hex(idm), let pmmBytes = Self.hex(pmm) else {
      throw ScoreImportError(code: "sdga_invalid_credential")
    }
    var body = idmBytes
    body.append(pmmBytes)
    for title in Self.titles {
      let response = try await exchange(command: 0x01, body: body, cabinet: cabinet, title: title)
      guard response.count >= 46 else { continue }
      let result = Self.le16(response, 8)
      let felicaIndex = Self.le32(response, 32)
      let accessCode = Self.hexString(response, 36, 10)
      if accessCode.isEmpty || accessCode.allSatisfy({ $0 == "0" })
        || felicaIndex == 0xFFFF_FFFF
      {
        continue
      }
      if (4...10).contains(result) { throw ScoreImportError(code: "sdga_locked") }
      guard result == 1 else { throw ScoreImportError(code: "sdga_result_\(result)") }
      return try await lookupAccount(
        accessCode, uid: nil, cabinet: cabinet, title: title, kind: "idm")
    }
    throw ScoreImportError(code: "sdga_no_access_code")
  }

  private func resolveAccessCode(_ code: String, uid: String?, cabinet: SdgaCabinet) async throws
    -> SdgaAimeAccount {
    var lastError: Error?
    for title in Self.titles {
      do {
        return try await lookupAccount(code, uid: uid, cabinet: cabinet, title: title, kind: "accessCode")
      } catch let error as ScoreImportError {
        let retryable = error.code == "sdga_no_account" || error.code == "sdga_no_account_no_uid"
        if !retryable { throw error }
        lastError = error
      }
    }
    throw lastError ?? ScoreImportError(code: "sdga_no_account")
  }

  private func lookupAccount(
    _ code: String, uid: String?, cabinet: SdgaCabinet, title: String, kind: String
  ) async throws -> SdgaAimeAccount {
    guard let codeBytes = Self.hex(code) else {
      throw ScoreImportError(code: "sdga_invalid_credential")
    }
    let m1 = Self.hex(uid ?? Self.defaultMifareUID) ?? Data()
    var body = codeBytes
    body.append(contentsOf: [0x01, 0x03])
    body.append(m1)
    let response = try await exchange(command: 0x0F, body: body, cabinet: cabinet, title: title)
    guard response.count >= 36 else { throw ScoreImportError(code: "sdga_bad_response") }
    let result = Self.le16(response, 8)
    if (4...10).contains(result) { throw ScoreImportError(code: "sdga_locked") }
    if result == 2 {
      // The placeholder UID is only a guess: when the user typed none, say so.
      throw ScoreImportError(
        code: (uid ?? "").isEmpty ? "sdga_no_account_no_uid" : "sdga_no_account")
    }
    guard result == 1 else { throw ScoreImportError(code: "sdga_result_\(result)") }
    let userID = Int(Self.le32(response, 32))
    guard userID > 0, userID != Int(0xFFFF_FFFF) else {
      throw ScoreImportError(code: "sdga_no_account")
    }
    let portal = response.count > 36 ? Int(response[36]) : 0
    let authKey: String
    if response.count > 40 {
      let end = min(response.count, 40 + 256)
      let raw = response.subdata(in: 40..<end)
      let terminator = raw.firstIndex(of: 0) ?? raw.count
      authKey = String(decoding: raw.prefix(terminator), as: UTF8.self)
    } else {
      authKey = ""
    }
    Logger(subsystem: "org.rhythmeta.maimaid", category: "Sdga")
      .debug("aimedb \(kind, privacy: .public) user=\(userID) portal=\(portal) authKey=\(authKey.count)B")
    return SdgaAimeAccount(userID: userID, accessCode: code, authKey: authKey, portal: portal)
  }

  // MARK: - Framing

  private func exchange(
    command: UInt16, body: Data, cabinet: SdgaCabinet, title: String
  ) async throws -> Data {
    let frame = Self.frame(command: command, body: body, cabinet: cabinet, title: title)
    var padded = frame
    if padded.count % 16 != 0 {
      padded.append(Data(repeating: 0, count: 16 - padded.count % 16))
    }
    let ciphertext = try Self.aes(padded, key: config.commonKey, encrypt: true)
    let raw = try await transmit(ciphertext)
    return try decrypt(raw)
  }

  private func transmit(_ payload: Data) async throws -> Data {
    guard let port = NWEndpoint.Port(rawValue: UInt16(config.aimePort)) else {
      throw ScoreImportError(code: "sdga_network")
    }
    let connection = NWConnection(host: NWEndpoint.Host(config.aimeHost), port: port, using: .tcp)
    defer { connection.cancel() }

    return try await withTimeout(timeout) {
      try await Self.start(connection)
      try await Self.send(payload, on: connection)
      var buffer = Data()
      var needed = 0
      while true {
        let chunk = try await Self.receive(on: connection)
        if chunk.isEmpty { break }
        buffer.append(chunk)
        if needed == 0 && buffer.count >= 32 {
          let head = try Self.aes(buffer.prefix(32), key: self.config.commonKey, encrypt: false)
          guard head.count >= 8 else { throw ScoreImportError(code: "sdga_bad_response") }
          let declared = Int(head[head.startIndex + 6]) | Int(head[head.startIndex + 7]) << 8
          guard declared >= 32 else { break }
          needed = ((declared + 15) / 16) * 16
        }
        if needed > 0 && buffer.count >= needed { break }
      }
      guard !buffer.isEmpty else { throw ScoreImportError(code: "sdga_no_response") }
      return buffer
    }
  }

  private func decrypt(_ raw: Data) throws -> Data {
    let usable = raw.count - raw.count % 16
    guard usable >= 32 else { throw ScoreImportError(code: "sdga_bad_response") }
    let plain = try Self.aes(raw.prefix(usable), key: config.commonKey, encrypt: false)
    guard plain.count >= 8 else { throw ScoreImportError(code: "sdga_bad_response") }
    let declared = Int(plain[plain.startIndex + 6]) | Int(plain[plain.startIndex + 7]) << 8
    guard declared >= 32, declared <= plain.count else {
      throw ScoreImportError(code: "sdga_bad_response")
    }
    return plain.prefix(declared)
  }

  // MARK: - Helpers

  private static let defaultMifareUID = "01020304"
  private static let titles = ["SDGA", "SDEZ"]

  private static func frame(
    command: UInt16, body: Data, cabinet: SdgaCabinet, title: String
  ) -> Data {
    var header = Data()
    var magic = UInt16(0xA13E).littleEndian
    var version = UInt16(0x4020).littleEndian
    var cmd = command.littleEndian
    var length = UInt16(32 + body.count).littleEndian
    var result = UInt16(0).littleEndian
    withUnsafeBytes(of: &magic) { header.append(contentsOf: $0) }
    withUnsafeBytes(of: &version) { header.append(contentsOf: $0) }
    withUnsafeBytes(of: &cmd) { header.append(contentsOf: $0) }
    withUnsafeBytes(of: &length) { header.append(contentsOf: $0) }
    withUnsafeBytes(of: &result) { header.append(contentsOf: $0) }
    var game = Data(title.utf8).prefix(5)
    game.append(Data(repeating: 0, count: 6 - game.count))
    header.append(game)
    var place = UInt32(cabinet.placeID).littleEndian
    withUnsafeBytes(of: &place) { header.append(contentsOf: $0) }
    let chip = cabinet.keychip.filter { $0.isLetter || $0.isNumber }
    var chipBytes = Data(chip.utf8).prefix(11)
    chipBytes.append(Data(repeating: 0, count: 12 - chipBytes.count))
    header.append(chipBytes)
    return header + body
  }

  private static func aes(_ data: Data, key: String, encrypt: Bool) throws -> Data {
    let keyData = Data(key.utf8)
    guard keyData.count == 16, !data.isEmpty, data.count % 16 == 0 else {
      throw ScoreImportError(code: "sdga_crypto")
    }
    var output = [UInt8](repeating: 0, count: data.count + kCCBlockSizeAES128)
    var moved = 0
    let status = data.withUnsafeBytes { input in
      keyData.withUnsafeBytes { keyBytes in
        CCCrypt(
          CCOperation(encrypt ? kCCEncrypt : kCCDecrypt),
          CCAlgorithm(kCCAlgorithmAES),
          CCOptions(kCCOptionECBMode),
          keyBytes.baseAddress, keyData.count, nil,
          input.baseAddress, data.count,
          &output, output.count, &moved)
      }
    }
    guard status == kCCSuccess else { throw ScoreImportError(code: "sdga_crypto") }
    return Data(output.prefix(moved))
  }

  private static func hex(_ value: String) -> Data? {
    guard value.count % 2 == 0, !value.isEmpty else { return nil }
    var data = Data()
    var index = value.startIndex
    while index < value.endIndex {
      let next = value.index(index, offsetBy: 2)
      guard let byte = UInt8(value[index..<next], radix: 16) else { return nil }
      data.append(byte)
      index = next
    }
    return data
  }

  private static func hexString(_ data: Data, _ start: Int, _ length: Int) -> String {
    guard data.count >= start + length else { return "" }
    let slice = data.dropFirst(start).prefix(length)
    return slice.map { String(format: "%02X", $0) }.joined()
  }

  private static func le16(_ data: Data, _ offset: Int) -> Int {
    let base = data.startIndex + offset
    guard base + 1 < data.endIndex else { return 0 }
    return Int(data[base]) | Int(data[base + 1]) << 8
  }

  private static func le32(_ data: Data, _ offset: Int) -> UInt32 {
    let base = data.startIndex + offset
    guard base + 3 < data.endIndex else { return 0 }
    return UInt32(data[base])
      | UInt32(data[base + 1]) << 8 | UInt32(data[base + 2]) << 16 | UInt32(data[base + 3]) << 24
  }

  // MARK: - Network primitives

  private static func start(_ connection: NWConnection) async throws {
    try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
      let finished = Locked(false)
      connection.stateUpdateHandler = { state in
        switch state {
        case .ready:
          finished.once { continuation.resume() }
        case .failed(let error):
          finished.once { continuation.resume(throwing: error) }
        case .cancelled:
          finished.once { continuation.resume(throwing: CancellationError()) }
        default:
          break
        }
      }
      connection.start(queue: .global(qos: .utility))
    }
  }

  private static func send(_ data: Data, on connection: NWConnection) async throws {
    try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
      connection.send(
        content: data,
        completion: .contentProcessed { error in
          if let error { continuation.resume(throwing: error) } else { continuation.resume() }
        })
    }
  }

  private static func receive(on connection: NWConnection) async throws -> Data {
    try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Data, Error>) in
      connection.receive(minimumIncompleteLength: 1, maximumLength: 4096) { data, _, isComplete, error in
        if let error { continuation.resume(throwing: error) }
        else if let data, !data.isEmpty { continuation.resume(returning: data) }
        else { continuation.resume(returning: Data()) }
        _ = isComplete
      }
    }
  }
}

/// Minimal one-shot flag for continuation safety.
nonisolated private final class Locked: @unchecked Sendable {
  private var used = false
  private let lock = NSLock()
  init(_ value: Bool) { used = value }
  func once(_ body: () -> Void) {
    lock.lock()
    defer { lock.unlock() }
    guard !used else { return }
    used = true
    body()
  }
}
