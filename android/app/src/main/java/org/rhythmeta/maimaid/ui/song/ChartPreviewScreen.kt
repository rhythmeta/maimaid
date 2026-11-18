package org.rhythmeta.maimaid.ui.song

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.floor
import kotlinx.coroutines.flow.collectLatest
import org.rhythmeta.maimaid.BuildConfig
import org.rhythmeta.maimaid.R
import org.rhythmeta.maimaid.core.AppContainer
import org.rhythmeta.maimaid.core.chart.Chart
import org.rhythmeta.maimaid.core.chart.ChartAudioEvents
import org.rhythmeta.maimaid.core.chart.ChartAudioState
import org.rhythmeta.maimaid.core.chart.ChartPreviewSettings
import org.rhythmeta.maimaid.core.chart.ChartRenderConfig
import org.rhythmeta.maimaid.core.chart.SimaiParser
import org.rhythmeta.maimaid.core.chart.TimingTimeline
import org.rhythmeta.maimaid.core.chart.audio.PcmChartAudioEngine
import org.rhythmeta.maimaid.core.chart.audio.TrackDownloadProgress
import org.rhythmeta.maimaid.core.chart.render.ChartRenderer
import kotlinx.coroutines.launch
import org.rhythmeta.maimaid.ui.components.squircleShape
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Full-page chart preview: arcade-faithful stage plus transport controls. */
@Composable
internal fun ChartPreviewScreen(
	  modifier: Modifier = Modifier,
    songId: String,
    sheetKey: String?,
    container: AppContainer,
    contentTopPadding: Dp,
    saveRequested: Boolean = false,
    onSaveRequestHandled: () -> Unit = {},
) {
    // Keep the display awake while the preview page is open, otherwise the screen timeout cuts the
    // playback off mid-song. The window flag is used instead of a PowerManager wakelock, so no
    // permission is needed and other screens keep their own timeout.
    val view = LocalView.current
    DisposableEffect(view) {
        val previous = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = previous }
    }
    val viewModel: ChartPreviewViewModel = viewModel(
        key = "chart-preview-$songId",
        factory = ChartPreviewViewModel.Factory(songId, sheetKey, container),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val audioState by viewModel.audioState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val usesPcm by viewModel.pcmEngine.usesPcm.collectAsStateWithLifecycle()
    val pcmPreparing by viewModel.pcmEngine.preparing.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val savedMessage = stringResource(R.string.chart_preview_save_done)
    val saveFailedMessage = stringResource(R.string.chart_preview_save_failed)
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { destination ->
        if (destination != null) {
            viewModel.saveChartZip(destination) { error ->
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(
                        message = if (error == null) savedMessage else saveFailedMessage,
                        duration = SnackbarDuration.Custom(CHART_SAVE_SNACKBAR_MS),
                    )
                }
            }
        }
    }
    val saveName = remember(state) {
        val ready = state as? ChartPreviewUiState.Ready
        chartSaveFileName(ready?.song?.title, ready?.selected?.sheet?.difficulty)
    }
    LaunchedEffect(saveRequested) {
        if (!saveRequested) return@LaunchedEffect
        onSaveRequestHandled()
        saveLauncher.launch(saveName)
    }
    val comboLabel = stringResource(R.string.chart_preview_hud_combo)
    val breakNoExLabel = stringResource(R.string.chart_preview_hud_break_no_ex)
    val renderConfig = remember(settings, comboLabel, breakNoExLabel) {
        settings.toRenderConfig(comboLabel, breakNoExLabel)
    }

    Box(modifier = modifier.fillMaxSize()) {
        when (val current = state) {
            ChartPreviewUiState.Loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            is ChartPreviewUiState.Failed -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = current.message,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    IconButton(onClick = viewModel::load) {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = stringResource(R.string.chart_preview_retry),
                        )
                    }
                }
            }
            is ChartPreviewUiState.Ready -> ChartPreviewContent(
                ready = current,
                coverFile = current.song?.let { song ->
                    container.coverImageStore.fileFor(song.imageName)
                },
                embeddedCover = viewModel.cachedAudioCover(),
                audioState = audioState,
                settings = settings,
                onSettingsChange = { next -> viewModel.updateSettings { next } },
                onSelectSheet = viewModel::selectSheet,
                onMusicVolumeChange = viewModel::setMusicVolume,
                onPlaybackSpeedChange = viewModel::setPlaybackSpeed,
                renderConfig = renderConfig,
                contentTopPadding = contentTopPadding,
                pcmEngine = viewModel.pcmEngine,
                usesPcm = usesPcm,
                pcmPreparing = pcmPreparing,
                hasAudio = viewModel.hasAudioAsset,
            )
        }
        SnackbarHost(
            state = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
        )
    }
}

