package org.rhythmeta.maimaid.ui

import android.os.Build
import android.view.RoundedCorner
import androidx.activity.BackEventCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel as composeViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import org.rhythmeta.maimaid.R
import org.rhythmeta.maimaid.core.AppContainer
import org.rhythmeta.maimaid.core.data.SongCollectionExport
import org.rhythmeta.maimaid.ui.catalog.CatalogScreen
import org.rhythmeta.maimaid.ui.catalog.CatalogDisplayMode
import org.rhythmeta.maimaid.ui.catalog.CatalogSortTopBarAction
import org.rhythmeta.maimaid.ui.catalog.CatalogFilterSettings
import org.rhythmeta.maimaid.ui.catalog.CatalogSearchBar
import org.rhythmeta.maimaid.core.data.CatalogSortOption
import org.rhythmeta.maimaid.ui.catalog.CatalogTopBarActions
import org.rhythmeta.maimaid.ui.catalog.CatalogContainerTransform
import org.rhythmeta.maimaid.ui.catalog.CatalogCoverTransition
import org.rhythmeta.maimaid.ui.catalog.LocalSongNavigationHost
import org.rhythmeta.maimaid.ui.catalog.SongCoverSource
import org.rhythmeta.maimaid.ui.catalog.SongNavigationHost
import org.rhythmeta.maimaid.ui.best.BestTableConstantTopBarAction
import org.rhythmeta.maimaid.ui.common.DetailScreen
import org.rhythmeta.maimaid.ui.components.LiquidGlassTab
import org.rhythmeta.maimaid.ui.components.LiquidGlassTabBar
import org.rhythmeta.maimaid.ui.components.TopBarBottomShape
import org.rhythmeta.maimaid.ui.home.HomeScreen
import org.rhythmeta.maimaid.ui.dan.DanPageSwitcher
import org.rhythmeta.maimaid.ui.dan.DanRegularPage
import org.rhythmeta.maimaid.ui.navigation.AppDetail
import org.rhythmeta.maimaid.ui.navigation.AppRoute
import org.rhythmeta.maimaid.ui.navigation.RootDestination
import org.rhythmeta.maimaid.ui.onboarding.InitialCatalogGate
import org.rhythmeta.maimaid.ui.profile.ProfileEditorSheet
import org.rhythmeta.maimaid.ui.random.RandomSongSessionState
import org.rhythmeta.maimaid.ui.recommendation.RecommendationNewPage
import org.rhythmeta.maimaid.ui.recommendation.RecommendationPageSwitcher
import org.rhythmeta.maimaid.ui.scorequery.ScoreQueryTopBarActions
import org.rhythmeta.maimaid.ui.scorequery.ScoreQueryViewModel
import org.rhythmeta.maimaid.ui.scanner.ScannerScreen
import org.rhythmeta.maimaid.ui.settings.SettingsScreen
import org.rhythmeta.maimaid.ui.theme.LocalEnableFloatingBottomBar
import org.rhythmeta.maimaid.ui.theme.LocalEnableFloatingBottomBarBlur
import org.rhythmeta.maimaid.ui.theme.LocalEnableBlur
import org.rhythmeta.maimaid.ui.theme.LocalEnablePredictiveBack
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.TopAppBarState
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.nav.transition.NavMotion
import top.yukonga.miuix.kmp.nav.transition.NavSettleSpec
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import top.yukonga.miuix.kmp.nav.transition.NavTransition
import top.yukonga.miuix.kmp.nav.transition.NavTransitions
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowListPopup
import top.yukonga.miuix.kmp.window.WindowDialog
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur

private val SettingsDetailTransition = object : NavTransition by NavTransitions.MiuixDefault {
    override val motion: NavMotion = NavMotion(
        commit = NavTransitions.MiuixDefault.motion.commit,
        cancel = NavTransitions.MiuixDefault.motion.cancel,
        programmatic = NavSettleSpec.Tween(
            durationMillis = 400,
            easing = FastOutSlowInEasing,
        ),
    )
}

@Composable
private fun NavigationEventGate(
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    val parent = LocalNavigationEventDispatcherOwner.current
    if (parent == null) {
        content()
        return
    }
    val dispatcher = remember(parent) {
        NavigationEventDispatcher(parent = parent.navigationEventDispatcher)
    }
    SideEffect { dispatcher.isEnabled = enabled }
    DisposableEffect(dispatcher) {
        onDispose { dispatcher.dispose() }
    }
    val owner = remember(dispatcher) {
        object : NavigationEventDispatcherOwner {
            override val navigationEventDispatcher: NavigationEventDispatcher = dispatcher
        }
    }
    CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides owner, content = content)
}

