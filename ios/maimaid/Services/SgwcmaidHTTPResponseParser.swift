import Foundation

/// A minimal HTTP/1.1 response head parser.
///
/// Wrapped in its own type so the framing rules (content length, chunked
/// transfer encoding, close-delimited bodies) can be unit tested without a
/// network connection.
nonisolated enum SgwcmaidHTTPResponseParser {
  struct Header: Sendable {
    let name: String
    let value: String
  }

  struct Head: Sendable {
    let statusCode: Int
    let headers: [Header]
    let bodyOffset: Int

    var headerMap: [String: [String]] {
      Dictionary(grouping: headers, by: \.name).mapValues { $0.map(\.value) }
    }

    func values(forHeader name: String) -> [String] {
      headers.filter { $0.name == name.lowercased() }.map(\.value)
    }
  }

  enum Framing: Equatable {
    case none
    case contentLength(Int)
    case chunked
    case untilClose
  }

  private static let crlf = Data([13, 10])
  private static let headerTerminator = Data([13, 10, 13, 10])
  private static let maximumBodyBytes = 64 * 1024 * 1024

  /// Returns `nil` while the header block is still incomplete.
  static func parseHead(_ data: Data) throws -> Head? {
    guard let terminator = data.range(of: headerTerminator) else { return nil }
    let headData = data.subdata(in: 0..<terminator.lowerBound)
    guard !headData.isEmpty, let text = String(data: headData, encoding: .isoLatin1) else {
      throw SgwcmaidHTTPError.malformedResponse
    }
    let lines = text.components(separatedBy: "\r\n")
    guard let statusLine = lines.first else { throw SgwcmaidHTTPError.malformedResponse }
    let statusParts = statusLine.split(separator: " ", omittingEmptySubsequences: true)
    guard statusParts.count >= 2,
      let statusCode = Int(statusParts[1]),
      (100...599).contains(statusCode)
    else {
      throw SgwcmaidHTTPError.malformedResponse
    }

    var headers: [Header] = []
    for line in lines.dropFirst() where !line.isEmpty {
      guard let separator = line.firstIndex(of: ":") else {
        throw SgwcmaidHTTPError.malformedResponse
      }
      let name = line[..<separator].trimmingCharacters(in: .whitespaces).lowercased()
      let value = line[line.index(after: separator)...].trimmingCharacters(in: .whitespaces)
      guard !name.isEmpty else { throw SgwcmaidHTTPError.malformedResponse }
      headers.append(Header(name: name, value: value))
    }
    return Head(statusCode: statusCode, headers: headers, bodyOffset: terminator.upperBound)
  }

  static func framing(for head: Head) -> Framing {
    if head.statusCode == 204 || head.statusCode == 304 || (100...199).contains(head.statusCode) {
      return .none
    }
    if let encoding = head.values(forHeader: "transfer-encoding").first?.lowercased(),
      encoding.contains("chunked") {
      return .chunked
    }
    if let rawLength = head.values(forHeader: "content-length").first,
      let length = Int(rawLength), length >= 0 {
      return .contentLength(length)
    }
    return .untilClose
  }

  // swiftlint:disable cyclomatic_complexity
  /// Returns `nil` while more bytes are required to finish the chunked body.
  static func chunkedBody(_ data: Data, from offset: Int) throws -> Data? {
    var index = offset
    var body = Data()
    while true {
      guard let lineEnd = crlfOffset(in: data, from: index) else { return nil }
      guard let line = String(bytes: data[index..<lineEnd], encoding: .utf8) else {
        throw SgwcmaidHTTPError.malformedResponse
      }
      let sizeToken = line.split(separator: ";", maxSplits: 1).first.map(String.init) ?? ""
      guard let size = Int(sizeToken.trimmingCharacters(in: .whitespaces), radix: 16), size >= 0 else {
        throw SgwcmaidHTTPError.malformedResponse
      }
      index = lineEnd + 2
      if size == 0 {
        // The last-chunk line is followed by optional trailers and a blank line.
        while true {
          guard let trailerEnd = crlfOffset(in: data, from: index) else { return nil }
          let trailer = data.subdata(in: index..<trailerEnd)
          index = trailerEnd + 2
          if trailer.isEmpty { return body }
        }
      }
      guard data.count - index >= size + 2 else { return nil }
      guard data[index + size] == 13, data[index + size + 1] == 10 else {
        throw SgwcmaidHTTPError.malformedResponse
      }
      body.append(data.subdata(in: index..<(index + size)))
      guard body.count <= maximumBodyBytes else { throw SgwcmaidHTTPError.responseTooLarge }
      index += size + 2
    }
  }
  // swiftlint:enable cyclomatic_complexity

  private static func crlfOffset(in data: Data, from offset: Int) -> Int? {
    guard offset >= 0, offset < data.count else { return nil }
    guard let range = data.range(of: crlf, options: [], in: offset..<data.count) else { return nil }
    return range.lowerBound
  }
}
