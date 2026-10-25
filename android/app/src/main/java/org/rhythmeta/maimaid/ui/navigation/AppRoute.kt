package org.rhythmeta.maimaid.ui.navigation

import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.nav.core.NavKey

@Serializable
sealed interface AppRoute : NavKey {
    @Serializable
    data object Home : AppRoute

    @Serializable
    data object BestTable : AppRoute

    @Serializable
    data object RandomSong : AppRoute

    @Serializable
    data object Recommendations : AppRoute

    @Serializable
    data object ScoreQuery : AppRoute

    @Serializable
    data object ConstantTable : AppRoute

    @Serializable
    data object PlateProgress : AppRoute

    @Serializable
    data object Dan : AppRoute

    @Serializable
    data class DanDetail(val categoryId: String?, val title: String?) : AppRoute

    @Serializable
    data object CommunityAliases : AppRoute

    @Serializable
    data object StaticData : AppRoute

    @Serializable
    data object Profiles : AppRoute

    @Serializable
    data object BackendAuth : AppRoute

    @Serializable
    data object DivingFishImport : AppRoute

    @Serializable
    data object LxnsImport : AppRoute

    @Serializable
    data object SgwcmaidImport : AppRoute

    @Serializable
    data object OtogameImport : AppRoute

    @Serializable
    data object OtogameLogin : AppRoute

    @Serializable
    data object Appearance : AppRoute

    @Serializable
    data object About : AppRoute

    @Serializable
    data object SongCollections : AppRoute

    @Serializable
    data class CollectionDetail(val collectionId: String) : AppRoute

    @Serializable
    data class Song(val songId: String) : AppRoute

    /** Song detail opened from a song cover and presented with its shared-cover transition. */
    @Serializable
    data class CatalogSong(val songId: String, val transitionId: String) : AppRoute
}

fun AppDetail.toRoute(
    songId: String? = null,
    danCategoryId: String? = null,
    danCategoryTitle: String? = null,
    collectionId: String? = null,
): AppRoute = when (this) {
    AppDetail.BestTable -> AppRoute.BestTable
    AppDetail.RandomSong -> AppRoute.RandomSong
    AppDetail.Recommendations -> AppRoute.Recommendations
    AppDetail.ScoreQuery -> AppRoute.ScoreQuery
    AppDetail.ConstantTable -> AppRoute.ConstantTable
    AppDetail.PlateProgress -> AppRoute.PlateProgress
    AppDetail.Dan -> AppRoute.Dan
    AppDetail.DanDetail -> AppRoute.DanDetail(danCategoryId, danCategoryTitle)
    AppDetail.CommunityAliases -> AppRoute.CommunityAliases
    AppDetail.StaticData -> AppRoute.StaticData
    AppDetail.Profiles -> AppRoute.Profiles
    AppDetail.BackendAuth -> AppRoute.BackendAuth
    AppDetail.DivingFishImport -> AppRoute.DivingFishImport
    AppDetail.LxnsImport -> AppRoute.LxnsImport
    AppDetail.SgwcmaidImport -> AppRoute.SgwcmaidImport
    AppDetail.OtogameImport -> AppRoute.OtogameImport
    AppDetail.OtogameLogin -> AppRoute.OtogameLogin
    AppDetail.Appearance -> AppRoute.Appearance
    AppDetail.About -> AppRoute.About
    AppDetail.Song -> AppRoute.Song(songId.orEmpty())
    AppDetail.Collections -> AppRoute.SongCollections
    AppDetail.CollectionDetail -> AppRoute.CollectionDetail(collectionId.orEmpty())
}

val AppRoute.appDetail: AppDetail?
    get() = when (this) {
        AppRoute.Home -> null
        AppRoute.BestTable -> AppDetail.BestTable
        AppRoute.RandomSong -> AppDetail.RandomSong
        AppRoute.Recommendations -> AppDetail.Recommendations
        AppRoute.ScoreQuery -> AppDetail.ScoreQuery
        AppRoute.ConstantTable -> AppDetail.ConstantTable
        AppRoute.PlateProgress -> AppDetail.PlateProgress
        AppRoute.Dan -> AppDetail.Dan
        is AppRoute.DanDetail -> AppDetail.DanDetail
        AppRoute.CommunityAliases -> AppDetail.CommunityAliases
        AppRoute.StaticData -> AppDetail.StaticData
        AppRoute.Profiles -> AppDetail.Profiles
        AppRoute.BackendAuth -> AppDetail.BackendAuth
        AppRoute.DivingFishImport -> AppDetail.DivingFishImport
        AppRoute.LxnsImport -> AppDetail.LxnsImport
        AppRoute.SgwcmaidImport -> AppDetail.SgwcmaidImport
        AppRoute.OtogameImport -> AppDetail.OtogameImport
        AppRoute.OtogameLogin -> AppDetail.OtogameLogin
        AppRoute.Appearance -> AppDetail.Appearance
        AppRoute.About -> AppDetail.About
        AppRoute.SongCollections -> AppDetail.Collections
        is AppRoute.CollectionDetail -> AppDetail.CollectionDetail
        is AppRoute.Song, is AppRoute.CatalogSong -> AppDetail.Song
    }

val AppRoute.songId: String?
    get() = when (this) {
        is AppRoute.Song -> songId
        is AppRoute.CatalogSong -> songId
        else -> null
    }

val AppRoute.danCategoryId: String?
    get() = (this as? AppRoute.DanDetail)?.categoryId

val AppRoute.danCategoryTitle: String?
    get() = (this as? AppRoute.DanDetail)?.title

val AppRoute.collectionId: String?
    get() = (this as? AppRoute.CollectionDetail)?.collectionId
