package org.rhythmeta.maimaid.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.TopAppBarDefaults
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import kotlin.math.roundToInt

/** Blur radius of the flat top bar material, in dp. */
private val TopBarBlurRadius = 24.dp

/** Page tint drawn over the flat top bar material. */
private const val TopBarSurfaceAlpha = 0.52f

/** Blur radius at the top edge of the progressive band, in dp. */
private val TopBarProgressiveBlurRadius = 80.dp

/**
 * Falloff exponent of the progressive band, matching MIUIX's glass top bar: most of the radius
 * change happens near the strong (top) end, leaving a long soft tail towards the page.
 */
private const val TopBarProgressiveCurve = 0.25f

/** Page tint blended into the progressive band; it fades out together with the blur. */
private const val TopBarProgressiveTintAlpha = 0.3f

/** Room left below the covered chrome before the band's blur reaches zero. */
private val TopBarBandExtra = 20.dp

/** How much taller than the covered chrome the progressive band is drawn. */
private const val TopBarBandScale = 1.2f

/** Fade-in duration of the progressive band once content scrolls underneath the bar. */
private const val TopBarBandFadeMillis = 100

/**
 * Page scaffold whose top bar can use MIUIX's progressive (gradient) blur.
 *
 * With progressive blur the bar itself stays transparent: a band behind it fades in once the page
 * scrolls content underneath, blurs strongest at the top edge and clears towards the page, and is
 * drawn taller than the covered chrome so the transition has no hard edge. This mirrors the MIUIX
 * glass top bar. Without progressive blur the bar keeps the flat blurred material it always had.
 */
@Composable
fun AppPageScaffold(
    title: String,
    pageBackground: Color,
    blurEnabled: Boolean,
    progressiveBlurEnabled: Boolean,
    showTopBar: Boolean = true,
    largeTitle: Boolean = true,
    smallTitleVisible: Boolean? = null,
    titleBarVisible: Boolean? = null,
    contentScrolled: Boolean? = null,
    topBarScrollBehavior: ScrollBehavior = MiuixScrollBehavior(),
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    bottomContent: @Composable () -> Unit = {},
    content: @Composable (PaddingValues, NestedScrollConnection) -> Unit,
) {
    val progressive = blurEnabled && progressiveBlurEnabled
    val pageBackdrop = rememberLayerBackdrop {
        drawRect(pageBackground)
        drawContent()
    }
    val scrollState = topBarScrollBehavior.state
    val scrollStateContentScrolled by remember(scrollState) {
        derivedStateOf { scrollState.contentOffset < -1f || scrollState.overscrollOffset < -1f }
    }
    val flatTopBarModifier = Modifier
        .drawBackdrop(
            backdrop = pageBackdrop,
            shape = { TopBarBottomShape },
            effects = { blur(TopBarBlurRadius.toPx()) },
            onDrawSurface = { drawRect(pageBackground.copy(alpha = TopBarSurfaceAlpha)) },
            enabled = blurEnabled,
        )
        .clip(TopBarBottomShape)
    val topBarModifier = when {
        !blurEnabled -> Modifier
            .background(pageBackground)
            .clip(TopBarBottomShape)
        progressive -> Modifier
        else -> flatTopBarModifier
    }
    val bandProgress = animateFloatAsState(
        targetValue = if (contentScrolled ?: scrollStateContentScrolled) 1f else 0f,
        animationSpec = tween(durationMillis = TopBarBandFadeMillis, easing = LinearEasing),
        label = "top-bar-band",
    )
    val bandVisible by remember(bandProgress) {
        derivedStateOf { bandProgress.value > 0.001f }
    }
    val bandColors = BlurDefaults.blurColors(
        blendColors = remember(pageBackground) {
            listOf(BlendColorEntry(pageBackground.copy(alpha = TopBarProgressiveTintAlpha)))
        },
    )

    MiuixScaffold(
        modifier = modifier,
        containerColor = pageBackground,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            if (showTopBar) {
                val measurements = remember { TopBarMeasurements() }
                TopBarSlot(
                    progressive = progressive,
                    bandProgress = bandProgress,
                    bandVisible = bandVisible,
                    backdrop = pageBackdrop,
                    colors = bandColors,
                    measurements = measurements,
                ) {
                    if (titleBarVisible != null) {
                        CollapsibleTitleTopAppBar(
                            title = title,
                            titleVisible = titleBarVisible,
                            modifier = topBarModifier,
                            navigationIcon = navigationIcon,
                            actions = actions,
                            scrollBehavior = topBarScrollBehavior,
                            bottomContent = {
                                MeasuredBottomContent(measurements) { bottomContent() }
                            },
                        )
                    } else if (largeTitle) {
                        TopAppBar(
                            title = title,
                            largeTitle = title,
                            modifier = topBarModifier,
                            color = Color.Transparent,
                            navigationIcon = navigationIcon,
                            actions = actions,
                            bottomContent = {
                                MeasuredBottomContent(measurements) { bottomContent() }
                            },
                            scrollBehavior = topBarScrollBehavior,
                            defaultWindowInsetsPadding = true,
                        )
                    } else if (smallTitleVisible != null) {
                        AnimatedSmallTopAppBar(
                            title = title,
                            titleVisible = smallTitleVisible,
                            modifier = topBarModifier,
                            color = Color.Transparent,
                            navigationIcon = navigationIcon,
                            actions = actions,
                            scrollBehavior = topBarScrollBehavior,
                        )
                    } else {
                        SmallTopAppBar(
                            title = title,
                            navigationIcon = navigationIcon,
                            modifier = topBarModifier,
                            color = Color.Transparent,
                            actions = actions,
                            scrollBehavior = topBarScrollBehavior,
                            defaultWindowInsetsPadding = true,
                        )
                    }
                }
            }
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (blurEnabled) Modifier.layerBackdrop(pageBackdrop) else Modifier),
        ) {
            content(paddingValues, topBarScrollBehavior.nestedScrollConnection)
        }
    }
}

