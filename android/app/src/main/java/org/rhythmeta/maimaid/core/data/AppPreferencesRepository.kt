package org.rhythmeta.maimaid.core.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import org.rhythmeta.maimaid.ui.theme.AppThemeSettings
import org.rhythmeta.maimaid.ui.theme.ColorMode
import org.rhythmeta.maimaid.ui.theme.DefaultAppThemeSettings
import org.rhythmeta.maimaid.ui.theme.effectiveFor

private val Context.appPreferencesDataStore by preferencesDataStore(name = "app_preferences")

class AppPreferencesRepository(
    private val context: Context,
) {

    private val backupSettingKinds = mapOf(
        "color_mode" to "int",
        "key_color" to "int",
        "color_style" to "string",
        "color_spec" to "string",
        "enable_blur" to "bool",
        "enable_floating_bottom_bar" to "bool",
        "enable_floating_bottom_bar_blur" to "bool",
        "enable_predictive_back" to "bool",
        "page_scale" to "int",
        "theme_mode" to "string",
        "theme_color_source" to "string",
        "theme_custom_color_argb" to "int",
        "show_scanner_bounding_boxes" to "bool",
        "third_party_score_sync_enabled" to "bool",
        "catalog_sort_option" to "string",
        "catalog_sort_ascending" to "bool",
        "catalog_grid_columns" to "int",
        "catalog_hide_unavailable_songs" to "bool",
        "catalog_show_playable_songs_only" to "bool",
        "score_query_display_mode" to "string",
        "score_query_grid_columns" to "int",
        "score_query_sort_mode" to "string",
        "score_query_sort_ascending" to "bool",
        "best50_constant_mode" to "string"
    )

    suspend fun exportBackupSettings(): List<org.rhythmeta.backup.proto.BackupProtocol.Setting> =
        context.appPreferencesDataStore.data.first().asMap().mapNotNull { (key, value) ->
            val kind = backupSettingKinds[key.name] ?: return@mapNotNull null
            org.rhythmeta.backup.proto.BackupProtocol.Setting.newBuilder()
                .setKey("android.maimaid.${key.name}").setKind(kind).apply {
                    when (kind) {
                        "int" -> integerValue = (value as Int).toLong()
                        "string" -> stringValue = value as String
                        "bool" -> boolValue = value as Boolean
                    }
                }.build()
        }

    suspend fun restoreBackupSettings(settings: List<org.rhythmeta.backup.proto.BackupProtocol.Setting>) {
        val own = settings.filter { it.key.startsWith("android.maimaid.") }
        own.forEach { value ->
            val name = value.key.removePrefix("android.maimaid.")
            require(backupSettingKinds[name] == value.kind) { "Unsupported personal setting." }
            if (value.kind == "int") require(value.integerValue in Int.MIN_VALUE..Int.MAX_VALUE)
        }
        context.appPreferencesDataStore.edit { values ->
            for ((name, kind) in backupSettingKinds) when (kind) {
                "int" -> values.remove(intPreferencesKey(name))
                "string" -> values.remove(stringPreferencesKey(name))
                "bool" -> values.remove(booleanPreferencesKey(name))
            }
            for (value in own) {
                val name = value.key.removePrefix("android.maimaid.")
                when (value.kind) {
                    "int" -> values[intPreferencesKey(name)] = value.integerValue.toInt()
                    "string" -> values[stringPreferencesKey(name)] = value.stringValue
                    "bool" -> values[booleanPreferencesKey(name)] = value.boolValue
                }
            }
        }
    }

    val themeSettings: Flow<AppThemeSettings> = context.appPreferencesDataStore.data.map { preferences ->
        val colorMode = ColorMode.fromValue(preferences[ColorModeKey] ?: ColorMode.SYSTEM.value)
        val paletteStyle = preferences[ColorStyleKey]
            ?.let { value -> runCatching { PaletteStyle.valueOf(value) }.getOrNull() }
            ?: DefaultAppThemeSettings.paletteStyle
        val colorSpec = preferences[ColorSpecKey]
            ?.let { value -> runCatching { ColorSpec.SpecVersion.valueOf(value) }.getOrNull() }
            ?: DefaultAppThemeSettings.colorSpec
        AppThemeSettings(
            colorMode = colorMode,
            keyColor = preferences[KeyColorKey] ?: DefaultAppThemeSettings.keyColor,
            paletteStyle = paletteStyle,
            colorSpec = colorSpec.effectiveFor(paletteStyle),
            enableBlur = preferences[EnableBlurKey] ?: DefaultAppThemeSettings.enableBlur,
            enableFloatingBottomBar = preferences[EnableFloatingBottomBarKey]
                ?: DefaultAppThemeSettings.enableFloatingBottomBar,
            enableFloatingBottomBarBlur = preferences[EnableFloatingBottomBarBlurKey]
                ?: DefaultAppThemeSettings.enableFloatingBottomBarBlur,
            enablePredictiveBack = preferences[EnablePredictiveBackKey]
                ?: DefaultAppThemeSettings.enablePredictiveBack,
            pageScale = ((preferences[PageScaleKey] ?: 100) / 100f).coerceIn(0.8f, 1.1f),
        )
    }

    suspend fun setThemeSettings(settings: AppThemeSettings) {
        context.appPreferencesDataStore.edit { preferences ->
            preferences[ColorModeKey] = settings.colorMode.value
            preferences[KeyColorKey] = settings.keyColor
            preferences[ColorStyleKey] = settings.paletteStyle.name
            preferences[ColorSpecKey] = settings.colorSpec.effectiveFor(settings.paletteStyle).name
            preferences[EnableBlurKey] = settings.enableBlur
            preferences[EnableFloatingBottomBarKey] = settings.enableFloatingBottomBar
            preferences[EnableFloatingBottomBarBlurKey] = settings.enableFloatingBottomBarBlur
            preferences[EnablePredictiveBackKey] = settings.enablePredictiveBack
            preferences[PageScaleKey] = (settings.pageScale.coerceIn(0.8f, 1.1f) * 100f).toInt()
        }
    }

    suspend fun setColorMode(mode: ColorMode) = updateTheme { it.copy(colorMode = mode) }
    suspend fun setKeyColor(color: Int) = updateTheme { it.copy(keyColor = color) }
    suspend fun setPaletteStyle(style: PaletteStyle) = updateTheme { it.copy(paletteStyle = style) }
    suspend fun setColorSpec(spec: ColorSpec.SpecVersion) = updateTheme { it.copy(colorSpec = spec) }
    suspend fun setEnableBlur(enabled: Boolean) = updateTheme { it.copy(enableBlur = enabled) }
    suspend fun setEnableFloatingBottomBar(enabled: Boolean) = updateTheme {
        it.copy(
            enableFloatingBottomBar = enabled,
            enableFloatingBottomBarBlur = it.enableFloatingBottomBarBlur && enabled,
        )
    }
    suspend fun setEnableFloatingBottomBarBlur(enabled: Boolean) = updateTheme {
        it.copy(enableFloatingBottomBarBlur = enabled && it.enableFloatingBottomBar)
    }
    suspend fun setEnablePredictiveBack(enabled: Boolean) = updateTheme { it.copy(enablePredictiveBack = enabled) }
    suspend fun setPageScale(scale: Float) = updateTheme { it.copy(pageScale = scale) }

    private suspend fun updateTheme(transform: (AppThemeSettings) -> AppThemeSettings) {
        val current = themeSettings.first()
        setThemeSettings(transform(current))
    }

	val showScannerBoundingBoxes: Flow<Boolean> = context.appPreferencesDataStore.data.map { preferences ->
        preferences[ShowScannerBoundingBoxesKey] ?: false
    }

    val thirdPartyScoreSyncEnabled: Flow<Boolean> = context.appPreferencesDataStore.data.map { preferences ->
        preferences[ThirdPartyScoreSyncEnabledKey] ?: false
    }

    val catalogSortOption: Flow<CatalogSortOption> = context.appPreferencesDataStore.data.map { preferences ->
        preferences[CatalogSortOptionKey]
            ?.let { stored -> CatalogSortOption.entries.firstOrNull { it.name == stored } }
            ?: CatalogSortOption.DefaultOrder
    }

    val catalogSortAscending: Flow<Boolean> = context.appPreferencesDataStore.data.map { preferences ->
        preferences[CatalogSortAscendingKey] ?: true
    }

    val catalogGridColumns: Flow<Int> = context.appPreferencesDataStore.data.map { preferences ->
        (preferences[CatalogGridColumnsKey] ?: 4).coerceIn(3, 9)
    }

    val catalogHideUnavailableSongs: Flow<Boolean> = context.appPreferencesDataStore.data.map { preferences ->
        preferences[CatalogHideUnavailableSongsKey] ?: false
    }

    val catalogShowPlayableSongsOnly: Flow<Boolean> = context.appPreferencesDataStore.data.map { preferences ->
        preferences[CatalogShowPlayableSongsOnlyKey] ?: false
    }

    val scoreQueryDisplayMode: Flow<ScoreQueryDisplayMode> = context.appPreferencesDataStore.data.map { preferences ->
        preferences[ScoreQueryDisplayModeKey]
            ?.let { stored -> ScoreQueryDisplayMode.entries.firstOrNull { it.name == stored } }
            ?: ScoreQueryDisplayMode.Grid
    }

    val scoreQueryGridColumns: Flow<Int> = context.appPreferencesDataStore.data.map { preferences ->
        (preferences[ScoreQueryGridColumnsKey] ?: 5).coerceIn(3, 9)
    }

    val scoreQuerySortMode: Flow<ScoreQuerySortMode> = context.appPreferencesDataStore.data.map { preferences ->
        preferences[ScoreQuerySortModeKey]
            ?.let { stored -> ScoreQuerySortMode.entries.firstOrNull { it.name == stored } }
            ?: ScoreQuerySortMode.Rating
    }

    val scoreQuerySortAscending: Flow<Boolean> = context.appPreferencesDataStore.data.map { preferences ->
        preferences[ScoreQuerySortAscendingKey] ?: false
    }

    val best50ConstantMode: Flow<Best50ConstantMode> = context.appPreferencesDataStore.data.map { preferences ->
        preferences[Best50ConstantModeKey]
            ?.let { stored -> Best50ConstantMode.entries.firstOrNull { it.name == stored } }
            ?: Best50ConstantMode.Server
    }

	suspend fun setShowScannerBoundingBoxes(show: Boolean) {
        context.appPreferencesDataStore.edit { preferences ->
            preferences[ShowScannerBoundingBoxesKey] = show
        }
    }

    suspend fun setThirdPartyScoreSyncEnabled(enabled: Boolean) {
        context.appPreferencesDataStore.edit { preferences ->
            preferences[ThirdPartyScoreSyncEnabledKey] = enabled
        }
    }

    suspend fun setCatalogSortOption(sortOption: CatalogSortOption) {
        context.appPreferencesDataStore.edit { preferences ->
            preferences[CatalogSortOptionKey] = sortOption.name
        }
    }

    suspend fun setCatalogSortAscending(ascending: Boolean) {
        context.appPreferencesDataStore.edit { preferences ->
            preferences[CatalogSortAscendingKey] = ascending
        }
    }

    suspend fun setCatalogGridColumns(columns: Int) {
        context.appPreferencesDataStore.edit { preferences ->
            preferences[CatalogGridColumnsKey] = columns.coerceIn(3, 9)
        }
    }

    suspend fun setCatalogHideUnavailableSongs(hide: Boolean) {
        context.appPreferencesDataStore.edit { preferences ->
            preferences[CatalogHideUnavailableSongsKey] = hide
        }
    }

    suspend fun setCatalogShowPlayableSongsOnly(show: Boolean) {
        context.appPreferencesDataStore.edit { preferences ->
            preferences[CatalogShowPlayableSongsOnlyKey] = show
        }
    }

    suspend fun setScoreQueryDisplayMode(displayMode: ScoreQueryDisplayMode) {
        context.appPreferencesDataStore.edit { preferences ->
            preferences[ScoreQueryDisplayModeKey] = displayMode.name
        }
    }

    suspend fun setScoreQueryGridColumns(columns: Int) {
        context.appPreferencesDataStore.edit { preferences ->
            preferences[ScoreQueryGridColumnsKey] = columns.coerceIn(3, 9)
        }
    }

    suspend fun setScoreQuerySortMode(sortMode: ScoreQuerySortMode) {
        context.appPreferencesDataStore.edit { preferences ->
            preferences[ScoreQuerySortModeKey] = sortMode.name
        }
    }

    suspend fun setScoreQuerySortAscending(ascending: Boolean) {
        context.appPreferencesDataStore.edit { preferences ->
            preferences[ScoreQuerySortAscendingKey] = ascending
        }
    }

    suspend fun setBest50ConstantMode(mode: Best50ConstantMode) {
        context.appPreferencesDataStore.edit { preferences ->
            preferences[Best50ConstantModeKey] = mode.name
        }
    }

    private companion object {
        val ColorModeKey = intPreferencesKey("color_mode")
        val KeyColorKey = intPreferencesKey("key_color")
        val ColorStyleKey = stringPreferencesKey("color_style")
        val ColorSpecKey = stringPreferencesKey("color_spec")
        val EnableBlurKey = booleanPreferencesKey("enable_blur")
        val EnableFloatingBottomBarKey = booleanPreferencesKey("enable_floating_bottom_bar")
        val EnableFloatingBottomBarBlurKey = booleanPreferencesKey("enable_floating_bottom_bar_blur")
        val EnablePredictiveBackKey = booleanPreferencesKey("enable_predictive_back")
        val PageScaleKey = intPreferencesKey("page_scale")
        val ThemeModeKey = stringPreferencesKey("theme_mode")
        val ThemeColorSourceKey = stringPreferencesKey("theme_color_source")
        val ThemeCustomColorArgbKey = intPreferencesKey("theme_custom_color_argb")
        val ShowScannerBoundingBoxesKey = booleanPreferencesKey("show_scanner_bounding_boxes")
        val ThirdPartyScoreSyncEnabledKey = booleanPreferencesKey("third_party_score_sync_enabled")
        val CatalogSortOptionKey = stringPreferencesKey("catalog_sort_option")
        val CatalogSortAscendingKey = booleanPreferencesKey("catalog_sort_ascending")
        val CatalogGridColumnsKey = intPreferencesKey("catalog_grid_columns")
        val CatalogHideUnavailableSongsKey = booleanPreferencesKey("catalog_hide_unavailable_songs")
        val CatalogShowPlayableSongsOnlyKey = booleanPreferencesKey("catalog_show_playable_songs_only")
        val ScoreQueryDisplayModeKey = stringPreferencesKey("score_query_display_mode")
        val ScoreQueryGridColumnsKey = intPreferencesKey("score_query_grid_columns")
        val ScoreQuerySortModeKey = stringPreferencesKey("score_query_sort_mode")
        val ScoreQuerySortAscendingKey = booleanPreferencesKey("score_query_sort_ascending")
        val Best50ConstantModeKey = stringPreferencesKey("best50_constant_mode")
    }
}
