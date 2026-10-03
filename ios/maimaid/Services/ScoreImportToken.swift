import Foundation

struct ScoreImportToken: Decodable, Sendable {
  let accessToken: String
  let refreshToken: String

  private enum CodingKeys: String, CodingKey {
    case accessToken = "access_token"
    case refreshToken = "refresh_token"
    case data
  }
  init(from decoder: any Decoder) throws {
    let container = try decoder.container(keyedBy: CodingKeys.self)
    let values =
      container.contains(.accessToken)
      ? container
      : try container.nestedContainer(
        keyedBy: CodingKeys.self, forKey: .data)
    accessToken = try values.decode(String.self, forKey: .accessToken)
    refreshToken = try values.decode(String.self, forKey: .refreshToken)
    guard !accessToken.isEmpty, !refreshToken.isEmpty else {
      throw ScoreImportError(code: "invalid_token_response")
    }
  }
}
