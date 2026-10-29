package org.rhythmeta.maimaid.core.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.rhythmeta.maimaid.core.data.CatalogSyncByteBudget
import org.rhythmeta.maimaid.core.data.CatalogSyncProgress
import org.rhythmeta.maimaid.core.data.CatalogSyncProgressReporter
import org.rhythmeta.maimaid.core.data.CatalogSyncStage
import org.rhythmeta.maimaid.core.data.CatalogSyncStateStore
import org.rhythmeta.maimaid.core.data.CoverImageStore
import org.rhythmeta.maimaid.core.data.PresetAvatarRepository
import org.rhythmeta.maimaid.core.data.StaticAssetConfiguration
import org.rhythmeta.maimaid.core.data.StaticAssetUrls
import org.rhythmeta.maimaid.core.data.StaticManifestSizes
import org.rhythmeta.maimaid.core.database.MaimaidDatabase
import org.rhythmeta.maimaid.core.network.StaticBundleClient

/**
 * Warms the on-disk cover and preset-avatar caches.
 *
 * This is intentionally decoupled from [org.rhythmeta.maimaid.core.data.CatalogRepository.refresh]:
 * the catalog becomes usable as soon as the bundle is imported, while images are a best-effort
 * cache that the UI can always fall back to over the network.
 */
class CatalogAssetSyncer(
    database: MaimaidDatabase,
    private val client: StaticBundleClient,
    private val coverImageStore: CoverImageStore,
    private val presetAvatarRepository: PresetAvatarRepository,
    private val syncStateStore: CatalogSyncStateStore,
) {
    private val catalogDao = database.catalogDao()
    private val mutex = Mutex()
    private val mutableProgress = MutableStateFlow<CatalogSyncProgress?>(null)

    /** Non-null only while a run is in flight, so observers can drive a progress notification. */
    val progress: StateFlow<CatalogSyncProgress?> = mutableProgress.asStateFlow()

    /**
     * Downloads every missing cover and preset avatar.
     *
     * Per-image failures are tolerated by the image stores, so a run that reaches the end counts as
     * a success even when some files could not be fetched. Only a failure to reach the CDN at all
     * keeps [CatalogSyncStateStore.isImageAssetsSyncPending] set, which lets the next launch retry.
     */
    suspend fun sync(): Boolean = mutex.withLock {
        var reachedEnd = false
        try {
            // Reconfigures StaticAssetUrls for a cold process, but a manifest hiccup must not stop
            // the download when the catalog sync already resolved the CDN in this process.
            val manifest = try {
                client.fetchManifest()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            val assets = manifest?.assets ?: StaticAssetUrls.currentConfiguration()
            val sizes = manifest?.sizes

            val coverReporter = downloadMissingCovers(assets, sizes)
            downloadMissingPresetAvatars(assets, sizes, coverReporter.transferredBytes())

            reachedEnd = true
            true
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        } finally {
            if (reachedEnd) syncStateStore.setImageAssetsSyncPending(false)
            mutableProgress.value = null
        }
    }

    private suspend fun downloadMissingCovers(
        assets: StaticAssetConfiguration?,
        sizes: StaticManifestSizes?,
    ): CatalogSyncProgressReporter {
        val imageNames = catalogDao.imageNames()
        val reporter = CatalogSyncProgressReporter(
            stage = CatalogSyncStage.Covers,
            overallStart = 0f,
            overallEnd = 0.85f,
            byteBudget = CatalogSyncByteBudget(
                // Start from what is already on disk so a resumed run or a settings-page update
                // reports "how much of the catalog I have", not "how much moved this run".
                baselineBytes = (sizes?.bundleBytes ?: 0L) + coverImageStore.installedBytes(imageNames),
                totalBytes = sizes?.totalBytes,
            ),
            onProgress = { mutableProgress.value = it },
        )
        reporter.start()
        coverImageStore.downloadMissing(imageNames, reporter::onItemsProgress) { name, destination ->
            client.downloadCover(name, destination, assets, reporter::onTransfer)
        }
        reporter.complete()
        return reporter
    }

    private suspend fun downloadMissingPresetAvatars(
        assets: StaticAssetConfiguration?,
        sizes: StaticManifestSizes?,
        coversTransferredBytes: Long,
    ) {
        val avatars = presetAvatarRepository.list()
        val reporter = CatalogSyncProgressReporter(
            stage = CatalogSyncStage.PresetAvatars,
            overallStart = 0.85f,
            overallEnd = 1f,
            byteBudget = CatalogSyncByteBudget(
                baselineBytes = coversTransferredBytes + presetAvatarRepository.installedBytes(avatars),
                totalBytes = sizes?.totalBytes,
            ),
            onProgress = { mutableProgress.value = it },
        )
        reporter.start()
        presetAvatarRepository.downloadMissing(avatars, reporter::onItemsProgress) { id, destination ->
            client.downloadPresetAvatar(id, destination, assets, reporter::onTransfer)
        }
        reporter.complete()
    }
}
