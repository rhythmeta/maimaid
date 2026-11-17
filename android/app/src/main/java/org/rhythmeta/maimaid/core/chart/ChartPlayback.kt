package org.rhythmeta.maimaid.core.chart

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.rhythmeta.maimaid.core.data.ChartAssetIndex
import org.rhythmeta.maimaid.core.network.StaticBundleClient
import kotlin.coroutines.resume

sealed interface ChartAudioState {
    data object Idle : ChartAudioState
    data object Preparing : ChartAudioState
    data class Ready(val durationMillis: Int) : ChartAudioState
    data class Failed(val message: String) : ChartAudioState
}

/**
 * System MediaPlayer wrapper for the optional chart audio. The player position is exposed as the
 * chart preview's playback clock while the UI is rendering; seek requests are serialized so an old
 * asynchronous MediaPlayer callback cannot restart an obsolete position.
 */
class ChartAudioPlayer(
    context: Context,
) {
    private data class SeekRequest(
        val generation: Long,
        val positionMillis: Int,
    )

    private val appContext = context.applicationContext
    private val cacheDirectory = File(appContext.filesDir, "chart-audio").apply { mkdirs() }
    private var player: MediaPlayer? = null
    private var preparedDigest: String? = null
    private var pendingVolume: Float = 1f
    private var pendingSpeed: Float = 1f
    private val mutableSeekCompletions = kotlinx.coroutines.flow.MutableStateFlow(0)
    private var nextSeekGeneration = 0L
    private var activeSeek: SeekRequest? = null
    private var pendingSeek: SeekRequest? = null
    private var wantsPlayback = false

    /** Increments every time a seek finishes; the chart clock freezes until then. */
    val seekCompletions: kotlinx.coroutines.flow.StateFlow<Int> = mutableSeekCompletions

    var state: ChartAudioState = ChartAudioState.Idle
        private set

    val isPrepared: Boolean get() = player != null && state is ChartAudioState.Ready

    val isPlaying: Boolean
        get() = runCatching { player?.isPlaying == true }.getOrDefault(false)

    suspend fun prepare(
        asset: ChartAssetIndex.File,
        client: StaticBundleClient,
    ) {
        state = ChartAudioState.Preparing
        try {
            val extension = asset.path.substringAfterLast('.', missingDelimiterValue = "mp3")
            val destination = File(cacheDirectory, "${asset.sha256.lowercase()}.$extension")
            if (!ChartAudioCacheIntegrity.matches(destination, asset.size, asset.sha256)) {
                client.downloadChartAssetToFile(asset, destination)
            }
            prepareFile(destination, asset.sha256.lowercase())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            state = ChartAudioState.Failed(error.message ?: "Unable to prepare audio")
        }
    }

    private suspend fun prepareFile(file: File, digest: String) = withContext(Dispatchers.Main.immediate) {
        if (preparedDigest == digest && player != null) {
            state = ChartAudioState.Ready(player?.duration ?: 0)
            dispatchPendingSeek()
            return@withContext
        }
        if (player != null) {
            invalidateSeeks()
            player?.release()
        }
        preparedDigest = null
        suspendCancellableCoroutine<Unit> { continuation ->
            val current = MediaPlayer()
            player = current
            current.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            current.setDataSource(appContext, Uri.fromFile(file))
            current.setOnPreparedListener {
                preparedDigest = digest
                it.setVolume(pendingVolume, pendingVolume)
                runCatching {
                    it.playbackParams = it.playbackParams.setSpeed(pendingSpeed)
                }
                state = ChartAudioState.Ready(it.duration)
                if (continuation.isActive) continuation.resume(Unit)
                dispatchPendingSeek()
            }
            current.setOnErrorListener { _, what, extra ->
                if (continuation.isActive) {
                    continuation.resumeWith(
                        Result.failure(IllegalStateException("MediaPlayer error $what/$extra")),
                    )
                }
                true
            }
            continuation.invokeOnCancellation {
                invalidateSeeks()
                current.release()
                if (player === current) player = null
            }
            current.setOnSeekCompleteListener { completedPlayer ->
                completeActiveSeek(completedPlayer)
            }
            current.prepareAsync()
        }
    }

    fun play() {
        wantsPlayback = true
        val current = player ?: return
        runCatching { if (!current.isPlaying) current.start() }
    }

    fun pause() {
        wantsPlayback = false
        val current = player ?: return
        runCatching { if (current.isPlaying) current.pause() }
    }

    /**
     * Seeks the track and, when [playAfterSeek] is set, starts playback only after the seek has
     * completed. Starting immediately would resume from the previous position and leave the
     * music permanently behind the chart.
     */
    fun seekTo(positionMillis: Int, playAfterSeek: Boolean = false) {
        wantsPlayback = playAfterSeek
        val duration = player?.let { runCatching { it.duration }.getOrDefault(positionMillis) }
            ?: positionMillis
        val request = SeekRequest(
            generation = ++nextSeekGeneration,
            positionMillis = positionMillis.coerceIn(0, duration.coerceAtLeast(0)),
        )
        // MediaPlayer cannot cancel an in-flight seek. Keep only the newest request and dispatch it
        // after the current callback, so an old callback can never start an obsolete position.
        pendingSeek = request
        dispatchPendingSeek()
    }

    private fun dispatchPendingSeek() {
        val current = player ?: return
        if (activeSeek != null || pendingSeek == null) return
        val request = pendingSeek ?: return
        pendingSeek = null
        activeSeek = request.copy(
            positionMillis = request.positionMillis.coerceIn(0, current.duration.coerceAtLeast(0)),
        )
        runCatching { current.seekTo(activeSeek?.positionMillis ?: 0) }
            .onFailure {
                if (activeSeek?.generation == request.generation) activeSeek = null
                if (request.generation == nextSeekGeneration) {
                    mutableSeekCompletions.value = mutableSeekCompletions.value + 1
                }
                dispatchPendingSeek()
            }
    }

    private fun completeActiveSeek(completedPlayer: MediaPlayer) {
        val request = activeSeek ?: return
        activeSeek = null
        if (request.generation == nextSeekGeneration) {
            if (wantsPlayback) runCatching { if (!completedPlayer.isPlaying) completedPlayer.start() }
            mutableSeekCompletions.value = mutableSeekCompletions.value + 1
        }
        dispatchPendingSeek()
    }

    private fun invalidateSeeks() {
        nextSeekGeneration++
        activeSeek = null
        pendingSeek = null
    }

    fun setVolume(volume: Float) {
        pendingVolume = volume.coerceIn(0f, 1f)
        val current = player ?: return
        runCatching { current.setVolume(pendingVolume, pendingVolume) }
    }

    fun setPlaybackSpeed(speed: Float) {
        pendingSpeed = speed.coerceIn(0.1f, 2f)
        val current = player ?: return
        runCatching {
            current.playbackParams = current.playbackParams.setSpeed(pendingSpeed)
        }
    }

    fun currentPositionMillis(): Int {
        val current = player ?: return 0
        return runCatching { current.currentPosition }.getOrDefault(0)
    }

    fun release() {
        invalidateSeeks()
        wantsPlayback = false
        runCatching { player?.release() }
        player = null
        preparedDigest = null
        state = ChartAudioState.Idle
    }
}

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
