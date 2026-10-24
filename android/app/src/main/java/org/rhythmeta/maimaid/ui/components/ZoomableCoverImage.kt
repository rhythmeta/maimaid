package org.rhythmeta.maimaid.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest

/** Keeps recycled grid slots visually stable while their cover request changes size. */
@Composable
internal fun ZoomableCoverImage(
    model: Any?,
    imageSize: Int,
    animateCoverChanges: Boolean,
    modifier: Modifier = Modifier,
    onPainterChanged: ((Painter?) -> Unit)? = null,
    fallbackPainter: Painter? = null,
) {
    val context = LocalContext.current
    val request = remember(context, model, imageSize) {
        ImageRequest.Builder(context)
            .data(model)
            .size(imageSize)
            .crossfade(false)
            .build()
    }
    val imagePainter = rememberAsyncImagePainter(request)
    var displayedCover by remember { mutableStateOf(fallbackPainter) }
    val loadedCover = (imagePainter.state as? AsyncImagePainter.State.Success)?.painter
    LaunchedEffect(loadedCover, fallbackPainter) {
        if (loadedCover != null) {
            displayedCover = loadedCover
        } else if (fallbackPainter != null) {
            displayedCover = fallbackPainter
        }
    }
    val visibleCover = if (fallbackPainter != null) loadedCover ?: fallbackPainter else displayedCover
    SideEffect { onPainterChanged?.invoke(visibleCover) }
    Crossfade(
        targetState = visibleCover,
        animationSpec = if (animateCoverChanges) tween(180) else snap(),
        modifier = modifier,
        label = "coverReplacement",
    ) { cover ->
        if (cover != null) {
            Image(
                painter = cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
