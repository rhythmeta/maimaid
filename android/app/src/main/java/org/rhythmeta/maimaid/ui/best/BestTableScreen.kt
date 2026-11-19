package org.rhythmeta.maimaid.ui.best

import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import org.rhythmeta.maimaid.R
import org.rhythmeta.maimaid.core.data.StaticAssetUrls
import org.rhythmeta.maimaid.core.AppContainer
import org.rhythmeta.maimaid.core.data.Best50State
import org.rhythmeta.maimaid.core.data.Best50ConstantMode
import org.rhythmeta.maimaid.core.data.Best50FilterSettings
import org.rhythmeta.maimaid.core.data.Best50StatsCalculator
import org.rhythmeta.maimaid.core.data.Best50SummaryStats
import org.rhythmeta.maimaid.core.data.CoverImageStore
import org.rhythmeta.maimaid.core.data.RatingUtils
import org.rhythmeta.maimaid.core.data.ScoreRules
import org.rhythmeta.maimaid.core.database.GameVersionEntity
import org.rhythmeta.maimaid.core.database.SongCategoryEntity
import org.rhythmeta.maimaid.core.database.UserProfileEntity
import org.rhythmeta.maimaid.ui.catalog.SongCardTransitionContent
import org.rhythmeta.maimaid.ui.song.ScoreTintBadge
import org.rhythmeta.maimaid.ui.components.BestTableSettingsSheet
import org.rhythmeta.maimaid.ui.components.SquircleExtension
import org.rhythmeta.maimaid.ui.components.SongListScrollBar
import org.rhythmeta.maimaid.ui.catalog.SongCoverNavigation
import org.rhythmeta.maimaid.ui.catalog.rememberSongCoverNavigation
import org.rhythmeta.maimaid.ui.components.fadingMarquee
import org.rhythmeta.maimaid.ui.components.squircleShape
import org.rhythmeta.maimaid.ui.util.ScoreStatusColors
import org.rhythmeta.maimaid.ui.util.SongVisualUtils
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun BestTableScreen(
    container: AppContainer,
    activeProfile: UserProfileEntity?,
    versions: List<GameVersionEntity>,
    categories: List<SongCategoryEntity>,
    contentTopPadding: Dp,
    exportRequested: Boolean,
    onExportRequestHandled: () -> Unit,
    filterRequested: Boolean,
    onFilterRequestHandled: () -> Unit,
    onFilterActiveChanged: (Boolean) -> Unit,
    selectedPage: Int,
    onOpenSong: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedVersion by remember { mutableStateOf<String?>(null) }
    val filterSettings by container.appPreferencesRepository.best50FilterSettings
        .collectAsStateWithLifecycle(Best50FilterSettings())
    val theoretical by container.appPreferencesRepository.best50Theoretical
        .collectAsStateWithLifecycle(false)
    var showSettings by remember { mutableStateOf(false) }
    val constantMode by container.appPreferencesRepository.best50ConstantMode
        .collectAsStateWithLifecycle(Best50ConstantMode.Server)
    val best50Flow = remember(selectedVersion, constantMode, theoretical) {
        if (theoretical) {
            container.best50Repository.observeTheoreticalBest50(versionOverride = selectedVersion)
        } else {
            container.best50Repository.observeBest50(
                versionOverride = selectedVersion,
                constantMode = constantMode,
            )
        }
    }
    val best50 by best50Flow.collectAsStateWithLifecycle(Best50State())
    var b35Text by remember(activeProfile?.id) {
        mutableStateOf((activeProfile?.b35Count ?: 35).toString())
    }
    var b15Text by remember(activeProfile?.id) {
        mutableStateOf((activeProfile?.b15Count ?: 15).toString())
    }
    val versionOptions = remember(versions) {
        listOf<String?>(null) + versions.sortedByDescending(GameVersionEntity::sortOrder).map { it.name }
    }
    val versionLabels = versionOptions.map { version ->
        version?.let { SongVisualUtils.versionAbbreviation(it, versions) }
            ?: stringResource(R.string.best50_version_auto)
    }
    val constantModeLabels = listOf(
        stringResource(R.string.catalog_sort_default),
        stringResource(R.string.best50_use_fitted_constant),
        stringResource(R.string.best50_use_version_constant),
    )
    val filterCategories = remember(categories) {
        val categoryOrder = categories.associate { it.name to it.sortOrder }
        categories.map(SongCategoryEntity::name)
            .distinct()
            .sortedWith(compareBy({ categoryOrder[it] ?: Int.MAX_VALUE }, { it }))
    }
    val exportVersion = best50.latestVersion?.let { version ->
        SongVisualUtils.versionAbbreviation(version, versions)
    }
    val darkTheme = SongVisualUtils.isDarkTheme(MiuixTheme.colorScheme.background)
    val filterActive = !theoretical && !filterSettings.isEmpty
    val displayedBest50 = remember(best50, filterSettings, theoretical) {
        if (theoretical) best50 else best50.applyingFilters(filterSettings)
    }

    val commitCapacity = {
        val b35 = b35Text.toIntOrNull()?.coerceAtLeast(1) ?: 35
        val b15 = b15Text.toIntOrNull()?.coerceAtLeast(1) ?: 15
        b35Text = b35.toString()
        b15Text = b15.toString()
        if (b35 != (activeProfile?.b35Count ?: 35) || b15 != (activeProfile?.b15Count ?: 15)) {
            scope.launch { container.profileRepository.updateBestCapacity(b35, b15) }
        }
    }

    LaunchedEffect(activeProfile?.b35Count, activeProfile?.b15Count) {
        activeProfile?.let {
            b35Text = it.b35Count.toString()
            b15Text = it.b15Count.toString()
        }
    }
    LaunchedEffect(filterRequested) {
        if (filterRequested) {
            showSettings = true
            onFilterRequestHandled()
        }
    }
    LaunchedEffect(filterActive) {
        onFilterActiveChanged(filterActive)
    }
    LaunchedEffect(exportRequested, displayedBest50, activeProfile?.name, exportVersion, darkTheme) {
        if (exportRequested) {
            if (!displayedBest50.isEmpty) {
                shareBest50(
                    context = context,
                    state = displayedBest50,
                    userName = activeProfile?.name,
                    version = exportVersion,
                    coverImageStore = container.coverImageStore,
                    darkTheme = darkTheme,
                )
            }
            onExportRequestHandled()
        }
    }

    val newListState = rememberLazyListState()
    val oldListState = rememberLazyListState()
    val isNewPage = selectedPage != BestTableOldPage
    val listState = if (isNewPage) newListState else oldListState
    val pageEntries = if (isNewPage) displayedBest50.b15 else displayedBest50.b35
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = contentTopPadding + 12.dp,
                end = 16.dp,
                bottom = 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                BestRatingSummary(
                    total = displayedBest50.total,
                    b35 = displayedBest50.b35,
                    b15 = displayedBest50.b15,
                )
            }
            bestEntryPage(
                sectionKey = if (isNewPage) "new" else "old",
                entries = pageEntries,
                emptyMessageResource = if (filterActive) R.string.catalog_empty else R.string.best50_empty,
                coverImageStore = container.coverImageStore,
                onOpenSong = onOpenSong,
            )
        }
        SongListScrollBar(
            state = listState,
            trackPadding = PaddingValues(top = contentTopPadding + 12.dp, bottom = 12.dp),
        )
    }
    BestTableSettingsSheet(
        visible = showSettings,
        versionLabels = versionLabels,
        selectedVersionIndex = versionOptions.indexOf(selectedVersion).coerceAtLeast(0),
        versionOverridden = selectedVersion != null,
        constantModeLabels = constantModeLabels,
        selectedConstantModeIndex = if (theoretical) {
            BestTableConstantModes.indexOf(Best50ConstantMode.Version)
        } else {
            BestTableConstantModes.indexOf(constantMode).coerceAtLeast(0)
        },
        b35Text = b35Text,
        b15Text = b15Text,
        categories = filterCategories,
        filterSettings = filterSettings,
        theoretical = theoretical,
        onVersionSelected = { index -> selectedVersion = versionOptions[index] },
        onConstantModeSelected = { index ->
            val mode = BestTableConstantModes.getOrNull(index) ?: Best50ConstantMode.Server
            if (mode != constantMode) {
                scope.launch { container.appPreferencesRepository.setBest50ConstantMode(mode) }
            }
        },
        onB35TextChange = { input ->
            if (input.length <= 2 && input.all(Char::isDigit)) {
                b35Text = input
            }
        },
        onB15TextChange = { input ->
            if (input.length <= 2 && input.all(Char::isDigit)) {
                b15Text = input
            }
        },
        onCapacityCommit = commitCapacity,
        onFilterSettingsChange = { settings ->
            scope.launch { container.appPreferencesRepository.setBest50FilterSettings(settings) }
        },
        onTheoreticalChange = { enabled ->
            scope.launch { container.appPreferencesRepository.setBest50Theoretical(enabled) }
            if (enabled && constantMode != Best50ConstantMode.Version) {
                scope.launch { container.appPreferencesRepository.setBest50ConstantMode(Best50ConstantMode.Version) }
            }
        },
        onReset = {
            selectedVersion = null
            b35Text = BestTableCapacityOldDefault.toString()
            b15Text = BestTableCapacityNewDefault.toString()
            if (constantMode != Best50ConstantMode.Server) {
                scope.launch { container.appPreferencesRepository.setBest50ConstantMode(Best50ConstantMode.Server) }
            }
            if ((activeProfile?.b35Count ?: BestTableCapacityOldDefault) != BestTableCapacityOldDefault ||
                (activeProfile?.b15Count ?: BestTableCapacityNewDefault) != BestTableCapacityNewDefault
            ) {
                scope.launch {
                    container.profileRepository.updateBestCapacity(
                        BestTableCapacityOldDefault,
                        BestTableCapacityNewDefault,
                    )
                }
            }
            scope.launch {
                container.appPreferencesRepository.setBest50FilterSettings(Best50FilterSettings())
            }
        },
        onDismiss = {
            commitCapacity()
            showSettings = false
        },
    )
}

