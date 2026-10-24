package org.rhythmeta.maimaid.ui.navigation

import kotlinx.serialization.Serializable

@Serializable
enum class RootDestination {
    Home,
    Scanner,
    Catalog,
    Settings,
}

@Serializable
enum class AppDetail {
    BestTable,
    RandomSong,
    Recommendations,
    ScoreQuery,
    ConstantTable,
    PlateProgress,
    Dan,
    DanDetail,
    CommunityAliases,
    StaticData,
    Profiles,
    BackendAuth,
    DivingFishImport,
    LxnsImport,
    OtogameImport,
    OtogameLogin,
    Appearance,
    About,
    Song,
    Collections,
    CollectionDetail,
}
