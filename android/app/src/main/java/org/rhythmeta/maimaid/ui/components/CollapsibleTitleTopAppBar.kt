package org.rhythmeta.maimaid.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.TopAppBar

/** Keeps the search field composed and focused while the title and actions slide out of its way. */
@Composable
internal fun CollapsibleTitleTopAppBar(
    title: String,
    titleVisible: Boolean,
    modifier: Modifier,
    navigationIcon: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit,
    scrollBehavior: ScrollBehavior,
    bottomContent: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal))
            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top)),
    ) {
        AnimatedVisibility(
            visible = titleVisible,
            enter = expandVertically(
                animationSpec = tween(300, easing = LinearOutSlowInEasing),
                expandFrom = Alignment.Top,
            ) + fadeIn(tween(200)),
            exit = shrinkVertically(
                animationSpec = tween(300, easing = LinearOutSlowInEasing),
                shrinkTowards = Alignment.Top,
            ) + fadeOut(tween(200)),
            modifier = if (titleVisible) Modifier else Modifier.clearAndSetSemantics {},
        ) {
            TopAppBar(
                title = title,
                color = Color.Transparent,
                navigationIcon = navigationIcon,
                actions = actions,
                scrollBehavior = scrollBehavior,
            )
        }
        bottomContent()
    }
}
