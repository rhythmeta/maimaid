package org.rhythmeta.maimaid.core.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.combine
import org.rhythmeta.maimaid.core.database.Best50Row
import org.rhythmeta.maimaid.core.database.GameVersionEntity
import org.rhythmeta.maimaid.core.database.MaimaidDatabase
import org.rhythmeta.maimaid.core.database.SheetEntity
import org.rhythmeta.maimaid.core.database.SongEntity

data class Best50State(
    val total: Int = 0,
    val b35: List<RatingUtils.Entry> = emptyList(),
    val b15: List<RatingUtils.Entry> = emptyList(),
    val latestVersion: String? = null,
    val isEmpty: Boolean = true,
)

@OptIn(ExperimentalCoroutinesApi::class)
class Best50Repository(
    private val database: MaimaidDatabase,
    private val profileRepository: ProfileRepository,
    private val catalogRepository: CatalogRepository,
) {
    fun observeBest50(
        versionOverride: String? = null,
        b35CountOverride: Int? = null,
        b15CountOverride: Int? = null,
        constantMode: Best50ConstantMode = Best50ConstantMode.Server,
    ): Flow<Best50State> =
        profileRepository.activeProfile.flatMapLatest { profile ->
            if (profile == null) {
                flowOf(Best50State())
            } else {
                combine(
                    database.scoreDao().observeBest50Rows(profile.id),
                    catalogRepository.versions,
                    catalogRepository.songs,
                    catalogRepository.sheets,
                    catalogRepository.chartFit,
                ) { rows, versions, songs, sheets, chartFit ->
                    calculateBest50(
                        rows = rows,
                        versions = versions,
                        songs = songs,
                        sheets = sheets,
                        server = profile.server,
                        b35Count = b35CountOverride ?: profile.b35Count,
                        b15Count = b15CountOverride ?: profile.b15Count,
                        versionOverride = versionOverride,
                        constantMode = constantMode,
                        chartFit = chartFit,
                    )
                }
            }
        }.flowOn(Dispatchers.Default)

    /**
     * Theoretical Best 50 for a version: the highest-rated charts available with a perfect
     * perfect 101% play (the rating formula still caps at 100.5%), always evaluated with
     * version constants. Filters and capacities do not apply.
     */
    fun observeTheoreticalBest50(
        versionOverride: String? = null,
    ): Flow<Best50State> =
        profileRepository.activeProfile.flatMapLatest { profile ->
            if (profile == null) {
                flowOf(Best50State())
            } else {
                combine(
                    catalogRepository.versions,
                    catalogRepository.songs,
                    catalogRepository.sheets,
                    catalogRepository.chartFit,
                ) { versions, songs, sheets, chartFit ->
                    calculateTheoreticalBest50(
                        versions = versions,
                        songs = songs,
                        sheets = sheets,
                        server = profile.server,
                        versionOverride = versionOverride,
                        chartFit = chartFit,
                    )
                }
            }
        }.flowOn(Dispatchers.Default)
}

