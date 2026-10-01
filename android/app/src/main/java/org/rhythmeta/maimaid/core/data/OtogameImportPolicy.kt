package org.rhythmeta.maimaid.core.data

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

internal object OtogameImportPolicy {
    const val PLAYLOG_PAGE_LIMIT = 4

    fun isEligibleServer(server: String?): Boolean = server?.trim()?.equals("jp", ignoreCase = true) == true

    fun achievement(rawValue: Long): Double = rawValue / 10_000.0

    fun difficulty(code: Int): String? = when (code) {
        0 -> "basic"
        1 -> "advanced"
        2 -> "expert"
        3 -> "master"
        4 -> "remaster"
        10 -> "utage"
        else -> null
    }

    fun difficultyCode(playlog: OtogamePlaylog): Int =
        playlog.difficulty.takeIf { it >= 0 } ?: playlog.levelInfo.difficulty

    fun chartType(playlog: OtogamePlaylog): String = when {
        difficultyCode(playlog) == 10 -> "utage"
        playlog.music.isDeluxe -> "dx"
        else -> "standard"
    }

    fun rank(code: Int): String? = when (code) {
        0 -> "D"
        1 -> "C"
        2 -> "B"
        3 -> "BB"
        4 -> "BBB"
        5 -> "A"
        6 -> "AA"
        7 -> "AAA"
        8 -> "S"
        9 -> "S+"
        10 -> "SS"
        11 -> "SS+"
        12 -> "SSS"
        13 -> "SSS+"
        else -> null
    }

    fun fullCombo(code: Int): String? = when (code) {
        1 -> "fc"
        2 -> "fcp"
        3 -> "ap"
        4 -> "app"
        else -> null
    }

    fun fullSync(code: Int): String? = when (code) {
        1 -> "fs"
        2 -> "fsp"
        3 -> "fsd"
        4 -> "fsdp"
        5 -> "sync"
        else -> null
    }

    fun stableRecordId(profileId: String, playlog: OtogamePlaylog): String =
        recordId(profileId.lowercase(), playlog)

    // The migration preserves hashes made with the profile UUID's original case.
    fun isPreviouslyImported(profileId: String, playlog: OtogamePlaylog, existingIds: Set<String>): Boolean =
        stableRecordId(profileId, playlog) in existingIds || recordId(profileId, playlog) in existingIds

    private fun recordId(profileId: String, playlog: OtogamePlaylog): String {
        val identity = listOf(
            profileId,
            "otogame",
            playlog.music.musicId,
            difficultyCode(playlog).toString(),
            playlog.playDate.toString(),
            playlog.trackNo.toString(),
            playlog.achievement.toString(),
            playlog.deluxeScore.toString(),
        ).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(StandardCharsets.UTF_8))
        // Match iOS OtogameImportPolicy and the portable backup UUID contract.
        digest[6] = ((digest[6].toInt() and 0x0f) or 0x50).toByte()
        digest[8] = ((digest[8].toInt() and 0x3f) or 0x80).toByte()
        val bytes = ByteBuffer.wrap(digest)
        return UUID(bytes.long, bytes.long).toString()
    }
}
