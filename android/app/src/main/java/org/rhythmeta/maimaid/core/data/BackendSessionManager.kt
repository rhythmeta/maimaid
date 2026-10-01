package org.rhythmeta.maimaid.core.data

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.rhythmeta.maimaid.core.network.BackendApiClient
import org.rhythmeta.maimaid.core.network.BackendApiException

enum class BackendWebAuthMode(val queryValue: String) {
    Login("login"),
    Register("register"),
    Forgot("forgot"),
}

enum class BackendSessionNotice {
    LoginSucceeded,
    AuthLinkSucceeded,
    AuthLinkFailed,
}

data class BackendSessionState(
    val user: BackendAuthUser? = null,
    val isChecking: Boolean = false,
    val notice: BackendSessionNotice? = null,
) {
    val isAuthenticated: Boolean get() = user != null
}

data class BackendAuthRedirect(
    val type: String?,
    val result: String?,
    val code: String?,
    val sessionCode: String?,
    val state: String? = null,
) {
    companion object {
        fun parse(rawUrl: String): BackendAuthRedirect? {
            val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return null
            if (uri.scheme != "maimaid" || uri.host != "auth" || uri.path != "/callback") return null
            val values = parseParameters(uri.rawQuery) + parseParameters(uri.rawFragment)
            return BackendAuthRedirect(
                type = values["type"]?.lowercase(),
                result = values["result"]?.lowercase(),
                code = values["code"]?.lowercase(),
                sessionCode = values["sessionCode"],
                state = values["state"],
            )
        }

        private fun parseParameters(rawValue: String?): Map<String, String> = rawValue
            ?.split('&')
            ?.mapNotNull { pair ->
                val parts = pair.split('=', limit = 2)
                val key = decode(parts.firstOrNull().orEmpty())
                if (key.isBlank()) null else key to decode(parts.getOrNull(1).orEmpty())
            }
            ?.toMap()
            .orEmpty()

        private fun decode(value: String): String = URLDecoder.decode(value, Charsets.UTF_8.name())
    }
}