internal fun calculateBest50(
	rows: List<Best50Row>,
	versions: List<GameVersionEntity>,
	songs: List<org.rhythmeta.maimaid.core.database.SongEntity>,
	sheets: List<SheetEntity>,
	server: String,
	b35Count: Int,
	b15Count: Int,
	versionOverride: String?,
	constantMode: Best50ConstantMode = Best50ConstantMode.Server,
	chartFit: StaticBundleResponse.ChartFitPayload = StaticBundleResponse.ChartFitPayload(),
): Best50State {
    val versionNames = versions.sortedBy { it.sortOrder }.map { it.name }
    val latest = versionOverride?.takeIf { candidate ->
        versionNames.any { it.equals(candidate, ignoreCase = true) }
    } ?: RatingUtils.latestVersionForServer(songs, sheets, versions, server)
    val hasVersionOverride = versionOverride != null
    // A concrete version selection is an explicit historical snapshot. Treat
    // every chart as playable and use the shared JP/INTL version-window rules.
    val categoryServer = if (hasVersionOverride && server.equals("cn", ignoreCase = true)) "jp" else server
    val afterCircle = RatingUtils.isAfterCircle(latest, versionNames)
    val sheetsByKey = sheets.filterNot { it.isRemoved }.associateBy { it.sheetKey }
    val sheetsBySong = sheetsByKey.values.groupBy(SheetEntity::songIdentifier)
    val b35 = mutableListOf<RatingUtils.Entry>()
    val b15 = mutableListOf<RatingUtils.Entry>()
    rows.forEach { row ->
        if (row.category.contains("utage", ignoreCase = true) || row.type.contains("utage", ignoreCase = true)) return@forEach
        val sheet = sheetsByKey[row.sheetKey] ?: return@forEach
        if (!hasVersionOverride && !ServerChartPolicy.isPlayable(sheet, server)) return@forEach
        val metadata = ServerChartPolicy.metadata(sheet, server)
        val isNew = RatingUtils.category(
            metadata.version ?: row.songVersion,
            latest,
            categoryServer,
            true,
            versionNames,
            songId = sheet.providerSongId,
        ) ?: return@forEach
        val remasterUsesOldBucket = isNew &&
            sheet.difficulty.equals("remaster", ignoreCase = true) &&
            sheetsBySong[sheet.songIdentifier].orEmpty().any { baseSheet ->
                !baseSheet.difficulty.equals("remaster", ignoreCase = true) &&
                    RatingUtils.category(
                        ServerChartPolicy.metadata(baseSheet, server).version
                            ?: songs.firstOrNull { it.songIdentifier == sheet.songIdentifier }?.version,
                        latest,
                        categoryServer,
                        hasVersionOverride || ServerChartPolicy.isPlayable(baseSheet, server),
                        versionNames,
                        songId = baseSheet.providerSongId,
                    ) == false
            }
        val level = Best50ConstantResolver.resolve(
            sheet = sheet,
            serverConstant = metadata.ratingLevel,
            selectedVersion = latest,
            mode = constantMode,
            chartFit = chartFit,
        ) ?: return@forEach
        val rating = RatingUtils.calculate(level, row.achievement, row.fc, afterCircle)
        if (rating <= 0) return@forEach
        (if (isNew && !remasterUsesOldBucket) b15 else b35).add(
            RatingUtils.Entry(
                sheetKey = row.sheetKey,
                songIdentifier = row.songIdentifier,
                songId = row.songId,
                title = row.title,
                imageName = row.imageName,
                achievement = row.achievement,
                rating = rating,
                level = level,
                difficulty = row.difficulty,
                type = row.type,
                dxScore = row.dxScore,
                maxDxScore = row.maxDxScore,
                fc = row.fc,
                fs = row.fs,
                isNew = isNew && !remasterUsesOldBucket,
                category = row.category,
            ),
        )
    }
    val selected35 = b35.sortedByDescending { it.rating }.take(b35Count.coerceAtLeast(0))
    val selected15 = b15.sortedByDescending { it.rating }.take(b15Count.coerceAtLeast(0))
    return Best50State(
        total = (selected35 + selected15).sumOf { it.rating },
        b35 = selected35,
        b15 = selected15,
        latestVersion = latest,
        isEmpty = selected35.isEmpty() && selected15.isEmpty(),
    )
}


