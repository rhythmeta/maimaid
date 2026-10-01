import Foundation
import Observation
import Security
import CryptoKit

struct BackendAuthUser: Codable, Equatable {
    let id: String
    let email: String
    let username: String
    let usernameDiscriminator: String
    let handle: String
    let isAdmin: Bool

    init(
        id: String,
        email: String,
        username: String? = nil,
        usernameDiscriminator: String? = nil,
        handle: String? = nil,
        isAdmin: Bool
    ) {
        let resolvedUsername = username?.isEmpty == false ? username ?? email : email
        let resolvedDiscriminator = usernameDiscriminator ?? ""

        self.id = id
        self.email = email
        self.username = resolvedUsername
        self.usernameDiscriminator = resolvedDiscriminator

        if let handle, !handle.isEmpty {
            self.handle = handle
        } else if !resolvedDiscriminator.isEmpty {
            self.handle = "\(resolvedUsername)#\(resolvedDiscriminator)"
        } else {
            self.handle = email
        }

        self.isAdmin = isAdmin
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let id = try container.decode(String.self, forKey: .id)
        let email = try container.decode(String.self, forKey: .email)
        let username = try container.decodeIfPresent(String.self, forKey: .username)
        let usernameDiscriminator = try container.decodeIfPresent(String.self, forKey: .usernameDiscriminator)
        let handle = try container.decodeIfPresent(String.self, forKey: .handle)
        let isAdmin = try container.decode(Bool.self, forKey: .isAdmin)

        self.init(
            id: id,
            email: email,
            username: username,
            usernameDiscriminator: usernameDiscriminator,
            handle: handle,
            isAdmin: isAdmin
        )
    }
}

private struct BackendTokenBundle: Codable {
    let user: BackendAuthUser
    let accessToken: String
    let refreshToken: String
    let expiresIn: Int
}

private struct BackendAuthPayload: Codable {
    let user: BackendAuthUser
    let accessToken: String
    let refreshToken: String
    let expiresIn: Int
}

private struct BackendResendVerificationPayload: Codable {
    let verificationEmailSent: Bool
}

private struct BackendSuccessResponse: Codable {
    let success: Bool?
}

private typealias BackendMePayload = BackendAuthUser

private struct BackendRefreshRequest: Encodable {
    let refreshToken: String
}

private struct BackendSessionExchangeRequest: Encodable {
    let sessionCode: String
    let clientId = "maimaid"
    let redirectUri = "maimaid://auth/callback"
    let codeVerifier: String
}

private struct KeychainTokenStore {
    private static let account = "org.rhythmeta.maimaid.account.tokens"
    private static let service = "in.shikoch.maimaid"

    static func load() -> BackendTokenBundle? {
        let query: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: service,
            kSecAttrAccount: account,
            kSecAttrSynchronizable: kCFBooleanFalse as Any,
            kSecReturnData: true,
            kSecMatchLimit: kSecMatchLimitOne
        ]

        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess, let data = item as? Data else {
            return nil
        }
        return try? BackendAPIClient.decoder.decode(BackendTokenBundle.self, from: data)
    }

    static func save(_ bundle: BackendTokenBundle) {
        guard let data = try? BackendAPIClient.encoder.encode(bundle) else {
            return
        }

        let query: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: service,
            kSecAttrAccount: account,
            kSecAttrSynchronizable: kCFBooleanFalse as Any
        ]

        let update: [CFString: Any] = [
            kSecValueData: data,
            kSecAttrAccessible: kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
            kSecAttrSynchronizable: kCFBooleanFalse as Any
        ]

        let status = SecItemUpdate(query as CFDictionary, update as CFDictionary)
        if status == errSecItemNotFound {
            let insert: [CFString: Any] = [
                kSecClass: kSecClassGenericPassword,
                kSecAttrService: service,
                kSecAttrAccount: account,
                kSecValueData: data,
                kSecAttrAccessible: kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
                kSecAttrSynchronizable: kCFBooleanFalse as Any
            ]
            SecItemAdd(insert as CFDictionary, nil)
        }
    }

    static func clear() {
        let query: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: service,
            kSecAttrAccount: account,
            kSecAttrSynchronizable: kCFBooleanFalse as Any
        ]
        SecItemDelete(query as CFDictionary)
    }
}

