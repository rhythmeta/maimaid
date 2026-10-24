package org.rhythmeta.maimaid.ui.profile

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withTranslation
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.launch
import org.rhythmeta.maimaid.R
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun AvatarCropEditor(
    bitmap: Bitmap,
    onDismiss: () -> Unit,
    onApply: (Bitmap) -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MiuixTheme.colorScheme.background),
        ) {
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(top = 12.dp, start = 12.dp),
            ) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.action_cancel),
                )
            }
            Text(
                text = stringResource(R.string.avatar_editor_title),
                style = MiuixTheme.textStyles.title3,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 18.dp),
            )
            AvatarCropContent(
                bitmap = bitmap,
                onApply = onApply,
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
                    .padding(top = 76.dp),
            )
        }
    }
}

@Composable
private fun AvatarCropContent(
    bitmap: Bitmap,
    onApply: (Bitmap) -> Unit,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    var scale by remember(bitmap) { mutableFloatStateOf(1f) }
    var rotation by remember(bitmap) { mutableFloatStateOf(0f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val transformState = rememberTransformableState { zoom, pan, rotationChange ->
        val diameter = canvasSize.width.toFloat().coerceAtLeast(1f)
        val base = baseSize(bitmap, diameter)
        scale = (scale * zoom).coerceIn(minScale(base, diameter), MaximumScale)
        offset = clampOffset(offset + pan, base, diameter, scale)
        rotation = (rotation + rotationChange) % 360f
    }
    LaunchedEffect(bitmap, canvasSize) {
        if (canvasSize.width > 0) {
            val diameter = canvasSize.width.toFloat().coerceAtLeast(1f)
            scale = minScale(baseSize(bitmap, diameter), diameter)
            offset = Offset.Zero
            rotation = 0f
        }
    }
    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        val cropSize = minOf(maxWidth - 32.dp, maxHeight * 0.58f)
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.avatar_editor_hint),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(18.dp))
            Box(
                modifier = Modifier
                    .size(cropSize)
                    .clip(CircleShape)
                    .background(Color.Black)
                    .onSizeChanged { canvasSize = it }
                    .transformable(transformState),
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val diameter = size.minDimension
                    val base = baseSize(bitmap, diameter)
                    drawIntoCanvas { canvas ->
                        canvas.nativeCanvas.withTranslation(
                            size.width / 2f + offset.x,
                            size.height / 2f + offset.y,
                        ) {
                            rotate(rotation)
                            scale(scale, scale)
                            drawBitmap(
                                bitmap,
                                null,
                                RectF(-base.x / 2f, -base.y / 2f, base.x / 2f, base.y / 2f),
                                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
                            )
                        }
                    }
                    drawCircle(
                        Color.White.copy(alpha = 0.85f),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()),
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    text = stringResource(R.string.avatar_editor_reset),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        scope.launch {
                            val diameter = canvasSize.width.toFloat()
                            if (diameter > 0f) {
                                scale = minScale(baseSize(bitmap, diameter), diameter)
                                offset = Offset.Zero
                                rotation = 0f
                            }
                        }
                    },
                )
                Button(
                    onClick = {
                        val diameter = canvasSize.width.toFloat()
                        if (diameter > 0f) {
                            onApply(
                                renderAvatar(
                                    bitmap,
                                    baseSize(bitmap, diameter),
                                    scale,
                                    rotation,
                                    offset,
                                    diameter,
                                ),
                            )
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Icon(Icons.Rounded.Check, contentDescription = null)
                    Text(
                        text = stringResource(R.string.avatar_editor_apply),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

private fun baseSize(bitmap: Bitmap, diameter: Float): Offset {
    val ratio = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
    return if (ratio >= 1f) Offset(diameter * ratio, diameter) else Offset(diameter, diameter / ratio)
}

private fun minScale(base: Offset, diameter: Float): Float = (diameter / min(base.x, base.y)).coerceAtLeast(1f)

private fun clampOffset(value: Offset, base: Offset, diameter: Float, scale: Float): Offset {
    val x = max(base.x * scale / 2f - diameter / 2f, 0f)
    val y = max(base.y * scale / 2f - diameter / 2f, 0f)
    return Offset(value.x.coerceIn(-x, x), value.y.coerceIn(-y, y))
}

private fun renderAvatar(
    bitmap: Bitmap,
    base: Offset,
    scale: Float,
    rotation: Float,
    offset: Offset,
    diameter: Float,
): Bitmap {
    val output = createBitmap(AvatarExportSize, AvatarExportSize)
    val canvas = Canvas(output)
    val ratio = AvatarExportSize.toFloat() / diameter
    canvas.withTranslation(
        AvatarExportSize / 2f + offset.x * ratio,
        AvatarExportSize / 2f + offset.y * ratio,
    ) {
        rotate(rotation)
        scale(scale * ratio, scale * ratio)
        drawBitmap(
            bitmap,
            null,
            RectF(-base.x / 2f, -base.y / 2f, base.x / 2f, base.y / 2f),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
        )
    }
    return output
}

private const val MaximumScale = 4f
private const val AvatarExportSize = 512
