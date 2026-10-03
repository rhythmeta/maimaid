import Foundation

struct ScoreImportError: LocalizedError {
  let code: String
  var errorDescription: String? {
    switch code {
    case "invalid_grant", "unauthorized": String(localized: "import.lxns.status.failed.expired")
    case "profile_changed": String(localized: "import.local.profileChanged")
    default: String(localized: "import.status.failed") + " (\(code))"
    }
  }
}
