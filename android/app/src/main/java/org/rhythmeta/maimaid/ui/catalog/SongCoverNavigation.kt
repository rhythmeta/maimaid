package org.rhythmeta.maimaid.ui.catalog

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.currentCompositeKeyHashCode
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp

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
    var cardColor by mutableStateOf<Color?>(null)
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

    fun onPainter(painter: Painter?) {
        if (painter != null) source.painter = painter
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
): SongCoverNavigation {
    val host = LocalSongNavigationHost.current.takeIf { enabled }
    val identity = "${currentCompositeKeyHashCode}:$songId:$key"
    val localSource = remember(identity) { SongCoverSource(songId, identity, cornerRadius) }
    val source = host?.active?.genericSource?.takeIf { it.identity == identity } ?: localSource
    SideEffect { source.cardColor = cardColor }
    return remember(source, host, onClick) { SongCoverNavigation(source, host, onClick) }
}