@Composable
private fun ChartPreviewContent(
    ready: ChartPreviewUiState.Ready,
    coverFile: java.io.File?,
    embeddedCover: ByteArray?,
    audioState: ChartAudioState,
    settings: ChartPreviewSettings,
    onSettingsChange: (ChartPreviewSettings) -> Unit,
    onSelectSheet: (String) -> Unit,
    onMusicVolumeChange: (Float) -> Unit,
    onPlaybackSpeedChange: (Float) -> Unit,
    renderConfig: ChartRenderConfig,
    contentTopPadding: Dp,
    pcmEngine: PcmChartAudioEngine,
    usesPcm: Boolean,
    pcmPreparing: Boolean,
    hasAudio: Boolean,
) {
    val option = ready.selected
    val chartResult = remember(option.maidata, option.slot) {
        runCatching { SimaiParser.parse(option.maidata, option.slot) }
    }
    val chart = chartResult.getOrNull()
    if (chart == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = chartResult.exceptionOrNull()?.message
                    ?: stringResource(R.string.chart_preview_parse_failed),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        return
    }

    // The reference stops at totalMeasures × beatsPerMeasure + 500 ms; chart.measures already
    // carries one measure of padding on each side, so the last note's hit effects and fireworks
    // get to finish. Keep a floor of 1.5 s after the final note for very short charts.
    val chartTimeline = remember(chart) { TimingTimeline.fromChart(chart) }
    val playbackDurationMs = remember(chart) {
        maxOf(
            chartTimeline.msFromBeat(chart.measures * 4.0) + 500.0,
            chart.endTimeMs + 1_500.0,
        )
    }
    val clock = remember(chart) { PreviewClock(playbackDurationMs) }
    // Holding the stage plays at 1.5x the user's speed; releasing returns to the setting.
    var speedBoostActive by remember { mutableStateOf(false) }
    // Apply the boost from state (not from the gesture callback): the pointer handler used to keep
    // a stale settings snapshot and forced 1x on release.
    LaunchedEffect(speedBoostActive, settings.playbackSpeed) {
        onPlaybackSpeedChange(
            if (speedBoostActive) {
                (BOOST_SPEED * settings.playbackSpeed).toFloat()
            } else {
                settings.playbackSpeed.toFloat()
            },
        )
    }
    // Leaving the app must not leave the chart (and its audio) running in the background; the
    // preview comes back paused so the user can resume deliberately.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, clock) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) clock.isPlaying = false
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // Community MP3s carry a sharper cover than the catalog image; prefer it when present.
    val cover = remember(coverFile, embeddedCover) {
        embeddedCover?.let(::decodeCover) ?: decodeCover(coverFile)
    }
    var soundEnabled by rememberSaveable { mutableStateOf(true) }
    // Debug-only overlay: mirrors the streaming download so timing can be judged on device.
    var downloadProgress by remember { mutableStateOf<TrackDownloadProgress?>(null) }
    DisposableEffect(pcmEngine) {
        onDispose {
            pcmEngine.pause()
            pcmEngine.stopSfx()
            if (BuildConfig.DEBUG) android.util.Log.i(TAG_AUDIO, pcmEngine.debugSummary())
        }
    }
    DisposableEffect(cover) {
        onDispose { cover?.recycle() }
    }
    LaunchedEffect(soundEnabled) {
        pcmEngine.setSfxEnabled(soundEnabled)
    }

    LaunchedEffect(settings.musicOffsetMs) {
        if (usesPcm) pcmEngine.setMusicOffsetMs(settings.musicOffsetMs)
    }
    LaunchedEffect(settings.musicVolume) {
        onMusicVolumeChange(settings.musicVolume)
    }
    LaunchedEffect(settings.playbackSpeed) {
        onPlaybackSpeedChange(settings.playbackSpeed.toFloat())
    }
    LaunchedEffect(settings.answerVolume, settings.judgeVolume) {
        pcmEngine.setAnswerVolume(settings.answerVolume)
        pcmEngine.setJudgeVolume(settings.judgeVolume)
    }
    LaunchedEffect(settings.sfxOffsetMs) {
        pcmEngine.setSfxOffsetMs(settings.sfxOffsetMs)
    }
    // The notes must never run ahead of the music: hold the clock until the selected track has
    // finished downloading and MediaPlayer handed it back, which is what `waitingForAudio` waits
    // for. A failed download opens the gate anyway so the chart still plays silently; the audio
    // status line reports the failure.
    val waitingForAudio = previewAudioPending(
        hasAudio = hasAudio,
        audioState = audioState,
    ) || (hasAudio && pcmPreparing)
    // Publish the frame-addressed event timeline to the PCM mixer. This is safe before the
    // engine has finished preparing; it keeps the configuration for the moment it becomes ready.
    LaunchedEffect(chart, playbackDurationMs, usesPcm) {
        if (!usesPcm) return@LaunchedEffect
        pcmEngine.configure(
            events = ChartAudioEvents.prepare(chart.notes),
            leadInMs = chart.leadInMs,
            durationMs = playbackDurationMs,
            musicOffsetMs = settings.musicOffsetMs,
            sfxOffsetMs = settings.sfxOffsetMs,
        )
    }
    // Rewind whenever another chart is shown; the start effect below decides when it may run.
    LaunchedEffect(chart) {
        clock.position.doubleValue = 0.0
        if (usesPcm) pcmEngine.seekTo(0.0, playAfterSeek = false)
        clock.isPlaying = false
    }
    // Hold the whole preview while a new track downloads/decodes. Without this, the previous
    // track kept playing under the loading overlay and the new one then restarted from zero.
    LaunchedEffect(chart, waitingForAudio) {
        if (waitingForAudio) {
            clock.isPlaying = false
            return@LaunchedEffect
        }
        clock.isPlaying = true
    }

    LaunchedEffect(chart, clock, usesPcm) {
        snapshotFlow { clock.isPlaying }.collectLatest { playing ->
            if (!playing) {
                pcmEngine.pause()
                pcmEngine.stopSfx()
                return@collectLatest
            }
            if (usesPcm) pcmEngine.play()
            var lastFrameNanos = 0L
            var lastStatsLogMs = 0L
            var lastProgressPollMs = 0L
            while (clock.isPlaying) {
                withFrameNanos { now ->
                    if (clock.isScrubbing) {
                        lastFrameNanos = now
                        return@withFrameNanos
                    }
                    val deltaMs = if (lastFrameNanos == 0L) {
                        0.0
                    } else {
                        (now - lastFrameNanos) / 1_000_000.0
                    }
                    val speed = settings.playbackSpeed *
                        (if (speedBoostActive) BOOST_SPEED.toDouble() else 1.0)
                    val nowMs = android.os.SystemClock.uptimeMillis()
                    if (nowMs - lastProgressPollMs >= DOWNLOAD_PROGRESS_POLL_MS) {
                        lastProgressPollMs = nowMs
                        downloadProgress = pcmEngine.downloadProgress()
                    }
                    val position = if (usesPcm && pcmEngine.isReady) {
                        // The PCM engine is the only audio path: its timestamp-based clock is
                        // already sample-accurate and drives both music and SFX.
                        pcmEngine.chartPositionMs()
                    } else {
                        // Silent chart (no track / failed track): keep the visuals running.
                        (clock.position.doubleValue + deltaMs * speed)
                            .coerceAtMost(clock.durationMs)
                    }
                    clock.position.doubleValue = position.coerceIn(0.0, clock.durationMs)
                    if (BuildConfig.DEBUG) {
                        val nowMs = android.os.SystemClock.uptimeMillis()
                        if (nowMs - lastStatsLogMs >= AUDIO_STATS_INTERVAL_MS) {
                            lastStatsLogMs = nowMs
                            if (usesPcm) android.util.Log.i(TAG_AUDIO, pcmEngine.debugSummary())
                        }
                    }
                    if (position >= clock.durationMs) {
                        clock.isPlaying = false
                        pcmEngine.pause()
                        pcmEngine.stopSfx()
                    }
                    if (downloadProgress != null && pcmEngine.downloadProgress() == null) {
                        downloadProgress = null
                    }
                    lastFrameNanos = now
                }
            }
        }
    }

    val stageDescription = stringResource(R.string.chart_preview_title)
    val densityData = remember(chart, clock.durationMs) {
        ChartDensityData.from(chart.notes, clock.durationMs)
    }
    val rhythmData = remember(chart) { ChartRhythmData.from(chart, chartTimeline) }
    val onSeekPreview: (Double) -> Unit = { positionMs ->
        if (!clock.isScrubbing) {
            clock.wasPlayingBeforeScrub = clock.isPlaying
            clock.isScrubbing = true
            if (clock.wasPlayingBeforeScrub) pcmEngine.pause()
        }
        clock.position.doubleValue = positionMs.coerceIn(0.0, clock.durationMs)
    }
    val onSeekFinished: () -> Unit = {
        clock.isScrubbing = false
        if (usesPcm && pcmEngine.isReady) {
            pcmEngine.seekTo(
                clock.position.doubleValue,
                playAfterSeek = clock.wasPlayingBeforeScrub,
            )
        }
        clock.wasPlayingBeforeScrub = false
    }

    val onMeasureJump: (Int) -> Unit = { delta ->
        val currentMs = clock.position.doubleValue
        val currentBeat = chartTimeline.beatFromMs(currentMs)
        val measure = floor(currentBeat / 4.0).toInt()
        val measureStartMs = chartTimeline.msFromBeat(measure * 4.0)
        val targetMeasure = when {
            delta < 0 ->
                if (currentMs - measureStartMs < 200.0) measure - 1 else measure
            else -> measure + 1
        }.coerceAtLeast(0)
        val target = chartTimeline.msFromBeat(targetMeasure * 4.0)
            .coerceIn(0.0, clock.durationMs)
        onSeekPreview(target)
        onSeekFinished()
    }

    fun setSpeedBoost(active: Boolean) {
        if (speedBoostActive != active) speedBoostActive = active
    }

    val onTogglePlay: () -> Unit = toggle@{
        // Nothing can start before the track is ready; the stage shows the download in progress.
        if (waitingForAudio) return@toggle
        if (clock.position.doubleValue >= clock.durationMs) {
            clock.position.doubleValue = 0.0
            if (usesPcm) pcmEngine.seekTo(0.0, playAfterSeek = false)
        }
        // Pausing during a hold must not leave the boosted speed behind.
        setSpeedBoost(false)
        clock.isPlaying = !clock.isPlaying
    }
    // The boost is a gesture overlay: it must not outlive the screen or get stuck when the
    // underlying setting changes mid-hold.
    DisposableEffect(Unit) {
        onDispose { onPlaybackSpeedChange(settings.playbackSpeed.toFloat()) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(top = contentTopPadding + 8.dp, bottom = 32.dp),
    ) {
        if (ready.options.size > 1) {
            PreviewDifficultySelector(
                options = ready.options,
                selectedSheetKey = option.sheet.sheetKey,
                onSelect = onSelectSheet,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(10.dp))
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .aspectRatio(1f)
                .clip(squircleShape(20.dp))
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTapGestures(
                        // A single tap is nothing; pause needs a double tap.
                        onTap = {},
                        onDoubleTap = { onTogglePlay() },
                        // Hold to fast-forward, but only while the chart is actually playing.
                        onLongPress = { if (clock.isPlaying) setSpeedBoost(true) },
                        onPress = {
                            // `tryAwaitRelease` covers both up and cancellation of this pointer.
                            // Other taps elsewhere cannot end this press.
                            tryAwaitRelease()
                            setSpeedBoost(false)
                        },
                    )
                }
                .semantics {
                    contentDescription = stageDescription
                    role = Role.Button
                },
        ) {
            ChartStage(
                chart = chart,
                clock = clock,
                config = renderConfig,
                cover = cover,
                speedBoostActive = speedBoostActive,
            )
            PreviewStageOverlay(clock = clock, waitingForAudio = waitingForAudio)
            val progress = downloadProgress
            if (progress != null) {
                MusicDownloadOverlay(
                    progress = progress,
                    // Bottom-left, just above the BREAK counter.
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 12.dp, bottom = 34.dp),
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        PreviewTransport(
            clock = clock,
            enabled = !waitingForAudio,
            soundEnabled = soundEnabled,
            onTogglePlay = onTogglePlay,
            onToggleSound = { soundEnabled = !soundEnabled },
            onPreviousMeasure = { onMeasureJump(-1) },
            onNextMeasure = { onMeasureJump(1) },
        )
        Spacer(Modifier.height(4.dp))
        ChartDensityTimeline(
            data = densityData,
            positionMs = { clock.position.doubleValue },
            onScrubStart = onSeekPreview,
            onScrub = onSeekPreview,
            onScrubEnd = onSeekFinished,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(4.dp))
        ChartRhythmTimeline(
            data = rhythmData,
            positionMs = { clock.position.doubleValue },
            // Keeping the chart flow speed also pins the lane's own wall-clock travel: with the
            // chart slowed down, the lane must widen its window instead of crawling.
            speedScale = settings.rhythmSpeed *
                if (settings.alwaysKeepHiSpeed) {
                    1.0 / settings.playbackSpeed.coerceAtLeast(0.1)
                } else {
                    1.0
                },
            isPlaying = clock.isPlaying,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        ChartDensityLegend(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )

        Spacer(Modifier.height(8.dp))

        Spacer(Modifier.height(12.dp))
        ChartSpeedSettings(
            settings = settings,
            onChange = onSettingsChange,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(12.dp))
        ChartDisplaySettings(
            settings = settings,
            onChange = onSettingsChange,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(12.dp))
        ChartAudioSettings(
            settings = settings,
            onChange = onSettingsChange,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun ChartStage(
    chart: Chart,
    clock: PreviewClock,
    config: ChartRenderConfig,
    cover: Bitmap?,
    speedBoostActive: Boolean,
) {
    val context = LocalContext.current
    val renderer = remember { ChartRenderer() }
    val timeline = remember(chart) { TimingTimeline.fromChart(chart) }
    val sensorImage = remember { loadSensorImage(context.assets) }
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawIntoCanvas { canvas ->
            val sizePx = size.minDimension
            val beats = timeline.beatFromMs(clock.position.doubleValue)
            renderer.render(
                canvas = canvas.nativeCanvas,
                sizePx = sizePx,
                chart = chart,
                currentBeats = beats,
							config = config,
                isPlaying = clock.isPlaying,
                background = cover,
                sensorImage = sensorImage,
                speedMultiplier = if (speedBoostActive) BOOST_SPEED else 1f,
            )
        }
    }
}

/**
 * Debug overlay for the streaming music download: download speed on the first line and the
 * percentage on the second. Plain white text without a background so the stage stays visible.
 */
@Composable
private fun MusicDownloadOverlay(
    progress: TrackDownloadProgress,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val speed = remember(progress.bytesPerSecond, context) {
        android.text.format.Formatter.formatShortFileSize(
            context,
            progress.bytesPerSecond.coerceAtLeast(0L),
        )
    }
    Column(modifier = modifier) {
    		Text(
            text = stringResource(R.string.chart_preview_music_download_percent, progress.percent),
            style = MiuixTheme.textStyles.footnote2,
            color = Color.White,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(R.string.chart_preview_music_download_speed, speed),
            style = MiuixTheme.textStyles.footnote2,
            color = Color.White,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun PreviewStageOverlay(clock: PreviewClock, waitingForAudio: Boolean) {
    if (waitingForAudio) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        colors = ProgressIndicatorDefaults.progressIndicatorColors(
                            foregroundColor = Color.White,
                            backgroundColor = Color.Transparent,
                        ),
                        size = 28.dp,
                    )
                }
                Text(
                    text = stringResource(R.string.chart_preview_audio_loading),
                    style = MiuixTheme.textStyles.footnote1,
                    color = Color.White,
                )
            }
        }
        return
    }
    if (clock.isPlaying) return
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.45f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.PlayArrow,
                contentDescription = stringResource(R.string.chart_preview_play),
                tint = Color.White,
                modifier = Modifier.size(36.dp),
            )
        }
    }
}

