package org.rhythmeta.maimaid.ui.catalog

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.draw.drawBehind
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.nav.core.LocalNavTransitionScope
import kotlin.math.max

@Composable
internal fun CatalogContainerTransform(
    state: CatalogCoverTransition?,
    background: Color,
    content: @Composable () -> Unit,
) {
    if (state == null) {
        content()
        return
    }
    val navigation = LocalNavTransitionScope.current
    SideEffect { state.attach(navigation) }
    DisposableEffect(state) {
        onDispose { state.detach() }
    }
    Box(Modifier.fillMaxSize().drawBehind {
        val progress = catalogContainerProgress(navigation.relativeDepth)
        val sourceColor = state.genericSource?.takeIf { state.usesListMotion }?.cardColor
        val color = sourceColor?.let { lerp(it, background, (progress / 0.56f).coerceIn(0f, 1f)) } ?: background
        drawRect(color)
    }) {
        Box(Modifier.fillMaxSize().graphicsLayer {
            val progress = catalogContainerProgress(navigation.relativeDepth)
            val bounds = state.containerBounds(Size(size.width, size.height), progress)
            alpha = if (state.usesListMotion) catalogDetailAlpha(progress) else 1f
            transformOrigin = TransformOrigin(0f, 0f)
            if (state.usesListMotion) {
                scaleX = 1f
                scaleY = 1f
                translationX = 0f
                translationY = state.listContentTranslation(progress)
            } else {
                val scale = max(bounds.width / size.width.coerceAtLeast(1f), bounds.height / size.height.coerceAtLeast(1f))
                scaleX = scale
                scaleY = scale
                translationX = bounds.center.x - size.width * scale / 2f
                translationY = bounds.top
            }
        }) {
            Box(Modifier.fillMaxSize().onGloballyPositioned(state::updateDetailRoot)) {
                CompositionLocalProvider(LocalCatalogCoverTransition provides state) {
                    content()
                }
            }
        }
        Canvas(Modifier.fillMaxSize().clearAndSetSemantics {}) {
            val progress = catalogContainerProgress(navigation.relativeDepth)
            val target = state.detailBounds
            val shared = target != null
            val coverAlpha = if (shared) {
                if (progress < 1f) 1f else 0f
            } else {
                catalogCoverAlpha(progress)
            }
            val painter = state.coverPainter
            val intrinsic = painter.intrinsicSize
            val imageSize = if (intrinsic.isSpecified && intrinsic.width.isFinite() && intrinsic.height.isFinite() &&
                intrinsic.width > 0f && intrinsic.height > 0f
            ) intrinsic else Size(1f, 1f)
            if (coverAlpha > 0f) {
                val bounds = if (target != null) state.sharedCoverBounds(target, progress)
                    else state.fallbackCoverBounds(Size(size.width, size.height), progress)
                val drawScale = max(bounds.width / imageSize.width, bounds.height / imageSize.height)
                val drawSize = Size(imageSize.width * drawScale, imageSize.height * drawScale)
                val visible = if (shared) {
                    state.sharedCoverBounds(state.detailClip ?: target, progress)
                } else {
                    bounds
                }
                val sourceRadius = state.coverCornerRadiusPx(this)
                val radius = if (shared) interpolate(sourceRadius, 26.dp.toPx(), progress) else sourceRadius
                val path = Path().apply { addRoundRect(RoundRect(bounds, CornerRadius(radius))) }
                val source = state.genericSource
                val painterAlpha = source?.painterAlpha?.let { interpolate(it, 1f, progress) } ?: 1f
                val painterSaturation = source?.painterSaturation?.let { interpolate(it, 1f, progress) } ?: 1f
                val colorFilter = if (painterSaturation < 0.999f) {
                    ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(painterSaturation) })
                } else {
                    null
                }
                clipRect(visible.left, visible.top, visible.right, visible.bottom) {
                    clipPath(path) {
                        if (!state.usesListMotion) {
                            state.genericSource?.cardColor?.let { surface ->
                                drawRect(surface, topLeft = bounds.topLeft, size = bounds.size, alpha = coverAlpha)
                            }
                        }
                        translate(bounds.center.x - drawSize.width / 2f, bounds.center.y - drawSize.height / 2f) {
                            with(painter) { draw(drawSize, alpha = coverAlpha * painterAlpha, colorFilter = colorFilter) }
                        }
                    }
                }
            }
            state.genericSource?.cardBorderColor?.let { borderColor ->
                val borderAlpha = catalogListContentAlpha(progress)
                if (borderAlpha > 0f) {
                    val borderWidth = interpolate(
                        1.dp.toPx(),
                        (state.genericSource?.cardBorderWidth ?: 1.dp).toPx(),
                        borderAlpha,
                    )
                    val bounds = state.containerBounds(Size(size.width, size.height), progress)
                    val inset = borderWidth / 2f
                    val cornerRadius = (state.containerCornerRadiusPx(this, progress) - inset).coerceAtLeast(0f)
                    drawRoundRect(
                        color = borderColor.copy(alpha = borderColor.alpha * borderAlpha),
                        topLeft = androidx.compose.ui.geometry.Offset(bounds.left + inset, bounds.top + inset),
                        size = Size(
                            (bounds.width - borderWidth).coerceAtLeast(0f),
                            (bounds.height - borderWidth).coerceAtLeast(0f),
                        ),
                        cornerRadius = CornerRadius(cornerRadius),
                        style = Stroke(width = borderWidth),
                    )
                }
            }
        }
        SongCardContentOverlay(
            state = state,
            progress = { catalogContainerProgress(navigation.relativeDepth) },
            pageSize = { Size(navigation.layoutSize.width.toFloat(), navigation.layoutSize.height.toFloat()) },
        )
    }
}
