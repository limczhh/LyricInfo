package com.mediasession.metadata.debug.ui.page

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.glass.GlassTopAppBarDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun MediaSessionTopAppBar(
    title: String,
    isContentScrolled: Boolean,
    scrollBehavior: ScrollBehavior,
    navigationIcon: @Composable () -> Unit = {},
    bottomContent: @Composable () -> Unit = {}
) {
    val surfaceColor = MiuixTheme.colorScheme.surface
    val bandBrush = remember(surfaceColor) {
        GlassTopAppBarDefaults.bandBrush(surfaceColor)
    }

    GlassTopAppBar(
        title = title,
        isContentScrolled = isContentScrolled,
        expandable = true,
        progressiveBlur = false,
        backdrop = null,
        scrollBehavior = scrollBehavior,
        titleBlurRadius = 0.dp,
        backgroundAlpha = { 1f },
        bandBrush = bandBrush,
        bandOverhang = GlassTopAppBarDefaults.BandOverhang,
        navigationIcon = navigationIcon,
        bottomContent = bottomContent
    )
}
