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
    var isPresented by mutableStateOf(true)
        private set
    var genericSource by mutableStateOf<SongCoverSource?>(null)
    private var source by mutableStateOf(source)
    private var navigation by mutableStateOf<NavTransitionScope?>(null)
    private var detailRoot: LayoutCoordinates? = null
    private var detailCover: LayoutCoordinates? = null
    private var detailViewportTop = 0f
    var detailBounds by mutableStateOf<Rect?>(null)
        private set
    var detailClip by mutableStateOf<Rect?>(null)
        private set

    val hidesSource: Boolean
        get() = isPresented && navigation?.let { catalogContainerProgress(it.relativeDepth) > 0f } == true

    /**
     * During a return, hand the card's own content back during the final 18% of the
     * shared-cover path. The moving cover remains the sole image until that handoff.
     */
    val sourceContentAlpha: Float
        get() {
            if (!isPresented) return 1f
            val scope = navigation ?: return 1f
            val progress = catalogContainerProgress(scope.relativeDepth)
            return if (isReturning()) {
                catalogListContentAlpha(progress)
            } else {
                if (progress > 0f) 0f else 1f
            }
        }
    val hidesDetailCover: Boolean
        get() = detailBounds != null && navigation?.let { catalogContainerProgress(it.relativeDepth) < 1f } == true

    private val sourceCover: Rect
        get() = genericSource?.bounds?.translate(-navigationOrigin()) ?: source
    private val sourceContainer: Rect
        get() = genericSource?.cardBounds?.translate(-navigationOrigin()) ?: sourceCover
    val usesListMotion: Boolean
        get() = genericSource?.cardBounds != null

    fun genericBodyBounds(): Rect? = genericSource?.bodyBounds?.translate(-navigationOrigin())

    fun updateSource(boundsInRoot: Rect) {
        source = boundsInRoot.translate(-navigationOrigin())
    }

    fun updateDetailRoot(coordinates: LayoutCoordinates) {
        detailRoot = coordinates
        updateDetailBounds()
    }

    fun updateDetailCover(coordinates: LayoutCoordinates?, viewportTop: Float = 0f) {
        detailCover = coordinates
        detailViewportTop = viewportTop
        updateDetailBounds()
    }

    private fun updateDetailBounds() {
        val root = detailRoot?.takeIf { it.isAttached }
        val cover = detailCover?.takeIf { it.isAttached }
        if (root == null || cover == null) {
            detailBounds = null
            detailClip = null
            return
        }
        // Measure inside the scaled page so its animated ancestor cannot move the anchor.
        val bounds = root.localBoundingBoxOf(cover, clipBounds = false)
        val visible = root.localBoundingBoxOf(cover, clipBounds = true)
            .intersect(Rect(0f, detailViewportTop, root.size.width.toFloat(), root.size.height.toFloat()))
        detailBounds = bounds.takeIf { visible.width > 0f && visible.height > 0f }
        detailClip = visible.takeIf { detailBounds != null }
    }

    fun attach(scope: NavTransitionScope) {
        navigation = scope
    }

    fun detach() {
        isPresented = false
        navigation = null
        detailRoot = null
        detailCover = null
        detailBounds = null
        detailClip = null
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

    fun containerCornerRadiusPx(density: Density, progress: Float): Float = interpolate(
        containerCornerRadiusPx(density),
        with(density) { deviceCornerRadius.toPx() },
        progress,
    )

    fun listContentTranslation(progress: Float): Float = sourceContainer.top * (1f - progress.coerceIn(0f, 1f))

    fun coverCornerRadiusPx(density: Density): Float = with(density) {
        val cover = genericSource ?: return@with 0f
        val scale = if (cover.layoutWidth > 0) sourceCover.width / cover.layoutWidth else 1f
        cover.cornerRadius.toPx() * scale
    }

    fun containerCornerRadiusPx(density: Density): Float = with(density) {
        val cover = genericSource
        if (cover?.cardBounds != null) cover.cardCornerRadius.toPx() else coverCornerRadiusPx(this)
    }

    private val returnSpec = NavSettleSpec.Spring(dampingRatio = 1f, stiffness = 700f)
    private val enterMotion = NavMotion(
        commit = returnSpec,
        programmatic = NavSettleSpec.Tween(500, CatalogEmphasizedEasing),
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
            val sourceRadius = containerCornerRadiusPx(scope.density)
            val radius = interpolate(sourceRadius, with(scope.density) { deviceCornerRadius.toPx() }, progress)
            alpha = if (progress > 0f) 1f else 0f
            clip = progress < 1f
            val bounds = containerBounds(Size(size.width, size.height), progress)
            shape = CoverContainerShape(bounds, if (usesListMotion) radius else radius.coerceAtLeast(sourceRadius))
        }
    }

    val transition: NavTransition = object : NavTransition by visualTransition {
        override val motion: NavMotion
            get() = if (isReturning()) returnMotion else enterMotion
    }
}

internal fun catalogContainerProgress(relativeDepth: Float): Float = (1f + relativeDepth).coerceIn(0f, 1f)
internal fun catalogDetailAlpha(progress: Float): Float = ((progress - 0.28f) / 0.28f).coerceIn(0f, 1f)
internal fun catalogCoverAlpha(progress: Float): Float = 1f - ((progress - 0.10f) / 0.18f).coerceIn(0f, 1f)
internal fun catalogListContentAlpha(progress: Float): Float = (1f - progress / 0.18f).coerceIn(0f, 1f)

private data class CoverContainerShape(val bounds: Rect, val cornerRadius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(
            RoundRect(bounds, CornerRadius(cornerRadius.coerceIn(0f, bounds.minDimension / 2f))),
        )
}

internal fun interpolate(from: Float, to: Float, fraction: Float): Float =
    from + (to - from) * fraction.coerceIn(0f, 1f)
