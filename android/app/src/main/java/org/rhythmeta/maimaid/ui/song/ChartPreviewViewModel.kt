package org.rhythmeta.maimaid.ui.song

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.rhythmeta.maimaid.core.AppContainer
import org.rhythmeta.maimaid.core.chart.ChartAssetIdResolver
import org.rhythmeta.maimaid.core.chart.ChartAudioState
import org.rhythmeta.maimaid.core.chart.ChartTrackCache
import org.rhythmeta.maimaid.core.chart.Mp3CoverArt
import org.rhythmeta.maimaid.core.chart.SimaiParser
import org.rhythmeta.maimaid.core.chart.audio.ChartPcmStream
import org.rhythmeta.maimaid.core.chart.audio.ChartStreamingTrack
import org.rhythmeta.maimaid.core.chart.audio.PcmChartAudioEngine
import org.rhythmeta.maimaid.core.data.ChartAssetIndex
import org.rhythmeta.maimaid.core.database.SheetEntity
import org.rhythmeta.maimaid.core.database.SongEntity
import java.io.BufferedOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.rhythmeta.maimaid.ui.util.SongVisualUtils

/** One previewable sheet: its catalog sheet, simai slot and the published maidata it lives in. */
data class PreviewChartOption(
    val sheet: SheetEntity,
    val slot: Int,
    val entry: ChartAssetIndex.Entry,
    val maidata: String,
)

sealed interface ChartPreviewUiState {
    data object Loading : ChartPreviewUiState

    data class Ready(
        val song: SongEntity?,
        val options: List<PreviewChartOption>,
        val selectedSheetKey: String,
    ) : ChartPreviewUiState {
        val selected: PreviewChartOption
            get() = options.firstOrNull { it.sheet.sheetKey == selectedSheetKey } ?: options.first()
    }

    data class Failed(val message: String) : ChartPreviewUiState
}

/**
 * Loads every previewable chart of a song. The published maidata does not always contain all
 * difficulties, so only slots actually present in the asset become selectable.
 */