@Composable
private fun PreviewDifficultySelector(
    options: List<PreviewChartOption>,
    selectedSheetKey: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val multipleTypes = options.map { it.sheet.type.lowercase() }.distinct().size > 1
    val tabs = options.map { option ->
        buildString {
            if (multipleTypes) {
                append(
                    when {
                        option.sheet.type.equals("dx", ignoreCase = true) -> "DX "
                        option.sheet.type.contains("utage", ignoreCase = true) -> "宴 "
                        else -> "STD "
                    },
                )
            }
            append(
                if (option.sheet.type.contains("utage", ignoreCase = true)) {
                    option.sheet.difficulty.ifBlank { "宴" }
                } else {
                    option.sheet.difficulty.shortDifficultyName()
                },
            )
        }
    }
    val selectedIndex = options
        .indexOfFirst { it.sheet.sheetKey == selectedSheetKey }
        .coerceAtLeast(0)
    TabRow(
        tabs = tabs,
        selectedTabIndex = selectedIndex,
        onTabSelected = { index -> options.getOrNull(index)?.let { onSelect(it.sheet.sheetKey) } },
        modifier = modifier,
    )
}

@Composable
private fun PreviewTransport(
    clock: PreviewClock,
    enabled: Boolean,
    soundEnabled: Boolean,
    onTogglePlay: () -> Unit,
    onToggleSound: () -> Unit,
    onPreviousMeasure: () -> Unit,
    onNextMeasure: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Row(
            modifier = Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            IconButton(onClick = onPreviousMeasure) {
                Icon(
                    Icons.Rounded.ChevronLeft,
                    contentDescription = stringResource(R.string.chart_previous_measure),
                )
            }
            IconButton(onClick = onTogglePlay, enabled = enabled) {
                Icon(
                    if (clock.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = stringResource(
                        if (clock.isPlaying) {
                            R.string.chart_preview_pause
                        } else {
                            R.string.chart_preview_play
                        },
                    ),
                )
            }
            IconButton(onClick = onNextMeasure) {
                Icon(
                    Icons.Rounded.ChevronRight,
                    contentDescription = stringResource(R.string.chart_next_measure),
                )
            }
        }
        IconButton(
            onClick = onToggleSound,
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            Icon(
                if (soundEnabled) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff,
                contentDescription = stringResource(
                    if (soundEnabled) {
                        R.string.chart_preview_sound_off
                    } else {
                        R.string.chart_preview_sound_on
                    },
                ),
            )
        }
    }
}

