import Foundation
import Network
import Security

nonisolated struct SgwcmaidHTTPResponse: Sendable {
  let statusCode: Int
  let headers: [String: [String]]
  let body: Data

  func values(forHeader name: String) -> [String] {
    headers[name.lowercased()] ?? []
  }

  func value(forHeader name: String) -> String? {
    values(forHeader: name).first
  }
}

nonisolated enum SgwcmaidHTTPError: Error, Equatable {
  case invalidURL
  case insecureScheme
  case connection(String)
  case timeout
  case malformedResponse
  case truncatedResponse
  case responseTooLarge
}

/// A deliberately small HTTP/1.1 client for the SGWCMAID upstreams.
///
/// The Wahlap game API answers with an empty body whenever the request carries
/// an `Accept-Encoding` header, and `URLSession` always injects one (even when
/// the request sets it to an empty value). Raw TLS connections give us exact
/// control over the request headers, cookies, and timeouts while still
/// evaluating the server trust with the bundled intermediate certificates.
nonisolated final class SgwcmaidHTTPTransport: Sendable {
  static let shared = SgwcmaidHTTPTransport()

  private let connectTimeout: TimeInterval = 15
  private let readTimeout: TimeInterval = 60
  private let responseTimeout: TimeInterval = 120
  private let maximumResponseBytes = 64 * 1024 * 1024
  private let queue = DispatchQueue(
    label: "org.rhythmeta.maimaid.sgwcmaid.http", qos: .utility)

  /// Performs a POST and returns the status, headers, and body without applying
  /// any application-level success rules.
  @concurrent func post(url: URL, headers: [String: String], body: Data) async throws
    -> SgwcmaidHTTPResponse {
    let target = try SgwcmaidHTTPTarget(url: url)
    let connection = try makeConnection(for: target)
    defer { connection.cancel() }

    try await waitUntilReady(connection)
    try await send(target.requestData(headers: headers, body: body), on: connection)
    return try await receiveResponse(on: connection)
  }

  private func makeConnection(for target: SgwcmaidHTTPTarget) throws -> NWConnection {
    guard let port = NWEndpoint.Port(rawValue: target.port) else {
      throw SgwcmaidHTTPError.invalidURL
    }
    let tcp = NWProtocolTCP.Options()
    tcp.connectionTimeout = Int(connectTimeout)
    tcp.noDelay = true

    let parameters: NWParameters
    if target.usesTLS {
      let tls = NWProtocolTLS.Options()
      sec_protocol_options_set_min_tls_protocol_version(tls.securityProtocolOptions, .TLSv12)
      sec_protocol_options_set_tls_server_name(tls.securityProtocolOptions, target.host)
      let pems = SgwcmaidTrustAnchors.pemCertificates
      sec_protocol_options_set_verify_block(
        tls.securityProtocolOptions,
        { _, secTrust, complete in
          let trust = sec_trust_copy_ref(secTrust).takeRetainedValue()
          let anchors = SgwcmaidTrustAnchors.certificates(from: pems)
          if !anchors.isEmpty {
            SecTrustSetAnchorCertificates(trust, anchors as CFArray)
            SecTrustSetAnchorCertificatesOnly(trust, false)
          }
          var error: CFError?
          complete(SecTrustEvaluateWithError(trust, &error))
        },
        queue)
      parameters = NWParameters(tls: tls, tcp: tcp)
    } else {
      parameters = NWParameters(tls: nil, tcp: tcp)
    }

    return NWConnection(
      host: NWEndpoint.Host(target.host), port: port, using: parameters)
  }

  private func waitUntilReady(_ connection: NWConnection) async throws {
    try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
      let gate = SgwcmaidContinuationGate(continuation)
      connection.stateUpdateHandler = { state in
        switch state {
        case .ready:
          gate.resume(returning: ())
        case .failed(let error):
          gate.resume(throwing: Self.connectionError(error))
        case .cancelled:
          gate.resume(throwing: SgwcmaidHTTPError.connection("Connection cancelled"))
        default:
          break
        }
      }
      connection.start(queue: queue)
      let timeout = DispatchWorkItem {
        gate.resume(throwing: SgwcmaidHTTPError.timeout)
        connection.cancel()
      }
      gate.arm(timeout, after: connectTimeout, on: queue)
    }
  }

  private func send(_ data: Data, on connection: NWConnection) async throws {
    try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
      let gate = SgwcmaidContinuationGate(continuation)
      connection.send(
        content: data,
        completion: .contentProcessed { error in
          if let error {
            gate.resume(throwing: SgwcmaidHTTPError.connection(error.localizedDescription))
          } else {
            gate.resume(returning: ())
          }
        })
      let timeout = DispatchWorkItem {
        gate.resume(throwing: SgwcmaidHTTPError.timeout)
        connection.cancel()
      }
      gate.arm(timeout, after: connectTimeout, on: queue)
    }
  }

  private func receiveResponse(on connection: NWConnection) async throws
    -> SgwcmaidHTTPResponse {
    var buffer = Data()
    var head: SgwcmaidHTTPResponseParser.Head?
    var reachedEnd = false
    let deadline = Date().addingTimeInterval(responseTimeout)

    while true {
      if head == nil, let parsed = try SgwcmaidHTTPResponseParser.parseHead(buffer) {
        head = parsed
      }
      if let head,
        let completed = try completedResponse(head: head, buffer: buffer, reachedEnd: reachedEnd) {
        return completed
      }

      if reachedEnd { throw SgwcmaidHTTPError.truncatedResponse }

      let remaining = deadline.timeIntervalSinceNow
      guard remaining > 0 else { throw SgwcmaidHTTPError.timeout }
      let chunk = try await receiveChunk(on: connection, timeout: min(remaining, readTimeout))
      if let data = chunk.data {
        buffer.append(data)
        guard buffer.count <= maximumResponseBytes else {
          throw SgwcmaidHTTPError.responseTooLarge
        }
      }
      if chunk.isComplete { reachedEnd = true }
    }
  }

  private func completedResponse(
    head: SgwcmaidHTTPResponseParser.Head, buffer: Data, reachedEnd: Bool
  ) throws -> SgwcmaidHTTPResponse? {
    switch SgwcmaidHTTPResponseParser.framing(for: head) {
    case .none:
      return SgwcmaidHTTPResponse(
        statusCode: head.statusCode, headers: head.headerMap, body: Data())
    case .contentLength(let length):
      guard buffer.count - head.bodyOffset >= length else { return nil }
      return response(
        head, body: buffer.subdata(in: head.bodyOffset..<(head.bodyOffset + length)))
    case .chunked:
      guard let body = try SgwcmaidHTTPResponseParser.chunkedBody(buffer, from: head.bodyOffset)
      else { return nil }
      return response(head, body: body)
    case .untilClose:
      guard reachedEnd else { return nil }
      return response(head, body: buffer.subdata(in: head.bodyOffset..<buffer.count))
    }
  }

  private func receiveChunk(on connection: NWConnection, timeout: TimeInterval) async throws
    -> SgwcmaidHTTPChunk {
    try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<SgwcmaidHTTPChunk, Error>) in
      let gate = SgwcmaidContinuationGate(continuation)
      connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { data, _, isComplete, error in
        if let error {
          gate.resume(throwing: SgwcmaidHTTPError.connection(error.localizedDescription))
        } else {
          gate.resume(returning: SgwcmaidHTTPChunk(data: data, isComplete: isComplete))
        }
      }
      let item = DispatchWorkItem {
        gate.resume(throwing: SgwcmaidHTTPError.timeout)
        connection.cancel()
      }
      gate.arm(item, after: timeout, on: queue)
    }
  }

  private func response(_ head: SgwcmaidHTTPResponseParser.Head, body: Data)
    -> SgwcmaidHTTPResponse {
    SgwcmaidHTTPResponse(
      statusCode: head.statusCode, headers: head.headerMap, body: body)
  }

  private static func connectionError(_ error: NWError) -> SgwcmaidHTTPError {
    switch error {
    case .tls(let status):
      return .connection("TLS error \(status)")
    case .posix(let code):
      return .connection("POSIX error \(code.rawValue)")
    case .dns(let code):
      return .connection("DNS error \(code)")
    case .wifiAware(let error):
      return .connection("Wi-Fi Aware error \(error)")
    @unknown default:
      return .connection(String(describing: error))
    }
  }
}

