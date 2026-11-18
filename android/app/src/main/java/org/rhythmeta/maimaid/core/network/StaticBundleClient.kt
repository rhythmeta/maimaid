package org.rhythmeta.maimaid.core.network

import android.graphics.BitmapFactory
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import org.rhythmeta.maimaid.core.data.StaticBundleResponse
import org.rhythmeta.maimaid.core.data.ChartAssetIndex
import org.rhythmeta.maimaid.core.data.StaticAssetConfiguration
import org.rhythmeta.maimaid.core.data.StaticAssetUrls
import org.rhythmeta.maimaid.core.data.StaticManifest
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class StaticBundleClient(
    private val baseUrl: String,
    private val json: Json,
) {
    suspend fun fetchManifest(): StaticManifest = get<StaticManifest>().also {
        StaticAssetUrls.configure(it.assets)
    }

    suspend fun fetchBundle(
        manifest: StaticManifest,
        onTransfer: (downloadedBytes: Long, totalBytes: Long?) -> Unit = { _, _ -> },
    ): StaticBundleResponse {
        val bundleUrl = URL(URL("${baseUrl.trimEnd('/')}/"), manifest.bundle).toString()
        return getUrl<StaticBundleResponse>(bundleUrl, onTransfer).validatedAgainst(manifest)
    }

    suspend fun fetchChartAssetIndex(): ChartAssetIndex =
        getUrl("${baseUrl.trimEnd('/')}/chart-assets/index.json")

    /** Downloads a content-addressed chart asset and verifies its published digest and size. */
    suspend fun downloadChartAsset(asset: ChartAssetIndex.File): ByteArray =
        withContext(Dispatchers.IO) {
            validateChartAssetPath(asset)
            val bytes = getBytes("${baseUrl.trimEnd('/')}${asset.path}")
            check(bytes.size.toLong() == asset.size) {
                "Chart asset size mismatch: expected ${asset.size}, got ${bytes.size}"
            }
            val actualDigest = MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            check(actualDigest == asset.sha256.lowercase()) {
                "Chart asset digest mismatch"
            }
            bytes
        }

    suspend fun downloadChartAssetText(asset: ChartAssetIndex.File): String =
        downloadChartAsset(asset).toString(Charsets.UTF_8)

    suspend fun downloadChartAssetToFile(
        asset: ChartAssetIndex.File,
        destination: File,
    ) = withContext(Dispatchers.IO) {
        validateChartAssetPath(asset)
        require(asset.size > 0L) { "Chart asset must not be empty" }
        val temporary = File(
            destination.parentFile,
            ".${destination.name}.${System.nanoTime()}.download",
        )
        val digest = MessageDigest.getInstance("SHA-256")
        val connection = URL("${baseUrl.trimEnd('/')}${asset.path}").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.setRequestProperty("Accept", "audio/mpeg, text/plain, */*")
            connection.setRequestProperty("X-Maimaid-Client", "android")
            connection.setRequestProperty("User-Agent", "maimaid-android")
            check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
            temporary.parentFile?.mkdirs()
            var size = 0L
            connection.inputStream.use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(TRANSFER_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        size += count
                        check(size <= asset.size) { "Chart asset exceeds published size" }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
            check(size == asset.size) { "Chart asset size mismatch: expected ${asset.size}, got $size" }
            val actualDigest = digest.digest().joinToString("") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            }
            check(actualDigest == asset.sha256.lowercase()) { "Chart asset digest mismatch" }
            destination.parentFile?.mkdirs()
            atomicReplace(temporary, destination)
        } finally {
            connection.disconnect()
            temporary.delete()
        }
    }

    /**
     * Streams a chart asset: every received chunk is handed to [onChunk] while the verified copy is
     * written to [destination]. The preview can therefore start playing before the transfer ends;
     * the file only appears at its final name once size and digest match the published values.
     */
    suspend fun streamChartAssetToFile(
        asset: ChartAssetIndex.File,
        destination: File,
        onChunk: (ByteArray, Int, Int) -> Unit,
    ) = withContext(Dispatchers.IO) {
        validateChartAssetPath(asset)
        require(asset.size > 0L) { "Chart asset must not be empty" }
        val temporary = File(
            destination.parentFile,
            ".${destination.name}.${System.nanoTime()}.download",
        )
        val digest = MessageDigest.getInstance("SHA-256")
        val connection =
            URL("${baseUrl.trimEnd('/')}${asset.path}").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.setRequestProperty("Accept", "audio/mpeg, text/plain, */*")
            connection.setRequestProperty("X-Maimaid-Client", "android")
            connection.setRequestProperty("User-Agent", "maimaid-android")
            check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
            temporary.parentFile?.mkdirs()
            var size = 0L
            connection.inputStream.use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(TRANSFER_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        size += count
                        check(size <= asset.size) { "Chart asset exceeds published size" }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        onChunk(buffer, 0, count)
                    }
                }
            }
            check(size == asset.size) { "Chart asset size mismatch: expected ${asset.size}, got $size" }
            val actualDigest = digest.digest().joinToString("") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            }
            check(actualDigest == asset.sha256.lowercase()) { "Chart asset digest mismatch" }
            destination.parentFile?.mkdirs()
            atomicReplace(temporary, destination)
        } finally {
            connection.disconnect()
            temporary.delete()
        }
    }

    suspend fun downloadCover(
        imageName: String,
        destination: File,
        assets: StaticAssetConfiguration?,
        onTransfer: (downloadedBytes: Long, totalBytes: Long?) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        downloadImage(
            urlStrings = StaticAssetUrls.coverCandidates(imageName, assets),
            destination = destination,
            emptyResponseMessage = "Empty cover response",
            onTransfer = onTransfer,
        )
    }

    suspend fun downloadPresetAvatar(
        id: Int,
        destination: File,
        assets: StaticAssetConfiguration?,
        onTransfer: (downloadedBytes: Long, totalBytes: Long?) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        downloadImage(
            urlStrings = StaticAssetUrls.presetAvatarCandidates(id, assets),
            destination = destination,
            emptyResponseMessage = "Empty preset avatar response",
            onTransfer = onTransfer,
        )
    }

    private fun downloadImage(
        urlStrings: List<String>,
        destination: File,
        emptyResponseMessage: String,
        onTransfer: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ) {
        var lastError: Exception? = null
        for (urlString in urlStrings) {
            try {
                downloadImage(urlString, destination, emptyResponseMessage, onTransfer)
                return
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                lastError = error
            }
        }
        throw lastError ?: IllegalStateException(emptyResponseMessage)
    }

    private fun downloadImage(
        urlString: String,
        destination: File,
        emptyResponseMessage: String,
        onTransfer: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ) {
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        val temporary = File(destination.parentFile, "${destination.name}.download")
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.setRequestProperty("Accept", ImageRequestHeaders.ACCEPT)
            connection.setRequestProperty("User-Agent", "maimaid-android")
            check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
            onTransfer(0L, connection.contentLengthLong.takeIf { it > 0L })
            temporary.parentFile?.mkdirs()
            connection.inputStream.use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(TRANSFER_BUFFER_SIZE)
                    while (true) {
                        val byteCount = input.read(buffer)
                        if (byteCount < 0) break
                        output.write(buffer, 0, byteCount)
                        onTransfer(byteCount.toLong(), null)
                    }
                }
            }
            check(temporary.length() > 0L) { emptyResponseMessage }
            val imageBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temporary.path, imageBounds)
            check(imageBounds.outWidth > 0 && imageBounds.outHeight > 0) { "Unsupported image response" }
            check(temporary.renameTo(destination)) { "Unable to cache cover" }
        } finally {
            connection.disconnect()
            temporary.delete()
        }
    }

    private suspend inline fun <reified T> get(
        noinline onTransfer: ((downloadedBytes: Long, totalBytes: Long?) -> Unit)? = null,
    ): T = getUrl("${baseUrl.trimEnd('/')}/manifest.json", onTransfer)

    @OptIn(ExperimentalSerializationApi::class)
    private suspend inline fun <reified T> getUrl(
        url: String,
        noinline onTransfer: ((downloadedBytes: Long, totalBytes: Long?) -> Unit)? = null,
    ): T = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("X-Maimaid-Client", "android")
            connection.setRequestProperty("User-Agent", "maimaid-android")

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            if (status in 200..299) {
                onTransfer?.invoke(0L, connection.contentLengthLong.takeIf { it > 0L })
            }
            if (status !in 200..299) {
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                error("HTTP $status: ${body.take(200)}")
            }
            val responseStream = stream ?: error("Empty HTTP response")
            responseStream.use { input ->
                val progressStream = if (onTransfer != null) {
                    TransferInputStream(input, onTransfer)
                } else {
                    input
                }
                json.decodeFromStream<T>(progressStream)
            }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun getBytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.setRequestProperty("Accept", "text/plain, audio/mpeg, */*")
            connection.setRequestProperty("X-Maimaid-Client", "android")
            connection.setRequestProperty("User-Agent", "maimaid-android")
            val status = connection.responseCode
            check(status in 200..299) { "HTTP $status" }
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    private fun ByteArray.toHexString(): String =
        joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun validateChartAssetPath(asset: ChartAssetIndex.File) {
        require(asset.path.startsWith("/chart-assets/files/")) {
            "Unexpected chart asset path"
        }
        require(asset.path.substringAfterLast('/').matches(Regex("[0-9a-f]{64}\\.(txt|mp3)"))) {
            "Unexpected chart asset filename"
        }
        require(asset.sha256.matches(Regex("[0-9a-fA-F]{64}"))) {
            "Unexpected chart asset digest"
        }
        require(asset.size > 0L) { "Unexpected chart asset size" }
    }

    private fun atomicReplace(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun StaticBundleResponse.validatedAgainst(manifest: StaticManifest): StaticBundleResponse {
        check(version == manifest.version && md5 == manifest.md5) { "Static bundle metadata mismatch" }
        return this
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 15_000
        const val READ_TIMEOUT_MILLIS = 60_000
        const val TRANSFER_BUFFER_SIZE = 64 * 1_024
    }

    private class TransferInputStream(
        input: InputStream,
        private val onTransfer: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ) : FilterInputStream(input) {
        override fun read(): Int {
            val value = super.read()
            if (value >= 0) onTransfer(1L, null)
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val count = super.read(buffer, offset, length)
            if (count > 0) onTransfer(count.toLong(), null)
            return count
        }
    }
}