@Composable
fun MaimaidApp(
    viewModel: MainViewModel,
    container: AppContainer,
    onSendLogs: () -> Unit,
    initialDetail: AppDetail? = null,
    resetToHome: Boolean = false,
    pendingCollectionImport: SongCollectionExport? = null,
    onCollectionImportConfirmed: (SongCollectionExport) -> Unit = {},
    onCollectionImportDismissed: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val themeSettings by viewModel.themeSettings.collectAsStateWithLifecycle()
    val enableBlur = LocalEnableBlur.current
    val collections by container.songCollectionRepository.collections.collectAsStateWithLifecycle(emptyList())
    val initialCatalogState by viewModel.initialCatalogState.collectAsStateWithLifecycle()
    val showScannerBoundingBoxes by viewModel.showScannerBoundingBoxes.collectAsStateWithLifecycle()
    val thirdPartyScoreSyncEnabled by viewModel.thirdPartyScoreSyncEnabled.collectAsStateWithLifecycle()
    val catalogSortOption by viewModel.catalogSortOption.collectAsStateWithLifecycle()
    val catalogSortAscending by viewModel.catalogSortAscending.collectAsStateWithLifecycle()
    val catalogGridColumns by viewModel.catalogGridColumns.collectAsStateWithLifecycle()
    val catalogHideUnavailableSongs by viewModel.catalogHideUnavailableSongs.collectAsStateWithLifecycle()
    val catalogShowPlayableSongsOnly by viewModel.catalogShowPlayableSongsOnly.collectAsStateWithLifecycle()
    if (initialCatalogState != InitialCatalogState.Ready) {
        InitialCatalogGate(
            state = initialCatalogState,
            syncStatus = uiState.catalogSyncStatus,
            onStartDownload = viewModel::startInitialCatalogSync,
        )
        return
    }
    val navBackStack = rememberNavBackStack<AppRoute>(AppRoute.Root())
    val navigationCornerRadius = rememberNavSystemCornerRadius()
    val songCoverTransitions = remember { mutableStateMapOf<String, CatalogCoverTransition>() }
    var catalogCoverTransition by remember { mutableStateOf<CatalogCoverTransition?>(null) }
    var navigationOrigin by remember { mutableStateOf(Offset.Zero) }
    var destination by rememberSaveable { mutableStateOf(RootDestination.Home) }
    var animateRootTransition by remember { mutableStateOf(true) }
    val rootBackProgress = remember { Animatable(0f) }
    val appUpdateViewModel: org.rhythmeta.maimaid.ui.update.AppUpdateViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = org.rhythmeta.maimaid.ui.update.AppUpdateViewModel.Factory(container.appUpdateRepository),
    )
    var detail by rememberSaveable { mutableStateOf<AppDetail?>(null) }
    var selectedSongId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedDanCategoryId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedDanCategoryTitle by rememberSaveable { mutableStateOf<String?>(null) }
    val communityAliasListState = rememberSaveable(saver = LazyListState.Saver) {
        LazyListState()
    }
    val constantTableListState = rememberSaveable(saver = LazyListState.Saver) {
        LazyListState()
    }
    val communityAliasScrollBehavior = MiuixScrollBehavior()
    val constantTableTopAppBarState = rememberSaveable(saver = TopAppBarState.Saver) {
        TopAppBarState(
            initialHeightOffsetLimit = -Float.MAX_VALUE,
            initialHeightOffset = 0f,
            initialContentOffset = 0f,
        )
    }
    val constantTableScrollBehavior = MiuixScrollBehavior(state = constantTableTopAppBarState)
    var songDetailBackground by remember { mutableStateOf<Pair<String, Color>?>(null) }
    var songDetailTitle by remember { mutableStateOf<Pair<String, String>?>(null) }
    var catalogDisplayMode by rememberSaveable { mutableStateOf(CatalogDisplayMode.List) }
    var catalogFilterSettings by remember { mutableStateOf(CatalogFilterSettings()) }
    var catalogQuery by rememberSaveable { mutableStateOf("") }
    var catalogSearchExpanded by rememberSaveable { mutableStateOf(false) }
    var catalogSearchVisible by rememberSaveable { mutableStateOf(true) }
    var catalogSearchFocusRequestToken by rememberSaveable { mutableIntStateOf(0) }
    var restoreCatalogSearchFocus by rememberSaveable { mutableStateOf(false) }
    var showCatalogFilter by rememberSaveable { mutableStateOf(false) }
    var showConstantTableFilter by rememberSaveable { mutableStateOf(false) }
    var showHomeProfileEditor by rememberSaveable { mutableStateOf(false) }
    var profileCreateRequested by rememberSaveable { mutableStateOf(false) }
    var bestTableExportRequested by rememberSaveable { mutableStateOf(false) }
    var randomSongFilterRequested by rememberSaveable { mutableStateOf(false) }
    var collectionsCreateRequested by rememberSaveable { mutableStateOf(false) }
    var collectionsImportRequested by rememberSaveable { mutableStateOf(false) }
    var collectionsDisplayMode by rememberSaveable { mutableStateOf(CatalogDisplayMode.List) }
    var collectionsSortOption by rememberSaveable { mutableStateOf(CatalogSortOption.DefaultOrder) }
    var collectionsSortAscending by rememberSaveable { mutableStateOf(true) }
    var selectedCollectionId by rememberSaveable { mutableStateOf<String?>(null) }
    var collectionsRenameRequested by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(initialDetail, resetToHome) {
        when {
            resetToHome -> {
                navBackStack.clear()
                navBackStack.add(AppRoute.Root())
                destination = RootDestination.Home
                detail = null
            }
            initialDetail != null -> {
                navBackStack.clear()
                navBackStack.add(AppRoute.Root())
                navBackStack.add(AppRoute.Detail(initialDetail))
                destination = RootDestination.Home
                detail = initialDetail
            }
            else -> {
                val rootIndices = navBackStack.indices.filter { navBackStack[it] is AppRoute.Root }
                if (rootIndices.size > 1) {
                    destination = (navBackStack[rootIndices.last()] as AppRoute.Root).destination
                    rootIndices.drop(1).asReversed().forEach(navBackStack::removeAt)
                    if ((navBackStack.firstOrNull() as? AppRoute.Root)?.destination != RootDestination.Home) {
                        navBackStack[0] = AppRoute.Root()
                    }
                }
            }
        }
    }
    var randomSongFilterActive by rememberSaveable { mutableStateOf(false) }
    var recommendationSelectedPage by rememberSaveable {
        mutableIntStateOf(RecommendationNewPage)
    }
    var recommendationSwitcherVisible by rememberSaveable { mutableStateOf(true) }
    var danSelectedPage by rememberSaveable { mutableIntStateOf(DanRegularPage) }
    var danSwitcherVisible by rememberSaveable { mutableStateOf(true) }
    var danHasTruePage by rememberSaveable { mutableStateOf(false) }
    var scoreQuerySearchExpanded by rememberSaveable { mutableStateOf(false) }
    var scoreQuerySearchVisible by rememberSaveable { mutableStateOf(true) }
    var scoreQuerySearchFocusRequestToken by rememberSaveable { mutableIntStateOf(0) }
    var restoreScoreQuerySearchFocus by rememberSaveable { mutableStateOf(false) }
    var showScoreQueryFilter by rememberSaveable { mutableStateOf(false) }
    var songReturnDetail by rememberSaveable { mutableStateOf<AppDetail?>(null) }
    var communityAliasesFromSong by rememberSaveable { mutableStateOf(false) }
    var communityAliasesSourceSongId by rememberSaveable { mutableStateOf<String?>(null) }
    var communityAliasesSourceReturnDetail by rememberSaveable { mutableStateOf<AppDetail?>(null) }
    val randomSongSessionState = remember { RandomSongSessionState() }
    val catalogSearchInteractionSource = remember { MutableInteractionSource() }
    val catalogSearchFocused by catalogSearchInteractionSource.collectIsFocusedAsState()
    val scoreQuerySearchInteractionSource = remember { MutableInteractionSource() }
    val scoreQuerySearchFocused by scoreQuerySearchInteractionSource.collectIsFocusedAsState()
    val focusManager = LocalFocusManager.current
    val softwareKeyboardController = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val catalogSearchShowThreshold = with(density) { 56.dp.toPx() }
    val catalogSearchHideThreshold = with(density) { 36.dp.toPx() }
    val catalogSearchScrollConnection = remember(
        catalogSearchShowThreshold,
        catalogSearchHideThreshold,
    ) {
        object : NestedScrollConnection {
            private var downwardDistance = 0f
            private var upwardDistance = 0f

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput) {
                    return super.onPreScroll(available, source)
                }

                when {
                    available.y > 0f -> {
                        upwardDistance = 0f
                        if (catalogSearchVisible) {
                            downwardDistance = 0f
                        } else {
                            downwardDistance += available.y
                            if (downwardDistance >= catalogSearchShowThreshold) {
                                catalogSearchVisible = true
                                downwardDistance = 0f
                            }
                        }
                    }
                    available.y < 0f -> {
                        downwardDistance = 0f
                        if (catalogSearchFocused || restoreCatalogSearchFocus) {
                            upwardDistance = 0f
                        } else if (catalogSearchVisible) {
                            upwardDistance -= available.y
                            if (upwardDistance >= catalogSearchHideThreshold) {
                                catalogSearchVisible = false
                                upwardDistance = 0f
                            }
                        } else {
                            upwardDistance = 0f
                        }
                    }
                }
                return super.onPreScroll(available, source)
            }
        }
    }
    val recommendationSwitcherScrollConnection = remember(
        catalogSearchShowThreshold,
        catalogSearchHideThreshold,
    ) {
        object : NestedScrollConnection {
            private var downwardDistance = 0f
            private var upwardDistance = 0f

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput) {
                    return super.onPreScroll(available, source)
                }

                when {
                    available.y > 0f -> {
                        upwardDistance = 0f
                        if (recommendationSwitcherVisible) {
                            downwardDistance = 0f
                        } else {
                            downwardDistance += available.y
                            if (downwardDistance >= catalogSearchShowThreshold) {
                                recommendationSwitcherVisible = true
                                downwardDistance = 0f
                            }
                        }
                    }
                    available.y < 0f -> {
                        downwardDistance = 0f
                        if (recommendationSwitcherVisible) {
                            upwardDistance -= available.y
                            if (upwardDistance >= catalogSearchHideThreshold) {
                                recommendationSwitcherVisible = false
                                upwardDistance = 0f
                            }
                        } else {
                            upwardDistance = 0f
                        }
                    }
                }
                return super.onPreScroll(available, source)
            }
        }
    }
    val danSwitcherScrollConnection = remember(
        catalogSearchShowThreshold,
        catalogSearchHideThreshold,
    ) {
        object : NestedScrollConnection {
            private var downwardDistance = 0f
            private var upwardDistance = 0f

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput) {
                    return super.onPreScroll(available, source)
                }

                when {
                    available.y > 0f -> {
                        upwardDistance = 0f
                        if (danSwitcherVisible) {
                            downwardDistance = 0f
                        } else {
                            downwardDistance += available.y
                            if (downwardDistance >= catalogSearchShowThreshold) {
                                danSwitcherVisible = true
                                downwardDistance = 0f
                            }
                        }
                    }
                    available.y < 0f -> {
                        downwardDistance = 0f
                        if (danSwitcherVisible) {
                            upwardDistance -= available.y
                            if (upwardDistance >= catalogSearchHideThreshold) {
                                danSwitcherVisible = false
                                upwardDistance = 0f
                            }
                        } else {
                            upwardDistance = 0f
                        }
                    }
                }
                return super.onPreScroll(available, source)
            }
        }
    }
    val scoreQuerySearchScrollConnection = remember(
        catalogSearchShowThreshold,
        catalogSearchHideThreshold,
        scoreQuerySearchFocused,
        restoreScoreQuerySearchFocus,
    ) {
        object : NestedScrollConnection {
            private var downwardDistance = 0f
            private var upwardDistance = 0f

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput) {
                    return super.onPreScroll(available, source)
                }

                when {
                    available.y > 0f -> {
                        upwardDistance = 0f
                        if (scoreQuerySearchVisible) {
                            downwardDistance = 0f
                        } else {
                            downwardDistance += available.y
                            if (downwardDistance >= catalogSearchShowThreshold) {
                                scoreQuerySearchVisible = true
                                downwardDistance = 0f
                            }
                        }
                    }
                    available.y < 0f -> {
                        downwardDistance = 0f
                        if (scoreQuerySearchFocused || restoreScoreQuerySearchFocus) {
                            upwardDistance = 0f
                        } else if (scoreQuerySearchVisible) {
                            upwardDistance -= available.y
                            if (upwardDistance >= catalogSearchHideThreshold) {
                                scoreQuerySearchVisible = false
                                upwardDistance = 0f
                            }
                        } else {
                            upwardDistance = 0f
                        }
                    }
                }
                return super.onPreScroll(available, source)
            }
        }
    }

    LaunchedEffect(catalogHideUnavailableSongs) {
        if (catalogFilterSettings.hideUnavailableSongs != catalogHideUnavailableSongs) {
            catalogFilterSettings = catalogFilterSettings.copy(
                hideUnavailableSongs = catalogHideUnavailableSongs,
            )
        }
    }
    LaunchedEffect(catalogShowPlayableSongsOnly) {
        if (catalogFilterSettings.showPlayableSongsOnly != catalogShowPlayableSongsOnly) {
            catalogFilterSettings = catalogFilterSettings.copy(
                showPlayableSongsOnly = catalogShowPlayableSongsOnly,
            )
        }
    }

    LaunchedEffect(destination, detail, restoreCatalogSearchFocus) {
        if (
            restoreCatalogSearchFocus &&
            destination == RootDestination.Catalog &&
            detail == null
        ) {
            catalogSearchVisible = true
            catalogSearchExpanded = true
            withFrameNanos { }
            catalogSearchFocusRequestToken++
        }
    }

    LaunchedEffect(
        catalogSearchFocused,
        destination,
        detail,
        restoreCatalogSearchFocus,
    ) {
        if (
            restoreCatalogSearchFocus &&
            catalogSearchFocused &&
            destination == RootDestination.Catalog &&
            detail == null
        ) {
            withFrameNanos { }
            softwareKeyboardController?.show()
            restoreCatalogSearchFocus = false
        }
    }

    LaunchedEffect(detail, restoreScoreQuerySearchFocus) {
        if (restoreScoreQuerySearchFocus && detail == AppDetail.ScoreQuery) {
            scoreQuerySearchVisible = true
            scoreQuerySearchExpanded = true
            withFrameNanos { }
            scoreQuerySearchFocusRequestToken++
        }
    }

    LaunchedEffect(scoreQuerySearchFocused, detail, restoreScoreQuerySearchFocus) {
        if (
            restoreScoreQuerySearchFocus &&
            scoreQuerySearchFocused &&
            detail == AppDetail.ScoreQuery
        ) {
            withFrameNanos { }
            softwareKeyboardController?.show()
            restoreScoreQuerySearchFocus = false
        }
    }

    val openDetail: (AppDetail) -> Unit = { next ->
        catalogCoverTransition = null
        if (next == AppDetail.CommunityAliases && detail != AppDetail.Song) {
            communityAliasesFromSong = false
            communityAliasesSourceSongId = null
            communityAliasesSourceReturnDetail = null
        }
        if (next == AppDetail.Recommendations) {
            recommendationSwitcherVisible = true
        }
        if (next == AppDetail.ScoreQuery) {
            scoreQuerySearchVisible = true
        }
        val route = AppRoute.Detail(
            detail = next,
            songId = selectedSongId.takeIf { next == AppDetail.Song },
            danCategoryId = selectedDanCategoryId.takeIf { next == AppDetail.DanDetail },
            collectionId = selectedCollectionId.takeIf { next == AppDetail.CollectionDetail },
        )
        if (navBackStack.lastOrNull() != route) {
            navBackStack.add(route)
        }
        detail = next
    }
    val pushSongRoute: (String) -> Unit = { songId ->
        val route = AppRoute.Song(songId)
        selectedSongId = songId
        if (navBackStack.lastOrNull() != route) navBackStack.add(route)
        detail = AppDetail.Song
    }
    fun openSongWithCover(source: SongCoverSource) {
        val bounds = source.bounds ?: return
        val painter = source.painter ?: return
        if (catalogSearchFocused) {
            restoreCatalogSearchFocus = true
            focusManager.clearFocus()
            softwareKeyboardController?.hide()
        }
        if (detail == AppDetail.ScoreQuery && scoreQuerySearchFocused) {
            restoreScoreQuerySearchFocus = true
            focusManager.clearFocus()
            softwareKeyboardController?.hide()
        }
        val current = navBackStack.lastOrNull()
        if (current is AppRoute.Song) return
        val transitionId = java.util.UUID.randomUUID().toString()
        val route = AppRoute.Song(source.songId, transitionId)
        selectedSongId = source.songId
        songReturnDetail = detail.takeIf {
            it == AppDetail.RandomSong ||
                it == AppDetail.BestTable ||
                it == AppDetail.Recommendations ||
                it == AppDetail.ScoreQuery ||
                it == AppDetail.ConstantTable ||
                it == AppDetail.PlateProgress ||
                it == AppDetail.DanDetail ||
                it == AppDetail.Collections ||
                it == AppDetail.CollectionDetail ||
                it == AppDetail.CommunityAliases
        }
        val transition = CatalogCoverTransition(
            songId = source.songId,
            source = bounds.translate(-navigationOrigin),
            coverPainter = painter,
            deviceCornerRadius = navigationCornerRadius,
            isReturning = { navBackStack.lastOrNull() != route },
            navigationOrigin = { navigationOrigin },
        ).also { it.genericSource = source }
        songCoverTransitions[transitionId] = transition
        catalogCoverTransition = transition
        if (navBackStack.lastOrNull() != route) navBackStack.add(route)
        detail = AppDetail.Song
    }
    val openSong: (String) -> Unit = { songId ->
        if (catalogSearchFocused) {
            restoreCatalogSearchFocus = true
            focusManager.clearFocus()
            softwareKeyboardController?.hide()
        }
        songReturnDetail = null
        catalogCoverTransition = null
        pushSongRoute(songId)
    }
    val openSongFromDetail: (String) -> Unit = { songId ->
        if (detail == AppDetail.ScoreQuery && scoreQuerySearchFocused) {
            restoreScoreQuerySearchFocus = true
            focusManager.clearFocus()
            softwareKeyboardController?.hide()
        }
        songReturnDetail = detail.takeIf {
            it == AppDetail.RandomSong ||
                it == AppDetail.BestTable ||
                it == AppDetail.Recommendations ||
                it == AppDetail.ScoreQuery ||
                it == AppDetail.ConstantTable ||
                it == AppDetail.PlateProgress ||
                it == AppDetail.DanDetail ||
                it == AppDetail.Collections ||
                it == AppDetail.CollectionDetail ||
                it == AppDetail.CommunityAliases
        }
        catalogCoverTransition = null
        pushSongRoute(songId)
    }
    val openCollection: (String) -> Unit = { collectionId ->
        selectedCollectionId = collectionId
        openDetail(AppDetail.CollectionDetail)
    }
    val openCommunityAliasesFromSong: () -> Unit = {
        communityAliasesFromSong = true
        communityAliasesSourceSongId = selectedSongId
        communityAliasesSourceReturnDetail = songReturnDetail
        openDetail(AppDetail.CommunityAliases)
    }
    val rootPage: @Composable (RootDestination, Dp) -> Unit = { page, contentTopPadding ->
        when (page) {
            RootDestination.Home -> HomeScreen(
                state = uiState,
                contentTopPadding = contentTopPadding,
                onOpenDetail = openDetail,
                onEditProfile = { showHomeProfileEditor = true },
            )
            RootDestination.Scanner -> ScannerScreen(
                container = container,
                songs = uiState.songs,
                sheets = uiState.sheets,
                aliases = uiState.songAliases,
                scores = uiState.scores,
                server = uiState.activeProfile?.server ?: "jp",
                showBoundingBoxes = showScannerBoundingBoxes,
                contentTopPadding = contentTopPadding,
                enabled = destination == RootDestination.Scanner && detail == null,
                onOpenSong = openSong,
            )
            RootDestination.Catalog -> CatalogScreen(
                songs = uiState.songs,
                sheets = uiState.sheets,
                scores = uiState.scores,
                songCategories = uiState.songCategories,
                gameVersions = uiState.gameVersions,
                coverImageStore = container.coverImageStore,
                displayMode = catalogDisplayMode,
                sortOption = catalogSortOption,
                sortAscending = catalogSortAscending,
                filterSettings = catalogFilterSettings,
                query = catalogQuery,
                gridColumns = catalogGridColumns,
                songAliases = uiState.songAliases,
                server = uiState.activeProfile?.server ?: "jp",
                contentTopPadding = contentTopPadding,
                showFilterDialog = showCatalogFilter,
                onFilterSettingsChange = { settings ->
                    if (settings.hideUnavailableSongs != catalogFilterSettings.hideUnavailableSongs) {
                        viewModel.setCatalogHideUnavailableSongs(settings.hideUnavailableSongs)
                    }
                    if (settings.showPlayableSongsOnly != catalogFilterSettings.showPlayableSongsOnly) {
                        viewModel.setCatalogShowPlayableSongsOnly(settings.showPlayableSongsOnly)
                    }
                    catalogFilterSettings = settings
                },
                onGridColumnsChange = viewModel::setCatalogGridColumns,
                onDismissFilter = { showCatalogFilter = false },
                onOpenSong = openSong,
            )
            RootDestination.Settings -> SettingsScreen(
                backendApiClient = container.backendApiClient,
                contentTopPadding = contentTopPadding,
                showScannerBoundingBoxes = showScannerBoundingBoxes,
                onShowScannerBoundingBoxesChange = viewModel::setShowScannerBoundingBoxes,
                thirdPartyScoreSyncEnabled = thirdPartyScoreSyncEnabled,
                canSyncThirdPartyScores = uiState.activeProfile?.server
                    ?.equals("cn", ignoreCase = true) == true,
                canImportOtogame = uiState.activeProfile?.server
                    ?.equals("jp", ignoreCase = true) == true,
                onThirdPartyScoreSyncEnabledChange = viewModel::setThirdPartyScoreSyncEnabled,
                onOpenDetail = openDetail,
                onSendLogs = onSendLogs,
                onCheckUpdates = { appUpdateViewModel.check() },
            )
        }
    }

    val selectedSong = selectedSongId?.let { id -> uiState.songs.firstOrNull { it.songIdentifier == id } }
    val selectedCollectionTitle = selectedCollectionId
        ?.let { id -> collections.firstOrNull { it.id == id }?.name }
    val closeDetail: () -> Unit = {
        if (navBackStack.size > 1) {
            val currentDetail = detail
            if (currentDetail == AppDetail.RandomSong) {
                randomSongFilterActive = false
                randomSongFilterRequested = false
            }
            if (currentDetail == AppDetail.CommunityAliases) {
                communityAliasesFromSong = false
                communityAliasesSourceSongId = null
                communityAliasesSourceReturnDetail = null
            }
            if (currentDetail == AppDetail.CollectionDetail) {
                selectedCollectionId = null
                collectionsRenameRequested = false
            }
            navBackStack.removeLastOrNull()
            val previous = navBackStack.lastOrNull()
            if (previous is AppRoute.Detail) {
                detail = previous.detail
                selectedSongId = previous.songId
                selectedDanCategoryId = previous.danCategoryId
                selectedCollectionId = previous.collectionId
            } else if (previous is AppRoute.Song) {
                detail = AppDetail.Song
                selectedSongId = previous.songId
                selectedDanCategoryId = null
                selectedCollectionId = null
            } else {
                detail = null
                selectedSongId = null
                selectedDanCategoryId = null
                selectedCollectionId = null
            }
        }
    }
    val detailNavigationIcon: @Composable () -> Unit = {
        if (detail != null) {
            IconButton(onClick = {
                closeDetail()
            }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
        }
    }
    val detailActionsForRoute: @Composable RowScope.(AppDetail, String?) -> Unit = { activeDetail, songId ->
        val actionSong = songId?.let { id -> uiState.songs.firstOrNull { it.songIdentifier == id } }
        if (activeDetail == AppDetail.Song && actionSong != null) {
            IconButton(onClick = {
                viewModel.setSongFavorite(actionSong.songIdentifier, !actionSong.isFavorite)
            }) {
                Icon(
                    imageVector = if (actionSong.isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = stringResource(
                        if (actionSong.isFavorite) R.string.song_remove_favorite else R.string.song_add_favorite,
                    ),
                    tint = if (actionSong.isFavorite) Color(0xFFE85D5D) else MiuixTheme.colorScheme.onSurface,
                )
            }
        } else if (activeDetail == AppDetail.Collections) {
            var menuExpanded by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menuExpanded = !menuExpanded }) {
                    Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.collections_add))
                }
                WindowListPopup(
                    show = menuExpanded,
                    alignment = PopupPositionProvider.Align.End,
                    enableWindowDim = true,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    ListPopupColumn {
                        DropdownImpl(
                            text = stringResource(R.string.collections_import_clipboard),
                            optionSize = 2,
                            isSelected = false,
                            index = 0,
                            onSelectedIndexChange = {
                                menuExpanded = false
                                collectionsImportRequested = true
                            },
                        )
                        DropdownImpl(
                            text = stringResource(R.string.collections_new),
                            optionSize = 2,
                            isSelected = false,
                            index = 1,
                            onSelectedIndexChange = {
                                menuExpanded = false
                                collectionsCreateRequested = true
                            },
                        )
                    }
                }
            }
        } else if (activeDetail == AppDetail.CollectionDetail) {
            IconButton(onClick = {
                collectionsDisplayMode = if (collectionsDisplayMode == CatalogDisplayMode.Grid) {
                    CatalogDisplayMode.List
                } else {
                    CatalogDisplayMode.Grid
                }
            }) {
                Icon(
                    if (collectionsDisplayMode == CatalogDisplayMode.Grid) Icons.AutoMirrored.Rounded.List else Icons.Rounded.GridView,
                    contentDescription = stringResource(R.string.collections_display_mode),
                )
            }
            CatalogSortTopBarAction(
                sortOption = collectionsSortOption,
                sortAscending = collectionsSortAscending,
                onSortOptionChange = { collectionsSortOption = it },
                onSortAscendingChange = { collectionsSortAscending = it },
            )
            IconButton(onClick = { collectionsRenameRequested = true }) {
                Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.collections_rename))
            }
        } else if (activeDetail == AppDetail.Profiles) {
            IconButton(onClick = { profileCreateRequested = true }) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = stringResource(R.string.profile_create),
                )
            }
        } else if (activeDetail == AppDetail.BestTable) {
            BestTableConstantTopBarAction(container)
            IconButton(onClick = { bestTableExportRequested = true }) {
                Icon(
                    imageVector = Icons.Rounded.IosShare,
                    contentDescription = stringResource(R.string.best50_export),
                )
            }
        } else if (activeDetail == AppDetail.RandomSong) {
            IconButton(onClick = { randomSongFilterRequested = true }) {
                Icon(
                    imageVector = Icons.Rounded.FilterList,
                    contentDescription = stringResource(R.string.catalog_filter_title),
                    tint = if (randomSongFilterActive) {
                        MiuixTheme.colorScheme.primary
                    } else {
                        MiuixTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }
    @Composable
    fun DetailEntry(route: AppRoute.Detail) {
        val activeDetail = route.detail
        val backgroundColor = MiuixTheme.colorScheme.surface
        val routeSongId = route.songId ?: selectedSongId
        val routeDanCategoryId = route.danCategoryId ?: selectedDanCategoryId
        val routeCollectionId = route.collectionId ?: selectedCollectionId
        val routeCollectionTitle = routeCollectionId
            ?.let { id -> collections.firstOrNull { it.id == id }?.name }
        val routeSong = routeSongId?.let { id -> uiState.songs.firstOrNull { it.songIdentifier == id } }
        val detailScrollBehavior = when (activeDetail) {
            AppDetail.CommunityAliases -> communityAliasScrollBehavior
            AppDetail.ConstantTable -> constantTableScrollBehavior
            else -> MiuixScrollBehavior()
        }
        val scoreQueryViewModel = if (activeDetail == AppDetail.ScoreQuery) {
            composeViewModel<ScoreQueryViewModel>(factory = ScoreQueryViewModel.Factory(container))
        } else {
            null
        }
        val scoreQueryState = scoreQueryViewModel?.state?.collectAsStateWithLifecycle()?.value
        val scoreQueryText = scoreQueryViewModel?.query?.collectAsStateWithLifecycle()?.value.orEmpty()
        val pageBackdrop = rememberLayerBackdrop()
        val pageBackground = if (activeDetail == AppDetail.Song) {
            songDetailBackground?.takeIf { it.first == routeSongId }?.second ?: backgroundColor
        } else {
            backgroundColor
        }
        val detailTitleText = when (activeDetail) {
            AppDetail.CollectionDetail -> routeCollectionTitle ?: detailTitle(AppDetail.Collections)
            AppDetail.DanDetail -> selectedDanCategoryTitle ?: detailTitle(AppDetail.Dan)
            else -> detailTitle(activeDetail)
        }
        val needsScrollConnection = activeDetail in setOf(
            AppDetail.BestTable,
            AppDetail.Recommendations,
            AppDetail.ScoreQuery,
            AppDetail.ConstantTable,
            AppDetail.PlateProgress,
            AppDetail.Dan,
            AppDetail.DanDetail,
            AppDetail.CommunityAliases,
            AppDetail.Collections,
            AppDetail.CollectionDetail,
            AppDetail.DivingFishImport,
            AppDetail.LxnsImport,
            AppDetail.OtogameImport,
            AppDetail.Appearance,
        )
        val detailModifier = Modifier.fillMaxSize().then(
            if (needsScrollConnection) {
                Modifier.nestedScroll(detailScrollBehavior.nestedScrollConnection)
                    .then(
                        when (activeDetail) {
                            AppDetail.Recommendations -> Modifier.nestedScroll(recommendationSwitcherScrollConnection)
                            AppDetail.ScoreQuery -> Modifier.nestedScroll(scoreQuerySearchScrollConnection)
                            else -> Modifier
                        },
                    )
            } else {
                Modifier
            },
        )

        CatalogContainerTransform(
            state = catalogCoverTransition.takeIf { activeDetail == AppDetail.Song },
            background = pageBackground,
        ) {
        Scaffold(
            modifier = detailModifier,
            containerColor = Color.Transparent,
            topBar = {
                when (activeDetail) {
                    AppDetail.Song -> SmallTopAppBar(
                        title = songDetailTitle?.takeIf { it.first == routeSongId }?.second
                            ?: routeSong?.title.orEmpty(),
                        modifier = Modifier.drawPlainBackdrop(
                            backdrop = pageBackdrop,
                            shape = { TopBarBottomShape },
                            effects = { blur(24.dp.toPx()) },
                        ).clip(TopBarBottomShape),
                        color = Color.Transparent,
                        navigationIcon = detailNavigationIcon,
                        actions = { detailActionsForRoute(activeDetail, routeSongId) },
                        defaultWindowInsetsPadding = true,
                    )
                    AppDetail.StaticData, AppDetail.OtogameLogin, AppDetail.ConstantTable -> SmallTopAppBar(
                        title = detailTitleText,
                        modifier = Modifier.drawPlainBackdrop(
                            backdrop = pageBackdrop,
                            shape = { TopBarBottomShape },
                            effects = { if (enableBlur) blur(24.dp.toPx()) },
                            onDrawSurface = { drawRect(backgroundColor.copy(alpha = if (enableBlur) 0.52f else 1f)) },
                        ).clip(TopBarBottomShape),
                        color = Color.Transparent,
                        navigationIcon = detailNavigationIcon,
                        actions = {
                            if (activeDetail == AppDetail.ConstantTable) {
                                IconButton(onClick = { showConstantTableFilter = true }) {
                                    Icon(Icons.Rounded.FilterList, contentDescription = stringResource(R.string.catalog_filter_title))
                                }
                            } else {
                                detailActionsForRoute(activeDetail, routeSongId)
                            }
                        },
                        defaultWindowInsetsPadding = true,
                    )
                    else -> TopAppBar(
                        title = detailTitleText,
                        largeTitle = detailTitleText,
                        modifier = Modifier.drawPlainBackdrop(
                            backdrop = pageBackdrop,
                            shape = { TopBarBottomShape },
                            effects = { if (enableBlur) blur(24.dp.toPx()) },
                            onDrawSurface = { drawRect(backgroundColor.copy(alpha = if (enableBlur) 0.52f else 1f)) },
                        ).clip(TopBarBottomShape),
                        color = Color.Transparent,
                        navigationIcon = detailNavigationIcon,
                        actions = {
                            when {
                                activeDetail == AppDetail.ScoreQuery && scoreQueryState != null -> ScoreQueryTopBarActions(
                                    displayMode = scoreQueryState.displayMode,
                                    sortMode = scoreQueryState.sortMode,
                                    sortAscending = scoreQueryState.sortAscending,
                                    filterActive = !scoreQueryState.filterSettings.isEmpty,
                                    onDisplayModeChange = { mode -> scoreQueryViewModel?.setDisplayMode(mode) },
                                    onSortModeChange = { mode -> scoreQueryViewModel?.setSortMode(mode) },
                                    onSortAscendingChange = { ascending -> scoreQueryViewModel?.setSortAscending(ascending) },
                                    onShowFilter = { showScoreQueryFilter = true },
                                )
                                else -> detailActionsForRoute(activeDetail, routeSongId)
                            }
                        },
                        scrollBehavior = detailScrollBehavior,
                        defaultWindowInsetsPadding = true,
                        bottomContent = {
                            when (activeDetail) {
                                AppDetail.Recommendations -> RecommendationPageSwitcher(
                                    selectedPage = recommendationSelectedPage,
                                    visible = recommendationSwitcherVisible,
                                    onSelectedPageChange = { recommendationSelectedPage = it },
                                )
                                AppDetail.DanDetail -> if (danHasTruePage) {
                                    DanPageSwitcher(
                                        selectedPage = danSelectedPage,
                                        visible = danSwitcherVisible,
                                        onSelectedPageChange = { danSelectedPage = it },
                                    )
                                }
                                AppDetail.ScoreQuery -> if (scoreQueryViewModel != null) {
                                    CatalogSearchBar(
                                        query = scoreQueryText,
                                        onQueryChange = scoreQueryViewModel::setQuery,
                                        expanded = scoreQuerySearchExpanded,
                                        onExpandedChange = { scoreQuerySearchExpanded = it },
                                        visible = scoreQuerySearchVisible || scoreQuerySearchFocused || restoreScoreQuerySearchFocus,
                                        focusRequestToken = scoreQuerySearchFocusRequestToken,
                                        backEnabled = detail == AppDetail.ScoreQuery && scoreQuerySearchFocused,
                                        interactionSource = scoreQuerySearchInteractionSource,
                                        labelResource = R.string.score_query_search_hint,
                                    )
                                }
                                else -> Unit
                            }
                        },
                    )
                }
            },
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
        ) { paddingValues ->
            Box(
                modifier = Modifier.fillMaxSize().then(
                    when (activeDetail) {
                        AppDetail.StaticData, AppDetail.Collections, AppDetail.CollectionDetail ->
                            Modifier.padding(paddingValues).layerBackdrop(pageBackdrop)
                        else -> Modifier.layerBackdrop(pageBackdrop).background(pageBackground)
                    },
                ),
            ) {
                DetailScreen(
                    detail = activeDetail,
                    state = uiState,
                    selectedSongId = routeSongId,
                    selectedDanCategoryId = routeDanCategoryId,
                    container = container,
                    songContentTopPadding = paddingValues.calculateTopPadding(),
                    communityAliasListState = communityAliasListState,
                    constantTableListState = constantTableListState,
                    recommendationSelectedPage = recommendationSelectedPage,
                    danSelectedPage = danSelectedPage,
                    scoreQueryViewModel = scoreQueryViewModel,
                    showScoreQueryFilter = showScoreQueryFilter,
                    showConstantTableFilter = showConstantTableFilter,
                    profileCreateRequested = profileCreateRequested,
                    bestTableExportRequested = bestTableExportRequested,
                    randomSongFilterRequested = randomSongFilterRequested,
                    randomSongSessionState = randomSongSessionState,
                    onProfileCreateRequestHandled = { profileCreateRequested = false },
                    onBestTableExportRequestHandled = { bestTableExportRequested = false },
                    onRandomSongFilterRequestHandled = { randomSongFilterRequested = false },
                    onRandomSongFilterActiveChanged = { randomSongFilterActive = it },
                    onDismissScoreQueryFilter = { showScoreQueryFilter = false },
                    onDismissConstantTableFilter = { showConstantTableFilter = false },
                    onOpenSong = openSongFromDetail,
                    onOpenDanCategory = { categoryId, categoryTitle ->
                        selectedDanCategoryId = categoryId
                        selectedDanCategoryTitle = categoryTitle
                        danSelectedPage = DanRegularPage
                        danSwitcherVisible = true
                        danHasTruePage = false
                        openDetail(AppDetail.DanDetail)
                    },
                    onOpenCommunityAliases = openCommunityAliasesFromSong,
                    onOpenOtogameLogin = { openDetail(AppDetail.OtogameLogin) },
                    onSongDetailBackgroundChanged = { color ->
                        if (activeDetail == AppDetail.Song && routeSongId != null) {
                            songDetailBackground = color?.let { routeSongId to it }
                        }
                    },
                    onSongDetailTitleChanged = { title ->
                        if (activeDetail == AppDetail.Song && routeSongId != null) {
                            songDetailTitle = routeSongId to title
                        }
                    },
                    themeSettings = themeSettings,
                    onColorModeChange = viewModel::setColorMode,
                    onKeyColorChange = viewModel::setKeyColor,
                    onPaletteStyleChange = viewModel::setPaletteStyle,
                    onColorSpecChange = viewModel::setColorSpec,
                    onEnableBlurChange = viewModel::setEnableBlur,
                    onEnableFloatingBottomBarChange = viewModel::setEnableFloatingBottomBar,
                    onEnableFloatingBottomBarBlurChange = viewModel::setEnableFloatingBottomBarBlur,
                    onEnablePredictiveBackChange = viewModel::setEnablePredictiveBack,
                    onPageScaleChange = viewModel::setPageScale,
                    collectionsDisplayMode = collectionsDisplayMode,
                    collectionsSortOption = collectionsSortOption,
                    collectionsSortAscending = collectionsSortAscending,
                    selectedCollectionId = routeCollectionId,
                    collectionsRenameRequested = collectionsRenameRequested,
                    onSelectedCollectionIdChange = { id -> id?.let(openCollection) },
                    collectionsCreateRequested = collectionsCreateRequested,
                    collectionsImportRequested = collectionsImportRequested,
                    onCollectionsCreateRequestHandled = { collectionsCreateRequested = false },
                    onCollectionsImportRequestHandled = { collectionsImportRequested = false },
                    onCollectionsRenameRequestHandled = { collectionsRenameRequested = false },
                    coverTransition = catalogCoverTransition.takeIf { activeDetail == AppDetail.Song },
                )
            }
        }
        }
    }
    val rootActions: @Composable RowScope.(RootDestination) -> Unit = { page ->
        if (page == RootDestination.Catalog) {
            CatalogTopBarActions(
                displayMode = catalogDisplayMode,
                sortOption = catalogSortOption,
                sortAscending = catalogSortAscending,
                filterActive = catalogFilterSettings.hasTransientFilters,
                onDisplayModeChange = { catalogDisplayMode = it },
                onSortOptionChange = viewModel::setCatalogSortOption,
                onSortAscendingChange = viewModel::setCatalogSortAscending,
                onShowFilter = { showCatalogFilter = true },
            )
        }
    }
    val rootBottomContent: @Composable (RootDestination) -> Unit = { page ->
        if (page == RootDestination.Catalog) {
            CatalogSearchBar(
                query = catalogQuery,
                onQueryChange = { catalogQuery = it },
                expanded = catalogSearchExpanded,
                onExpandedChange = { catalogSearchExpanded = it },
                visible = catalogSearchVisible || catalogSearchFocused || restoreCatalogSearchFocus,
                focusRequestToken = catalogSearchFocusRequestToken,
                backEnabled = destination == RootDestination.Catalog &&
                    detail == null &&
                    catalogSearchFocused,
                interactionSource = catalogSearchInteractionSource,
            )
        }
    }

    // KernelSU's Miuix scaffolds use the surface role as the page canvas:
    // #F7F7F7 in light mode and #000000 in dark mode. Cards remain on the
    // surfaceContainer role, preserving the intended contrast.
    val backgroundColor = MiuixTheme.colorScheme.surface
    val navigationBackdrop = rememberLayerBackdrop {
        drawRect(backgroundColor)
        drawContent()
    }
    val predictiveBackEnabled = LocalEnablePredictiveBack.current
    val rootBackEnabled = navBackStack.size == 1 && detail == null && destination != RootDestination.Home
    fun returnToHomeWithoutSecondTransition() {
        animateRootTransition = false
        destination = RootDestination.Home
    }
    if (predictiveBackEnabled) {
        PredictiveBackHandler(enabled = rootBackEnabled) { progress: Flow<BackEventCompat> ->
            try {
                progress.collect { event ->
                    rootBackProgress.snapTo(event.progress.coerceIn(0f, 1f))
                }
                rootBackProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(180, easing = FastOutSlowInEasing),
                )
                returnToHomeWithoutSecondTransition()
                rootBackProgress.snapTo(0f)
                withFrameNanos { }
                animateRootTransition = true
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    rootBackProgress.animateTo(
                        targetValue = 0f,
                        animationSpec = spring(dampingRatio = 1f, stiffness = 146f),
                    )
                }
                throw cancelled
            }
        }
    } else {
        BackHandler(enabled = navBackStack.size > 1 || rootBackEnabled) {
            if (navBackStack.size > 1) {
                closeDetail()
            } else {
                destination = RootDestination.Home
            }
        }
    }
    val songNavigationHost = remember(catalogCoverTransition) {
        SongNavigationHost(
            active = catalogCoverTransition,
            openCover = ::openSongWithCover,
        )
    }
    CompositionLocalProvider(LocalSongNavigationHost provides songNavigationHost) {
    NavigationEventGate(LocalEnablePredictiveBack.current) {
        NavDisplay(
            backStack = navBackStack,
            modifier = Modifier
                .fillMaxSize()
                .zIndex(2f)
                .onGloballyPositioned { navigationOrigin = it.positionInRoot() },
            transition = NavTransitions.MiuixDefault,
            effects = NavDisplayEffects(cornerClipRadius = navigationCornerRadius),
            onBack = {
                if (navBackStack.size > 1) {
                    closeDetail()
                } else if (destination != RootDestination.Home) {
                    animateRootTransition = true
                    destination = RootDestination.Home
                }
            },
        ) {
            entry<AppRoute.Root>(
                transition = SettingsDetailTransition,
                swipeDismiss = NavSwipeDirection.None,
            ) {
                val rootBackdrop = rememberLayerBackdrop {
                    drawRect(backgroundColor)
                    drawContent()
                }
                Box(Modifier.fillMaxSize().background(backgroundColor)) {
                    if (destination != RootDestination.Home && navBackStack.size == 1) {
                        RootLayer(
                            destination = RootDestination.Home,
                            modifier = Modifier.fillMaxSize().graphicsLayer {
                                translationX = -size.width * (1f - rootBackProgress.value)
                            },
                            content = rootPage,
                        )
                    }
                    AnimatedContent(
                        targetState = destination,
                        modifier = Modifier.fillMaxSize().graphicsLayer {
                            translationX = size.width * rootBackProgress.value
                        },
                        transitionSpec = {
                            if (!animateRootTransition) {
                                EnterTransition.None togetherWith ExitTransition.None
                            } else {
                                val animationSpec = tween<IntOffset>(
                                    durationMillis = 360,
                                    easing = FastOutSlowInEasing,
                                )
                                if (targetState.ordinal > initialState.ordinal) {
                                    slideInHorizontally(animationSpec) { it } togetherWith
                                        slideOutHorizontally(animationSpec) { -it }
                                } else {
                                    slideInHorizontally(animationSpec) { -it } togetherWith
                                        slideOutHorizontally(animationSpec) { it }
                                }
                            }
                        },
                        label = "root-page-transition",
                    ) { activeDestination ->
                        RootLayer(
                            destination = activeDestination,
                            modifier = Modifier.fillMaxSize().layerBackdrop(rootBackdrop),
                            showTopBar = activeDestination != RootDestination.Scanner,
                            actions = { rootActions(activeDestination) },
                            bottomContent = { rootBottomContent(activeDestination) },
                            scrollObserver = catalogSearchScrollConnection.takeIf {
                                activeDestination == RootDestination.Catalog
                            },
                            content = rootPage,
                        )
                    }
                    AppNavigationBar(
                        destination = destination,
                        backdrop = navigationBackdrop,
                        onDestinationSelected = { nextDestination ->
                            if (nextDestination != destination) {
                                animateRootTransition = true
                                destination = nextDestination
                            }
                        },
                        modifier = Modifier
                            .align(androidx.compose.ui.Alignment.BottomCenter)
                            .then(
                                if (LocalEnableFloatingBottomBar.current) {
                                    Modifier.navigationBarsPadding().padding(bottom = 8.dp)
                                } else {
                                    Modifier
                                },
                            ),
                    )
                }
            }
            entry<AppRoute.Detail>(
                transition = SettingsDetailTransition,
                swipeDismiss = if (predictiveBackEnabled) {
                    NavSwipeDirection.LeftToRight
                } else {
                    NavSwipeDirection.None
                },
            ) { route ->
                LaunchedEffect(route) {
                    detail = route.detail
                    selectedSongId = route.songId
                    selectedDanCategoryId = route.danCategoryId
                    selectedCollectionId = route.collectionId
                }
                DetailEntry(route)
            }
            entry<AppRoute.Song>(
                transition = catalogCoverTransition?.transition ?: SettingsDetailTransition,
                swipeDismiss = if (predictiveBackEnabled) {
                    NavSwipeDirection.LeftToRight
                } else {
                    NavSwipeDirection.None
                },
            ) { route ->
                val routeTransition = route.transitionId?.let(songCoverTransitions::get)
                DisposableEffect(route, routeTransition) {
                    onDispose {
                        if (route !in navBackStack) {
                            route.transitionId?.let(songCoverTransitions::remove)
                            if (catalogCoverTransition === routeTransition) {
                                catalogCoverTransition = navBackStack.asReversed()
                                    .filterIsInstance<AppRoute.Song>()
                                    .firstNotNullOfOrNull { it.transitionId?.let(songCoverTransitions::get) }
                            }
                        }
                    }
                }
                LaunchedEffect(route) {
                    detail = AppDetail.Song
                    selectedSongId = route.songId
                    selectedDanCategoryId = null
                    selectedCollectionId = null
                }
                DetailEntry(AppRoute.Detail(AppDetail.Song, songId = route.songId))
            }
        }
    }
    }
        org.rhythmeta.maimaid.ui.update.AppUpdateHost(
            viewModel = appUpdateViewModel,
            blocked = detail != null || destination == RootDestination.Scanner || showHomeProfileEditor ||
                showCatalogFilter || showConstantTableFilter || showScoreQueryFilter ||
                pendingCollectionImport != null,
        )

        ProfileEditorSheet(
            visible = showHomeProfileEditor,
            profile = uiState.activeProfile,
            container = container,
            onDismiss = { showHomeProfileEditor = false },
        )

        pendingCollectionImport?.let { collection ->
            WindowDialog(
                show = true,
                title = stringResource(R.string.collections_import_prompt_title),
                summary = stringResource(R.string.collections_import_prompt_summary, collection.name),
                onDismissRequest = onCollectionImportDismissed,
            ) {
                TextButton(
                    text = stringResource(R.string.collections_import_cancel),
                    onClick = onCollectionImportDismissed,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { onCollectionImportConfirmed(collection) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(stringResource(R.string.collections_import_confirm))
                }
            }
        }
    }

@Composable
private fun RootLayer(
    destination: RootDestination,
    modifier: Modifier = Modifier,
    showTopBar: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    bottomContent: @Composable () -> Unit = {},
    scrollObserver: NestedScrollConnection? = null,
    content: @Composable (RootDestination, Dp) -> Unit,
) {
    val title = rootTitle(destination)
    val enableBlur = LocalEnableBlur.current
    val scrollBehavior = MiuixScrollBehavior()
    val backgroundColor = MiuixTheme.colorScheme.surface
    val pageBackdrop = rememberLayerBackdrop()
    val rootScrollConnection = remember(scrollBehavior, scrollObserver) {
        val topBarConnection = scrollBehavior.nestedScrollConnection
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                scrollObserver?.onPreScroll(available, source)
                return topBarConnection.onPreScroll(available, source)
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                scrollObserver?.onPostScroll(consumed, available, source)
                return topBarConnection.onPostScroll(consumed, available, source)
            }

            override suspend fun onPreFling(available: Velocity): Velocity =
                topBarConnection.onPreFling(available)

            override suspend fun onPostFling(
                consumed: Velocity,
                available: Velocity,
            ): Velocity = topBarConnection.onPostFling(consumed, available)
        }
    }
    Box(modifier = modifier) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(rootScrollConnection),
            containerColor = Color.Transparent,
            topBar = {
                if (showTopBar) {
                    TopAppBar(
                        title = title,
                        largeTitle = title,
                        modifier = Modifier.drawPlainBackdrop(
                            backdrop = pageBackdrop,
                            shape = { TopBarBottomShape },
                            effects = { if (enableBlur) blur(24.dp.toPx()) },
                            onDrawSurface = {
                                drawRect(backgroundColor.copy(alpha = if (enableBlur) 0.52f else 1f))
                            },
                        ).clip(TopBarBottomShape),
                        color = Color.Transparent,
                        actions = actions,
                        scrollBehavior = scrollBehavior,
                        defaultWindowInsetsPadding = true,
                        bottomContent = bottomContent,
                    )
                }
            },
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(pageBackdrop)
                    .background(backgroundColor),
            ) {
                content(destination, paddingValues.calculateTopPadding())
            }
        }
    }
}

