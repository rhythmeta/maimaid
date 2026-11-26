package org.rhythmeta.maimaid.core.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import org.rhythmeta.maimaid.BuildConfig

/** The account AiMeDB resolves for one credential. */
internal data class AimeDbAccount(
    val userId: Long,
    val accessCode: String,
    val authKey: String,
    val portal: Int,
    val credentialKind: String,
)

/**
 * The AiMeDB binary framing used by the international cabinets.
 *
 * A request is a 32-byte little-endian header plus a command body, padded with zero bytes
 * to a 16-byte boundary and encrypted with AES-128-ECB. This is a different protocol from
 * the China server's HTTP AimeDB: there is no JSON, no token, and no one-time code.
 */
internal object SdgaAimeFrame {
    const val MAGIC = 0xA13E
    const val HEADER_SIZE = 32
    const val CMD_FELICA_LOOKUP = 0x01
    const val CMD_LOOKUP_EX = 0x0F
    const val NO_AIME_ID = 0xFFFFFFFFL

    fun build(
        command: Int,
        body: ByteArray,
        placeId: Int,
        keychip: String,
        gameId: String,
        protocolVersion: Int,
    ): ByteArray {
        val length = HEADER_SIZE + body.size
        val header = ByteArray(HEADER_SIZE)
        putLe16(header, 0, MAGIC)
        putLe16(header, 2, protocolVersion)
        putLe16(header, 4, command)
        putLe16(header, 6, length)
        putLe16(header, 8, 0) // Result: always 0 towards the server.
        val game = gameId.toByteArray(Charsets.US_ASCII).copyOf(6)
        game.copyInto(header, 10)
        putLe32(header, 16, placeId)
        chipId(keychip).copyInto(header, 20)
        return header + body
    }

    /** `clientId` reduced to alphanumerics, truncated to 11 characters and NUL padded. */
    fun chipId(keychip: String): ByteArray =
        keychip.filter { it.isLetterOrDigit() }.take(11).toByteArray(Charsets.US_ASCII).copyOf(12)

    fun putLe16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    fun putLe32(target: ByteArray, offset: Int, value: Int) {
        for (index in 0 until 4) {
            target[offset + index] = ((value shr (8 * index)) and 0xFF).toByte()
        }
    }

    fun le16(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xFF) or ((source[offset + 1].toInt() and 0xFF) shl 8)

    fun le32(source: ByteArray, offset: Int): Long =
        (0 until 4).fold(0L) { acc, index ->
            acc or ((source[offset + index].toLong() and 0xFF) shl (8 * index))
        }
}

/**
 * Resolves an `IDm`/`AccessCode` into a game account through the international AiMeDB.
 *
 * The card can be resolved again at any time, so unlike the China cabinet flow there is no
 * "code already used" state and no server-side session to protect.
 */