internal fun List<RatingUtils.Entry>.filteredBy(
    settings: Best50FilterSettings,
): List<RatingUtils.Entry> {
    if (settings.isEmpty) return this
    return filter { entry ->
        (settings.selectedDifficulties.isEmpty() ||
            entry.difficulty.lowercase(Locale.ROOT) in settings.selectedDifficulties) &&
            (settings.selectedCategories.isEmpty() || entry.category in settings.selectedCategories) &&
            (settings.selectedTypes.isEmpty() || entry.type.normalizedChartType() in settings.selectedTypes) &&
            (settings.selectedRanks.isEmpty() || RatingUtils.rank(entry.achievement) in settings.selectedRanks) &&
            ScoreRules.displayFc(entry.fc).matchesSelection(settings.selectedFc) &&
            ScoreRules.displayFs(entry.fs).matchesSelection(settings.selectedFs)
    }
}

private fun Best50State.applyingFilters(settings: Best50FilterSettings): Best50State {
    if (settings.isEmpty) return this
    val filteredB35 = b35.filteredBy(settings)
    val filteredB15 = b15.filteredBy(settings)
    return copy(
        total = (filteredB35 + filteredB15).sumOf(RatingUtils.Entry::rating),
        b35 = filteredB35,
        b15 = filteredB15,
        isEmpty = filteredB35.isEmpty() && filteredB15.isEmpty(),
    )
}