@Composable
private fun rememberDeviceCornerRadius(): Dp {
    val view = LocalView.current
    val density = LocalDensity.current
    val orientation = LocalConfiguration.current.orientation
    val radiusPixels = remember(view, orientation) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val insets = view.rootWindowInsets
            listOf(
                RoundedCorner.POSITION_TOP_LEFT,
                RoundedCorner.POSITION_TOP_RIGHT,
                RoundedCorner.POSITION_BOTTOM_RIGHT,
                RoundedCorner.POSITION_BOTTOM_LEFT,
            ).maxOfOrNull { position -> insets?.getRoundedCorner(position)?.radius ?: 0 } ?: 0
        } else {
            0
        }
    }
    return if (radiusPixels > 0) {
        with(density) { radiusPixels.toDp() }
    } else {
        28.dp
    }
}

@Composable
private fun AppNavigationBar(
    destination: RootDestination,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    onDestinationSelected: (RootDestination) -> Unit,
) {
    val destinations = RootDestination.entries
    val tabs = listOf(
        LiquidGlassTab(Icons.Rounded.Home, stringResource(R.string.nav_home)),
        LiquidGlassTab(Icons.Rounded.DocumentScanner, stringResource(R.string.nav_scan)),
        LiquidGlassTab(Icons.Rounded.Search, stringResource(R.string.nav_library)),
        LiquidGlassTab(Icons.Rounded.Settings, stringResource(R.string.nav_settings)),
    )
    if (LocalEnableFloatingBottomBar.current) {
        LiquidGlassTabBar(
            selectedIndex = destinations.indexOf(destination),
            onSelected = { index -> onDestinationSelected(destinations[index]) },
            backdrop = backdrop,
            tabs = tabs,
            isBlurEnabled = LocalEnableFloatingBottomBarBlur.current,
            modifier = modifier,
        )
    } else {
        NavigationBar(modifier = modifier, color = MiuixTheme.colorScheme.surface) {
            tabs.forEachIndexed { index, tab ->
                NavigationBarItem(
                    modifier = Modifier.weight(1f),
                    icon = tab.icon,
                    label = tab.label,
                    selected = index == destinations.indexOf(destination),
                    onClick = { onDestinationSelected(destinations[index]) },
                )
            }
        }
    }
}