internal fun calculateTheoreticalBest50(
    versions: List<GameVersionEntity>,
    songs: List<SongEntity>,
    sheets: List<SheetEntity>,
    server: String,
    versionOverride: String?,
    chartFit: StaticBundleResponse.ChartFitPayload = StaticBundleResponse.ChartFitPayload(),
): Best50State {
    val versionNames = versions.sortedBy { it.sortOrder }.map { it.name }
    val latest = versionOverride?.takeIf { candidate ->
        versionNames.any { it.equals(candidate, ignoreCase = true) }
    } ?: RatingUtils.latestVersionForServer(songs, sheets, versions, server)
    val hasVersionOverride = versionOverride != null
    val categoryServer = if (hasVersionOverride && server.equals("cn", ignoreCase = true)) "jp" else server
    val afterCircle = RatingUtils.isAfterCircle(latest, versionNames)
    val songsById = songs.filterNot { it.isRemoved }.associateBy(SongEntity::songIdentifier)
    val sheetsByKey = sheets.filterNot { it.isRemoved }
    val sheetsBySong = sheetsByKey.groupBy(SheetEntity::songIdentifier)
    val b35 = mutableListOf<RatingUtils.Entry>()
    val b15 = mutableListOf<RatingUtils.Entry>()
    sheetsByKey.forEach { sheet ->
        if (sheet.type.contains("utage", ignoreCase = true)) return@forEach
        val song = songsById[sheet.songIdentifier] ?: return@forEach
        if (song.category.contains("utage", ignoreCase = true)) return@forEach
        if (!hasVersionOverride && !ServerChartPolicy.isPlayable(sheet, server)) return@forEach
        val metadata = ServerChartPolicy.metadata(sheet, server)
        val isNew = RatingUtils.category(
            metadata.version ?: song.version,
            latest,
            categoryServer,
            true,
            versionNames,
            songId = sheet.providerSongId,
        ) ?: return@forEach
        val remasterUsesOldBucket = isNew &&
            sheet.difficulty.equals("remaster", ignoreCase = true) &&
            sheetsBySong[song.songIdentifier].orEmpty().any { baseSheet ->
                !baseSheet.difficulty.equals("remaster", ignoreCase = true) &&
                    RatingUtils.category(
                        ServerChartPolicy.metadata(baseSheet, server).version ?: song.version,
                        latest,
                        categoryServer,
                        hasVersionOverride || ServerChartPolicy.isPlayable(baseSheet, server),
                        versionNames,
                        songId = baseSheet.providerSongId,
                    ) == false
            }
        val level = Best50ConstantResolver.resolve(
            sheet = sheet,
            serverConstant = metadata.ratingLevel,
            selectedVersion = latest,
            mode = Best50ConstantMode.Version,
            chartFit = chartFit,
        ) ?: return@forEach
        val achievement = TheoreticalAchievement
        val fc = TheoreticalFc
        val rating = RatingUtils.calculate(level, achievement, fc, afterCircle)
        if (rating <= 0) return@forEach
        val maxDxScore = ScoreRules.effectiveMaxDxScore(sheet.total)
        (if (isNew && !remasterUsesOldBucket) b15 else b35).add(
            RatingUtils.Entry(
                sheetKey = sheet.sheetKey,
                songIdentifier = song.songIdentifier,
                songId = sheet.providerSongId,
                title = song.title,
                imageName = song.imageName,
                achievement = achievement,
                rating = rating,
                level = level,
                difficulty = sheet.difficulty,
                type = sheet.type,
                dxScore = maxDxScore,
                maxDxScore = maxDxScore,
                fc = fc,
                fs = TheoreticalFs,
                isNew = isNew && !remasterUsesOldBucket,
                category = song.category,
            ),
        )
    }
    val selected35 = b35.sortedWith(TheoreticalEntryOrder).take(TheoreticalOldCount)
    val selected15 = b15.sortedWith(TheoreticalEntryOrder).take(TheoreticalNewCount)
    return Best50State(
        total = (selected35 + selected15).sumOf(RatingUtils.Entry::rating),
        b35 = selected35,
        b15 = selected15,
        latestVersion = latest,
        isEmpty = selected35.isEmpty() && selected15.isEmpty(),
    )
}

private const val TheoreticalOldCount = 35
private const val TheoreticalNewCount = 15
private const val TheoreticalAchievement = 101.0
private const val TheoreticalFc = "app"
private const val TheoreticalFs = "fsdp"
private val TheoreticalEntryOrder =
    compareByDescending<RatingUtils.Entry> { it.rating }.thenBy { it.sheetKey }