private fun String?.matchesSelection(selection: Set<String>): Boolean =
    selection.isEmpty() || (this != null && this in selection)

private fun String?.normalizedChartType(): String? = when (this?.lowercase(Locale.ROOT)) {
    "std", "standard" -> "std"
    "dx" -> "dx"
    else -> this?.lowercase(Locale.ROOT)
}

internal const val BestTableNewPage = 0
internal const val BestTableOldPage = 1

private const val BestTableCapacityOldDefault = 35
private const val BestTableCapacityNewDefault = 15

private const val BestSummaryNoValue = "—"

private val BestTableConstantModes = listOf(
    Best50ConstantMode.Server,
    Best50ConstantMode.Fitted,
    Best50ConstantMode.Version,
)

@Composable
internal fun BestTablePageSwitcher(
    selectedPage: Int,
    visible: Boolean,
    onSelectedPageChange: (Int) -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
        exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
    ) {
        TabRowWithContour(
            tabs = listOf(
                stringResource(R.string.best50_tab_new),
                stringResource(R.string.best50_tab_old),
            ),
            selectedTabIndex = selectedPage,
            onTabSelected = onSelectedPageChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            minWidth = 112.dp,
            maxWidth = 180.dp,
        )
    }
}

@Composable
private fun BestRatingSummary(
    total: Int,
    b35: List<RatingUtils.Entry>,
    b15: List<RatingUtils.Entry>,
) {
    val darkTheme = SongVisualUtils.isDarkTheme(MiuixTheme.colorScheme.background)
    val b35Sum = b35.sumOf(RatingUtils.Entry::rating)
    val b15Sum = b15.sumOf(RatingUtils.Entry::rating)
    val totalStats = remember(b35, b15) { Best50StatsCalculator.calculate(b35 + b15) }
    val newStats = remember(b15) { Best50StatsCalculator.calculate(b15) }
    val oldStats = remember(b35) { Best50StatsCalculator.calculate(b35) }
    val totalRating = b35Sum + b15Sum
    val entryCount = b35.size + b15.size
    val averageRating = if (entryCount > 0) totalRating.toDouble() / entryCount else 0.0
    val oldPercent = if (totalRating > 0) b35Sum * 100.0 / totalRating else 0.0
    val newPercent = if (totalRating > 0) b15Sum * 100.0 / totalRating else 0.0
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 18.dp,
        insideMargin = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.best50_rating),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Text(
                        text = total.toString(),
                        style = MiuixTheme.textStyles.headline1.copy(
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.SansSerif,
                            brush = ratingBrush(total, darkTheme),
                        ),
                        color = Color.Unspecified,
                    )
                }
                Column(
                    modifier = Modifier.fillMaxHeight(),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.best50_old_sum, b35Sum),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Text(
                        text = stringResource(R.string.best50_new_sum, b15Sum),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Text(
                        text = stringResource(R.string.best50_average_rating_line, averageRating),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                RatingShareBar(newRating = b15Sum, oldRating = b35Sum)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(
                            R.string.best50_share,
                            stringResource(R.string.best50_capacity_new),
                            newPercent,
                        ),
                        style = MiuixTheme.textStyles.footnote2,
                        fontWeight = FontWeight.Bold,
                        color = NewShareColor,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stringResource(
                            R.string.best50_share,
                            stringResource(R.string.best50_capacity_old),
                            oldPercent,
                        ),
                        style = MiuixTheme.textStyles.footnote2,
                        fontWeight = FontWeight.Bold,
                        color = OldShareColor,
                        textAlign = TextAlign.End,
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BestSummaryStatsHeader()
                BestSummaryStatsRow(
                    count = b35.size + b15.size,
                    stats = totalStats,
                    background = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                )
                BestSummaryStatsRow(
                    count = b15.size,
                    stats = newStats,
                    background = NewShareColor.copy(alpha = 0.12f),
                )
                BestSummaryStatsRow(
                    count = b35.size,
                    stats = oldStats,
                    background = OldShareColor.copy(alpha = 0.12f),
                )
            }
        }
    }
}

