package org.rhythmeta.maimaid.core.chart

import android.content.Context
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.rhythmeta.maimaid.core.chart.audio.ChartAudioCacheLru
import org.rhythmeta.maimaid.core.data.ChartAssetIndex
import org.rhythmeta.maimaid.core.network.StaticBundleClient

/**
 * Verified on-disk cache for chart tracks. Only the downloaded MP3 is cached, at
 * `filesDir/chart-audio/<sha256>.<ext>`; the decoded PCM is kept in memory by the mixer and never
 * written here.
 *
 * This replaces the old `ChartAudioPlayer` download helper now that MediaPlayer is gone: the
 * preview always plays through the PCM engine. The directory holds at most
 * [ChartAudioCacheLru.MAX_TRACKS] songs; preparing a new one evicts the least recently used one.
 */
object ChartTrackCache {
    fun cacheDirectory(context: Context): File =
        File(context.applicationContext.filesDir, "chart-audio").apply { mkdirs() }

    fun cachedFile(context: Context, asset: ChartAssetIndex.File): File? {
        val extension = asset.path.substringAfterLast('.', missingDelimiterValue = "mp3")
        val file = File(cacheDirectory(context), "${asset.sha256.lowercase()}.$extension")
        return file.takeIf { it.isFile && it.length() > 0L }
    }

    /**
     * Downloads [asset] while forwarding its bytes to [onChunk], so decoding can start immediately.
     * The verified file still lands in the cache under its usual name.
     */
    suspend fun stream(
        context: Context,
        asset: ChartAssetIndex.File,
        client: StaticBundleClient,
        onChunk: (ByteArray, Int, Int) -> Unit,
    ): File {
        val extension = asset.path.substringAfterLast('.', missingDelimiterValue = "mp3")
        val destination = File(cacheDirectory(context), "${asset.sha256.lowercase()}.$extension")
        client.streamChartAssetToFile(asset, destination, onChunk)
        return withContext(Dispatchers.IO) {
            val directory = cacheDirectory(context)
            destination.setLastModified(System.currentTimeMillis())
            ChartAudioCacheLru.purgeLegacyPcmFiles(directory)
            ChartAudioCacheLru.prune(directory, keepKeys = setOf(asset.sha256.lowercase()))
            destination
        }
    }

    /** Downloads [asset] unless a byte-identical cached copy already exists. */
    suspend fun ensure(
        context: Context,
        asset: ChartAssetIndex.File,
        client: StaticBundleClient,
    ): File {
        val extension = asset.path.substringAfterLast('.', missingDelimiterValue = "mp3")
        val destination = File(cacheDirectory(context), "${asset.sha256.lowercase()}.$extension")
        if (!ChartAudioCacheIntegrity.matches(destination, asset.size, asset.sha256)) {
            try {
                client.downloadChartAssetToFile(asset, destination)
            } catch (error: CancellationException) {
                throw error
            }
        }
        // Mark the song as used and keep chart-audio/ to the configured song count. Both the MP3
        // and PCM caches share this directory, so the eviction is done here as well as after the
        // PCM is written.
        return withContext(Dispatchers.IO) {
            val directory = cacheDirectory(context)
            destination.setLastModified(System.currentTimeMillis())
            // Drop PCM files left behind by older versions; nothing writes them any more.
            ChartAudioCacheLru.purgeLegacyPcmFiles(directory)
            ChartAudioCacheLru.prune(directory, keepKeys = setOf(asset.sha256.lowercase()))
            destination
        }
    }
}

/** SHA-256 + size verification for the downloaded chart track. */
internal object ChartAudioCacheIntegrity {
    fun matches(file: File, expectedSize: Long, expectedSha256: String): Boolean {
        if (!file.isFile || file.length() != expectedSize) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1_024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actualSha256 = digest.digest().joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
        return actualSha256.equals(expectedSha256, ignoreCase = true)
    }
}