@Composable
private fun rootTitle(destination: RootDestination): String = when (destination) {
    RootDestination.Home -> stringResource(R.string.nav_home)
    RootDestination.Scanner -> stringResource(R.string.nav_scan)
    RootDestination.Catalog -> stringResource(R.string.nav_library)
    RootDestination.Settings -> stringResource(R.string.nav_settings)
}

@Composable
private fun detailTitle(detail: AppDetail): String = when (detail) {
    AppDetail.BestTable -> stringResource(R.string.detail_best_table)
    AppDetail.RandomSong -> stringResource(R.string.detail_random_song)
    AppDetail.Recommendations -> stringResource(R.string.detail_recommendations)
    AppDetail.ScoreQuery -> stringResource(R.string.detail_score_query)
    AppDetail.ConstantTable -> stringResource(R.string.detail_constant_table)
    AppDetail.PlateProgress -> stringResource(R.string.detail_plate_progress)
    AppDetail.Dan -> stringResource(R.string.detail_dan)
    AppDetail.DanDetail -> stringResource(R.string.detail_dan)
    AppDetail.CommunityAliases -> stringResource(R.string.detail_community_aliases)
    AppDetail.StaticData -> stringResource(R.string.detail_static_data)
    AppDetail.Profiles -> stringResource(R.string.detail_profiles)
    AppDetail.BackendAuth -> stringResource(R.string.detail_cloud_account)
    AppDetail.DivingFishImport -> stringResource(R.string.detail_diving_fish)
    AppDetail.LxnsImport -> stringResource(R.string.detail_lxns)
    AppDetail.OtogameImport -> stringResource(R.string.detail_otogame)
    AppDetail.OtogameLogin -> stringResource(R.string.detail_otogame_login)
    AppDetail.Appearance -> stringResource(R.string.detail_appearance)
    AppDetail.About -> stringResource(R.string.detail_about)
    AppDetail.Song -> ""
    AppDetail.Collections -> stringResource(R.string.settings_collections)
    AppDetail.CollectionDetail -> stringResource(R.string.settings_collections)
}
