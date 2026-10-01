package org.rhythmeta.maimaid.core.backup

import android.content.Context
import android.os.Build
import androidx.room.withTransaction
import com.google.protobuf.ByteString
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.rhythmeta.backup.proto.BackupProtocol as Proto
import org.rhythmeta.maimaid.BuildConfig
import org.rhythmeta.maimaid.core.data.AppPreferencesRepository
import org.rhythmeta.maimaid.core.data.BackendSessionManager
import org.rhythmeta.maimaid.core.data.ProfileAvatarStore
import org.rhythmeta.maimaid.core.database.*

@Serializable
data class CloudBackup(val id: String, val game: String, val formatVersion: Int, val size: Long, val uncompressedSize: Long, val sha256: String, val deviceName: String, val profileCount: Int, val committedAt: String, val downloadUrl: String)
@Serializable private data class BackupList(val backups: List<CloudBackup>)
@Serializable private data class Upload(val id: String, val uploadUrl: String, val headers: Map<String, String>)

class CloudBackupService(
    context: Context,
    private val database: MaimaidDatabase,
    private val preferences: AppPreferencesRepository,
    private val avatars: ProfileAvatarStore,
    private val session: BackendSessionManager,
) {
    private val directory = File(context.noBackupFilesDir, "rhythmeta-backups").apply { mkdirs() }
    private val rollback = File(directory, "before-restore.pb.gz")
    private val journal = File(directory, "restore-pending")
    private val foreignSettings = File(directory, "foreign-settings.pb")
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val base = "maimaid/v1/backups"

    suspend fun list(): List<CloudBackup> = json.decodeFromJsonElement(BackupList.serializer(), session.authorizedRequest(base)).backups

    suspend fun backup() = mutex.withLock {
        recoverUnlocked()
        val snapshot = export()
        val bytes = withContext(Dispatchers.IO) { BackupCodec.encode(snapshot) }
        val metadata = buildJsonObject {
            put("formatVersion", 1); put("size", bytes.size); put("uncompressedSize", snapshot.serializedSize)
            put("sha256", BackupCodec.sha256(bytes)); put("deviceName", "${Build.MANUFACTURER} ${Build.MODEL}".take(128))
            put("clientVersion", BuildConfig.VERSION_NAME); put("profileCount", snapshot.profilesCount)
        }
        val upload = json.decodeFromJsonElement(Upload.serializer(), session.authorizedRequest(base, "POST", metadata))
        withContext(Dispatchers.IO) {
            val connection = secureConnection(upload.uploadUrl)
            try {
                connection.requestMethod = "PUT"; connection.doOutput = true
                connection.setFixedLengthStreamingMode(bytes.size)
                upload.headers.forEach(connection::setRequestProperty)
                connection.outputStream.use { it.write(bytes) }
                check(connection.responseCode in 200..299) { "Backup upload failed (${connection.responseCode}). Retry the backup." }
            } finally { connection.disconnect() }
        }
        session.authorizedRequest("$base/${upload.id}/commit", "POST")
    }

    suspend fun restore(backup: CloudBackup) = mutex.withLock {
        recoverUnlocked()
        require(backup.game == "maimaid" && backup.formatVersion == 1 && backup.size in 1..BackupCodec.MAX_COMPRESSED.toLong())
        val snapshot = withContext(Dispatchers.IO) {
            val connection = secureConnection(backup.downloadUrl)
            val bytes = try {
                check(connection.responseCode == 200) { "Backup download failed." }
                connection.inputStream.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(65536)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(output.size().toLong() + count <= BackupCodec.MAX_COMPRESSED) { "Backup exceeds the size limit." }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
            } finally { connection.disconnect() }
            require(bytes.size.toLong() == backup.size && BackupCodec.sha256(bytes) == backup.sha256) { "Backup checksum does not match." }
            BackupCodec.decode(bytes, backup.uncompressedSize)
        }
        validateCatalog(snapshot)
        val local = export()
        withContext(Dispatchers.IO) {
            val staging = File(directory, "rollback.tmp")
            staging.outputStream().use { it.write(BackupCodec.encode(local)); it.fd.sync() }
            check(staging.renameTo(rollback)) { "Cannot save local rollback copy." }
            journal.outputStream().use { it.write(1); it.fd.sync() }
        }
        withContext(NonCancellable) {
            try {
                replace(snapshot)
                check(journal.delete()) { "Cannot complete restore journal." }
            } catch (error: Exception) {
                try { replace(local); journal.delete() } catch (rollbackError: Exception) { error.addSuppressed(rollbackError) }
                throw error
            }
        }
    }

    suspend fun recover() = mutex.withLock { recoverUnlocked() }
    private suspend fun recoverUnlocked() {
        if (!journal.exists()) return
        val snapshot = withContext(Dispatchers.IO) { BackupCodec.decode(rollback.readBytes()) }
        replace(snapshot)
        check(journal.delete()) { "Cannot complete recovery." }
    }

    private suspend fun export(): Proto.Snapshot {
        val snapshot = database.withTransaction {
            val output = Proto.Snapshot.newBuilder().setMagic("RHYTHMETA_BACKUP").setFormatVersion(1).setGame("maimaid")
                .setCreatedAt(System.currentTimeMillis()).setClientVersion(BuildConfig.VERSION_NAME)
            for (profile in database.profileDao().profiles()) {
                val avatar = profile.avatarPath?.let { path -> withContext(Dispatchers.IO) {
                    val file = File(path); require(file.length() <= 16 * 1024 * 1024); file.readBytes()
                } }
                output.addProfiles(Proto.Profile.newBuilder().setId(profile.id).setName(profile.name).setServer(profile.server)
                    .setAvatar(ByteString.copyFrom(avatar ?: byteArrayOf())).setAvatarUrl(profile.avatarUrl.orEmpty())
                    .setActive(profile.isActive).setCreatedAt(profile.createdAt).setDfUsername(profile.dfUsername)
                    .setPlayerRating(profile.playerRating).setPlate(profile.plate.orEmpty())
                    .setLastImportDf(profile.lastImportDateDf ?: 0).setLastImportLxns(profile.lastImportDateLxns ?: 0)
                    .setB35Count(profile.b35Count).setB15Count(profile.b15Count).setB35RecLimit(profile.b35RecLimit).setB15RecLimit(profile.b15RecLimit))
                database.scoreDao().scores(profile.id).forEach { output.addScores(result(it)) }
                database.scoreDao().playRecords(profile.id).forEach { row -> output.addPlayRecords(Proto.PlayRecord.newBuilder().setId(row.id).setResult(result(ScoreEntity(row.profileId,row.sheetKey,row.achievement,row.rank,row.dxScore,row.fc,row.fs,row.playedAt)))) }
            }
            val collections = database.songCollectionDao().collectionsIncludingDeleted().filter { it.deletedAt == null }
            val ids = collections.map { it.id }.toSet()
            collections.forEach { output.addCollections(Proto.Collection.newBuilder().setId(it.id).setName(it.name).setSortIndex(it.sortIndex).setCreatedAt(it.createdAt).setUpdatedAt(it.updatedAt)) }
            database.songCollectionDao().itemsIncludingDeleted().filter { it.deletedAt == null && it.collectionId in ids }.forEach {
                output.addCollectionItems(Proto.CollectionItem.newBuilder().setId(it.id).setCollectionId(it.collectionId).setSongId(it.songId).setChartType(it.chartType).setDifficulty(it.difficulty).setPosition(it.position).setCreatedAt(it.createdAt).setUpdatedAt(it.updatedAt))
            }
            output.addAllFavoriteSongIds(database.catalogDao().favoriteSongIds())
            output
        }
        snapshot.addAllSettings(preferences.exportBackupSettings())
        if (foreignSettings.exists()) snapshot.addAllSettings(Proto.Snapshot.parseFrom(foreignSettings.readBytes()).settingsList)
        return snapshot.build().also(BackupCodec::validate)
    }

    private fun result(row: ScoreEntity): Proto.Score = Proto.Score.newBuilder().setProfileId(row.profileId).setChartKey(row.sheetKey)
        .setAchievement(row.achievement).setRank(row.rank).setDxScore(row.dxScore).setFc(row.fc.orEmpty()).setFs(row.fs.orEmpty()).setAchievedAt(row.achievedAt).build()

    private suspend fun validateCatalog(snapshot: Proto.Snapshot) {
        val charts = database.catalogDao().sheets().map { it.sheetKey }.toSet()
        val required = snapshot.scoresList.map { it.chartKey } + snapshot.playRecordsList.map { it.result.chartKey }
        val songs = database.catalogDao().songIdentifiers().toSet()
        require(snapshot.favoriteSongIdsList.all { it in songs }) { "Some favorite songs are missing. Update the catalog before restoring." }
        require(required.all { it in charts }) { "Some charts are missing. Update the song catalog before restoring." }
    }

    private suspend fun replace(snapshot: Proto.Snapshot) {
        BackupCodec.validate(snapshot); validateCatalog(snapshot)
        val newAvatars = mutableListOf<String>()
        try {
            val profiles = snapshot.profilesList.map {
                val path = if (it.avatar.isEmpty) null else checkNotNull(avatars.saveRemote(it.avatar.toByteArray(), it.id)).also(newAvatars::add)
                UserProfileEntity(it.id,it.name,it.server,path,it.avatarUrl.ifEmpty { null },it.active,it.createdAt,it.dfUsername,it.playerRating,it.plate.ifEmpty { null },it.lastImportDf.takeIf { value -> value > 0 },it.lastImportLxns.takeIf { value -> value > 0 },it.b35Count,it.b15Count,it.b35RecLimit,it.b15RecLimit)
            }
            database.withTransaction {
                database.songCollectionDao().deleteAllItems(); database.songCollectionDao().deleteAllCollections()
                database.profileDao().deleteAll()
                profiles.forEach { database.profileDao().upsert(it) }
                snapshot.scoresList.forEach { database.scoreDao().upsertScore(entity(it)) }
                snapshot.playRecordsList.forEach { val r = it.result; database.scoreDao().upsertPlayRecord(PlayRecordEntity(it.id,r.profileId,r.chartKey,r.achievement,r.rank,r.dxScore,r.fc.ifEmpty { null },r.fs.ifEmpty { null },r.achievedAt)) }
                snapshot.collectionsList.forEach { database.songCollectionDao().upsertCollection(SongCollectionEntity(it.id,it.name,it.sortIndex,it.createdAt,it.updatedAt)) }
                snapshot.collectionItemsList.forEach { database.songCollectionDao().upsertItem(SongCollectionItemEntity(it.id,it.collectionId,it.songId,it.chartType,it.difficulty,it.position,it.createdAt,it.updatedAt)) }
                database.catalogDao().favoriteSongIds().forEach { database.catalogDao().setFavorite(it,false) }
                snapshot.favoriteSongIdsList.forEach { database.catalogDao().setFavorite(it,true) }
            }
            preferences.restoreBackupSettings(snapshot.settingsList)
            val foreign = Proto.Snapshot.newBuilder().addAllSettings(snapshot.settingsList.filterNot { it.key.startsWith("android.maimaid.") }).build()
            withContext(Dispatchers.IO) { foreignSettings.outputStream().use { it.write(foreign.toByteArray()); it.fd.sync() } }
        } catch (error: Exception) { newAvatars.forEach(avatars::deleteStored); throw error }
    }
    private fun entity(row: Proto.Score) = ScoreEntity(row.profileId,row.chartKey,row.achievement,row.rank,row.dxScore,row.fc.ifEmpty { null },row.fs.ifEmpty { null },row.achievedAt)
    private fun secureConnection(url: String): HttpURLConnection {
        require(URL(url).protocol == "https")
        return (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 15000; readTimeout = 120000; instanceFollowRedirects = false; setRequestProperty("Accept-Encoding", "identity") }
    }
}
