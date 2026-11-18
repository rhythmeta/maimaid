package org.rhythmeta.maimaid.ui.song

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.abs
import kotlinx.coroutines.flow.collectLatest
import org.rhythmeta.maimaid.R
import org.rhythmeta.maimaid.core.AppContainer
import org.rhythmeta.maimaid.core.chart.Chart
import org.rhythmeta.maimaid.core.chart.ChartAudioEvents
import org.rhythmeta.maimaid.core.chart.ChartAudioState
import org.rhythmeta.maimaid.core.chart.ChartPreviewSettings
import org.rhythmeta.maimaid.core.chart.ChartRenderConfig
import org.rhythmeta.maimaid.core.chart.ChartSfxPlayer
import org.rhythmeta.maimaid.core.chart.ChartSoundScheduler
import org.rhythmeta.maimaid.core.chart.SimaiParser
import org.rhythmeta.maimaid.core.chart.TimingTimeline
import org.rhythmeta.maimaid.core.chart.render.ChartRenderer
import kotlinx.coroutines.launch
import org.rhythmeta.maimaid.ui.components.squircleShape
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
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
    val seekCompletions by viewModel.seekCompletions.collectAsStateWithLifecycle()
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
                seekCompletions = seekCompletions,
                onSettingsChange = { next -> viewModel.updateSettings { next } },
                onSelectSheet = viewModel::selectSheet,
                onMusicVolumeChange = viewModel::setMusicVolume,
                onPlaybackSpeedChange = viewModel::setPlaybackSpeed,
                renderConfig = renderConfig,
                contentTopPadding = contentTopPadding,
                onPauseAudio = viewModel::pauseAudio,
                onSeekAudio = viewModel::seekAudio,
                audioPositionMillis = viewModel::audioPositionMillis,
                isAudioPrepared = { viewModel.isAudioPrepared },
                onPlayAudio = viewModel::playAudio,
                isAudioPlaying = { viewModel.isAudioPlaying },
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
    seekCompletions: Int,
    onSettingsChange: (ChartPreviewSettings) -> Unit,
    onSelectSheet: (String) -> Unit,
    onMusicVolumeChange: (Float) -> Unit,
    onPlaybackSpeedChange: (Float) -> Unit,
    renderConfig: ChartRenderConfig,
    contentTopPadding: Dp,
    onPauseAudio: () -> Unit,
    onSeekAudio: (Int, Boolean) -> Unit,
    audioPositionMillis: () -> Int,
    isAudioPrepared: () -> Boolean,
    onPlayAudio: () -> Unit,
    isAudioPlaying: () -> Boolean,
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

    val context = LocalContext.current
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
    val sfxPlayer = remember { ChartSfxPlayer(context) }
    val scheduler = remember(chart) {
        ChartSoundScheduler(ChartAudioEvents.prepare(chart.notes))
    }
    // Community MP3s carry a sharper cover than the catalog image; prefer it when present.
    val cover = remember(coverFile, embeddedCover) {
        embeddedCover?.let(::decodeCover) ?: decodeCover(coverFile)
    }
    var soundEnabled by rememberSaveable { mutableStateOf(true) }
    DisposableEffect(sfxPlayer) {
        onDispose { sfxPlayer.release() }
    }
    DisposableEffect(cover) {
        onDispose { cover?.recycle() }
    }
    LaunchedEffect(soundEnabled) {
        sfxPlayer.enabled = soundEnabled
    }
    val latestMusicOffsetMs by rememberUpdatedState(settings.musicOffsetMs)
    val latestSeekCompletions by rememberUpdatedState(seekCompletions)

    LaunchedEffect(settings.musicOffsetMs) {
        clock.audioStarted = false
        if (!clock.isPlaying && isAudioPrepared()) {
            val target = audioTargetMillis(clock.position.doubleValue, chart, settings.musicOffsetMs)
            onSeekAudio(target, false)
        }
    }
    LaunchedEffect(settings.musicVolume) {
        onMusicVolumeChange(settings.musicVolume)
    }
    LaunchedEffect(settings.playbackSpeed) {
        onPlaybackSpeedChange(settings.playbackSpeed.toFloat())
    }
    LaunchedEffect(settings.answerVolume, settings.judgeVolume) {
        sfxPlayer.answerVolume = settings.answerVolume
        sfxPlayer.judgeVolume = settings.judgeVolume
    }
    LaunchedEffect(settings.sfxOffsetMs) {
        scheduler.setUserOffset(settings.sfxOffsetMs.toDouble(), clock.position.doubleValue)
    }
    LaunchedEffect(chart) {
        clock.position.doubleValue = 0.0
        scheduler.reset(0.0)
        clock.isPlaying = true
    }

    LaunchedEffect(chart, clock) {
        snapshotFlow { clock.isPlaying }.collectLatest { playing ->
            if (!playing) {
                onPauseAudio()
                clock.audioStarted = false
                scheduler.reset(clock.position.doubleValue)
                sfxPlayer.stopAll()
                return@collectLatest
            }
            var lastFrameNanos = 0L
            while (clock.isPlaying) {
                withFrameNanos { now ->
                    if (clock.isScrubbing) {
                        lastFrameNanos = now
                        return@withFrameNanos
                    }
                    if (clock.holdForSeek) {
                        val nowUptime = android.os.SystemClock.uptimeMillis()
                        if (latestSeekCompletions != clock.seekHoldBase ||
                            nowUptime > clock.seekHoldDeadlineMs
                        ) {
                            clock.holdForSeek = false
                        } else {
                            lastFrameNanos = now
                            return@withFrameNanos
                        }
                    }
                    if (lastFrameNanos != 0L) {
                        val deltaMs = (now - lastFrameNanos) / 1_000_000.0

                        // Establish the music anchor before scheduling any SFX. A resume or
                        // seek can leave MediaPlayer a few milliseconds from the target; starting
                        // it directly avoids adding another asynchronous seek latency.
                        if (isAudioPrepared() && !clock.audioStarted && !clock.holdForSeek) {
                            val target = audioTargetMillis(
                                clock.position.doubleValue,
                                chart,
                                latestMusicOffsetMs,
                            )
                            if (target >= 0) {
                                if (abs(audioPositionMillis() - target) <= AUDIO_START_TOLERANCE_MS) {
                                    onPlayAudio()
                                } else {
                                    clock.beginSeekHold(latestSeekCompletions)
                                    onSeekAudio(target, true)
                                }
                                clock.audioStarted = true
                            }
                        }

                        val audioPlaying = clock.audioStarted &&
                            !clock.holdForSeek &&
                            isAudioPrepared() && isAudioPlaying()
                        val audioMasterPosition = if (
                            audioPlaying
                        ) {
                            (chart.leadInMs + audioPositionMillis() - latestMusicOffsetMs)
                                .coerceIn(0.0, clock.durationMs)
                        } else {
                            (clock.position.doubleValue + deltaMs * settings.playbackSpeed)
                                .coerceAtMost(clock.durationMs)
                        }
                        clock.position.doubleValue = audioMasterPosition

                        if (audioMasterPosition >= clock.durationMs) {
                            clock.isPlaying = false
                            clock.audioStarted = false
                            onPauseAudio()
                            sfxPlayer.stopAll()
                        }

                        // Do not emit SFX while music is paused or its seek is settling. This
                        // prevents the chart from getting ahead of the resumed music track.
                        if (audioPlaying) {
                            scheduler.advance(
                                currentTimeMs = clock.position.doubleValue,
                                player = sfxPlayer,
                                // SoundPool has no absolute future-start API. A wall-clock
                                // Handler delay would make SFX run ahead of MediaPlayer after a
                                // pause/seek, so trigger from the shared media position instead.
                                lookAheadMs = 0.0,
                            )
                        }

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
            if (clock.wasPlayingBeforeScrub) onPauseAudio()
        }
        clock.position.doubleValue = positionMs.coerceIn(0.0, clock.durationMs)
    }
    val onSeekFinished: () -> Unit = {
        clock.isScrubbing = false
        scheduler.reset(clock.position.doubleValue)
        sfxPlayer.stopAll()
        clock.audioStarted = false
        if (isAudioPrepared()) {
            val target = audioTargetMillis(
                clock.position.doubleValue,
                chart,
                settings.musicOffsetMs,
            )
            if (clock.wasPlayingBeforeScrub) {
                clock.audioStarted = true
                clock.beginSeekHold(latestSeekCompletions)
                onSeekAudio(target, true)
            } else {
                onSeekAudio(target, false)
            }
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

    val onTogglePlay: () -> Unit = {
        if (clock.position.doubleValue >= clock.durationMs) {
            clock.position.doubleValue = 0.0
            scheduler.reset(0.0)
        }
        clock.audioStarted = false
        clock.isPlaying = !clock.isPlaying
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
                .clickable { onTogglePlay() }
                .semantics {
                    contentDescription = stageDescription
                    role = Role.Button
                },
        ) {
            ChartStage(chart = chart, clock = clock, config = renderConfig, cover = cover)
            PreviewStageOverlay(clock = clock)
        }

        Spacer(Modifier.height(12.dp))
        PreviewTransport(
            clock = clock,
            enabled = true,
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
        AudioStatus(audioState = audioState, hasAudio = option.entry.files.audio != null)

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
                beatsPerMeasure = 4.0,
                config = config,
                isPlaying = clock.isPlaying,
                background = cover,
                sensorImage = sensorImage,
            )
        }
    }
}

@Composable
private fun PreviewStageOverlay(clock: PreviewClock) {
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

@Composable
private fun AudioStatus(audioState: ChartAudioState, hasAudio: Boolean) {
    val message = when {
        !hasAudio -> stringResource(R.string.chart_preview_audio_unavailable)
        audioState is ChartAudioState.Preparing -> stringResource(R.string.chart_preview_audio_loading)
        audioState is ChartAudioState.Failed -> audioState.message
        else -> null
    } ?: return
    Text(
        text = message,
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}


@Stable
private class PreviewClock(val durationMs: Double) {
    val position = mutableDoubleStateOf(0.0)
    var isPlaying by mutableStateOf(false)
    var isScrubbing by mutableStateOf(false)
    var wasPlayingBeforeScrub: Boolean = false
    var audioStarted: Boolean = false
    var holdForSeek: Boolean = false
    var seekHoldBase: Int = 0
    var seekHoldDeadlineMs: Long = 0L

    fun beginSeekHold(currentCompletions: Int) {
        holdForSeek = true
        seekHoldBase = currentCompletions
        seekHoldDeadlineMs = android.os.SystemClock.uptimeMillis() + 600
    }
}

private fun audioTargetMillis(positionMs: Double, chart: Chart, audioOffsetMs: Int): Int =
    (positionMs - chart.leadInMs - audioOffsetMs)
        .roundToInt()
        .coerceAtLeast(0)

private const val AUDIO_START_TOLERANCE_MS = 150
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
