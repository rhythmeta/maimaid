package org.rhythmeta.maimaid.ui.components

import android.view.Window
import android.view.WindowManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.snapTo
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur
import kotlinx.coroutines.launch
import org.rhythmeta.maimaid.R
import org.rhythmeta.maimaid.core.data.Best50FilterSettings
import org.rhythmeta.maimaid.ui.theme.LocalEnableBlur
import org.rhythmeta.maimaid.ui.util.ScoreStatusColors
import org.rhythmeta.maimaid.ui.util.SongVisualUtils
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

private enum class BottomSheetAnchor {
    Expanded,
    HalfExpanded,
    Hidden,
}

@Composable
internal fun ExpandableBottomSheet(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    expandActionLabel: String,
    collapseActionLabel: String,
    expandedStateDescription: String,
    halfExpandedStateDescription: String,
    header: @Composable BoxScope.() -> Unit,
    content: @Composable (topInset: Dp) -> Unit,
) {
    var mounted by remember { mutableStateOf(visible) }
    var locallyDismissed by remember { mutableStateOf(false) }
    val currentDismissRequest by rememberUpdatedState(onDismissRequest)
    val dialogWindowHandle = remember { DialogWindowHandle() }

    LaunchedEffect(visible) {
        if (visible) {
            mounted = true
        } else {
            locallyDismissed = false
        }
    }

    if ((visible || mounted) && !locallyDismissed) {
        Dialog(
            onDismissRequest = currentDismissRequest,
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            ConfigurePlatformDialog(dialogWindowHandle)
            ExpandableBottomSheetLayout(
                visible = visible,
                expandActionLabel = expandActionLabel,
                collapseActionLabel = collapseActionLabel,
                expandedStateDescription = expandedStateDescription,
                halfExpandedStateDescription = halfExpandedStateDescription,
                onDismissRequest = currentDismissRequest,
                onExitAnimationFinished = { mounted = false },
                onGestureDismissed = {
                    dialogWindowHandle.disableTouch()
                    locallyDismissed = true
                    mounted = false
                    currentDismissRequest()
                },
                header = header,
                content = content,
            )
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ExpandableBottomSheetLayout(
    visible: Boolean,
    expandActionLabel: String,
    collapseActionLabel: String,
    expandedStateDescription: String,
    halfExpandedStateDescription: String,
    onDismissRequest: () -> Unit,
    onExitAnimationFinished: () -> Unit,
    onGestureDismissed: () -> Unit,
    header: @Composable BoxScope.() -> Unit,
    content: @Composable (topInset: Dp) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val sheetHeight = (maxHeight - statusBarHeight).coerceAtLeast(1.dp)
        val hiddenOffset = with(density) { sheetHeight.toPx() }
        val halfExpandedOffset = hiddenOffset * HalfExpandedOffsetFraction
        val state = remember { AnchoredDraggableState(BottomSheetAnchor.Hidden) }
        val allAnchors = remember(hiddenOffset, halfExpandedOffset) {
            DraggableAnchors {
                BottomSheetAnchor.Expanded at 0f
                BottomSheetAnchor.HalfExpanded at halfExpandedOffset
                BottomSheetAnchor.Hidden at hiddenOffset
            }
        }
        val anchors = remember(hiddenOffset, halfExpandedOffset, state.settledValue) {
            DraggableAnchors {
                when (state.settledValue) {
                    BottomSheetAnchor.Expanded -> {
                        BottomSheetAnchor.Expanded at 0f
                        BottomSheetAnchor.HalfExpanded at halfExpandedOffset
                        BottomSheetAnchor.Hidden at hiddenOffset
                    }

                    BottomSheetAnchor.HalfExpanded -> {
                        BottomSheetAnchor.Expanded at 0f
                        BottomSheetAnchor.HalfExpanded at halfExpandedOffset
                        BottomSheetAnchor.Hidden at hiddenOffset
                    }

                    BottomSheetAnchor.Hidden -> {
                        BottomSheetAnchor.HalfExpanded at halfExpandedOffset
                        BottomSheetAnchor.Hidden at hiddenOffset
                    }
                }
            }
        }
        var entranceComplete by remember { mutableStateOf(false) }

        LaunchedEffect(anchors) {
            state.updateAnchors(anchors)
        }
        LaunchedEffect(visible) {
            state.updateAnchors(allAnchors)
            if (visible) {
                entranceComplete = false
                state.snapTo(BottomSheetAnchor.Hidden)
                state.animateTo(BottomSheetAnchor.HalfExpanded, BottomSheetAnimationSpec)
                entranceComplete = true
            } else {
                entranceComplete = false
                state.animateTo(BottomSheetAnchor.Hidden, BottomSheetAnimationSpec)
                onExitAnimationFinished()
            }
        }
        LaunchedEffect(state.settledValue, entranceComplete, visible) {
            if (
                visible &&
                entranceComplete &&
                state.settledValue == BottomSheetAnchor.Hidden
            ) {
                onGestureDismissed()
            }
        }
        val isImeVisible = WindowInsets.isImeVisible
        LaunchedEffect(isImeVisible, visible, entranceComplete) {
            if (
                isImeVisible &&
                visible &&
                entranceComplete &&
                state.settledValue == BottomSheetAnchor.HalfExpanded
            ) {
                state.animateTo(BottomSheetAnchor.Expanded, BottomSheetAnimationSpec)
            }
        }

        val offset = state.offset.takeUnless(Float::isNaN) ?: hiddenOffset
        val visibleSheetHeight = sheetHeight - with(density) { offset.toDp() }
        val dimProgress = ((hiddenOffset - offset) / (hiddenOffset - halfExpandedOffset))
            .coerceIn(0f, 1f)
        val sheetBackground = MiuixTheme.colorScheme.surface
        val sheetShape = remember { squircleShape(BottomSheetCornerRadius) }
        val sheetBackdrop = rememberLayerBackdrop {
            drawRect(sheetBackground)
            drawContent()
        }
        val outsideInteraction = remember { MutableInteractionSource() }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = dimProgress }
                .background(MiuixTheme.colorScheme.windowDimming)
                .clickable(
                    interactionSource = outsideInteraction,
                    indication = null,
                    onClick = onDismissRequest,
                ),
        )

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(sheetHeight)
                .offset { IntOffset(0, offset.roundToInt()) }
                .graphicsLayer {
                    shape = sheetShape
                    clip = true
                    shadowElevation = 18.dp.toPx()
                }
                .background(sheetBackground),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(visibleSheetHeight.coerceAtLeast(0.dp))
                    .layerBackdrop(sheetBackdrop)
                    .navigationBarsPadding(),
            ) {
                content(BottomSheetHeaderHeight)
            }
            ExpandableBottomSheetHeader(
                state = state,
                backdrop = sheetBackdrop,
                expandActionLabel = expandActionLabel,
                collapseActionLabel = collapseActionLabel,
                expandedStateDescription = expandedStateDescription,
                halfExpandedStateDescription = halfExpandedStateDescription,
                content = header,
            )
        }
    }
}

