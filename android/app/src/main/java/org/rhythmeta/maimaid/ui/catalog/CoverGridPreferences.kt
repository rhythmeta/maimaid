package org.rhythmeta.maimaid.ui.catalog

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

private val Context.coverGridPreferencesDataStore by preferencesDataStore(name = "cover_grid_preferences")

enum class CoverGridPage(internal val preferenceKey: String) {
    Catalog("catalog_grid_columns"),
    ScoreQuery("score_query_grid_columns"),
    Collections("collections_grid_columns"),
}

internal class CoverGridPreferences(context: Context, page: CoverGridPage) {
    private val store = context.applicationContext.coverGridPreferencesDataStore
    private val key = intPreferencesKey(page.preferenceKey)

    suspend fun readColumns(): Int = store.data.first()[key]?.takeIf { it == 3 || it == 5 } ?: 5

    suspend fun saveColumns(columns: Int) {
        require(columns == 3 || columns == 5)
        store.edit { it[key] = columns }
    }
}

@Composable
fun rememberCatalogPhotoGridState(page: CoverGridPage): CatalogPhotoGridState {
    val context = LocalContext.current.applicationContext
    val preferences = remember(context, page) { CoverGridPreferences(context, page) }
    val state = rememberSaveable(page, saver = CatalogPhotoGridState.Saver) { CatalogPhotoGridState() }
    LaunchedEffect(state, preferences) {
        var savedColumns = preferences.readColumns()
        state.restoreColumns(savedColumns)
        snapshotFlow {
            if (!state.transforming && (state.zoom == 1f || state.zoom == 2f)) {
                catalogGridColumns(state.zoom)
            } else {
                null
            }
        }.filterNotNull().distinctUntilChanged().collect { columns ->
            if (columns != savedColumns) {
                preferences.saveColumns(columns)
                savedColumns = columns
            }
        }
    }
    return state
}