@Composable
private fun RatingShareBar(
    newRating: Int,
    oldRating: Int,
) {
    val newWeight = newRating.coerceAtLeast(0).toFloat()
    val oldWeight = oldRating.coerceAtLeast(0).toFloat()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(squircleShape(3.dp))
            .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            if (newWeight > 0f) {
                Box(
                    modifier = Modifier
                        .weight(newWeight)
                        .fillMaxHeight()
                        .background(NewShareColor),
                )
            }
            if (oldWeight > 0f) {
                Box(
                    modifier = Modifier
                        .weight(oldWeight)
                        .fillMaxHeight()
                        .background(OldShareColor),
                )
            }
        }
    }
}

@Composable
private fun BestSummaryStatsHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Spacer(Modifier.weight(0.6f))
        Text(
            text = stringResource(R.string.best50_stats_average),
            style = MiuixTheme.textStyles.footnote2,
            fontWeight = FontWeight.Bold,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1.1f),
        )
        Text(
            text = stringResource(R.string.best50_stats_dx),
            style = MiuixTheme.textStyles.footnote2,
            fontWeight = FontWeight.Bold,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1.1f),
        )
        Text(
            text = stringResource(R.string.best50_stats_stars),
            style = MiuixTheme.textStyles.footnote2,
            fontWeight = FontWeight.Bold,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            textAlign = TextAlign.End,
            modifier = Modifier.width(40.dp),
        )
    }
}