@Stable
private class PreviewClock(val durationMs: Double) {
    val position = mutableDoubleStateOf(0.0)
    var isPlaying by mutableStateOf(false)
    var isScrubbing by mutableStateOf(false)
    var wasPlayingBeforeScrub: Boolean = false
}

/**
 * True while the selected sheet's track is still downloading or being handed to MediaPlayer.
 * Sheets without a track and tracks that failed to prepare are not pending, so the chart stays
 * usable (silently in the failure case) instead of waiting forever.
 */
internal fun previewAudioPending(hasAudio: Boolean, audioState: ChartAudioState): Boolean =
    hasAudio && when (audioState) {
        ChartAudioState.Idle, ChartAudioState.Preparing -> true
        is ChartAudioState.Ready, is ChartAudioState.Failed -> false
    }

private const val AUDIO_STATS_INTERVAL_MS = 5_000L
private const val DOWNLOAD_PROGRESS_POLL_MS = 100L
private const val TAG_AUDIO = "ChartPreviewAudio"
private const val BOOST_SPEED = 1.5f
private const val CHART_SAVE_SNACKBAR_MS = 2_500L


/** File name offered by the save dialog; strips characters file systems reject. */
private fun chartSaveFileName(title: String?, difficulty: String?): String {
    val base = listOfNotNull(
        title?.trim()?.takeIf { it.isNotEmpty() },
        difficulty?.trim()?.takeIf { it.isNotEmpty() },
    ).joinToString(" - ").ifEmpty { "chart" }
    val sanitized = base.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
    return "${sanitized.take(120)}.zip"
}

/** Counts playable objects (hold tails and touch hold tails are not separate objects). */

private fun decodeCover(bytes: ByteArray): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
    BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample },
    )
}.getOrNull()

private fun decodeCover(file: java.io.File?): Bitmap? {
    if (file == null || !file.isFile) return null
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
        BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }.getOrNull()
}



private fun String.shortDifficultyName(): String = when {
    contains("remaster", ignoreCase = true) -> "ReM"
    contains("master", ignoreCase = true) -> "MAS"
    contains("expert", ignoreCase = true) -> "EXP"
    contains("advanced", ignoreCase = true) -> "ADV"
    contains("basic", ignoreCase = true) -> "BAS"
    contains("utage", ignoreCase = true) -> "宴"
    else -> uppercase()
}


private fun loadSensorImage(assets: android.content.res.AssetManager): Bitmap? =
    runCatching {
        assets.open("maimai/chart/sensor.webp").use(BitmapFactory::decodeStream)
    }.getOrNull()