@Composable
private fun ExpandableBottomSheetHeader(
    state: AnchoredDraggableState<BottomSheetAnchor>,
    backdrop: Backdrop,
    expandActionLabel: String,
    collapseActionLabel: String,
    expandedStateDescription: String,
    halfExpandedStateDescription: String,
    content: @Composable BoxScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val enableBlur = LocalEnableBlur.current
    val sheetBackground = MiuixTheme.colorScheme.surface
    val expanded = state.settledValue == BottomSheetAnchor.Expanded
    val actionLabel = if (expanded) collapseActionLabel else expandActionLabel
    val stateLabel = if (expanded) expandedStateDescription else halfExpandedStateDescription
    val flingBehavior = AnchoredDraggableDefaults.flingBehavior(
        state = state,
        animationSpec = BottomSheetAnimationSpec,
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (enableBlur) {
                    Modifier.drawPlainBackdrop(
                        backdrop = backdrop,
                        shape = { RectangleShape },
                        effects = { blur(BottomSheetHeaderBlurRadius.toPx()) },
                        onDrawSurface = {
                            drawRect(sheetBackground.copy(alpha = 0.52f))
                        },
                    )
                } else {
                    Modifier.background(sheetBackground)
                },
            )
            .anchoredDraggable(
                state = state,
                orientation = Orientation.Vertical,
                flingBehavior = flingBehavior,
            )
            .semantics {
                stateDescription = stateLabel
                onClick(label = actionLabel) {
                    scope.launch {
                        state.animateTo(
                            if (expanded) {
                                BottomSheetAnchor.HalfExpanded
                            } else {
                                BottomSheetAnchor.Expanded
                            },
                            BottomSheetAnimationSpec,
                        )
                    }
                    true
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 8.dp, bottom = 4.dp)
                .size(width = 36.dp, height = 4.dp)
                .squircleSurface(
                    color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.24f),
                    cornerRadius = 2.dp,
                    extension = SquircleExtension,
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = BottomSheetHeaderHorizontalPadding),
            content = content,
        )
    }
}