@Composable
private fun BestSummaryStatsRow(
    count: Int,
    stats: Best50SummaryStats?,
    background: Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .squircleSurface(
                color = background,
                cornerRadius = 8.dp,
                extension = SquircleExtension,
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "B${count}",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.weight(0.6f),
        )
        Text(
            text = stats?.let { String.format(Locale.ROOT, "%.4f%%", it.averageAchievement) }
                ?: BestSummaryNoValue,
            style = MiuixTheme.textStyles.footnote2.copy(fontFamily = FontFamily.Monospace),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1.1f),
        )
        Text(
            text = stats?.takeIf { it.hasDxData }
                ?.let { String.format(Locale.ROOT, "%.2f%%", it.dxAchievement) }
                ?: BestSummaryNoValue,
            style = MiuixTheme.textStyles.footnote2.copy(fontFamily = FontFamily.Monospace),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1.1f),
        )
        Box(
            modifier = Modifier.width(40.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            if (stats?.hasDxData == true) {
                ScoreTintBadge(
                    text = "${stats.dxStars} ★",
                    color = if (stats.dxStars > 0) {
                        ScoreStatusColors.DxStar
                    } else {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    },
                    compact = true,
                )
            }
        }
    }
}

private fun LazyListScope.bestEntryPage(
    sectionKey: String,
    entries: List<RatingUtils.Entry>,
    emptyMessageResource: Int,
    coverImageStore: CoverImageStore,
    onOpenSong: (String) -> Unit,
) {
    if (entries.isEmpty()) {
        item(key = "$sectionKey-empty") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 16.dp,
                insideMargin = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer),
            ) {
                Text(
                    text = stringResource(emptyMessageResource),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    } else {
        items(
            items = entries,
            key = { entry -> "$sectionKey-${entry.sheetKey}" },
            contentType = { "best-entry" },
        ) { entry ->
            BestEntryCard(
                entry = entry,
                coverImageStore = coverImageStore,
                onClick = { onOpenSong(entry.songIdentifier) },
            )
        }
    }
}

