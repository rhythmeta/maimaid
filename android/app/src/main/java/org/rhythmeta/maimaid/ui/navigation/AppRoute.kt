package org.rhythmeta.maimaid.ui.navigation

import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.nav.core.NavKey

@Serializable
sealed interface AppRoute : NavKey {
    @Serializable
    data class Root(val destination: RootDestination = RootDestination.Home) : AppRoute

    @Serializable
    data class Detail(
        val detail: AppDetail,
        val songId: String? = null,
        val danCategoryId: String? = null,
        val collectionId: String? = null,
    ) : AppRoute

    @Serializable
    data class Song(
        val songId: String,
        val transitionId: String? = null,
    ) : AppRoute
}