class BackendSessionManager(
    private val authBaseUrl: String,
    private val apiClient: BackendApiClient,
    private val tokenStore: BackendTokenStore,
) {
    private val refreshMutex = Mutex()
    private var tokens: BackendTokenBundle? = tokenStore.load()
    private val mutableState = MutableStateFlow(BackendSessionState(user = tokens?.user))

    val state: StateFlow<BackendSessionState> = mutableState.asStateFlow()
    val isConfigured: Boolean get() = authBaseUrl.isNotBlank()

    fun accessTokenOrNull(): String? = tokens?.accessToken

    fun webAuthUrl(mode: BackendWebAuthMode): String? {
        val base = authBaseUrl.trim().trimEnd('/')
        if (base.isEmpty()) return null
        val pending = PendingAppLogin.create()
        tokenStore.savePendingLogin(pending)
        val separator = if ('?' in base) '&' else '?'
        return buildString {
            append(base)
            append(separator)
            append("authMode=")
            append(mode.queryValue)
            append("&redirect_uri=")
            append(URLEncoder.encode(AUTH_REDIRECT_URL, Charsets.UTF_8.name()))
            append("&client_id=maimaid&code_challenge_method=S256&code_challenge=")
            append(pending.challenge)
            append("&state=")
            append(pending.state)
        }
    }

    suspend fun checkSession() {
        if (tokens == null) {
            mutableState.value = BackendSessionState()
            return
        }
        mutableState.value = mutableState.value.copy(isChecking = true)
        try {
            val payload = authorizedRequest("auth/v1/me")
            val user = apiJson.decodeFromJsonElement(BackendAuthUser.serializer(), payload)
            applyTokens(requireNotNull(tokens).copy(user = user))
        } catch (error: BackendApiException) {
            if (error.statusCode == 401) clearSession()
        } catch (_: Exception) {
            // Keep the cached session while the service is temporarily unreachable.
        } finally {
            mutableState.value = mutableState.value.copy(isChecking = false)
        }
    }

    suspend fun handleAuthRedirect(rawUrl: String?) {
        val redirect = rawUrl?.let(BackendAuthRedirect::parse) ?: return
        if (redirect.type == "session") {
            val sessionCode = redirect.sessionCode
            val pending = tokenStore.pendingLogin()
            if (pending == null || !pending.accepts(redirect.state)) {
                mutableState.value = mutableState.value.copy(notice = BackendSessionNotice.AuthLinkFailed)
                return
            }
            tokenStore.clearPendingLogin()
            if (redirect.result == "success" && sessionCode != null && sessionCode.length >= 20) {
                runCatching {
                    val payload = apiClient.request(
                        path = "auth/v1/session:exchange",
                        method = "POST",
                        body = buildJsonObject {
                            put("sessionCode", sessionCode)
                            put("clientId", "maimaid")
                            put("redirectUri", AUTH_REDIRECT_URL)
                            put("codeVerifier", pending.verifier)
                        },
                    )
                    apiJson.decodeFromJsonElement(BackendTokenBundle.serializer(), payload)
                }.onSuccess { bundle ->
                    applyTokens(bundle)
                    mutableState.value = mutableState.value.copy(notice = BackendSessionNotice.LoginSucceeded)
                }.onFailure {
                    mutableState.value = mutableState.value.copy(notice = BackendSessionNotice.AuthLinkFailed)
                }
                return
            }
            mutableState.value = mutableState.value.copy(notice = BackendSessionNotice.AuthLinkFailed)
            return
        }
        mutableState.value = mutableState.value.copy(
            notice = if (redirect.result == "success") {
                BackendSessionNotice.AuthLinkSucceeded
            } else {
                BackendSessionNotice.AuthLinkFailed
            },
        )
    }

    fun consumeNotice() {
        mutableState.value = mutableState.value.copy(notice = null)
    }

    suspend fun logout() {
        val refreshToken = tokens?.refreshToken
        if (refreshToken != null) {
            runCatching {
                apiClient.request(
                    path = "auth/v1/logout",
                    method = "POST",
                    body = buildJsonObject { put("refreshToken", refreshToken) },
                )
            }
        }
        clearSession()
    }

    suspend fun authorizedRequest(
        path: String,
        method: String = "GET",
        body: JsonElement? = null,
    ): JsonElement {
        val initialTokens = tokens ?: throw BackendApiException(401, "unauthorized", "Authentication required.")
        return try {
            apiClient.request(path, method, body, initialTokens.accessToken)
        } catch (error: BackendApiException) {
            if (error.statusCode != 401 || !refresh(initialTokens.refreshToken)) throw error
            val refreshed = tokens ?: throw error
            apiClient.request(path, method, body, refreshed.accessToken)
        }
    }

    private suspend fun refresh(staleRefreshToken: String): Boolean = refreshMutex.withLock {
        val current = tokens ?: return@withLock false
        if (current.refreshToken != staleRefreshToken) return@withLock true
        return@withLock try {
            val payload = apiClient.request(
                path = "auth/v1/refresh",
                method = "POST",
                body = buildJsonObject { put("refreshToken", current.refreshToken) },
            )
            applyTokens(apiJson.decodeFromJsonElement(BackendTokenBundle.serializer(), payload))
            true
        } catch (error: BackendApiException) {
            if (error.statusCode in 400..499) clearSession()
            false
        }
    }

    private fun applyTokens(bundle: BackendTokenBundle) {
        tokens = bundle
        tokenStore.save(bundle)
        mutableState.value = mutableState.value.copy(user = bundle.user)
    }

    private fun clearSession() {
        tokens = null
        tokenStore.clear()
        mutableState.value = BackendSessionState()
    }

    private companion object {
        const val AUTH_REDIRECT_URL = "maimaid://auth/callback"
        val apiJson = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            explicitNulls = false
        }
    }
}