fileprivate nonisolated struct SgwcmaidHTTPChunk: Sendable {  // swiftlint:disable:this private_over_fileprivate
  let data: Data?
  let isComplete: Bool
}

fileprivate nonisolated struct SgwcmaidHTTPTarget {  // swiftlint:disable:this private_over_fileprivate
  let host: String
  let port: UInt16
  let requestPath: String
  let hostHeader: String
  let usesTLS: Bool

  init(url: URL) throws {
    guard let scheme = url.scheme?.lowercased(), let host = url.host(), !host.isEmpty else {
      throw SgwcmaidHTTPError.invalidURL
    }
    let isSecure = scheme == "https"
    let isLoopback = host == "localhost" || host == "127.0.0.1" || host == "::1"
    guard isSecure || (scheme == "http" && isLoopback) else {
      throw SgwcmaidHTTPError.insecureScheme
    }

    let defaultPort: UInt16 = isSecure ? 443 : 80
    guard let port = UInt16(exactly: url.port ?? Int(defaultPort)) else {
      throw SgwcmaidHTTPError.invalidURL
    }
    var path = url.path(percentEncoded: true)
    if path.isEmpty { path = "/" }
    if let query = url.query(percentEncoded: true), !query.isEmpty {
      path += "?" + query
    }

    let hostLiteral = host.contains(":") ? "[\(host)]" : host
    self.host = host
    self.port = port
    self.requestPath = path
    self.hostHeader = port == defaultPort ? hostLiteral : "\(hostLiteral):\(port)"
    self.usesTLS = isSecure
  }

  func requestData(headers: [String: String], body: Data) -> Data {
    var head = "POST \(requestPath) HTTP/1.1\r\nHost: \(hostHeader)\r\n"
    for name in headers.keys.sorted() {
      head += "\(name): \(headers[name] ?? "")\r\n"
    }
    head += "Content-Length: \(body.count)\r\nConnection: close\r\n\r\n"
    var data = Data(head.utf8)
    data.append(body)
    return data
  }
}

