package org.rhythmeta.maimaid.ui.song

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DisplaySettings
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.rhythmeta.maimaid.R
import org.rhythmeta.maimaid.core.chart.ChartPreviewSettings
import org.rhythmeta.maimaid.core.chart.JudgmentLineDesign
import org.rhythmeta.maimaid.core.chart.MirrorMode
import org.rhythmeta.maimaid.ui.components.SquircleExtension
import org.rhythmeta.maimaid.ui.components.squircleShape
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

@Composable
internal fun ChartSpeedSettings(
    settings: ChartPreviewSettings,
    onChange: (ChartPreviewSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    SettingsCard(
        modifier = modifier,
        title = stringResource(R.string.chart_settings_speed),
        icon = Icons.Rounded.Settings,
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        SettingSlider(
            label = stringResource(R.string.chart_settings_hi_speed),
            value = settings.hiSpeed.toFloat(),
            range = 3f..9f,
            format = { "%.2f".format(it) },
            onCommit = { onChange(settings.copy(hiSpeed = it.toDouble())) },
        )
        SettingSlider(
            label = stringResource(R.string.chart_settings_playback_speed),
            value = settings.playbackSpeed.toFloat(),
            range = 0.1f..2f,
            format = { "%.2fx".format(it) },
            onCommit = { onChange(settings.copy(playbackSpeed = it.toDouble())) },
        )
        SettingSlider(
            label = stringResource(R.string.chart_settings_rhythm_speed),
            value = settings.rhythmSpeed.toFloat(),
            range = 0.5f..2f,
            format = { "%.2fx".format(it) },
            onCommit = {
                val stepped = ((it * 100f).roundToInt() / 100f).toDouble()
                onChange(settings.copy(rhythmSpeed = stepped))
            },
        )
        SettingSlider(
            label = stringResource(R.string.chart_settings_slide_delay),
            value = settings.slideDelay.toFloat(),
            range = -1f..1f,
            format = { "%.1f".format(it) },
            onCommit = {
                val stepped = ((it * 10f).roundToInt() / 10f).toDouble()
                onChange(settings.copy(slideDelay = stepped))
            },
        )
        SwitchRow(
            title = stringResource(R.string.chart_settings_keep_hi_speed),
            checked = settings.alwaysKeepHiSpeed,
            onCheckedChange = { onChange(settings.copy(alwaysKeepHiSpeed = it)) },
        )
    }
}

@Composable
internal fun ChartDisplaySettings(
    settings: ChartPreviewSettings,
    onChange: (ChartPreviewSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    SettingsCard(
        modifier = modifier,
        title = stringResource(R.string.chart_settings_display),
        icon = Icons.Rounded.DisplaySettings,
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        SettingChipRow(
            title = stringResource(R.string.chart_settings_mirror),
            options = listOf(
                stringResource(R.string.chart_settings_mirror_none) to MirrorMode.NONE,
                stringResource(R.string.chart_settings_mirror_horizontal) to MirrorMode.HORIZONTAL,
                stringResource(R.string.chart_settings_mirror_vertical) to MirrorMode.VERTICAL,
                stringResource(R.string.chart_settings_mirror_rotate) to MirrorMode.ROTATE180,
            ),
            selected = settings.mirrorMode,
            onSelect = { onChange(settings.copy(mirrorMode = it)) },
        )
        SettingChipRow(
            title = stringResource(R.string.chart_settings_judgment),
            options = listOf(
                stringResource(R.string.chart_settings_judgment_hidden) to JudgmentLineDesign.BLIND,
                stringResource(R.string.chart_settings_judgment_no_line) to JudgmentLineDesign.NO_LINE,
                stringResource(R.string.chart_settings_judgment_simple) to JudgmentLineDesign.SIMPLE,
                stringResource(R.string.chart_settings_judgment_sensor) to JudgmentLineDesign.SENSOR,
            ),
            selected = settings.judgmentLineDesign,
            onSelect = { onChange(settings.copy(judgmentLineDesign = it)) },
        )
        SettingToggleRow(
            title = stringResource(R.string.chart_settings_effects),
            options = listOf(
                stringResource(R.string.chart_settings_slide_rotation) to settings.slideRotation,
                stringResource(R.string.chart_settings_pink_slide) to settings.pinkSlideStart,
                stringResource(R.string.chart_settings_highlight_ex) to settings.highlightExNotes,
                stringResource(R.string.chart_settings_break_slide_normal) to
                    settings.normalColorBreakSlide,
                stringResource(R.string.chart_settings_fireworks) to settings.showFireworks,
                stringResource(R.string.chart_settings_hit_effect) to settings.showHitEffect,
            ),
            onToggle = { index, checked ->
                onChange(
                    when (index) {
                        0 -> settings.copy(slideRotation = checked)
                        1 -> settings.copy(pinkSlideStart = checked)
                        2 -> settings.copy(highlightExNotes = checked)
                        3 -> settings.copy(normalColorBreakSlide = checked)
                        4 -> settings.copy(showFireworks = checked)
                        else -> settings.copy(showHitEffect = checked)
                    },
                )
            },
        )
        SettingToggleRow(
            title = stringResource(R.string.chart_settings_counts),
            options = listOf(
                stringResource(R.string.chart_settings_note_total) to settings.showNoteTotal,
                stringResource(R.string.chart_settings_break_count) to settings.showBreakCount,
            ),
            onToggle = { index, checked ->
                onChange(
                    if (index == 0) {
                        settings.copy(showNoteTotal = checked)
                    } else {
                        settings.copy(showBreakCount = checked)
                    },
                )
            },
        )
    }
}

@Composable
internal fun ChartAudioSettings(
    settings: ChartPreviewSettings,
    onChange: (ChartPreviewSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    SettingsCard(
        modifier = modifier,
        title = stringResource(R.string.chart_settings_audio),
        icon = Icons.AutoMirrored.Rounded.VolumeUp,
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        SettingSlider(
            label = stringResource(R.string.chart_settings_music_volume),
            value = settings.musicVolume,
            range = 0f..1f,
            format = { "${(it * 100).roundToInt()}%" },
            onCommit = { onChange(settings.copy(musicVolume = it)) },
        )
        SettingSlider(
            label = stringResource(R.string.chart_settings_answer_volume),
            value = settings.answerVolume,
            range = 0f..1f,
            format = { "${(it * 100).roundToInt()}%" },
            onCommit = { onChange(settings.copy(answerVolume = it)) },
        )
        SettingSlider(
            label = stringResource(R.string.chart_settings_judge_volume),
            value = settings.judgeVolume,
            range = 0f..1f,
            format = { "${(it * 100).roundToInt()}%" },
            onCommit = { onChange(settings.copy(judgeVolume = it)) },
        )
        SettingOffsetRow(
            label = stringResource(R.string.chart_settings_music_offset),
            value = settings.musicOffsetMs,
            onChange = { onChange(settings.copy(musicOffsetMs = it)) },
        )
        SettingOffsetRow(
            label = stringResource(R.string.chart_settings_sfx_offset),
            value = settings.sfxOffsetMs,
            onChange = { onChange(settings.copy(sfxOffsetMs = it)) },
        )
    }
}

@Composable
private fun SettingsCard(
    modifier: Modifier = Modifier,
    title: String,
    icon: ImageVector,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    content: @Composable () -> Unit,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            val arrowRotation by animateFloatAsState(
                targetValue = if (expanded) 180f else 0f,
                label = "settings-chevron",
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onExpandedChange(!expanded) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(icon, contentDescription = null)
                Text(
                    text = title,
                    style = MiuixTheme.textStyles.title4,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.rotate(arrowRotation),
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun SettingSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    onCommit: (Float) -> Unit,
) {
    var sliderValue by remember(value) { mutableFloatStateOf(value) }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MiuixTheme.textStyles.body2,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = format(sliderValue),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        Spacer(Modifier.height(6.dp))
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = { onCommit(sliderValue) },
            valueRange = range,
        )
    }
}

@Composable
private fun <T> SettingChipRow(
    title: String,
    options: List<Pair<String, T>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = title,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.height(6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { (label, value) ->
                SettingChip(
                    label = label,
                    selected = value == selected,
                    onClick = { onSelect(value) },
                )
            }
        }
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    options: List<Pair<String, Boolean>>,
    onToggle: (Int, Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = title,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.height(6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEachIndexed { index, (label, checked) ->
                SettingChip(
                    label = label,
                    selected = checked,
                    onClick = { onToggle(index, !checked) },
                )
            }
        }
    }
}

@Composable
private fun SettingChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val background = if (selected) {
        MiuixTheme.colorScheme.primary
    } else {
        MiuixTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    }
    val border = if (selected) {
        MiuixTheme.colorScheme.primary.copy(alpha = 0.45f)
    } else {
        MiuixTheme.colorScheme.onSurface.copy(alpha = 0.09f)
    }
    val foreground = if (selected) Color.White else MiuixTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .squircleSurface(color = background, cornerRadius = 50.dp, extension = SquircleExtension)
            .border(width = 1.dp, color = border, shape = CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = MiuixTheme.textStyles.footnote1, color = foreground)
    }
}

@Composable
private fun SettingOffsetRow(
    label: String,
    value: Int,
    onChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.body2,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { onChange((value - 10).coerceIn(-500, 500)) }) {
            Icon(Icons.Rounded.Remove, contentDescription = null)
        }
        var text by remember(value) { mutableStateOf(value.toString()) }
        fun commit() {
            val parsed = text.toIntOrNull() ?: value
            val clamped = parsed.coerceIn(-500, 500)
            text = clamped.toString()
            if (clamped != value) onChange(clamped)
        }
        val bringIntoView = remember { BringIntoViewRequester() }
			var focused by remember { mutableStateOf(false) }
        LaunchedEffect(focused) {
            if (focused) {
                kotlinx.coroutines.delay(180.milliseconds)
                bringIntoView.bringIntoView()
            }
        }
        BasicTextField(
            value = text,
            onValueChange = { input ->
                text = input.filter { it.isDigit() || it == '-' }.take(4)
            },
            textStyle = MiuixTheme.textStyles.body2.copy(
                color = MiuixTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            ),
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { commit() }),
            cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
            modifier = Modifier
                .width(72.dp)
                .bringIntoViewRequester(bringIntoView)
                .onFocusChanged { state ->
                    focused = state.isFocused
                    if (!state.isFocused) commit()
                }
                .border(
                    width = 1.dp,
                    color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                    shape = squircleShape(8.dp),
                )
                .padding(horizontal = 10.dp, vertical = 7.dp),
        )
        IconButton(onClick = { onChange((value + 10).coerceIn(-500, 500)) }) {
            Icon(Icons.Rounded.Add, contentDescription = null)
        }
        IconButton(onClick = { onChange(0) }) {
            Icon(
                Icons.Rounded.Refresh,
                contentDescription = stringResource(R.string.chart_settings_reset),
            )
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MiuixTheme.textStyles.body2,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
