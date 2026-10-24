package org.rhythmeta.maimaid.ui.catalog

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.nav.transition.NavMotion
import top.yukonga.miuix.kmp.nav.transition.NavSettleSpec
import top.yukonga.miuix.kmp.nav.transition.NavTransition
import top.yukonga.miuix.kmp.nav.transition.NavTransitionScope
import top.yukonga.miuix.kmp.nav.transition.navGraphicsTransition

internal val LocalCatalogCoverTransition = staticCompositionLocalOf<CatalogCoverTransition?> { null }

internal class CatalogCoverTransition(
    val songId: String,
    source: Rect,
    val coverPainter: Painter,
    private val deviceCornerRadius: Dp,
    private val isReturning: () -> Boolean,
    private val navigationOrigin: () -> Offset,
) {
    var genericSource by mutableStateOf<SongCoverSource?>(null)
    private var source by mutableStateOf(source)
    private var navigation by mutableStateOf<NavTransitionScope?>(null)
    private var detailRoot: LayoutCoordinates? = null
    private var detailCover: LayoutCoordinates? = null
    var detailBounds by mutableStateOf<Rect?>(null)
        private set

    val hidesSource: Boolean
        get() = navigation?.let { catalogContainerProgress(it.relativeDepth) > 0f } == true
    val hidesDetailCover: Boolean
        get() = detailBounds != null && navigation?.let { catalogContainerProgress(it.relativeDepth) < 1f } == true

    private val sourceCover: Rect
        get() = genericSource?.bounds?.translate(-navigationOrigin()) ?: source
    private val sourceContainer: Rect
        get() = genericSource?.cardBounds?.translate(-navigationOrigin()) ?: sourceCover
    val usesListMotion: Boolean
        get() = genericSource?.cardBounds != null

    fun updateSource(boundsInRoot: Rect) {
        source = boundsInRoot.translate(-navigationOrigin())
    }

    fun updateDetailRoot(coordinates: LayoutCoordinates) {
        detailRoot = coordinates
        updateDetailBounds()
    }

    fun updateDetailCover(coordinates: LayoutCoordinates?) {
        detailCover = coordinates
        updateDetailBounds()
    }

    private fun updateDetailBounds() {
        val root = detailRoot?.takeIf { it.isAttached }
        val cover = detailCover?.takeIf { it.isAttached }
        if (root == null || cover == null) {
            detailBounds = null
            return
        }
        detailBounds = root.localBoundingBoxOf(cover, clipBounds = false)
            .takeIf { it.width > 0f && it.height > 0f }
    }

    fun attach(scope: NavTransitionScope) {
        navigation = scope
    }

    fun detach() {
        navigation = null
        detailRoot = null
        detailCover = null
        detailBounds = null
    }

    fun sharedCoverBounds(target: Rect, progress: Float): Rect = Rect(
        interpolate(sourceCover.left, target.left, progress),
        interpolate(sourceCover.top, target.top, progress),
        interpolate(sourceCover.right, target.right, progress),
        interpolate(sourceCover.bottom, target.bottom, progress),
    )

    fun fallbackCoverBounds(pageSize: Size, progress: Float): Rect = Rect(
        interpolate(sourceCover.left, 0f, progress),
        interpolate(sourceCover.top, 0f, progress),
        interpolate(sourceCover.right, pageSize.width, progress),
        interpolate(sourceCover.bottom, pageSize.height, progress),
    )

    fun containerBounds(pageSize: Size, progress: Float): Rect = Rect(
        interpolate(sourceContainer.left, 0f, progress),
        interpolate(sourceContainer.top, 0f, progress),
        interpolate(sourceContainer.right, pageSize.width, progress),
        interpolate(sourceContainer.bottom, pageSize.height, progress),
    )

    fun listContentTranslation(progress: Float): Float = sourceContainer.top * (1f - progress.coerceIn(0f, 1f))

    fun coverCornerRadiusPx(density: Density): Float = with(density) { genericSource?.cornerRadius?.toPx() ?: 0f }

    private val returnSpec = NavSettleSpec.Spring(dampingRatio = 1f, stiffness = 700f)
    private val enterMotion = NavMotion(
        commit = returnSpec,
        programmatic = NavSettleSpec.Tween(500, androidx.compose.animation.core.FastOutSlowInEasing),
    )
    private val returnMotion = NavMotion(commit = returnSpec, programmatic = returnSpec)

    private val visualTransition = navGraphicsTransition(scrim = { 0f }) { scope ->
        scaleX = 1f
        scaleY = 1f
        translationX = 0f
        translationY = 0f
        alpha = if (scope.relativeDepth <= 0f) 0f else 1f
        clip = false
        shape = RectangleShape
        if (scope.relativeDepth <= 0f) {
            val progress = catalogContainerProgress(scope.relativeDepth)
            val radius = interpolate(
                coverCornerRadiusPx(scope.density),
                with(scope.density) { deviceCornerRadius.toPx() },
                progress,
            )
            alpha = if (progress > 0f) 1f else 0f
            clip = progress < 1f
            shape = CoverContainerShape(fallbackCoverBounds(Size(size.width, size.height), progress), radius)
        }
    }

    val transition: NavTransition = object : NavTransition by visualTransition {
        override val motion: NavMotion
            get() = if (isReturning()) returnMotion else enterMotion
    }
}

internal fun catalogContainerProgress(relativeDepth: Float): Float = (1f + relativeDepth).coerceIn(0f, 1f)
internal fun catalogDetailAlpha(progress: Float): Float = ((progress - 0.28f) / 0.28f).coerceIn(0f, 1f)

private data class CoverContainerShape(val bounds: Rect, val cornerRadius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(
            RoundRect(bounds, CornerRadius(cornerRadius.coerceIn(0f, bounds.minDimension / 2f))),
        )
}

internal fun interpolate(from: Float, to: Float, fraction: Float): Float =
    from + (to - from) * fraction.coerceIn(0f, 1f)