/** Bottom-content height recorded during measurement, used to size the progressive band. */
private class TopBarMeasurements {
    var bottomContentHeight: Int = 0
}

/** Publishes its measured height to [measurements] while being laid out. */
@Composable
private fun MeasuredBottomContent(
    measurements: TopBarMeasurements,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            measurements.bottomContentHeight = placeable.height
            layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
        },
    ) {
        content()
    }
}

/**
 * Places the top [bar] content, with a progressive-blur band behind it when [progressive].
 *
 * The band covers the chrome the bar keeps once collapsed (status bar, collapsed title row and any
 * bottom content) and is drawn taller than that, like MIUIX's glass top bar, so its gradient reaches
 * zero below the bar instead of ending on the bar's edge. The band overflows the layout on purpose:
 * the top bar slot must only be as tall as the bar itself.
 */
@Composable
private fun TopBarSlot(
    progressive: Boolean,
    bandProgress: State<Float>,
    bandVisible: Boolean,
    backdrop: LayerBackdrop,
    colors: BlurColors,
    measurements: TopBarMeasurements,
    bar: @Composable () -> Unit,
) {
    if (!progressive) {
        bar()
        return
    }
    val statusBarTop = WindowInsets.systemBars.asPaddingValues().calculateTopPadding()
    val density = LocalDensity.current
    val statusBarPx = with(density) { statusBarTop.toPx() }
    val collapsedPx = with(density) { TopAppBarDefaults.CollapsedHeight.toPx() }
    val extraPx = with(density) { TopBarBandExtra.toPx() }
    Layout(
        content = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = bandProgress.value }
                    .progressiveTextureBlur(
                        backdrop = backdrop,
                        shape = RectangleShape,
                        gradient = TopBarProgressiveGradient,
                        blurRadius = TopBarProgressiveBlurRadius.value,
                        colors = colors,
                        enabled = bandVisible,
                    ),
            )
            bar()
        },
        modifier = Modifier.fillMaxWidth(),
    ) { measurables, constraints ->
        // The bar is measured first: its bottom content height sizes the band.
        val barPlaceable = measurables[1].measure(constraints)
        // Searching removes the title row. Never size its blur using the hidden collapsed title.
        val coveredPx = minOf(
            barPlaceable.height.toFloat(),
            statusBarPx + collapsedPx + measurements.bottomContentHeight,
        )
        val bandHeightPx = ((coveredPx + extraPx) * TopBarBandScale).roundToInt()
        val bandPlaceable = measurables[0].measure(
            Constraints.fixed(barPlaceable.width, bandHeightPx),
        )
        layout(barPlaceable.width, barPlaceable.height) {
            bandPlaceable.placeRelative(0, 0)
            barPlaceable.placeRelative(0, 0)
        }
    }
}

/** Gradient of the progressive band: full strength at the top edge, clear towards the page. */
private val TopBarProgressiveGradient = ProgressiveBlur.Top.copy(curve = TopBarProgressiveCurve)