class ChartPreviewViewModel(
    private val songId: String,
    private val requestedSheetKey: String?,
    private val container: AppContainer,
) : ViewModel() {
    /** Speed / display / audio settings for this page. */
    val settings = container.appPreferencesRepository.chartPreviewSettings.stateIn(
        viewModelScope,
        kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000),
        org.rhythmeta.maimaid.core.chart.ChartPreviewSettings(),
    )
    private val mutableState = MutableStateFlow<ChartPreviewUiState>(ChartPreviewUiState.Loading)
    val state: StateFlow<ChartPreviewUiState> = mutableState.asStateFlow()

    private val mutableAudioState = MutableStateFlow<ChartAudioState>(ChartAudioState.Idle)
    val audioState: StateFlow<ChartAudioState> = mutableAudioState.asStateFlow()

    /** The only audio path: PCM music + SFX mixer backed by one low-latency AudioTrack. */
    val pcmEngine = PcmChartAudioEngine(container.applicationContext)
    /**
     * Audio asset shared by every chart type of this song (DX and STD use the same track). Picking a
     * single asset also means the MP3/PCM cache is populated once for the whole song.
     */
    private var songAudioAsset: ChartAssetIndex.File? = null
    private var preparedAudioSha: String? = null
    private var audioPrepareJob: Job? = null
    private var coverArtCache: Pair<String, ByteArray?>? = null

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            mutableState.value = ChartPreviewUiState.Loading
            try {
                val song = container.catalogRepository.songs.first()
                    .firstOrNull { it.songIdentifier == songId }
                val sheets = container.catalogRepository.observeSheetsForSong(songId).first()
                val index = container.staticBundleClient.fetchChartAssetIndex()
                val maidataByEntry = HashMap<Int, String?>()
                val options = mutableListOf<PreviewChartOption>()

                for (sheet in sheets) {
                    if (sheet.providerSongId <= 0) continue
                    val entry = ChartAssetIdResolver
                        .candidates(sheet.providerSongId, sheet.type)
                        .firstNotNullOfOrNull(index::entryFor)
                        ?: continue
                    val maidata = if (maidataByEntry.containsKey(entry.id)) {
                        maidataByEntry[entry.id]
                    } else {
                        runCatching {
                            entry.files.maidata?.let {
                                container.staticBundleClient.downloadChartAssetText(it)
                            }
                        }.getOrNull().also { maidataByEntry[entry.id] = it }
                    } ?: continue
                    val availableSlots = SimaiParser.availableDifficulties(maidata)
                    // UTAGE sheets carry a marker (e.g. 「協」) instead of a difficulty name and
                    // always live in slot 7; fall back to the only published slot if needed.
                    val slot = if (sheet.type.contains("utage", ignoreCase = true)) {
                        if (7 in availableSlots) 7 else availableSlots.maxOrNull()
                    } else {
                        simaiDifficultySlot(sheet.difficulty)
                    }
                    if (slot == null || slot !in availableSlots) continue
                    options += PreviewChartOption(sheet, slot, entry, maidata)
                }

                if (options.isEmpty()) error("No previewable chart for this song")

                // DX and STD reference separate chart entries but the same music. Pick one canonical
                // audio asset for the song (the base/STD entry where available) so switching chart
                // types reuses the downloaded MP3 and the decoded PCM instead of downloading again.
                songAudioAsset = options
                    .sortedBy { it.entry.id }
                    .firstNotNullOfOrNull { it.entry.files.audio }

                val ordered = options.sortedWith(
                    compareBy<PreviewChartOption> {
                        if (it.sheet.type.equals("dx", ignoreCase = true)) 0 else 1
                    }.thenBy {
                        SongVisualUtils.difficultyOrder(it.sheet.difficulty)
                    },
                )
                val requested = requestedSheetKey
                    ?.let { key -> ordered.firstOrNull { it.sheet.sheetKey == key } }
                // When the preview was opened from a song-detail card, only the difficulties of the
                // selected chart type are shown; otherwise fall back to every type.
                val scoped = if (requested != null) {
                    val type = SongVisualUtils.displayChartType(requested.sheet.type)
                    ordered.filter {
                        SongVisualUtils.displayChartType(it.sheet.type) == type
                    }
                } else {
                    ordered
                }
                // Always start on the hardest difficulty of the selected type; the requested sheet
                // is only used to scope the type.
                val selectedKey = scoped.maxByOrNull { it.slot }?.sheet?.sheetKey
                    ?: scoped.last().sheet.sheetKey

                mutableState.value = ChartPreviewUiState.Ready(
                    song = song,
                    options = scoped,
                    selectedSheetKey = selectedKey,
                )
                prepareAudio()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                mutableState.value = ChartPreviewUiState.Failed(
                    error.message ?: "Unable to load chart preview",
                )
            }
        }
    }

    /** Switches the previewed difficulty without re-downloading the maidata. */
    fun selectSheet(sheetKey: String) {
        val ready = mutableState.value as? ChartPreviewUiState.Ready ?: return
        if (ready.selectedSheetKey == sheetKey) return
        val next = ready.copy(selectedSheetKey = sheetKey)
        mutableState.value = next
        prepareAudio()
    }

    private fun prepareAudio() {
        val audio = songAudioAsset
        if (audio == null) {
            // Switching to a sheet without a track must not leave the previous track playing.
            pcmEngine.release()
            preparedAudioSha = null
            mutableAudioState.value = ChartAudioState.Idle
            return
        }
        // DX/STD share the same audio asset, so switching chart type must not reload it.
        if (preparedAudioSha == audio.sha256 && pcmEngine.isReady) return
        preparedAudioSha = audio.sha256
        // A superseded download/decode must not keep preparing a track that is no longer selected.
        audioPrepareJob?.cancel()
        audioPrepareJob = null
        // Stop the previous track immediately: the new one may take seconds to download/decode and
        // must not play over the old one (the preview would then restart from zero afterwards).
        pcmEngine.pause()
        pcmEngine.stopSfx()
        // Publish the loading state before the download/decode starts, so the preview holds the
        // chart and the status line can report why playback has not begun yet.
        mutableAudioState.value = ChartAudioState.Preparing
        audioPrepareJob = viewModelScope.launch {
            val cached = ChartTrackCache.cachedFile(container.applicationContext, audio)
            val prepared = if (cached != null) {
                runCatching { pcmEngine.prepare(cached, audio.sha256) }.getOrDefault(false)
            } else {
                // Nothing cached yet: play straight from the network. Decoding starts on the first
                // received bytes, so the preview no longer waits for the whole MP3 to download.
                val streamed = runCatching {
                    pcmEngine.prepare(
                        trackSource = ChartStreamingTrack { sink ->
                            ChartTrackCache.stream(
                                context = container.applicationContext,
                                asset = audio,
                                client = container.staticBundleClient,
                                onChunk = { bytes, offset, length ->
                                    sink.onChunk(bytes, offset, length)
                                },
                            )
                        },
                        key = "stream:${audio.sha256}",
                    )
                }.getOrDefault(false)
                if (streamed) {
                    true
                } else {
                    // Streaming failed (offline hiccup, unusual encoding): fall back to the verified
                    // download + file decode path so the chart still has music.
                    val file = try {
                        ChartTrackCache.ensure(
                            container.applicationContext,
                            audio,
                            container.staticBundleClient,
                        )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        mutableAudioState.value = ChartAudioState.Failed(
                            error.message ?: "Unable to download the chart track",
                        )
                        preparedAudioSha = null
                        audioPrepareJob = null
                        return@launch
                    }
                    runCatching { pcmEngine.prepare(file, audio.sha256) }.getOrDefault(false)
                }
            }
            mutableAudioState.value = if (prepared) {
                pcmEngine.state.value
            } else {
                ChartAudioState.Failed("Unable to decode the chart track")
            }
            if (!prepared) preparedAudioSha = null
            audioPrepareJob = null
        }
    }

    /** True when this song has a shared audio track (DX/STD use the same one). */
    val hasAudioAsset: Boolean get() = songAudioAsset != null

    fun pauseAudio() = pcmEngine.pause()

    fun playAudio() = pcmEngine.play()

    /** Seek: [chartTimeMs] is a chart position; the engine applies the offset mapping. */
    fun seekChartAudio(chartTimeMs: Double, playAfterSeek: Boolean) {
        pcmEngine.seekTo(chartTimeMs, playAfterSeek)
    }

    /**
     * Cover art embedded in the selected track. Community MP3s often carry a much sharper image
     * than the separately downloaded catalog cover, so the preview and the saved package prefer it.
     */
    fun cachedAudioCover(): ByteArray? {
        val audio = songAudioAsset ?: return null
        val file = ChartTrackCache.cachedFile(container.applicationContext, audio) ?: return null
        coverArtCache?.let { (digest, cached) -> if (digest == audio.sha256) return cached }
        val bytes = Mp3CoverArt.read(file)
        coverArtCache = audio.sha256 to bytes
        return bytes
    }

    /**
     * Writes the selected sheet as a zip with `maidata.txt`, `track.mp3` and `bg.png`. The audio is
     * downloaded first when it is not cached yet, so a save right after opening the page still
     * produces the complete package.
     */
    fun saveChartZip(destination: Uri, onResult: (Throwable?) -> Unit) {
        viewModelScope.launch {
            var failure: Throwable? = null
            try {
                val ready = mutableState.value as? ChartPreviewUiState.Ready
                    ?: error("Chart is not ready")
                val option = ready.selected
                val audio = songAudioAsset
                if (audio != null &&
                    ChartTrackCache.cachedFile(container.applicationContext, audio) == null
                ) {
                    ChartTrackCache.ensure(
                        container.applicationContext,
                        audio,
                        container.staticBundleClient,
                    )
                }
                withContext(Dispatchers.IO) {
                    val resolver = container.applicationContext.contentResolver
                    val stream = resolver.openOutputStream(destination)
                        ?: error("Unable to open the selected file")
                    stream.use { output ->
                        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
                            zip.putNextEntry(ZipEntry("maidata.txt"))
                            zip.write(option.maidata.toByteArray(Charsets.UTF_8))
                            zip.closeEntry()

                            val audioFile = audio?.let {
                                ChartTrackCache.cachedFile(container.applicationContext, it)
                            }
                            if (audioFile != null) {
                                zip.putNextEntry(ZipEntry("track.mp3"))
                                audioFile.inputStream().use { it.copyTo(zip) }
                                zip.closeEntry()
                            }

                            val embedded = audioFile?.let(Mp3CoverArt::read)
                            if (embedded != null) {
                                writePngEntry(zip, "bg.png", embedded)
                            } else {
                                val cover = ready.song?.imageName
                                    ?.let { container.coverImageStore.fileFor(it) }
                                if (cover != null) writePngEntry(zip, "bg.png", cover.readBytes())
                            }
                        }
                    }
                }
            } catch (error: Throwable) {
                failure = error
            }
            onResult(failure)
        }
    }

    private fun writePngEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        if (isPng(bytes)) {
            zip.write(bytes)
        } else {
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bitmap != null) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip)
                bitmap.recycle()
            }
        }
        zip.closeEntry()
    }

    private fun isPng(bytes: ByteArray): Boolean =
        bytes.size > 8 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte()

    fun updateSettings(transform: (org.rhythmeta.maimaid.core.chart.ChartPreviewSettings) ->
        org.rhythmeta.maimaid.core.chart.ChartPreviewSettings) {
        viewModelScope.launch {
            val current = settings.value
            container.appPreferencesRepository.setChartPreviewSettings(transform(current))
        }
    }

    fun setMusicVolume(volume: Float) = pcmEngine.setMusicVolume(volume)

    fun setPlaybackSpeed(speed: Float) = pcmEngine.setPlaybackSpeed(speed)

    @SuppressLint("EmptySuperCall")
		override fun onCleared() {
        pcmEngine.release()
        super.onCleared()
    }

    class Factory(
        private val songId: String,
        private val sheetKey: String?,
        private val container: AppContainer,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ChartPreviewViewModel::class.java))
            return ChartPreviewViewModel(songId, sheetKey, container) as T
        }
    }
}

/** Maps a catalog difficulty name to the simai `&inote_N` slot. */
internal fun simaiDifficultySlot(difficulty: String): Int = when {
    difficulty.contains("utage", ignoreCase = true) -> 7
    difficulty.contains("宴", ignoreCase = true) -> 7
    difficulty.contains("remaster", ignoreCase = true) -> 6
    difficulty.contains("master", ignoreCase = true) -> 5
    difficulty.contains("expert", ignoreCase = true) -> 4
    difficulty.contains("advanced", ignoreCase = true) -> 3
    difficulty.contains("basic", ignoreCase = true) -> 2
    else -> 5
}