@MainActor
@Observable
final class BackendSessionManager {
    static let shared = BackendSessionManager()
    private static let authRedirectURL = URL(string: "maimaid://auth/callback")

    private(set) var currentUser: BackendAuthUser?
    private(set) var pendingMessage: String?
    private(set) var pendingMessageIsError = false

    private var accessToken: String?
    private var refreshToken: String?
    private var isRefreshing = false
    private var pendingAppLogin: PendingAppLogin?

    var isConfigured: Bool {
        BackendConfig.baseURL != nil
    }

    var isAuthenticated: Bool {
        currentUser != nil && accessToken != nil && refreshToken != nil
    }

    private init() {
        if let cached = KeychainTokenStore.load() {
            currentUser = cached.user
            accessToken = cached.accessToken
            refreshToken = cached.refreshToken
        }
    }

    func webAuthURL(mode: String) -> URL? {
        guard let baseURL = BackendConfig.webAuthBaseURL,
              var components = URLComponents(url: baseURL, resolvingAgainstBaseURL: false),
              let pending = PendingAppLogin.create() else { return nil }
        pendingAppLogin = pending
        components.queryItems = [
            URLQueryItem(name: "client_id", value: "maimaid"),
            URLQueryItem(name: "redirect_uri", value: "maimaid://auth/callback"),
            URLQueryItem(name: "authMode", value: mode),
            URLQueryItem(name: "state", value: pending.state),
            URLQueryItem(name: "code_challenge_method", value: "S256"),
            URLQueryItem(name: "code_challenge", value: pending.challenge)
        ]
        return components.url
    }

    func clearPendingMessage() {
        pendingMessage = nil
        pendingMessageIsError = false
    }

    func handleAuthRedirect(_ url: URL) {
        guard isAppAuthCallbackURL(url) else {
            return
        }

        let type = value(of: "type", from: url)?.lowercased()
        let result = value(of: "result", from: url)?.lowercased()
        let code = value(of: "code", from: url)?.lowercased()

        if type == "session" {
            Task { @MainActor in
                await handleSessionRedirect(result: result, from: url)
            }
            return
        }

        if result == "success" {
            pendingMessage = "settings.cloud.message.authLinkSuccess"
            pendingMessageIsError = false
            return
        }

        if code == "invalid_verification_token" {
            pendingMessage = "settings.cloud.message.authLinkFailed"
            pendingMessageIsError = true
            return
        }

        pendingMessage = "settings.cloud.message.authLinkFailed"
        pendingMessageIsError = true
    }

    func accessTokenForRequest() -> String? {
        accessToken
    }

    func clearSession() {
        currentUser = nil
        accessToken = nil
        refreshToken = nil
        KeychainTokenStore.clear()
    }

    func checkSession() async {
        guard isConfigured else {
            clearSession()
            return
        }
        guard accessToken != nil else {
            currentUser = nil
            return
        }

        do {
            currentUser = try await loadCurrentUser()
            persistIfPossible()
        } catch {
            if await refreshSessionSilently() {
                do {
                    currentUser = try await loadCurrentUser()
                    persistIfPossible()
                } catch {
                    clearSession()
                }
            } else {
                clearSession()
            }
        }
    }

    func resendVerification(email: String) async throws -> Bool {
        let payload: BackendResendVerificationPayload = try await BackendAPIClient.request(
            path: "auth/v1/verification:resend",
            method: "POST",
            body: ["email": email],
            authentication: .none
        )
        return payload.verificationEmailSent
    }