@Composable
private fun BestEntryCard(
    entry: RatingUtils.Entry,
    coverImageStore: CoverImageStore,
    onClick: () -> Unit,
) {
    val coverNavigation = rememberSongCoverNavigation(
        songId = entry.songIdentifier,
        onClick = onClick,
        cornerRadius = 10.dp,
        key = entry.sheetKey,
        cardColor = MiuixTheme.colorScheme.surfaceContainer,
        cardCornerRadius = 16.dp,
    )
    SongCardTransitionContent(coverNavigation) { showCover ->
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (showCover) coverNavigation.cardModifier else Modifier)
                .clip(squircleShape(16.dp))
                .then(if (showCover) Modifier.clickable(onClick = coverNavigation::open) else Modifier),
            cornerRadius = 16.dp,
            insideMargin = PaddingValues(start = 8.dp),
            colors = CardDefaults.defaultColors(
                color = if (showCover) MiuixTheme.colorScheme.surfaceContainer else Color.Transparent,
            ),
        ) {
            BestEntryRow(
                entry = entry,
                coverImageStore = coverImageStore,
                coverNavigation = coverNavigation,
                showCover = showCover,
            )
        }
    }
}

@Composable
private fun BestEntryRow(
    entry: RatingUtils.Entry,
    coverImageStore: CoverImageStore,
    coverNavigation: SongCoverNavigation,
    showCover: Boolean,
) {
    val darkTheme = SongVisualUtils.isDarkTheme(MiuixTheme.colorScheme.background)
    val difficultyColor = SongVisualUtils.difficultyColor(
        difficulty = entry.difficulty,
        type = entry.type,
        darkTheme = darkTheme,
        brightenDark = true,
    )
    val typeColor = SongVisualUtils.chartTypeColor(entry.type, darkTheme, difficultyColor)
    val rank = RatingUtils.rank(entry.achievement)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(84.dp)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .height(52.dp)
                .width(4.dp)
                .squircleSurface(
                    color = difficultyColor,
                    cornerRadius = 2.dp,
                    extension = SquircleExtension,
                ),
        )
        Spacer(Modifier.width(10.dp))
        if (showCover) {
            BestJacket(entry.imageName, coverImageStore, coverNavigation)
        } else {
            Spacer(Modifier.size(56.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = entry.title,
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier
                    .fillMaxWidth()
                    .fadingMarquee(),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = rank,
                    style = MiuixTheme.textStyles.footnote1,
                    fontWeight = FontWeight.Black,
                    color = ScoreStatusColors.rank(rank) ?: MiuixTheme.colorScheme.onSurface,
                )
                Text(
                    text = String.format(Locale.ROOT, "%.4f%%", entry.achievement),
                    style = MiuixTheme.textStyles.footnote1.copy(fontFamily = FontFamily.Monospace),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                if (entry.dxScore > 0) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Star,
                            contentDescription = null,
                            modifier = Modifier.size(10.dp),
                            tint = DxScoreColor,
                        )
                        Text(
                            text = entry.dxScore.toString(),
                            style = MiuixTheme.textStyles.footnote2,
                            fontWeight = FontWeight.Bold,
                            color = DxScoreColor,
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                BestBadge(entry.type.uppercase(), typeColor)
                val dxStars = ScoreRules.dxStars(entry.dxScore, entry.maxDxScore)
                if (dxStars > 0) {
                    BestBadge("$dxStars ★", ScoreStatusColors.DxStar)
                }
                entry.fc?.takeIf(String::isNotBlank)?.let {
                    BestBadge(ScoreRules.displayFc(it) ?: it.uppercase(), ScoreStatusColors.combo(it))
                }
                entry.fs?.takeIf(String::isNotBlank)?.let {
                    val sync = ScoreRules.displayFs(it)?.let { display ->
                        if (display == "S") "SYNC" else display
                    } ?: it.uppercase()
                    BestBadge(sync, ScoreStatusColors.sync(it))
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(
            modifier = Modifier.padding(end = 14.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Text(
                text = entry.rating.toString(),
                style = MiuixTheme.textStyles.title3.copy(
                    fontWeight = FontWeight.Black,
                    brush = ratingBrush(entry.rating * 50, darkTheme),
                ),
                color = Color.Unspecified,
            )
            Text(
                text = stringResource(R.string.best50_base, entry.level),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun BestJacket(
    imageName: String,
    coverImageStore: CoverImageStore,
    coverNavigation: SongCoverNavigation? = null,
) {
    val model = remember(imageName) {
        coverImageStore.fileFor(imageName)
            ?: StaticAssetUrls.coverUrl(imageName)
    }
    Box(
        modifier = Modifier
            .size(56.dp)
            .then(coverNavigation?.modifier ?: Modifier)
            .squircleSurface(
                color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                cornerRadius = 10.dp,
                extension = SquircleExtension,
            ),
    ) {
        AsyncImage(
            model = model,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            onSuccess = { result -> coverNavigation?.onPainter(result.painter) },
        )
    }
}

@Composable
private fun BestBadge(text: String, color: Color?) {
    val resolved = color ?: MiuixTheme.colorScheme.onSurfaceVariantActions
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote2.copy(fontSize = 9.sp),
        fontWeight = FontWeight.Bold,
        color = resolved,
        modifier = Modifier
            .squircleSurface(
                color = resolved.copy(alpha = 0.13f),
                cornerRadius = 4.dp,
                extension = SquircleExtension,
            )
            .padding(horizontal = 5.dp, vertical = 2.dp),
    )
}

private suspend fun shareBest50(
    context: Context,
    state: Best50State,
    userName: String?,
    version: String?,
    coverImageStore: CoverImageStore,
    darkTheme: Boolean,
) {
    val file = Best50ImageExporter.renderToCache(
        context = context,
        state = state,
        userName = userName,
        version = version,
        coverImageStore = coverImageStore,
        darkTheme = darkTheme,
    ) ?: return
    val uri = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull() ?: return
    context.startActivity(
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            null,
        ),
    )
}

private fun ratingColor(rating: Int): Color = when {
    rating >= 15_000 -> Color(0xFFFF6100)
    rating >= 14_500 -> Color(0xFFE5E4E2)
    rating >= 14_000 -> Color(0xFFFFD700)
    rating >= 13_000 -> Color(0xFFC0C0C0)
    rating >= 12_000 -> Color(0xFFCD7F32)
    rating >= 10_000 -> Color(0xFFD084FF)
    rating >= 7_000 -> Color(0xFFFF5E5E)
    rating >= 4_000 -> Color(0xFFFFD400)
    rating >= 2_000 -> Color(0xFF46D246)
    rating >= 1_000 -> Color(0xFF56A6FF)
    else -> Color(0xFF8E8E93)
}

private fun ratingBrush(rating: Int, darkTheme: Boolean): Brush {
    fun Color.forTheme(): Color = if (darkTheme) {
        this
    } else {
        Color(red * 0.72f, green * 0.72f, blue * 0.72f, alpha)
    }

    return when {
    rating >= 15_000 -> Brush.linearGradient(
        colors = listOf(
            Color(0xFFFF5E5E),
            Color(0xFFFFBA5E),
            Color(0xFFFFF75E),
            Color(0xFF5EFF5E),
            Color(0xFF5EBAFF),
            Color(0xFFBA5EFF),
            Color(0xFFFF5EBA),
        ).map { it.forTheme() },
        start = Offset.Zero,
        end = Offset.Infinite,
    )
    rating >= 14_500 -> Brush.linearGradient(
        colors = listOf(
            Color(0xFFD3D3D3),
            Color.White,
            Color(0xFFD3D3D3),
        ).map { it.forTheme() },
        start = Offset.Zero,
        end = Offset.Infinite,
    )
    rating >= 14_000 -> Brush.verticalGradient(
        colors = listOf(Color(0xFFFFD700), Color(0xFFFFA500)).map { it.forTheme() },
    )
    else -> Brush.linearGradient(
        listOf(ratingColor(rating).forTheme(), ratingColor(rating).forTheme()),
    )
    }
}

private val BestAccent = Color(0xFFFF9500)
private val OldShareColor = Color(0xFF4ECDC4)
private val NewShareColor = Color(0xFFFF6B6B)
private val DxScoreColor = Color(0xFFE9B820)