/// Resumes a checked continuation exactly once and cancels its timeout timer
/// when the operation finishes first.
fileprivate nonisolated final class SgwcmaidContinuationGate<Value: Sendable>: @unchecked Sendable {
  // swiftlint:disable:previous private_over_fileprivate
  private let lock = NSLock()
  private var continuation: CheckedContinuation<Value, Error>?
  private var timeout: DispatchWorkItem?

  init(_ continuation: CheckedContinuation<Value, Error>) {
    self.continuation = continuation
  }

  func arm(_ item: DispatchWorkItem, after seconds: TimeInterval, on queue: DispatchQueue) {
    lock.lock()
    guard continuation != nil else {
      lock.unlock()
      item.cancel()
      return
    }
    timeout = item
    lock.unlock()
    queue.asyncAfter(deadline: .now() + seconds, execute: item)
  }

  func resume(returning value: Value) {
    resume(with: .success(value))
  }

  func resume(throwing error: Error) {
    resume(with: .failure(error))
  }

  private func resume(with result: Result<Value, Error>) {
    lock.lock()
    guard let continuation else {
      lock.unlock()
      return
    }
    self.continuation = nil
    let timeout = self.timeout
    self.timeout = nil
    lock.unlock()
    timeout?.cancel()
    continuation.resume(with: result)
  }
}