private class DialogWindowHandle {
    var window: Window? = null

    fun disableTouch() {
        window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
    }
}

@Composable
private fun ConfigurePlatformDialog(handle: DialogWindowHandle) {
    val view = LocalView.current
    DisposableEffect(view, handle) {
        val window = (view.parent as? DialogWindowProvider)?.window
        handle.window = window
        window?.apply {
            clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            setDimAmount(0f)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        onDispose {
            if (handle.window === window) {
                handle.window = null
            }
        }
    }
}

/**
 * Best 50 options sheet: theoretical mode, game version, capacity, calculation constants, and the
 * quick filters that narrow the rendered list (difficulty, category, chart type, rank, FC, and FS).
 * Theoretical mode keeps only the version and version-constant controls because the list is a
 * synthetic maximum instead of the player's scores.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun BestTableSettingsSheet(
    visible: Boolean,
    versionLabels: List<String>,
    selectedVersionIndex: Int,
    versionOverridden: Boolean,
    constantModeLabels: List<String>,
    selectedConstantModeIndex: Int,
    b35Text: String,
    b15Text: String,
    categories: List<String>,
    filterSettings: Best50FilterSettings,
    theoretical: Boolean,
    onVersionSelected: (Int) -> Unit,
    onConstantModeSelected: (Int) -> Unit,
    onB35TextChange: (String) -> Unit,
    onB15TextChange: (String) -> Unit,
    onCapacityCommit: () -> Unit,
    onFilterSettingsChange: (Best50FilterSettings) -> Unit,
    onTheoreticalChange: (Boolean) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val darkTheme = SongVisualUtils.isDarkTheme(MiuixTheme.colorScheme.background)
    val canReset = !filterSettings.isEmpty ||
        versionOverridden ||
        selectedConstantModeIndex != 0 ||
        (b35Text.toIntOrNull() ?: BestCapacityOldDefault) != BestCapacityOldDefault ||
        (b15Text.toIntOrNull() ?: BestCapacityNewDefault) != BestCapacityNewDefault
    val resetEnabled = canReset && !theoretical
    ExpandableBottomSheet(
        visible = visible,
        onDismissRequest = onDismiss,
        expandActionLabel = stringResource(R.string.catalog_filter_expand),
        collapseActionLabel = stringResource(R.string.catalog_filter_collapse),
        expandedStateDescription = stringResource(R.string.catalog_filter_sheet_expanded),
        halfExpandedStateDescription = stringResource(R.string.catalog_filter_sheet_half),
        header = {
            IconButton(
                onClick = onReset,
                enabled = resetEnabled,
                modifier = Modifier.align(Alignment.CenterStart),
            ) {
                Icon(
                    imageVector = Icons.Rounded.RestartAlt,
                    contentDescription = stringResource(R.string.catalog_filter_reset),
                    tint = if (resetEnabled) {
                        MiuixTheme.colorScheme.onSurface
                    } else {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    },
                )
            }
            Text(
                text = stringResource(R.string.best50_settings_title),
                style = MiuixTheme.textStyles.title3,
                modifier = Modifier.align(Alignment.Center),
                maxLines = 1,
            )
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterEnd)) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = stringResource(R.string.catalog_filter_done),
                    tint = MiuixTheme.colorScheme.primary,
                )
            }
        },
    ) { topInset ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp,
                top = topInset + 12.dp,
                end = 20.dp,
                bottom = 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 16.dp,
                    insideMargin = PaddingValues(0.dp),
                ) {
                    Column {
                        SwitchPreference(
                            checked = theoretical,
                            onCheckedChange = onTheoreticalChange,
                            title = stringResource(R.string.best50_theoretical),
                            summary = stringResource(R.string.best50_theoretical_summary),
                        )
                        WindowDropdownPreference(
                            items = versionLabels,
                            selectedIndex = selectedVersionIndex,
                            title = stringResource(R.string.best50_version_section),
                            summary = if (versionOverridden) {
                                stringResource(R.string.best50_version_overridden)
                            } else {
                                null
                            },
                            maxHeight = 360.dp,
                            onSelectedIndexChange = onVersionSelected,
                        )
                        WindowDropdownPreference(
                            items = constantModeLabels,
                            selectedIndex = selectedConstantModeIndex,
                            title = stringResource(R.string.best50_constant_section),
                            maxHeight = 280.dp,
                            enabled = !theoretical,
                            onSelectedIndexChange = onConstantModeSelected,
                        )
                        if (!theoretical) {
                            BestTableCapacityEditor(
                                b35Text = b35Text,
                                b15Text = b15Text,
                                onB35TextChange = onB35TextChange,
                                onB15TextChange = onB15TextChange,
                                onCommit = onCapacityCommit,
                            )
                        }
                    }
                }
            }
            if (!theoretical) {
                item {
                    BestTableSettingSection(title = stringResource(R.string.catalog_filter_difficulty)) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            BestTableDifficultyOptions.forEach { option ->
                                BestTableFilterChip(
                                    title = stringResource(option.labelResource),
                                    selected = option.value in filterSettings.selectedDifficulties,
                                    color = SongVisualUtils.difficultyColor(
                                        difficulty = option.value,
                                        darkTheme = darkTheme,
                                        brightenDark = true,
                                    ),
                                    onClick = {
                                        onFilterSettingsChange(
                                            filterSettings.copy(
                                                selectedDifficulties = filterSettings.selectedDifficulties
                                                    .toggled(option.value),
                                            ),
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
                item {
                    BestTableSettingSection(title = stringResource(R.string.catalog_filter_category)) {
                        BestTableFilterChipGroup(
                            values = categories,
                            selectedValues = filterSettings.selectedCategories,
                            colorForValue = { MiuixTheme.colorScheme.primary },
                            onToggle = { category ->
                                onFilterSettingsChange(
                                    filterSettings.copy(
                                        selectedCategories = filterSettings.selectedCategories
                                            .toggled(category),
                                    ),
                                )
                            },
                        )
                    }
                }
                item {
                    BestTableSettingSection(title = stringResource(R.string.catalog_filter_type)) {
                        BestTableFilterChipGroup(
                            values = BestTableChartTypes,
                            selectedValues = filterSettings.selectedTypes,
                            displayValue = { it.uppercase() },
                            colorForValue = { type ->
                                SongVisualUtils.chartTypeColor(
                                    type = type,
                                    darkTheme = darkTheme,
                                    fallbackColor = MiuixTheme.colorScheme.primary,
                                )
                            },
                            onToggle = { type ->
                                onFilterSettingsChange(
                                    filterSettings.copy(selectedTypes = filterSettings.selectedTypes.toggled(type)),
                                )
                            },
                        )
                    }
                }
                item {
                    BestTableSettingSection(title = stringResource(R.string.score_query_filter_rank)) {
                        BestTableFilterChipGroup(
                            values = BestTableRankOptions,
                            selectedValues = filterSettings.selectedRanks,
                            colorForValue = { rank ->
                                ScoreStatusColors.rank(rank) ?: MiuixTheme.colorScheme.primary
                            },
                            onToggle = { rank ->
                                onFilterSettingsChange(
                                    filterSettings.copy(selectedRanks = filterSettings.selectedRanks.toggled(rank)),
                                )
                            },
                        )
                    }
                }
                item {
                    BestTableSettingSection(title = stringResource(R.string.score_query_filter_fc)) {
                        BestTableFilterChipGroup(
                            values = BestTableFcOptions,
                            selectedValues = filterSettings.selectedFc,
                            colorForValue = { fc ->
                                ScoreStatusColors.combo(fc) ?: MiuixTheme.colorScheme.primary
                            },
                            onToggle = { fc ->
                                onFilterSettingsChange(
                                    filterSettings.copy(selectedFc = filterSettings.selectedFc.toggled(fc)),
                                )
                            },
                        )
                    }
                }
                item {
                    BestTableSettingSection(title = stringResource(R.string.score_query_filter_fs)) {
                        BestTableFilterChipGroup(
                            values = BestTableFsOptions,
                            selectedValues = filterSettings.selectedFs,
                            colorForValue = { fs ->
                                ScoreStatusColors.sync(fs) ?: MiuixTheme.colorScheme.primary
                            },
                            onToggle = { fs ->
                                onFilterSettingsChange(
                                    filterSettings.copy(selectedFs = filterSettings.selectedFs.toggled(fs)),
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BestTableSettingSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = title,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(start = 4.dp),
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 16.dp,
            insideMargin = PaddingValues(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun BestTableCapacityEditor(
    b35Text: String,
    b15Text: String,
    onB35TextChange: (String) -> Unit,
    onB15TextChange: (String) -> Unit,
    onCommit: () -> Unit,
) {
    val b35 = (b35Text.toIntOrNull() ?: BestCapacityOldDefault).coerceIn(BestCapacityMin, BestCapacityMax)
    val b15 = (b15Text.toIntOrNull() ?: BestCapacityNewDefault).coerceIn(BestCapacityMin, BestCapacityMax)
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.best50_capacity_section),
                fontSize = MiuixTheme.textStyles.headline1.fontSize,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(R.string.best50_capacity_total),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.width(8.dp))
            BestTableCapacityAmount(value = b35 + b15)
        }
        BestTableCapacitySlider(
            label = stringResource(R.string.best50_capacity_old),
            value = b35,
            snapValue = BestCapacityOldDefault,
            onValueChange = { value -> onB35TextChange(value.toString()) },
            onCommit = onCommit,
        )
        BestTableCapacitySlider(
            label = stringResource(R.string.best50_capacity_new),
            value = b15,
            snapValue = BestCapacityNewDefault,
            onValueChange = { value -> onB15TextChange(value.toString()) },
            onCommit = onCommit,
        )
    }
}

@Composable
private fun BestTableCapacitySlider(
    label: String,
    value: Int,
    snapValue: Int,
    onValueChange: (Int) -> Unit,
    onCommit: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.weight(1f),
            )
            BestTableCapacityAmount(value = value)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { raw ->
                onValueChange(raw.roundToInt().coerceIn(BestCapacityMin, BestCapacityMax))
            },
            valueRange = BestCapacityMin.toFloat()..BestCapacityMax.toFloat(),
            keyPoints = listOf(snapValue.toFloat()),
            showKeyPoints = true,
            magnetThreshold = BestCapacityMagnetThreshold,
            hapticEffect = SliderDefaults.SliderHapticEffect.Step,
            onValueChangeFinished = onCommit,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun BestTableCapacityAmount(value: Int) {
    Row {
        Text(
            text = value.toString(),
            style = MiuixTheme.textStyles.footnote1,
            fontWeight = FontWeight.Bold,
            color = MiuixTheme.colorScheme.primary,
            modifier = Modifier.alignByBaseline(),
        )
        Spacer(Modifier.width(2.dp))
        Text(
            text = stringResource(R.string.best50_capacity_unit),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.primary,
            modifier = Modifier.alignByBaseline(),
        )
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun BestTableFilterChipGroup(
    values: List<String>,
    selectedValues: Set<String>,
    colorForValue: @Composable (String) -> Color,
    onToggle: (String) -> Unit,
    displayValue: (String) -> String = { it },
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        values.forEach { value ->
            BestTableFilterChip(
                title = displayValue(value),
                selected = value in selectedValues,
                color = colorForValue(value),
                onClick = { onToggle(value) },
            )
        }
    }
}

@Composable
private fun BestTableFilterChip(
    title: String,
    selected: Boolean,
    color: Color,
    onClick: () -> Unit,
) {
    val background by animateColorAsState(
        targetValue = if (selected) color else MiuixTheme.colorScheme.onSurface.copy(alpha = 0.06f),
        label = "best-table-filter-background",
    )
    val border by animateColorAsState(
        targetValue = if (selected) color.copy(alpha = 0.45f) else {
            MiuixTheme.colorScheme.onSurface.copy(alpha = 0.09f)
        },
        label = "best-table-filter-border",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) Color.White else MiuixTheme.colorScheme.onSurface,
        label = "best-table-filter-content",
    )
    Box(
        modifier = Modifier
            .squircleSurface(
                color = background,
                cornerRadius = 50.dp,
                extension = SquircleExtension,
            )
            .border(width = 1.dp, color = border, shape = CircleShape)
            .toggleable(
                value = selected,
                role = Role.Checkbox,
                onValueChange = { onClick() },
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title,
            style = MiuixTheme.textStyles.footnote1,
            color = contentColor,
            maxLines = 1,
        )
    }
}

private fun Set<String>.toggled(value: String): Set<String> =
    if (value in this) this - value else this + value

private data class BestTableDifficultyOption(
    val value: String,
    val labelResource: Int,
)

private val BestTableDifficultyOptions = listOf(
    BestTableDifficultyOption("basic", R.string.catalog_filter_basic),
    BestTableDifficultyOption("advanced", R.string.catalog_filter_advanced),
    BestTableDifficultyOption("expert", R.string.catalog_filter_expert),
    BestTableDifficultyOption("master", R.string.catalog_filter_master),
    BestTableDifficultyOption("remaster", R.string.catalog_filter_remaster),
)

private const val BestCapacityMin = 1
private const val BestCapacityMax = 99
private const val BestCapacityOldDefault = 35
private const val BestCapacityNewDefault = 15
private const val BestCapacityMagnetThreshold = 0.03f

private val BestTableChartTypes = listOf("dx", "std")
private val BestTableRankOptions = listOf(
    "SSS+", "SSS", "SS+", "SS", "S+", "S", "AAA", "AA", "A", "BBB", "BB", "B", "C", "D",
)
private val BestTableFcOptions = listOf("AP+", "AP", "FC+", "FC")
private val BestTableFsOptions = listOf("FDX+", "FDX", "FS+", "FS")

private const val HalfExpandedOffsetFraction = 0.48f
private val BottomSheetHeaderHeight = 68.dp
private val BottomSheetCornerRadius = 24.dp
private val BottomSheetHeaderBlurRadius = 24.dp
private val BottomSheetHeaderHorizontalPadding = 12.dp
private val BottomSheetAnimationSpec = spring<Float>(
    dampingRatio = 0.86f,
    stiffness = 420f,
)
