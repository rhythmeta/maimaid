package org.rhythmeta.maimaid.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.repeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.MarqueeDefaults
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun Modifier.fadingMarquee(
    edgeWidth: Dp = 12.dp,
    spacing: MarqueeSpacing = MarqueeDefaults.Spacing,
): Modifier = this then FadingMarqueeElement(edgeWidth, spacing)

private data class FadingMarqueeElement(
    val edgeWidth: Dp,
    val spacing: MarqueeSpacing,
) : ModifierNodeElement<FadingMarqueeNode>() {
    override fun create() = FadingMarqueeNode(edgeWidth, spacing)

    override fun update(node: FadingMarqueeNode) {
        node.edgeWidth = edgeWidth
        node.spacing = spacing
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "fadingMarquee"
        properties["edgeWidth"] = edgeWidth
        properties["spacing"] = spacing
    }
}

private class FadingMarqueeNode(
    var edgeWidth: Dp,
    var spacing: MarqueeSpacing,
) : Modifier.Node(), LayoutModifierNode, DrawModifierNode {
    private var contentWidth by mutableIntStateOf(0)
    private var containerWidth by mutableIntStateOf(0)
    private var spacingPx by mutableIntStateOf(0)
    private var velocityPx by mutableFloatStateOf(0f)
    private val offset = Animatable(0f)
    private val layerPaint = Paint()
    private var contentLayer: GraphicsLayer? = null

    override fun onAttach() {
        contentLayer = requireGraphicsContext().createGraphicsLayer()
        coroutineScope.launch {
            // Match basicMarquee's speed regardless of the system animation duration scale.
            withContext(MarqueeMotionDurationScale) {
                snapshotFlow {
                    if (contentWidth > containerWidth && containerWidth > 0) {
                        Triple(contentWidth + spacingPx, containerWidth, velocityPx)
                    } else {
                        null
                    }
                }.collectLatest { dimensions ->
                    offset.snapTo(0f)
                    if (dimensions == null) return@collectLatest
                    val (distance, _, velocity) = dimensions
                    if (velocity <= 0f) return@collectLatest
                    try {
                        offset.animateTo(
                            targetValue = distance.toFloat(),
                            animationSpec = repeatable(
                                iterations = MarqueeDefaults.Iterations,
                                animation = tween(
                                    durationMillis = ceil(distance / velocity * 1_000).toInt(),
                                    delayMillis = MarqueeDefaults.RepeatDelayMillis,
                                    easing = LinearEasing,
                                ),
                            ),
                        )
                    } finally {
                        offset.snapTo(0f)
                    }
                }
            }
        }
    }

    override fun onDetach() {
        contentLayer?.let { requireGraphicsContext().releaseGraphicsLayer(it) }
        contentLayer = null
    }

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        val placeable = measurable.measure(constraints.copy(maxWidth = Constraints.Infinity))
        contentWidth = placeable.width
        containerWidth = constraints.constrainWidth(placeable.width)
        spacingPx = if (contentWidth > containerWidth) {
            with(spacing) { calculateSpacing(contentWidth, containerWidth) }.coerceAtLeast(0)
        } else {
            0
        }
        velocityPx = MarqueeDefaults.Velocity.toPx()
        return layout(containerWidth, placeable.height) {
            placeable.placeWithLayer(0, 0)
        }
    }

    // Intrinsic queries must not mutate the animation's measured dimensions.
    override fun IntrinsicMeasureScope.minIntrinsicWidth(
        measurable: IntrinsicMeasurable,
        height: Int,
    ): Int = 0

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(
        measurable: IntrinsicMeasurable,
        height: Int,
    ): Int = measurable.maxIntrinsicWidth(height)

    override fun IntrinsicMeasureScope.minIntrinsicHeight(
        measurable: IntrinsicMeasurable,
        width: Int,
    ): Int = measurable.minIntrinsicHeight(Constraints.Infinity)

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(
        measurable: IntrinsicMeasurable,
        width: Int,
    ): Int = measurable.maxIntrinsicHeight(Constraints.Infinity)

    override fun ContentDrawScope.draw() {
        val layer = contentLayer
        if (contentWidth <= containerWidth || containerWidth == 0 || layer == null) {
            drawContent()
            return
        }

        val width = containerWidth.toFloat()
        val distance = (contentWidth + spacingPx).toFloat()
        val firstLeft = if (layoutDirection == LayoutDirection.Ltr) {
            -offset.value
        } else {
            width - contentWidth + offset.value
        }
        val secondLeft = firstLeft + if (layoutDirection == LayoutDirection.Ltr) distance else -distance
        val edge = edgeWidth.toPx().coerceIn(0f, width / 2f)

        // Shrink each fade to zero as the actual text endpoint becomes fully visible.
        // Check both copies so the empty gap between iterations never creates a fade.
        fun leftFade(left: Float): Float =
            if (left < 0f && left + contentWidth > 0f) (-left).coerceAtMost(edge) else 0f
        fun rightFade(left: Float): Float =
            if (left < width && left + contentWidth > width) {
                (left + contentWidth - width).coerceAtMost(edge)
            } else {
                0f
            }
        val leftFadeWidth = maxOf(leftFade(firstLeft), leftFade(secondLeft))
        val rightFadeWidth = maxOf(rightFade(firstLeft), rightFade(secondLeft))

        layer.record(size = IntSize(contentWidth, size.height.roundToInt())) {
            this@draw.drawContent()
        }
        clipRect(right = width) {
            // Isolate the alpha mask so it reveals, rather than darkens, the real background.
            drawContext.canvas.saveLayer(Rect(Offset.Zero, size), layerPaint)
            try {
                if (firstLeft < width && firstLeft + contentWidth > 0f) {
                    translate(left = firstLeft) { drawLayer(layer) }
                }
                if (secondLeft < width && secondLeft + contentWidth > 0f) {
                    translate(left = secondLeft) { drawLayer(layer) }
                }
                if (leftFadeWidth > 0f) {
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(Color.Transparent, Color.Black),
                            startX = 0f,
                            endX = leftFadeWidth,
                        ),
                        size = Size(leftFadeWidth, size.height),
                        blendMode = BlendMode.DstIn,
                    )
                }
                if (rightFadeWidth > 0f) {
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(Color.Black, Color.Transparent),
                            startX = width - rightFadeWidth,
                            endX = width,
                        ),
                        topLeft = Offset(width - rightFadeWidth, 0f),
                        size = Size(rightFadeWidth, size.height),
                        blendMode = BlendMode.DstIn,
                    )
                }
            } finally {
                drawContext.canvas.restore()
            }
        }
    }
}

private object MarqueeMotionDurationScale : MotionDurationScale {
    override val scaleFactor = 1f
}
