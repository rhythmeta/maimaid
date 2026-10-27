package org.rhythmeta.maimaid.ui.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items as columnItems
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.rhythmeta.maimaid.R
import org.rhythmeta.maimaid.core.data.CatalogSortOption
import org.rhythmeta.maimaid.core.data.CoverImageStore
import org.rhythmeta.maimaid.core.database.GameVersionEntity
import org.rhythmeta.maimaid.core.database.ScoreEntity
import org.rhythmeta.maimaid.core.database.SheetEntity
import org.rhythmeta.maimaid.core.database.SongAliasEntity
import org.rhythmeta.maimaid.core.database.SongCategoryEntity
import org.rhythmeta.maimaid.core.database.SongEntity
import org.rhythmeta.maimaid.ui.util.SongVisualUtils
import org.rhythmeta.maimaid.ui.components.SongListScrollBar
import org.rhythmeta.maimaid.ui.components.ZoomableCoverGrid
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun CatalogScreen(
    songs: List<SongEntity>,
    sheets: List<SheetEntity>,
    scores: List<ScoreEntity>,
    songCategories: List<SongCategoryEntity>,
    gameVersions: List<GameVersionEntity>,
    coverImageStore: CoverImageStore,
    displayMode: CatalogDisplayMode,
    sortOption: CatalogSortOption,
    sortAscending: Boolean,
    filterSettings: CatalogFilterSettings,
    query: String,
    songAliases: List<SongAliasEntity>,
    server: String,
    contentTopPadding: Dp,
    showFilterDialog: Boolean,
    onFilterSettingsChange: (CatalogFilterSettings) -> Unit,
    onDismissFilter: () -> Unit,
    onOpenSong: (String) -> Unit,
) {
    val sheetsBySong = remember(sheets) { sheets.groupBy(SheetEntity::songIdentifier) }
    val aliasesBySong = remember(songAliases) {
        songAliases.groupBy(SongAliasEntity::songIdentifier)
            .mapValues { (_, aliases) -> aliases.map(SongAliasEntity::alias) }
    }
    val scoresBySheetKey = remember(scores) { scores.associateBy(ScoreEntity::sheetKey) }
    val displayedSongs = remember(
        songs,
        sheetsBySong,
        aliasesBySong,
        gameVersions,
        filterSettings,
        query,
        sortOption,
        sortAscending,
        server,
    ) {
        CatalogQuery.filterAndSort(
            songs = songs,
            sheetsBySong = sheetsBySong,
            aliasesBySong = aliasesBySong,
            versions = gameVersions,
            settings = filterSettings,
            searchText = query,
            sortOption = sortOption,
            sortAscending = sortAscending,
            server = server,
        )
    }
    val categories = remember(songCategories, songs) {
        val categoryOrder = songCategories.associate { it.name to it.sortOrder }
        songs.map(SongEntity::category)
            .distinct()
            .sortedWith(compareBy({ categoryOrder[it] ?: Int.MAX_VALUE }, { it }))
    }
    val versions = remember(gameVersions, songs) {
        songs.mapNotNull(SongEntity::version)
            .distinct()
            .sortedWith(
                compareByDescending<String> {
                    SongVisualUtils.versionSortOrder(it, gameVersions)
                }
                    .thenByDescending { it },
            )
    }
    val gridState = rememberCatalogPhotoGridState(CoverGridPage.Catalog)

    if (displayedSongs.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = contentTopPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.catalog_empty),
                color = MiuixTheme.colorScheme.onBackgroundVariant,
            )
        }
    } else if (displayMode == CatalogDisplayMode.Grid) {
        CatalogGrid(
            songs = displayedSongs,
            sheetsBySong = sheetsBySong,
            scoresBySheetKey = scoresBySheetKey,
            coverImageStore = coverImageStore,
            state = gridState,
            contentTopPadding = contentTopPadding,
            onOpenSong = onOpenSong,
        )
    } else {
        val listState = rememberLazyListState()
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    top = contentTopPadding + 6.dp,
                    end = 16.dp,
                    bottom = 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                columnItems(displayedSongs, key = SongEntity::songIdentifier) { song ->
                    SongCard(
                        song = song,
                        sheets = sheetsBySong[song.songIdentifier].orEmpty(),
                        scoresBySheetKey = scoresBySheetKey,
                        versions = gameVersions,
                        coverImageStore = coverImageStore,
                        onClick = { onOpenSong(song.songIdentifier) },
                    )
                }
            }
            SongListScrollBar(
                state = listState,
                trackPadding = PaddingValues(top = contentTopPadding + 6.dp, bottom = 96.dp),
            )
        }
    }

    CatalogFilterDialog(
        show = showFilterDialog,
        settings = filterSettings,
        categories = categories,
        versions = versions,
        onSettingsChange = onFilterSettingsChange,
        onDismiss = onDismissFilter,
    )
}

@Composable
private fun CatalogGrid(
    songs: List<SongEntity>,
    sheetsBySong: Map<String, List<SheetEntity>>,
    scoresBySheetKey: Map<String, ScoreEntity>,
    coverImageStore: CoverImageStore,
    state: CatalogPhotoGridState,
    contentTopPadding: Dp,
    onOpenSong: (String) -> Unit,
) {
    val progressScale = remember(state) {
        {
            state.zoom
            state.geometry?.let { it.maxCellSize / it.cellSize } ?: 1f
        }
    }
    ZoomableCoverGrid(
        modifier = Modifier.fillMaxSize(),
        contentTopPadding = contentTopPadding,
        contentBottomPadding = 96.dp,
        items = songs,
        state = state,
        itemKey = SongEntity::songIdentifier,
    ) { song, columns, imageSize, filler ->
        SongGridCell(
            song = song,
            sheets = sheetsBySong[song.songIdentifier].orEmpty(),
            scoresBySheetKey = scoresBySheetKey,
            coverImageStore = coverImageStore,
            columnCount = columns,
            cornerRadius = 0.dp,
            showDots = true,
            imageSize = imageSize,
            animateCoverChanges = state.transforming,
            progressScale = progressScale,
            filler = filler,
            enabled = !state.transforming,
            onClick = { onOpenSong(song.songIdentifier) },
        )
    }
}
