import Foundation

struct DivingFishDeviceAuthorization: Decodable {
  let deviceCode: String
  let userCode: String
  let verificationUri: URL
  let verificationUriComplete: URL?
  let expiresIn: Int
  let interval: Int?

  enum CodingKeys: String, CodingKey {
    case deviceCode = "device_code"
    case userCode = "user_code"
    case verificationUri = "verification_uri"
    case verificationUriComplete = "verification_uri_complete"
    case expiresIn = "expires_in"
    case interval
  }
}
