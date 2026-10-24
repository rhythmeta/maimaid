package org.rhythmeta.maimaid.ui.catalog

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.currentCompositeKeyHashCode
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

internal class SongCoverSource(
    val songId: String,
    val identity: String,
    val cornerRadius: Dp,
) {
    var bounds by mutableStateOf<Rect?>(null)
    var cardBounds by mutableStateOf<Rect?>(null)
    var layoutWidth by mutableStateOf(0)
    var layoutHeight by mutableStateOf(0)
    var painter by mutableStateOf<Painter?>(null)
    var painterAlpha by mutableStateOf(1f)
    var painterSaturation by mutableStateOf(1f)
    var cardColor by mutableStateOf<Color?>(null)
    var cardBorderColor by mutableStateOf<Color?>(null)
    var cardBorderWidth by mutableStateOf(1.dp)
    var cardCornerRadius by mutableStateOf(0.dp)
    var bodyBounds by mutableStateOf<Rect?>(null)
    var bodySize by mutableStateOf(IntSize.Zero)
    var bodyContent by mutableStateOf<(@Composable (Boolean) -> Unit)?>(null)
}

internal class SongNavigationHost(
    val active: CatalogCoverTransition?,
    val openCover: (SongCoverSource) -> Unit,
)

internal val LocalSongNavigationHost = staticCompositionLocalOf<SongNavigationHost?> { null }

internal class SongCoverNavigation(
    val source: SongCoverSource,
    private val host: SongNavigationHost?,
    private val fallback: () -> Unit,
) {
    val modifier: Modifier
        get() = Modifier
            .onGloballyPositioned { coordinates ->
                source.bounds = Rect(
                    coordinates.localToRoot(Offset.Zero),
                    coordinates.localToRoot(Offset(coordinates.size.width.toFloat(), coordinates.size.height.toFloat())),
                )
                source.layoutWidth = coordinates.size.width
                source.layoutHeight = coordinates.size.height
            }
            .graphicsLayer {
                alpha = if (host?.active?.genericSource === source && host.active.hidesSource) 0f else 1f
            }

    val cardModifier: Modifier
        get() = Modifier
            .onGloballyPositioned { coordinates ->
                source.cardBounds = Rect(
                    coordinates.localToRoot(Offset.Zero),
                    coordinates.localToRoot(Offset(coordinates.size.width.toFloat(), coordinates.size.height.toFloat())),
                )
            }
            .graphicsLayer {
                alpha = if (host?.active?.genericSource === source && host.active.hidesSource) 0f else 1f
            }

    fun onPainter(painter: Painter?, alpha: Float = 1f, saturation: Float = 1f) {
        if (painter != null) {
            source.painter = painter
            source.painterAlpha = alpha
            source.painterSaturation = saturation
        }
    }

    fun open() {
        val bounds = source.bounds
        if (host != null && source.painter != null && bounds != null && bounds.width > 0f && bounds.height > 0f) {
            host.openCover(source)
        } else {
            fallback()
        }
    }
}

@Composable
internal fun rememberSongCoverNavigation(
    songId: String,
    onClick: () -> Unit,
    cornerRadius: Dp,
    key: Any? = songId,
    enabled: Boolean = true,
    cardColor: Color? = null,
    cardBorderColor: Color? = null,
    cardBorderWidth: Dp = 1.dp,
    cardCornerRadius: Dp = 14.dp,
): SongCoverNavigation {
    val host = LocalSongNavigationHost.current.takeIf { enabled }
    val identity = "${currentCompositeKeyHashCode}:$songId:$key"
    val localSource = remember(identity) { SongCoverSource(songId, identity, cornerRadius) }
    val source = host?.active?.genericSource?.takeIf { it.identity == identity } ?: localSource
    SideEffect {
        source.cardColor = cardColor
        source.cardBorderColor = cardBorderColor
        source.cardBorderWidth = cardBorderWidth
        source.cardCornerRadius = cardCornerRadius
    }
    return remember(source, host, onClick) { SongCoverNavigation(source, host, onClick) }
}

/** Retains the card layout so non-cover content can be drawn during the return handoff. */
@Composable
internal fun SongCardTransitionContent(
    navigation: SongCoverNavigation,
    content: @Composable (Boolean) -> Unit,
) {
    val latest by rememberUpdatedState(content)
    val retained: @Composable (Boolean) -> Unit = remember { { showCover -> latest(showCover) } }
    SideEffect { navigation.source.bodyContent = retained }
    Box(
        modifier = Modifier.onGloballyPositioned { coordinates ->
            navigation.source.bodyBounds = Rect(
                coordinates.localToRoot(Offset.Zero),
                coordinates.localToRoot(Offset(coordinates.size.width.toFloat(), coordinates.size.height.toFloat())),
            )
            navigation.source.bodySize = coordinates.size
        },
    ) {
        content(true)
    }
}

@Composable
internal fun SongCardContentOverlay(
    state: CatalogCoverTransition,
    progress: () -> Float,
    pageSize: () -> Size,
) {
    val source = state.genericSource ?: return
    val body = source.bodyContent ?: return
    if (source.bodySize.width <= 0 || source.bodySize.height <= 0) return
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .requiredSize(
                with(density) { source.bodySize.width.toDp() },
                with(density) { source.bodySize.height.toDp() },
            )
            .clearAndSetSemantics {}
            .graphicsLayer {
                val fraction = progress()
                val bounds = state.genericBodyBounds()
                if (bounds != null) {
                    alpha = catalogListContentAlpha(fraction)
                    if (state.usesListMotion) {
                        translationX = bounds.left
                        translationY = bounds.top + state.listContentTranslation(fraction) -
                            state.listContentTranslation(0f)
                    } else {
                        val coverBounds = state.detailBounds?.let { state.sharedCoverBounds(it, fraction) }
                            ?: state.fallbackCoverBounds(pageSize(), fraction)
                        transformOrigin = TransformOrigin(0f, 0f)
                        scaleX = coverBounds.width / size.width.coerceAtLeast(1f)
                        scaleY = coverBounds.height / size.height.coerceAtLeast(1f)
                        translationX = coverBounds.left
                        translationY = coverBounds.top
                        clip = true
                    }
                } else {
                    alpha = 0f
                }
            },
    ) {
        body(false)
    }
}
