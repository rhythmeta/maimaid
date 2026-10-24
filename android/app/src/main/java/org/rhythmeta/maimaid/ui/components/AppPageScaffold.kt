package org.rhythmeta.maimaid.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold
import top.yukonga.miuix.kmp.basic.TopAppBar

@Composable
fun AppPageScaffold(
    title: String,
    pageBackground: Color,
    blurEnabled: Boolean,
    showTopBar: Boolean = true,
    largeTitle: Boolean = true,
    smallTitleVisible: Boolean? = null,
    topBarScrollBehavior: ScrollBehavior = MiuixScrollBehavior(),
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    bottomContent: @Composable () -> Unit = {},
    content: @Composable (PaddingValues, NestedScrollConnection) -> Unit,
) {
    val pageBackdrop = rememberLayerBackdrop()
    val topBarModifier = Modifier.drawPlainBackdrop(
        backdrop = pageBackdrop,
        shape = { TopBarBottomShape },
        effects = { if (blurEnabled) blur(24.dp.toPx()) },
        onDrawSurface = {
            drawRect(pageBackground.copy(alpha = if (blurEnabled) 0.52f else 1f))
        },
    ).clip(TopBarBottomShape)

    MiuixScaffold(
        modifier = modifier,
        containerColor = pageBackground,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            if (showTopBar && largeTitle) {
                TopAppBar(
                    title = title,
                    largeTitle = title,
                    modifier = topBarModifier,
                    color = Color.Transparent,
                    navigationIcon = navigationIcon,
                    actions = actions,
                    bottomContent = bottomContent,
                    scrollBehavior = topBarScrollBehavior,
                    defaultWindowInsetsPadding = true,
                )
            } else if (showTopBar && smallTitleVisible != null) {
                AnimatedSmallTopAppBar(
                    title = title,
                    titleVisible = smallTitleVisible,
                    modifier = topBarModifier,
                    color = Color.Transparent,
                    navigationIcon = navigationIcon,
                    actions = actions,
                    scrollBehavior = topBarScrollBehavior,
                )
            } else if (showTopBar) {
                top.yukonga.miuix.kmp.basic.SmallTopAppBar(
                    title = title,
                    navigationIcon = navigationIcon,
                    modifier = topBarModifier,
                    color = Color.Transparent,
                    actions = actions,
                    scrollBehavior = topBarScrollBehavior,
                    defaultWindowInsetsPadding = true,
                )
            }
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(pageBackdrop),
        ) {
            content(paddingValues, topBarScrollBehavior.nestedScrollConnection)
        }
    }
}
