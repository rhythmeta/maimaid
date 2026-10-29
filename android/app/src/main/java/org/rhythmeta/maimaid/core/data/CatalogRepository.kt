package org.rhythmeta.maimaid.core.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.rhythmeta.maimaid.core.database.CatalogDao
import org.rhythmeta.maimaid.core.database.GameVersionEntity
import org.rhythmeta.maimaid.core.database.MaimaidDatabase
import org.rhythmeta.maimaid.core.database.SheetEntity
import org.rhythmeta.maimaid.core.database.SongAliasEntity
import org.rhythmeta.maimaid.core.database.SongCategoryEntity
import org.rhythmeta.maimaid.core.database.SongEntity
import org.rhythmeta.maimaid.core.network.StaticBundleClient
import java.text.Normalizer

class CatalogRepository(
    private val database: MaimaidDatabase,
    private val client: StaticBundleClient,
    private val syncStateStore: CatalogSyncStateStore,
    private val presetAvatarRepository: PresetAvatarRepository,
    private val chartFitStore: ChartFitStore,
    private val danStore: DanStore,
) {
    private val catalogDao: CatalogDao = database.catalogDao()
    private val syncMutex = Mutex()
    private val mutableSyncStatus = MutableStateFlow<CatalogSyncStatus>(CatalogSyncStatus.Idle)
    private val mutableChartFit = MutableSharedFlow<StaticBundleResponse.ChartFitPayload>(replay = 1)
    private val mutableDanCategories = MutableSharedFlow<List<DanCategory>>(replay = 1)
    private val mutableKnownDownloadTotalBytes = MutableStateFlow<Long?>(null)

    val syncStatus = mutableSyncStatus.asStateFlow()

    /** Total bytes of a whole first-run download, once a manifest carrying `sizes` has been seen. */
    val knownDownloadTotalBytes: StateFlow<Long?> = mutableKnownDownloadTotalBytes.asStateFlow()
    val songCount: Flow<Int> = catalogDao.observeSongCount()
    val sheetCount: Flow<Int> = catalogDao.observeSheetCount()
    val featuredSongs: Flow<List<SongEntity>> = catalogDao.observeFeaturedSongs(limit = 6)
    val songs: Flow<List<SongEntity>> = catalogDao.observeSongs()
    val sheets: Flow<List<SheetEntity>> = catalogDao.observeSheets()
    val aliases: Flow<List<SongAliasEntity>> = catalogDao.observeAliases()
    val versions: Flow<List<GameVersionEntity>> = catalogDao.observeVersions()
    val categories: Flow<List<SongCategoryEntity>> = catalogDao.observeCategories()
    val chartFit: Flow<StaticBundleResponse.ChartFitPayload> = mutableChartFit.onStart {
        emit(chartFitStore.load())
    }
    val danCategories: Flow<List<DanCategory>> = mutableDanCategories.onStart {
        emit(danStore.load())
    }

    fun observeSheetsForSong(songIdentifier: String): Flow<List<SheetEntity>> =
        catalogDao.observeSheetsForSong(songIdentifier)

    fun observeAliasesForSong(songIdentifier: String): Flow<List<String>> =
        catalogDao.observeAliasesForSong(songIdentifier)

    suspend fun fetchStaticManifest(): StaticManifest = client.fetchManifest()

    /**
     * Resolves the download size before the user starts, so onboarding can state how much the whole
     * first-run download will be. Deliberately leaves [syncStatus] untouched.
     */
    suspend fun prefetchManifestForDownloadSize() {
        val manifest = try {
            client.fetchManifest()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return
        }
        publishKnownDownloadTotalBytes(manifest)
    }

    private fun publishKnownDownloadTotalBytes(manifest: StaticManifest) {
        manifest.sizes?.totalBytes?.takeIf { it > 0L }?.let { mutableKnownDownloadTotalBytes.value = it }
    }

    suspend fun currentStaticDataMd5(): String? = syncStateStore.currentMd5()

    suspend fun isImageAssetsSyncPending(): Boolean = syncStateStore.isImageAssetsSyncPending()

    // Existing installations may predate the sync metadata store, while a populated catalog
    // remains sufficient to enter the app and refresh in the background.
    suspend fun hasCompletedInitialSync(): Boolean = catalogDao.songCount() > 0

    suspend fun setFavorite(songIdentifier: String, isFavorite: Boolean) {
        catalogDao.setFavorite(songIdentifier, isFavorite)
    }

    /**
     * Downloads and imports the catalog only. Cover and preset-avatar images are deliberately left
     * out: they are a cache warm-up rather than a correctness requirement (every cover falls back to
     * a network fetch), so they must never block entering the app. [CatalogAssetSyncer] picks them
     * up afterwards and sets [CatalogSyncStateStore.isImageAssetsSyncPending] to false when done.
     */
    suspend fun refresh(force: Boolean = false) = syncMutex.withLock {
        val localSongCount = catalogDao.songCount()
        mutableSyncStatus.value = CatalogSyncStatus.Checking
        try {
            val manifest = client.fetchManifest()
            publishKnownDownloadTotalBytes(manifest)
            val currentMd5 = syncStateStore.currentMd5()
            if (
                localSongCount > 0 &&
                currentMd5 == manifest.md5 &&
                syncStateStore.isCurrentSchema() &&
                chartFitStore.hasCache() &&
                danStore.hasCache() &&
                !force
            ) {
                mutableSyncStatus.value = CatalogSyncStatus.Ready(manifest.version, fromCache = true)
                return@withLock
            }

            val bundleReporter = CatalogSyncProgressReporter(
                stage = CatalogSyncStage.CatalogBundle,
                overallStart = 0f,
                overallEnd = 0.7f,
                useByteProgress = true,
                // Scope the denominator to this stage: onboarding closes as soon as the catalog is
                // imported, so a whole-download denominator here would stall the bar at a few percent.
                byteBudget = CatalogSyncByteBudget(totalBytes = manifest.sizes?.bundleBytes),
                onProgress = { progress ->
                    mutableSyncStatus.value = CatalogSyncStatus.Downloading(manifest.version, progress)
                },
            )
            bundleReporter.start(totalItems = 1)
            val bundle = client.fetchBundle(manifest, bundleReporter::onTransfer)
            bundleReporter.complete()
            showSimpleStage(
                version = bundle.version,
                stage = CatalogSyncStage.ImportingCatalog,
                fraction = 0.9f,
                downloadedBytes = bundleReporter.transferredBytes(),
                totalBytes = manifest.sizes?.bundleBytes,
            )
            applyBundle(bundle)
            // Persist the avatar catalog only; the images themselves belong to the asset syncer.
            try {
                presetAvatarRepository.refresh(bundle.payload.resources.presetAvatars?.icons)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // The avatar list is a nice-to-have for the asset sync; never fail the catalog on it.
            }
            // The catalog is committed at this point, so record it before any image work starts.
            // A failed image download must not force the whole catalog to be fetched again.
            syncStateStore.save(bundle.version, bundle.md5)
            syncStateStore.setImageAssetsSyncPending(true)
            mutableSyncStatus.value = CatalogSyncStatus.Ready(bundle.version, fromCache = false)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mutableSyncStatus.value = CatalogSyncStatus.Failed(
                message = error.message ?: error::class.java.simpleName,
                hasLocalCatalog = localSongCount > 0,
            )
        }
    }

    private fun showSimpleStage(
        version: String,
        stage: CatalogSyncStage,
        fraction: Float,
        downloadedBytes: Long = 0L,
        totalBytes: Long? = null,
    ) {
        mutableSyncStatus.value = CatalogSyncStatus.Downloading(
            version = version,
            progress = CatalogSyncProgress(
                stage = stage,
                overallFraction = fraction,
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes,
            ),
        )
    }

    private suspend fun applyBundle(bundle: StaticBundleResponse) {
        val resources = bundle.payload.resources
        val catalog = resources.catalog
        val providerIds = buildProviderIdsByTitle(resources.songIds)
        val utageStats = UtageChartStatsIndex(resources.utageStats)
        val favoriteIds = catalogDao.favoriteSongIds().toSet()
        val providerIdToSongIdentifier = mutableMapOf<Int, String>()

        val songs = catalog.songs.mapIndexed { index, remote ->
            SongEntity(
                songIdentifier = remote.songId,
                category = remote.category.orEmpty(),
                title = preserveNonEmptyText(remote.title),
                artist = remote.artist.orEmpty(),
                imageName = remote.imageName?.trim().orEmpty(),
                version = remote.version,
                releaseDate = remote.releaseDate,
                sortOrder = index,
                bpm = remote.bpm,
                isNew = remote.isNew ?: false,
                isLocked = remote.isLocked ?: false,
                comment = remote.comment,
                isFavorite = remote.songId in favoriteIds,
                isRemoved = false,
            )
        }

        val sheets = catalog.songs.flatMap { remote ->
            val ids = providerIds[remote.title.orEmpty()]
                ?: providerIds[normalizeTitle(remote.title.orEmpty())]
                ?: emptyList()
            remote.sheets.map { sheet ->
                val providerSongId = ProviderSongIdResolver.resolve(sheet.internalId, sheet.type)
                    ?: selectProviderId(ids, sheet.type)
                val intlOverride = sheet.regionOverrides?.get("intl")
                val cnOverride = sheet.regionOverrides?.get("cn")
                val utageStat = if (sheet.type.equals("utage", ignoreCase = true)) {
                    utageStats.resolve(
                        providerSongId = providerSongId,
                        songTitle = remote.title.orEmpty(),
                        songIdentifier = remote.songId,
                        sheetDifficulty = sheet.difficulty,
                        sheetLevel = sheet.level,
                    )
                } else {
                    null
                }
                val utageNoteTypes = utageStat?.noteTypes
                if (providerSongId > 0) {
                    providerIdToSongIdentifier[providerSongId] = remote.songId
                }
                SheetEntity(
                    sheetKey = sheetKey(remote.songId, sheet.type, sheet.difficulty),
                    songIdentifier = remote.songId,
                    type = sheet.type,
                    difficulty = sheet.difficulty,
                    version = sheet.version,
                    level = sheet.level,
                    levelValue = sheet.levelValue,
                    internalLevel = sheet.internalLevel,
                    internalLevelValue = sheet.internalLevelValue,
                    noteDesigner = sheet.noteDesigner,
                    intlVersion = intlOverride?.version,
                    intlLevel = intlOverride?.level,
                    intlLevelValue = intlOverride?.levelValue,
                    intlInternalLevel = intlOverride?.internalLevel,
                    intlInternalLevelValue = intlOverride?.internalLevelValue,
                    cnVersion = cnOverride?.version,
                    cnLevel = cnOverride?.level,
                    cnLevelValue = cnOverride?.levelValue,
                    cnInternalLevel = cnOverride?.internalLevel,
                    cnInternalLevelValue = cnOverride?.internalLevelValue,
                    multiverInternalLevelValue = sheet.multiverInternalLevelValue,
                    tap = utageNoteTypes?.tap ?: sheet.noteCounts?.tap,
                    hold = utageNoteTypes?.hold ?: sheet.noteCounts?.hold,
                    slide = utageNoteTypes?.slide ?: sheet.noteCounts?.slide,
                    touch = utageNoteTypes?.touch ?: sheet.noteCounts?.touch,
                    breakCount = utageNoteTypes?.breakCount ?: sheet.noteCounts?.breakCount,
                    total = utageStat?.notes ?: sheet.noteCounts?.total,
                    regionJp = sheet.regions?.get("jp") ?: false,
                    regionIntl = sheet.regions?.get("intl") ?: false,
                    regionUsa = sheet.regions?.get("usa") ?: false,
                    regionCn = sheet.regions?.get("cn") ?: false,
                    providerSongId = providerSongId,
                    isRemoved = false,
                )
            }
        }

        val aliases = resources.aliases?.aliases.orEmpty().flatMap { item ->
            val songIdentifiers = ProviderSongIdResolver.relatedIds(item.songId)
                .mapNotNull(providerIdToSongIdentifier::get)
                .distinct()
            songIdentifiers.flatMap { songIdentifier ->
                item.aliases
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .distinctBy { it.lowercase() }
                    .map { alias -> SongAliasEntity(songIdentifier, alias) }
            }
        }.distinctBy { "${it.songIdentifier}\u0000${it.alias.lowercase()}" }

        database.withTransaction {
            catalogDao.markAllSongsRemoved()
            catalogDao.markAllSheetsRemoved()
            catalogDao.upsertSongs(songs)
            catalogDao.upsertSheets(sheets)
            catalogDao.upsertCategories(
                catalog.categories.mapIndexed { index, item ->
                    SongCategoryEntity(item.category, index)
                },
            )
            catalogDao.upsertVersions(
                catalog.versions.mapIndexed { index, item ->
                    GameVersionEntity(item.version, item.abbr, item.releaseDate, index)
                },
            )
            catalogDao.deleteAliases()
            catalogDao.upsertAliases(aliases)
        }
        val chartFit = resources.chartFit ?: resources.legacyChartFit
            ?: StaticBundleResponse.ChartFitPayload()
        chartFitStore.save(chartFit)
        mutableChartFit.emit(chartFit)
        danStore.save(resources.danInfo)
        mutableDanCategories.emit(resources.danInfo)
    }

    private fun buildProviderIdsByTitle(items: List<StaticBundleResponse.SongIdItem>): Map<String, List<Int>> {
        val result = mutableMapOf<String, MutableList<Int>>()
        items.forEach { item ->
            result.getOrPut(item.name) { mutableListOf() }.add(item.id)
            result.getOrPut(normalizeTitle(item.name)) { mutableListOf() }.add(item.id)
        }
        return result
    }

    private fun selectProviderId(ids: List<Int>, type: String): Int = when (type.lowercase()) {
        "utage" -> ids.firstOrNull { it >= 100_000 }
        "dx" -> ids.firstOrNull { it in 10_000..<100_000 }
        "std", "standard" -> ids.firstOrNull { it in 1..<10_000 }
        else -> null
    } ?: 0

    private fun normalizeTitle(value: String): String {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .replace(WhitespaceRegex, " ")
            .trim()
            .lowercase()
        return normalized.ifEmpty { if (value.isNotEmpty()) " " else "" }
    }

    private fun preserveNonEmptyText(value: String?): String {
        if (value == null) return ""
        val trimmed = value.trim()
        return trimmed.ifEmpty { value.ifEmpty { "" } }
    }

    companion object {
        private val WhitespaceRegex = Regex("\\s+")

        fun sheetKey(songIdentifier: String, type: String, difficulty: String): String =
            "${songIdentifier.length}:$songIdentifier|${type.lowercase()}|${difficulty.lowercase()}"
    }
}
