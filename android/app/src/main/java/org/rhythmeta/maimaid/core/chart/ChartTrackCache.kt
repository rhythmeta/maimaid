package org.rhythmeta.maimaid.core.chart

import android.content.Context
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import org.rhythmeta.maimaid.core.data.ChartAssetIndex
import org.rhythmeta.maimaid.core.network.StaticBundleClient

/**
 * Verified on-disk cache for chart tracks. The MP3 (and only the MP3) lives at
 * `filesDir/chart-audio/<sha256>.<ext>`; the PCM cache built from it lives next to it.
 *
 * This replaces the old `ChartAudioPlayer` download helper now that MediaPlayer is gone: the
 * preview always plays through the PCM engine.
 */
object ChartTrackCache {
    fun cacheDirectory(context: Context): File =
        File(context.applicationContext.filesDir, "chart-audio").apply { mkdirs() }

    fun cachedFile(context: Context, asset: ChartAssetIndex.File): File? {
        val extension = asset.path.substringAfterLast('.', missingDelimiterValue = "mp3")
        val file = File(cacheDirectory(context), "${asset.sha256.lowercase()}.$extension")
        return file.takeIf { it.isFile && it.length() > 0L }
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
        return destination
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
