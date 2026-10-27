package org.rhythmeta.maimaid.core.data

import java.net.URI

/**
 * Cabinet QR landing links served by the China-server site, and the SGWCMAID token they carry.
 *
 * A cabinet link looks like
 * `https://wq.wahlap.net/qrcode/req/{code}.html?l=...&t=...&d=...`, where `{code}` already
 * starts with `MAID`. The importable token is that code prefixed with `SGWC`; nothing else in
 * the link (timestamp, title, description) is needed.
 */
object SgwcmaidDeepLink {
    const val WEB_HOST = "wq.wahlap.net"
    const val WEB_PATH_PREFIX = "/qrcode/req/"
    const val APP_SCHEME = "maimaid"
    const val APP_HOST = "sgwcmaid"
    const val CODE_PREFIX = "SGWCMAID"

    private const val CABINET_CODE_PREFIX = "MAID"
    /** The cabinet code leaves off the `SGWC` that starts the import token. */
    private const val CARRIED_PREFIX = "SGWC"
    private const val HTML_SUFFIX = ".html"
    private const val MIN_CODE_LENGTH = 20
    private const val MAX_INPUT_LENGTH = 8_192

    /**
     * Returns the SGWCMAID carried by a cabinet link, app link, or bare code; null for anything
     * unrelated. Cabinet codes are uppercase alphanumerics, so links are normalized to uppercase
     * before the token is assembled.
     */
    fun extractCode(value: String?): String? {
        val raw = normalize(value) ?: return null
        if (raw.startsWith(CODE_PREFIX)) return raw.takeIf(::isImportableCode)
        val uri = parse(raw) ?: return null
        return when (uri.scheme?.lowercase()) {
            "http", "https" -> cabinetCodeFrom(uri)
            APP_SCHEME -> if (uri.host.equals(APP_HOST, ignoreCase = true)) {
                codeFromSegment(uri.path)
            } else {
                null
            }
            else -> null
        }
    }

    /** True when [value] points at the cabinet QR page, even if its code cannot be decoded. */
    fun isCabinetLink(value: String?): Boolean {
        val raw = normalize(value) ?: return false
        val uri = parse(raw) ?: return false
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return false
        return uri.host.equals(WEB_HOST, ignoreCase = true) &&
            uri.path.orEmpty().startsWith(WEB_PATH_PREFIX, ignoreCase = true)
    }

    private fun cabinetCodeFrom(uri: URI): String? {
        if (!uri.host.equals(WEB_HOST, ignoreCase = true)) return null
        val segments = uri.path.orEmpty().split('/').filter(String::isNotEmpty)
        if (segments.size != 3) return null
        if (!segments[0].equals("qrcode", ignoreCase = true)) return null
        if (!segments[1].equals("req", ignoreCase = true)) return null
        val name = segments[2]
        if (!name.endsWith(HTML_SUFFIX, ignoreCase = true)) return null
        return cabinetCode(name.dropLast(HTML_SUFFIX.length))
    }

    /** App links may carry the cabinet code with or without the `.html` suffix. */
    private fun codeFromSegment(segment: String?): String? {
        val name = segment?.split('/')?.lastOrNull(String::isNotEmpty) ?: return null
        return if (name.endsWith(HTML_SUFFIX, ignoreCase = true)) {
            cabinetCode(name.dropLast(HTML_SUFFIX.length))
        } else {
            cabinetCode(name)
        }
    }

    /** Accepts either the bare cabinet code (`MAID...`) or an already prefixed token. */
    private fun cabinetCode(stem: String): String? {
        val code = when {
            stem.startsWith(CODE_PREFIX, ignoreCase = true) -> stem
            stem.startsWith(CABINET_CODE_PREFIX, ignoreCase = true) -> CARRIED_PREFIX + stem
            else -> return null
        }
        return code.uppercase().takeIf(::isImportableCode)
    }

    private fun isImportableCode(code: String): Boolean =
        code.startsWith(CODE_PREFIX) &&
            code.length > MIN_CODE_LENGTH &&
            code.all(Char::isLetterOrDigit)

    private fun normalize(value: String?): String? {
        val trimmed = value?.trim() ?: return null
        if (trimmed.isEmpty() || trimmed.length > MAX_INPUT_LENGTH) return null
        return trimmed
    }

    private fun parse(value: String): URI? = runCatching { URI(value) }.getOrNull()
}
