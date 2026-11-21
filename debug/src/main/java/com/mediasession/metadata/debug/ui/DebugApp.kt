package com.mediasession.metadata.debug.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import com.mediasession.metadata.debug.ui.navigation.LocalNavigator
import com.mediasession.metadata.debug.ui.navigation.Navigator
import com.mediasession.metadata.debug.ui.navigation.Route
import com.mediasession.metadata.debug.ui.page.MediaSessionDetailPage
import com.mediasession.metadata.debug.ui.page.MediaSessionsPage

@Composable
fun DebugApp() {
    val themeController = remember {
        ThemeController(colorSchemeMode = ColorSchemeMode.System)
    }
    MiuixTheme(controller = themeController) {
        DebugNavigation()
    }
}

@Composable
private fun DebugNavigation() {
    val backStack = rememberNavBackStack<Route>(Route.Sessions)
    val navigator = remember { Navigator(backStack) }
    val cornerRadius = rememberNavSystemCornerRadius()

    CompositionLocalProvider(LocalNavigator provides navigator) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MiuixTheme.colorScheme.surface)
        ) {
            NavDisplay(
                backStack = backStack,
                onBack = navigator::pop,
                effects = NavDisplayEffects(cornerClipRadius = cornerRadius)
            ) {
                entry<Route.Sessions> {
                    MediaSessionsPage(onOpen = { navigator.navigate(Route.Detail(it)) })
                }
                entry<Route.Detail> { route ->
                    MediaSessionDetailPage(
                        sessionKey = route.sessionKey,
                        onBack = navigator::pop
                    )
                }
            }
        }
    }
}