    func logout() async {
        guard let refreshToken else {
            clearSession()
            return
        }

        do {
            let _: BackendSuccessResponse = try await BackendAPIClient.request(
                path: "auth/v1/logout",
                method: "POST",
                body: BackendRefreshRequest(refreshToken: refreshToken),
                authentication: .none
            )
        } catch {
            // Ignore remote logout failure and clear local session anyway.
        }

        clearSession()
    }

    func refreshSessionSilently() async -> Bool {
        if isRefreshing {
            return false
        }
        guard let refreshToken else {
            clearSession()
            return false
        }

        isRefreshing = true
        defer { isRefreshing = false }

        do {
            let payload: BackendAuthPayload = try await BackendAPIClient.request(
                path: "auth/v1/refresh",
                method: "POST",
                body: BackendRefreshRequest(refreshToken: refreshToken),
                authentication: .none
            )
            applyAuthPayload(payload)
            return true
        } catch {
            clearSession()
            return false
        }
    }

    private func applyAuthPayload(_ payload: BackendAuthPayload) {
        currentUser = payload.user
        accessToken = payload.accessToken
        refreshToken = payload.refreshToken
        persistIfPossible(expiresIn: payload.expiresIn)
    }

    private func loadCurrentUser() async throws -> BackendMePayload {
        try await BackendAPIClient.request(
            path: "auth/v1/me",
            method: "GET",
            authentication: .required
        )
    }

    private func persistIfPossible(expiresIn: Int = 0) {
        guard let currentUser, let accessToken, let refreshToken else {
            return
        }
        KeychainTokenStore.save(
            BackendTokenBundle(
                user: currentUser,
                accessToken: accessToken,
                refreshToken: refreshToken,
                expiresIn: expiresIn
            )
        )
    }

    private func isAppAuthCallbackURL(_ url: URL) -> Bool {
        guard let redirect = Self.authRedirectURL else {
            return false
        }
        guard
            let redirectComponents = URLComponents(url: redirect, resolvingAgainstBaseURL: false),
            let incomingComponents = URLComponents(url: url, resolvingAgainstBaseURL: false)
        else {
            return false
        }

        return incomingComponents.scheme == redirectComponents.scheme
            && incomingComponents.host == redirectComponents.host
            && incomingComponents.path == redirectComponents.path
    }

    private func value(of name: String, from url: URL) -> String? {
        guard let components = URLComponents(url: url, resolvingAgainstBaseURL: false) else {
            return nil
        }

        if let value = components.queryItems?.first(where: { $0.name == name })?.value {
            return value
        }

        guard let fragment = components.fragment else {
            return nil
        }

        return URLComponents(string: "?\(fragment)")?
            .queryItems?
            .first(where: { $0.name == name })?
            .value
    }

    private func handleSessionRedirect(result: String?, from url: URL) async {
        guard result == "success", let pending = pendingAppLogin,
              pending.state == value(of: "state", from: url),
              Date().timeIntervalSince(pending.createdAt) < 1800 else {
            pendingMessage = "settings.cloud.message.authLinkFailed"
            pendingMessageIsError = true
            return
        }

        pendingAppLogin = nil
        if let sessionCode = value(of: "sessionCode", from: url), sessionCode.count >= 20 {
            do {
                let payload: BackendAuthPayload = try await BackendAPIClient.request(
                    path: "auth/v1/session:exchange",
                    method: "POST",
                    body: BackendSessionExchangeRequest(sessionCode: sessionCode, codeVerifier: pending.verifier),
                    authentication: .none
                )
                applyAuthPayload(payload)
                pendingMessage = "settings.cloud.message.loginSuccess"
                pendingMessageIsError = false
                return
            } catch {
                pendingMessage = "settings.cloud.message.authLinkFailed"
                pendingMessageIsError = true
                return
            }
        }

        pendingMessage = "settings.cloud.message.authLinkFailed"
        pendingMessageIsError = true
    }
}
