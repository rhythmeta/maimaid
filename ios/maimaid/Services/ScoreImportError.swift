import Foundation

nonisolated struct ScoreImportError: LocalizedError {
  let code: String

  var errorDescription: String? {
    switch code {
    case "invalid_grant", "unauthorized":
      return String(localized: "import.lxns.status.failed.expired")
    case "profile_changed":
      return String(localized: "import.local.profileChanged")
    case "sgwcmaid_not_configured":
      return String(localized: "import.sgwcmaid.error.notConfigured")
    case "sgwcmaid_requires_code":
      return String(localized: "import.sgwcmaid.error.invalidCode")
    case "invalid_sgwcmaid":
      return String(localized: "import.sgwcmaid.error.invalidCode")
    case "chime_keychip_missing":
      return String(localized: "import.sgwcmaid.error.keychipMissing")
    case "chime_code_invalid_or_expired":
      return String(localized: "import.sgwcmaid.error.codeInvalid")
    case "chime_code_already_used":
      return String(localized: "import.sgwcmaid.error.codeUsed")
    case "chime_db_resolve_fail", "chime_db_access_timeout", "chime_db_access_fail",
      "chime_bad_response":
      return String(localized: "import.sgwcmaid.error.service")
    case "chime_empty_credentials":
      return String(localized: "import.sgwcmaid.error.credentials")
    case "chime_network":
      return String(localized: "import.sgwcmaid.error.network")
    case "chime_timeout":
      return String(localized: "import.sgwcmaid.error.timeout")
    case "game_account_already_logged_in", "game_login_100":
      return String(localized: "import.sgwcmaid.error.sessionBusy")
    case "game_preview_error", "game_empty_response", "game_pagination_stalled",
      "game_pagination_limit":
      return String(localized: "import.sgwcmaid.error.service")
    case "catalog_empty":
      return String(localized: "import.sgwcmaid.error.catalogEmpty")
    default:
      if code.hasPrefix("game_login_") {
        return String(localized: "import.sgwcmaid.error.login")
      }
      return String(localized: "import.status.failed") + " (\(code))"
    }
  }
}
