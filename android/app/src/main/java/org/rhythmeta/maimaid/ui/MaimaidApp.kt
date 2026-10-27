package org.rhythmeta.maimaid.ui

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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
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
import org.rhythmeta.maimaid.ui.components.AppPageScaffold
import org.rhythmeta.maimaid.ui.home.HomeScreen
import org.rhythmeta.maimaid.ui.dan.DanPageSwitcher
import org.rhythmeta.maimaid.ui.dan.DanRegularPage
import org.rhythmeta.maimaid.ui.navigation.AppDetail
import org.rhythmeta.maimaid.ui.navigation.AppRoute
import org.rhythmeta.maimaid.ui.navigation.RootDestination
import org.rhythmeta.maimaid.ui.navigation.appDetail
import org.rhythmeta.maimaid.ui.navigation.collectionId
import org.rhythmeta.maimaid.ui.navigation.danCategoryId
import org.rhythmeta.maimaid.ui.navigation.danCategoryTitle
import org.rhythmeta.maimaid.ui.navigation.songId
import org.rhythmeta.maimaid.ui.navigation.toRoute
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
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
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
    initialSgwcmaidCode: String? = null,
    onInitialSgwcmaidCodeConsumed: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val themeSettings by viewModel.themeSettings.collectAsStateWithLifecycle()
    val enableBlur = LocalEnableBlur.current
    val collections by container.songCollectionRepository.collections.collectAsStateWithLifecycle(emptyList())
    val initialCatalogState by viewModel.initialCatalogState.collectAsStateWithLifecycle()
    val showScannerBoundingBoxes by viewModel.showScannerBoundingBoxes.collectAsStateWithLifecycle()
    var pendingSgwcmaidCode by remember { mutableStateOf<String?>(null) }
    val thirdPartyScoreSyncEnabled by viewModel.thirdPartyScoreSyncEnabled.collectAsStateWithLifecycle()
    val catalogSortOption by viewModel.catalogSortOption.collectAsStateWithLifecycle()
    val catalogSortAscending by viewModel.catalogSortAscending.collectAsStateWithLifecycle()
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
    val navBackStack = rememberNavBackStack<AppRoute>(AppRoute.Home)
    val currentRoute = navBackStack.lastOrNull() as? AppRoute
    val navigationCornerRadius = rememberNavSystemCornerRadius()
    val songCoverTransitions = remember { mutableStateMapOf<String, CatalogCoverTransition>() }
    var catalogCoverTransition by remember { mutableStateOf<CatalogCoverTransition?>(null) }
    var navigationOrigin by remember { mutableStateOf(Offset.Zero) }
    var destination by rememberSaveable { mutableStateOf(RootDestination.Home) }
    var animateRootTransition by remember { mutableStateOf(true) }
    val rootBackProgress = remember { Animatable(0f) }
    val appUpdateViewModel: org.rhythmeta.maimaid.ui.update.AppUpdateViewModel = viewModel(
        factory = org.rhythmeta.maimaid.ui.update.AppUpdateViewModel.Factory(container.appUpdateRepository),
    )
    val detail = currentRoute?.appDetail
    val selectedSongId = currentRoute?.songId
    var selectedDanCategoryId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedDanCategoryTitle by rememberSaveable { mutableStateOf<String?>(null) }
    val communityAliasListState = rememberSaveable(saver = LazyListState.Saver) {
        LazyListState()
    }
    val constantTableListState = rememberSaveable(saver = LazyListState.Saver) {
        LazyListState()
    }
    val communityAliasScrollBehavior = MiuixScrollBehavior()
    val constantTableScrollBehavior = MiuixScrollBehavior()
    var songDetailBackground by remember { mutableStateOf<Pair<String, Color>?>(null) }
    var songDetailTitle by remember { mutableStateOf<Pair<String, String>?>(null) }
    var songDetailTitleHidden by rememberSaveable { mutableStateOf(false) }
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
                navBackStack.add(AppRoute.Home)
                destination = RootDestination.Home
            }
            initialDetail != null -> {
                navBackStack.clear()
                navBackStack.add(AppRoute.Home)
                navBackStack.add(initialDetail.toRoute())
                destination = RootDestination.Home
            }
        }
    }
    // A cabinet QR link ("open with" from WeChat) only prefills the import field. The hand-off is
    // consumed once the page owns the code, and the import itself always waits for the user.
    LaunchedEffect(initialSgwcmaidCode) {
        val requested = initialSgwcmaidCode ?: return@LaunchedEffect
        pendingSgwcmaidCode = requested.ifBlank { null }
        navBackStack.clear()
        navBackStack.add(AppRoute.Home)
        navBackStack.add(AppRoute.SgwcmaidImport)
        destination = RootDestination.Home
        onInitialSgwcmaidCodeConsumed()
    }
    LaunchedEffect(currentRoute) {
        selectedDanCategoryId = currentRoute?.danCategoryId
        selectedDanCategoryTitle = currentRoute?.danCategoryTitle
        selectedCollectionId = currentRoute?.collectionId
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
        if (next == AppDetail.Recommendations) {
            recommendationSwitcherVisible = true
        }
        if (next == AppDetail.ScoreQuery) {
            scoreQuerySearchVisible = true
        }
        val route = next.toRoute(
            songId = selectedSongId.takeIf { next == AppDetail.Song },
            danCategoryId = selectedDanCategoryId.takeIf { next == AppDetail.DanDetail },
            danCategoryTitle = selectedDanCategoryTitle.takeIf { next == AppDetail.DanDetail },
            collectionId = selectedCollectionId.takeIf { next == AppDetail.CollectionDetail },
        )
        if (navBackStack.lastOrNull() != route) {
            navBackStack.add(route)
        }
    }
    val pushSongRoute: (String) -> Unit = { songId ->
        val route = AppRoute.Song(songId)
        if (navBackStack.lastOrNull() != route) navBackStack.add(route)
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
        if (current is AppRoute.Song || current is AppRoute.CatalogSong) return
        val transitionId = java.util.UUID.randomUUID().toString()
        val route = AppRoute.CatalogSong(source.songId, transitionId)
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
    }
    val openSong: (String) -> Unit = { songId ->
        if (catalogSearchFocused) {
            restoreCatalogSearchFocus = true
            focusManager.clearFocus()
            softwareKeyboardController?.hide()
        }
        catalogCoverTransition = null
        pushSongRoute(songId)
    }
    val openSongFromDetail: (String) -> Unit = { songId ->
        if (detail == AppDetail.ScoreQuery && scoreQuerySearchFocused) {
            restoreScoreQuerySearchFocus = true
            focusManager.clearFocus()
            softwareKeyboardController?.hide()
        }
        catalogCoverTransition = null
        pushSongRoute(songId)
    }
    val openCollection: (String) -> Unit = { collectionId ->
        selectedCollectionId = collectionId
        openDetail(AppDetail.CollectionDetail)
    }
    val openCommunityAliasesFromSong: () -> Unit = {
        openDetail(AppDetail.CommunityAliases)
    }
    val rootPage: @Composable (RootDestination, Dp) -> Unit = { page, contentTopPadding ->
        when (page) {
            RootDestination.Home -> HomeScreen(
                state = uiState,
                presetAvatarRepository = container.presetAvatarRepository,
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
                onOpenSgwcmaidImport = { code ->
                    pendingSgwcmaidCode = code
                    openDetail(AppDetail.SgwcmaidImport)
                },
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
                activeProfileIsChina = uiState.activeProfile?.server
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

    val closeDetail: () -> Unit = {
        if (navBackStack.size > 1) {
					if (detail == AppDetail.RandomSong) {
                randomSongFilterActive = false
                randomSongFilterRequested = false
            }
            if (detail == AppDetail.CollectionDetail) {
                selectedCollectionId = null
                collectionsRenameRequested = false
            }
            navBackStack.removeLastOrNull()
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
            when (activeDetail) {
                    AppDetail.Song if actionSong != null -> {
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
                    }
                    AppDetail.Collections -> {
                        var menuExpanded by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { menuExpanded = !menuExpanded }) {
                                Icon(
                                    Icons.Rounded.Add,
                                    contentDescription = stringResource(R.string.collections_add)
                                )
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
                    }
                    AppDetail.CollectionDetail -> {
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
                            Icon(
                                Icons.Rounded.Edit,
                                contentDescription = stringResource(R.string.collections_rename)
                            )
                        }
                    }
                    AppDetail.Profiles -> {
                        IconButton(onClick = { profileCreateRequested = true }) {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = stringResource(R.string.profile_create),
                            )
                        }
                    }
                    AppDetail.BestTable -> {
                        BestTableConstantTopBarAction(container)
                        IconButton(onClick = { bestTableExportRequested = true }) {
                            Icon(
                                imageVector = Icons.Rounded.IosShare,
                                contentDescription = stringResource(R.string.best50_export),
                            )
                        }
                    }
                    AppDetail.RandomSong -> {
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

                else -> Unit
            }
    }
    @Composable
    fun DetailEntry(
        activeDetail: AppDetail,
        routeSongIdOverride: String? = null,
        routeDanCategoryIdOverride: String? = null,
        routeDanCategoryTitleOverride: String? = null,
        routeCollectionIdOverride: String? = null,
    ) {
        val backgroundColor = MiuixTheme.colorScheme.surface
        val routeSongId = routeSongIdOverride ?: selectedSongId
        val routeDanCategoryId = routeDanCategoryIdOverride ?: selectedDanCategoryId
        val routeCollectionId = routeCollectionIdOverride ?: selectedCollectionId
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
        val pageBackground = if (activeDetail == AppDetail.Song) {
            songDetailBackground?.takeIf { it.first == routeSongId }?.second ?: backgroundColor
        } else {
            backgroundColor
        }
        val detailTitleText = when (activeDetail) {
            AppDetail.CollectionDetail -> routeCollectionTitle ?: detailTitle(AppDetail.Collections)
            AppDetail.DanDetail -> routeDanCategoryTitleOverride ?: selectedDanCategoryTitle ?: detailTitle(AppDetail.Dan)
            else -> detailTitle(activeDetail)
        }
        CatalogContainerTransform(
            state = catalogCoverTransition.takeIf { activeDetail == AppDetail.Song },
            background = pageBackground,
        ) {
        AppPageScaffold(
            title = if (activeDetail == AppDetail.Song) {
                songDetailTitle?.takeIf { it.first == routeSongId }?.second ?: routeSong?.title.orEmpty()
            } else detailTitleText,
            pageBackground = pageBackground,
            blurEnabled = enableBlur,
            largeTitle = activeDetail != AppDetail.Song,
            smallTitleVisible = if (activeDetail == AppDetail.Song) routeSong == null || songDetailTitleHidden else null,
            topBarScrollBehavior = detailScrollBehavior,
            modifier = Modifier.fillMaxSize(),
            navigationIcon = detailNavigationIcon,
            actions = {
							when (activeDetail) {
									AppDetail.ConstantTable -> IconButton(onClick = { showConstantTableFilter = true }) {
										Icon(Icons.Rounded.FilterList, contentDescription = stringResource(R.string.catalog_filter_title))
									}
									AppDetail.ScoreQuery if scoreQueryState != null -> ScoreQueryTopBarActions(
										displayMode = scoreQueryState.displayMode,
										sortMode = scoreQueryState.sortMode,
										sortAscending = scoreQueryState.sortAscending,
										filterActive = !scoreQueryState.filterSettings.isEmpty,
										onDisplayModeChange = { mode -> scoreQueryViewModel.setDisplayMode(mode) },
										onSortModeChange = { mode -> scoreQueryViewModel.setSortMode(mode) },
										onSortAscendingChange = { ascending -> scoreQueryViewModel.setSortAscending(ascending) },
										onShowFilter = { showScoreQueryFilter = true },
									)
									else -> detailActionsForRoute(activeDetail, routeSongId)
							}
            },
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
        ) { paddingValues, topBarScrollConnection ->
            Box(
                modifier = Modifier.fillMaxSize()
                    .nestedScroll(topBarScrollConnection)
                    .then(
                    when (activeDetail) {
                        AppDetail.Recommendations -> Modifier.nestedScroll(recommendationSwitcherScrollConnection)
                        AppDetail.ScoreQuery -> Modifier.nestedScroll(scoreQuerySearchScrollConnection)
                        else -> Modifier
                    },
                ).then(
                    when (activeDetail) {
                        AppDetail.StaticData -> Modifier.padding(paddingValues)
                        else -> Modifier.background(pageBackground)
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
                    sgwcmaidInitialCode = pendingSgwcmaidCode,
                    onSgwcmaidInitialCodeConsumed = { pendingSgwcmaidCode = null },
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
                    onSongDetailHeaderTitleHiddenChange = { hidden ->
                        if (activeDetail == AppDetail.Song && routeSongId != null) {
                            songDetailTitleHidden = hidden
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
            entry<AppRoute.Home>(
                transition = SettingsDetailTransition,
                swipeDismiss = NavSwipeDirection.None,
            ) {
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
                        modifier = Modifier
                            .fillMaxSize()
                            .layerBackdrop(navigationBackdrop)
                            .graphicsLayer {
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
                            modifier = Modifier.fillMaxSize(),
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
            entry<AppRoute.BestTable>(transition = SettingsDetailTransition, swipeDismiss = if (predictiveBackEnabled) NavSwipeDirection.LeftToRight else NavSwipeDirection.None) {
                DetailEntry(AppDetail.BestTable)
            }
            entry<AppRoute.RandomSong>(transition = SettingsDetailTransition, swipeDismiss = if (predictiveBackEnabled) NavSwipeDirection.LeftToRight else NavSwipeDirection.None) {
                DetailEntry(AppDetail.RandomSong)
            }
            entry<AppRoute.Recommendations>(transition = SettingsDetailTransition, swipeDismiss = if (predictiveBackEnabled) NavSwipeDirection.LeftToRight else NavSwipeDirection.None) {
                DetailEntry(AppDetail.Recommendations)
            }
            entry<AppRoute.ScoreQuery>(transition = SettingsDetailTransition, swipeDismiss = if (predictiveBackEnabled) NavSwipeDirection.LeftToRight else NavSwipeDirection.None) {
                DetailEntry(AppDetail.ScoreQuery)
            }
            entry<AppRoute.ConstantTable>(transition = SettingsDetailTransition, swipeDismiss = if (predictiveBackEnabled) NavSwipeDirection.LeftToRight else NavSwipeDirection.None) {
                DetailEntry(AppDetail.ConstantTable)
            }
            entry<AppRoute.PlateProgress>(transition = SettingsDetailTransition, swipeDismiss = if (predictiveBackEnabled) NavSwipeDirection.LeftToRight else NavSwipeDirection.None) {
                DetailEntry(AppDetail.PlateProgress)
            }
            entry<AppRoute.Dan>(transition = SettingsDetailTransition, swipeDismiss = if (predictiveBackEnabled) NavSwipeDirection.LeftToRight else NavSwipeDirection.None) {
                DetailEntry(AppDetail.Dan)
            }
            entry<AppRoute.DanDetail>(transition = SettingsDetailTransition, swipeDismiss = if (predictiveBackEnabled) NavSwipeDirection.LeftToRight else NavSwipeDirection.None) { route ->
                DetailEntry(AppDetail.DanDetail, routeDanCategoryIdOverride = route.categoryId, routeDanCategoryTitleOverride = route.title)
            }
            entry<AppRoute.CommunityAliases>(transition = SettingsDetailTransition, swipeDismiss = if (predictiveBackEnabled) NavSwipeDirection.LeftToRight else NavSwipeDirection.None) {
                DetailEntry(AppDetail.CommunityAliases)
            }
            entry<AppRoute.StaticData>(transition = SettingsDetailTransition, swipeDismiss = if (predictiveBackEnabled) NavSwipeDirection.LeftToRight else NavSwipeDirection.None) {
                DetailEntry(AppDetail.StaticData)
            }
            entry<AppRoute.Profiles>(transition = SettingsDetailTransition, swipeDismiss = if (predictiveBackEnabled) NavSwipeDirection.LeftToRight else NavSwipeDirection.None) {
                DetailEntry(AppDetail.Profiles)
            }
            entry<AppRoute.BackendAuth>(transition = SettingsDetailTransition, swipeDismiss = if (predictiveBackEnabled) NavSwipeDirection.LeftToRight else NavSwipeDirection.None) {
                DetailEntry(AppDetail.BackendAuth)
            }
            entry<AppRoute.DivingFishImport>(transition = SettingsDetailTransition, swipeDismiss = if (predictiveBackEnabled) NavSwipeDirection.LeftToRight else NavSwipeDirection.None) {
                DetailEntry(AppDetail.DivingFishImport)
            }
            entry<AppRoute.LxnsImport>(transition = SettingsDetailTransition, swipeDismiss = if (predictiveBackEnabled) NavSwipeDirection.LeftToRight else NavSwipeDirection.None) {
                DetailEntry(AppDetail.LxnsImport)
            }
            entry<AppRoute.SgwcmaidImport>(transition = SettingsDetailTransition, swipeDismiss = if (predictiveBackEnabled) NavSwipeDirection.LeftToRight else NavSwipeDirection.None) {
                DetailEntry(AppDetail.SgwcmaidImport)
            }
            entry<AppRoute.OtogameImport>(transition = SettingsDetailTransition, swipeDismiss = if (predictiveBackEnabled) NavSwipeDirection.LeftToRight else NavSwipeDirection.None) {
                DetailEntry(AppDetail.OtogameImport)
            }
            entry<AppRoute.OtogameLogin>(transition = SettingsDetailTransition, swipeDismiss = if (predictiveBackEnabled) NavSwipeDirection.LeftToRight else NavSwipeDirection.None) {
                DetailEntry(AppDetail.OtogameLogin)
            }
            entry<AppRoute.Appearance>(transition = SettingsDetailTransition, swipeDismiss = if (predictiveBackEnabled) NavSwipeDirection.LeftToRight else NavSwipeDirection.None) {
                DetailEntry(AppDetail.Appearance)
            }
            entry<AppRoute.About>(transition = SettingsDetailTransition, swipeDismiss = if (predictiveBackEnabled) NavSwipeDirection.LeftToRight else NavSwipeDirection.None) {
                DetailEntry(AppDetail.About)
            }
            entry<AppRoute.SongCollections>(transition = SettingsDetailTransition, swipeDismiss = if (predictiveBackEnabled) NavSwipeDirection.LeftToRight else NavSwipeDirection.None) {
                DetailEntry(AppDetail.Collections)
            }
            entry<AppRoute.CollectionDetail>(transition = SettingsDetailTransition, swipeDismiss = if (predictiveBackEnabled) NavSwipeDirection.LeftToRight else NavSwipeDirection.None) { route ->
                DetailEntry(AppDetail.CollectionDetail, routeCollectionIdOverride = route.collectionId)
            }
            entry<AppRoute.Song>(
                transition = SettingsDetailTransition,
                swipeDismiss = if (predictiveBackEnabled) {
                    NavSwipeDirection.LeftToRight
                } else {
                    NavSwipeDirection.None
                },
            ) { route ->
                DetailEntry(AppDetail.Song, routeSongIdOverride = route.songId)
            }
            entry<AppRoute.CatalogSong>(
                transition = catalogCoverTransition?.transition ?: SettingsDetailTransition,
                swipeDismiss = if (predictiveBackEnabled) {
                    NavSwipeDirection.LeftToRight
                } else {
                    NavSwipeDirection.None
                },
            ) { route ->
                val routeTransition = songCoverTransitions[route.transitionId]
                DisposableEffect(route, routeTransition) {
                    onDispose {
                        if (route !in navBackStack) {
                            songCoverTransitions.remove(route.transitionId)
                            if (catalogCoverTransition === routeTransition) {
                                catalogCoverTransition = navBackStack.asReversed()
                                    .filterIsInstance<AppRoute.CatalogSong>()
                                    .firstNotNullOfOrNull { songCoverTransitions[it.transitionId] }
                            }
                        }
                    }
                }
                DetailEntry(AppDetail.Song, routeSongIdOverride = route.songId)
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
    AppPageScaffold(
        title = title,
        pageBackground = backgroundColor,
        blurEnabled = enableBlur,
        showTopBar = showTopBar,
        largeTitle = showTopBar,
        topBarScrollBehavior = scrollBehavior,
        modifier = modifier,
        actions = actions,
        bottomContent = bottomContent,
    ) { paddingValues, topBarConnection ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(topBarConnection)
                    .then(scrollObserver?.let(Modifier::nestedScroll) ?: Modifier)
                    .background(backgroundColor),
            ) {
                content(destination, paddingValues.calculateTopPadding())
            }
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
    AppDetail.SgwcmaidImport -> stringResource(R.string.detail_sgwcmaid)
    AppDetail.OtogameImport -> stringResource(R.string.detail_otogame)
    AppDetail.OtogameLogin -> stringResource(R.string.detail_otogame_login)
    AppDetail.Appearance -> stringResource(R.string.detail_appearance)
    AppDetail.About -> stringResource(R.string.detail_about)
    AppDetail.Song -> ""
    AppDetail.Collections -> stringResource(R.string.settings_collections)
    AppDetail.CollectionDetail -> stringResource(R.string.settings_collections)
}
