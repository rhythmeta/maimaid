package org.rhythmeta.maimaid.ui.components

import android.graphics.BlurMaskFilter
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.ceil

/**
 * Draws a soft drop shadow behind the content of this modifier.
 *
 * The shadow is a pre-blurred alpha bitmap of [shape] whose margin is [ShadowMarginFactor] times the
 * blur radius. `Modifier.dropShadow` sizes that bitmap to exactly the blur radius and therefore cuts
 * the blur's tail, which leaves a straight, faintly visible seam a radius away from the shape on all
 * four sides. Twice the radius keeps the truncated tail below one alpha level.
 *
 * [alpha] is read in the draw phase, so a caller can fade the shadow per frame without
 * recomposition and without putting the shadow inside a partial-alpha layer (which would be
 * rasterized offscreen and clipped back to hard edges).
 */
internal fun Modifier.softDropShadow(
    shape: Shape,
    shadow: Shadow,
    alpha: () -> Float = { 1f },
): Modifier = drawWithCache {
    val margin = (shadow.radius.toPx() * ShadowMarginFactor).coerceAtLeast(1f)
    val shadowBitmap = buildSoftShadowBitmap(shape, shadow, size, this, margin)
    val offsetX = shadow.offset.x.toPx() - margin
    val offsetY = shadow.offset.y.toPx() - margin
    onDrawBehind {
        val fraction = (alpha() * shadow.alpha).coerceIn(0f, 1f)
        if (fraction <= 0f) return@onDrawBehind
        drawImage(
            image = shadowBitmap,
            topLeft = Offset(offsetX, offsetY),
            alpha = fraction,
            colorFilter = ColorFilter.tint(shadow.color),
        )
    }
}

private fun buildSoftShadowBitmap(
    shape: Shape,
    shadow: Shadow,
    size: Size,
    density: Density,
    margin: Float,
): ImageBitmap {
    val width = ceil(size.width + margin * 2f).toInt().coerceAtLeast(1)
    val height = ceil(size.height + margin * 2f).toInt().coerceAtLeast(1)
    val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ALPHA_8)
    val canvas = android.graphics.Canvas(bitmap)
    val outline = shape.createOutline(size, LayoutDirection.Ltr, density)
    val path = when (outline) {
        is Outline.Generic -> outline.path
        is Outline.Rectangle -> Path().apply { addRect(outline.rect) }
        is Outline.Rounded -> Path().apply { addRoundRect(outline.roundRect) }
    }
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLACK
        maskFilter = BlurMaskFilter(with(density) { shadow.radius.toPx() }, BlurMaskFilter.Blur.NORMAL)
    }
    canvas.save()
    canvas.translate(margin, margin)
    canvas.drawPath(path.asAndroidPath(), paint)
    canvas.restore()
    paint.maskFilter = null
    return bitmap.asImageBitmap()
}

private const val ShadowMarginFactor = 2f