internal class AimeDbClient(
    private val host: String,
    private val port: Int,
    private val commonKey: String,
    private val gameId: String = "SDGA",
    private val protocolVersion: Int = 0x4020,
    private val companyCode: Int = 0x01,
    private val firmware: Int = 0x03,
    private val timeoutMs: Int = 5_000,
    /**
     * Title ids to try, in order. `null` keeps the production default: the configured id
     * first, then [SDEZ_GAME_ID]. Tests pass an explicit list to measure one id at a time.
     */
    titles: List<String>? = null,
) {
    /**
     * The card database is keyed by the title that registered the card. The released
     * reference client resolves cards with `SDEZ` (the base maimai DX title) while the
     * overseas profile uses `SDGA`, so try the configured id first and fall back to the
     * other one only when the server answers "invalid aime id".
     */
    private val gameIds: List<String> = (titles ?: listOf(gameId, SDEZ_GAME_ID)).distinct()

    fun resolve(credential: SdgaCredential, cabinet: SdgaCabinet): AimeDbAccount =
        when (credential) {
            is SdgaCredential.Idm -> resolveIdm(credential, cabinet)
            is SdgaCredential.AccessCode -> resolveAccessCode(credential, cabinet)
        }

    /** FeliCa card: `0x01` turns the IDm into an access code, then `0x0F` reads the account. */
    private fun resolveIdm(credential: SdgaCredential.Idm, cabinet: SdgaCabinet): AimeDbAccount {
        val pmm = SdgaCredentialParser.pmmFor(credential)
        val body = hex(credential.idm) + hex(pmm)
        for (title in gameIds) {
            val response = exchange(SdgaAimeFrame.CMD_FELICA_LOOKUP, body, cabinet, title)
            val result = SdgaAimeFrame.le16(response, 8)
            val accessCode = hexString(response, 36, 10)
            val felicaIndex = SdgaAimeFrame.le32(response, 32)
            if (BuildConfig.DEBUG) {
                android.util.Log.d(
                    "SdgaAimeDb",
                    "RX cmd=0x01 title=$title result=$result felicaIndex=$felicaIndex " +
                        "accessCode=${masked(accessCode)} bytes=${response.size}",
                )
            }
            if (accessCode.isEmpty() || accessCode.all { it == '0' } ||
                felicaIndex == SdgaAimeFrame.NO_AIME_ID
            ) {
                // The card itself did not resolve for this title: a wrong PMm, an unknown
                // card generation, or a card registered under the other title id.
                continue
            }
            if (result in 4..10) throw DirectImportException("sdga_locked")
            if (result != 1) throw DirectImportException("sdga_result_$result")
            // A FeliCa card has no Mifare UID, so 0x0F carries the placeholder tail.
            return lookupAccount(
                accessCode,
                m1Uid = null,
                cabinet = cabinet,
                kind = "idm",
                idm = credential.idm,
                title = title,
            )
        }
        throw DirectImportException("sdga_no_access_code")
    }

    /** Access code typed by the user: straight to `0x0F`. */
    private fun resolveAccessCode(
        credential: SdgaCredential.AccessCode,
        cabinet: SdgaCabinet,
    ): AimeDbAccount {
        var lastError: DirectImportException? = null
        for (title in gameIds) {
            try {
                return lookupAccount(
                    credential.code,
                    m1Uid = credential.m1Uid,
                    cabinet = cabinet,
                    kind = "accessCode",
                    idm = null,
                    title = title,
                )
            } catch (error: DirectImportException) {
                // Only "no such account" is worth retrying under the other title id.
                val retryable = error.code == "sdga_no_account" || error.code == "sdga_no_account_no_uid"
                if (!retryable) throw error
                lastError = error
            }
        }
        throw lastError ?: DirectImportException("sdga_no_account")
    }

    private fun lookupAccount(
        accessCode: String,
        m1Uid: String?,
        cabinet: SdgaCabinet,
        kind: String,
        idm: String?,
        title: String,
    ): AimeDbAccount {
        val body = hex(accessCode) + byteArrayOf(companyCode.toByte(), firmware.toByte()) + mifareUid(m1Uid)
        val response = exchange(SdgaAimeFrame.CMD_LOOKUP_EX, body, cabinet, title)
        val result = SdgaAimeFrame.le16(response, 8)
        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "SdgaAimeDb",
                "RX cmd=0x0F title=$title result=$result userId=" +
                    (if (response.size >= 36) SdgaAimeFrame.le32(response, 32) else -1) +
                    " accessCode=${masked(accessCode)} bytes=${response.size}",
            )
        }
        if (result in 4..10) throw DirectImportException("sdga_locked")
        // Mifare cards answer only when the lookup carries the card's own UID; without it
        // the server reports "invalid aime id" even for a registered account.
        if (result == 2) {
            // The placeholder is only a guess: when the user typed no UID, say so, because a
            // Mifare card whose real UID differs answers exactly like an unknown account.
            throw DirectImportException(
                if (m1Uid.isNullOrBlank()) "sdga_no_account_no_uid" else "sdga_no_account",
            )
        }
        if (result != 1) throw DirectImportException("sdga_result_$result")
        if (response.size < 36) throw DirectImportException("sdga_bad_response")
        val userId = SdgaAimeFrame.le32(response, 32)
        if (userId <= 0 || userId == SdgaAimeFrame.NO_AIME_ID) {
            throw DirectImportException("sdga_no_account")
        }
        val portal = if (response.size > 36) response[36].toInt() and 0xFF else 0
        val authKey = if (response.size > 40) {
            val end = minOf(response.size, 40 + 256)
            val raw = response.copyOfRange(40, end)
            val terminator = raw.indexOf(0).let { if (it < 0) raw.size else it }
            String(raw, 0, terminator, Charsets.ISO_8859_1)
        } else {
            ""
        }
        if (BuildConfig.DEBUG) {
            android.util.Log.d("SdgaAimeDb", "resolved kind=$kind userId=$userId portal=$portal authKey=${authKey.length}B idm=${idm != null}")
        }
        return AimeDbAccount(userId, accessCode, authKey, portal, kind)
    }

    /** Sends one encrypted frame and returns the decrypted response. */
    private fun exchange(
        command: Int,
        body: ByteArray,
        cabinet: SdgaCabinet,
        title: String,
    ): ByteArray {
        val frame = SdgaAimeFrame.build(
            command = command,
            body = body,
            placeId = cabinet.placeId,
            keychip = cabinet.keychip,
            gameId = title,
            protocolVersion = protocolVersion,
        )
        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "SdgaAimeDb",
                "TX cmd=0x${command.toString(16)} title=$title $host:$port store=${cabinet.placeId} " +
                    "keychip=${cabinet.keychip.take(4)}…${cabinet.keychip.takeLast(2)} body=${body.size}B",
            )
        }
        val padded = frame.copyOf(((frame.size + 15) / 16) * 16)
        val ciphertext = aes(Cipher.ENCRYPT_MODE, padded)
        val raw = try {
            transmit(ciphertext)
        } catch (error: SocketTimeoutException) {
            throw DirectImportException("sdga_network")
        } catch (error: IOException) {
            throw DirectImportException("sdga_network")
        }
        return decryptResponse(raw, command)
    }

    private fun transmit(payload: ByteArray): ByteArray {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.soTimeout = timeoutMs
            socket.getOutputStream().apply {
                write(payload)
                flush()
            }
            val input = socket.getInputStream()
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(4096)
            var needed = -1
            while (true) {
                val read = input.read(chunk)
                if (read <= 0) break
                buffer.write(chunk, 0, read)
                if (needed < 0 && buffer.size() >= SdgaAimeFrame.HEADER_SIZE) {
                    val head = aes(Cipher.DECRYPT_MODE, buffer.toByteArray().copyOf(SdgaAimeFrame.HEADER_SIZE))
                    val declared = SdgaAimeFrame.le16(head, 6)
                    if (declared < SdgaAimeFrame.HEADER_SIZE) break
                    needed = ((declared + 15) / 16) * 16
                }
                if (needed > 0 && buffer.size() >= needed) break
            }
            val raw = buffer.toByteArray()
            if (raw.isEmpty()) throw DirectImportException("sdga_no_response")
            return raw
        }
    }

    private fun decryptResponse(raw: ByteArray, command: Int): ByteArray {
        val usable = raw.size - (raw.size % 16)
        if (usable < SdgaAimeFrame.HEADER_SIZE) throw DirectImportException("sdga_bad_response")
        val plain = aes(Cipher.DECRYPT_MODE, raw.copyOf(usable))
        val declared = SdgaAimeFrame.le16(plain, 6)
        if (declared < SdgaAimeFrame.HEADER_SIZE || declared > plain.size) {
            throw DirectImportException("sdga_bad_response")
        }
        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "SdgaAimeDb",
                "cmd=0x${command.toString(16)} result=${SdgaAimeFrame.le16(plain, 8)} " +
                    "wire=${raw.size}B frame=${declared}B",
            )
        }
        return plain.copyOf(declared)
    }

    private fun aes(mode: Int, input: ByteArray): ByteArray =
        Cipher.getInstance("AES/ECB/NoPadding").run {
            init(mode, SecretKeySpec(commonKey.toByteArray(Charsets.UTF_8), "AES"))
            doFinal(input)
        }

    private fun mifareUid(value: String?): ByteArray {
        // The released reference client (the one whose login is confirmed working) sends
        // 01020304 when no card UID is known; an all-zero tail is answered with result=2.
        val uid = value?.takeIf { it.isNotBlank() } ?: DEFAULT_MIFARE_UID
        if (!uid.matches(Regex("[0-9A-Fa-f]{8}"))) throw DirectImportException("sdga_invalid_credential")
        return hex(uid)
    }

    private fun hex(value: String): ByteArray {
        if (value.length % 2 != 0 || !value.matches(Regex("[0-9A-Fa-f]+"))) {
            throw DirectImportException("sdga_invalid_credential")
        }
        return ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private companion object {
        /** Base maimai DX title id; the released client resolves cards with this. */
        const val SDEZ_GAME_ID = "SDEZ"

        /** Mifare UID placeholder used by the released client when the card UID is unknown. */
        const val DEFAULT_MIFARE_UID = "01020304"
    }

    /** Diagnostics never carry a whole card credential. */
    private fun masked(value: String): String =
        if (value.length <= 6) "<short>" else value.take(4) + "…" + value.takeLast(2)

    /** Uppercase hex of `[start, start + length)`, empty when the frame is too short. */
    private fun hexString(source: ByteArray, start: Int, length: Int): String {
        if (source.size < start + length) return ""
        return (start until start + length).joinToString("") {
            "%02X".format(source[it].toInt() and 0xFF)
        }
    }
}
