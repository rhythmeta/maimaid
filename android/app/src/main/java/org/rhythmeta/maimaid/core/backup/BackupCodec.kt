package org.rhythmeta.maimaid.core.backup

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import org.rhythmeta.backup.proto.BackupProtocol as Proto

object BackupCodec {
    const val MAX_COMPRESSED = 64 * 1024 * 1024
    const val MAX_RAW = 512 * 1024 * 1024
    fun encode(snapshot: Proto.Snapshot): ByteArray {
        validate(snapshot)
        require(snapshot.serializedSize <= MAX_RAW) { "Backup is too large." }
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { snapshot.writeTo(it) }
        return output.toByteArray().also { require(it.size <= MAX_COMPRESSED) { "Backup is too large." } }
    }
    fun decode(bytes: ByteArray, expectedSize: Long? = null): Proto.Snapshot {
        require(bytes.size <= MAX_COMPRESSED) { "Backup is too large." }
        val output = ByteArrayOutputStream()
        GZIPInputStream(bytes.inputStream()).use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size().toLong() + count <= MAX_RAW) { "Expanded backup is too large." }
                output.write(buffer, 0, count)
            }
        }
        expectedSize?.let { require(it == output.size().toLong()) { "Backup size does not match." } }
        return Proto.Snapshot.parseFrom(output.toByteArray()).also(::validate)
    }
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun validate(snapshot: Proto.Snapshot) {
        require(snapshot.magic == "RHYTHMETA_BACKUP" && snapshot.formatVersion == 1 && snapshot.game == "maimaid") { "Unsupported backup format or game." }
        require(snapshot.profilesCount in 1..10000 && snapshot.scoresCount <= 1_000_000 && snapshot.playRecordsCount <= 2_000_000) { "Invalid backup record count." }
        fun id(value: String) { require(runCatching { UUID.fromString(value).toString() == value.lowercase() }.getOrDefault(false)) { "Invalid record ID." } }
        fun unique(values: List<String>) { require(values.size == values.toSet().size) { "Duplicate record IDs." } }
        val profiles = snapshot.profilesList.map { it.id }
        unique(profiles); profiles.forEach(::id)
        require(snapshot.profilesList.count { it.active } == 1) { "A backup must have one active profile." }
        snapshot.profilesList.forEach { require(it.avatar.size() <= 16 * 1024 * 1024 && it.name.length <= 200) }
        val profileIds = profiles.toSet()
        fun result(score: Proto.Score) {
            require(score.profileId in profileIds && score.chartKey.isNotBlank() && score.chartKey.length <= 1024)
            require(score.achievement.isFinite() && score.achievement in 0.0..101.0 && score.dxScore >= 0)
        }
        snapshot.scoresList.forEach(::result)
        unique(snapshot.scoresList.map { "${it.profileId}|${it.chartKey}" })
        unique(snapshot.playRecordsList.map { it.id })
        snapshot.playRecordsList.forEach { id(it.id); require(it.hasResult()); result(it.result) }
        unique(snapshot.collectionsList.map { it.id }); snapshot.collectionsList.forEach { id(it.id) }
        val collections = snapshot.collectionsList.map { it.id }.toSet()
        unique(snapshot.collectionItemsList.map { it.id })
        unique(snapshot.collectionItemsList.map { "${it.collectionId}|${it.songId}|${it.chartType}|${it.difficulty}" })
        snapshot.collectionItemsList.forEach { id(it.id); require(it.collectionId in collections && it.songId.isNotBlank()) }
        require(snapshot.settingsCount <= 1000)
        unique(snapshot.settingsList.map { it.key })
    }
}
