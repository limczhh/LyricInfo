package com.mediasession.metadata.debug.ui.navigation

import androidx.compose.runtime.staticCompositionLocalOf
import top.yukonga.miuix.kmp.nav.core.NavKey

class Navigator(private val backStack: MutableList<NavKey>) {
    fun navigate(route: NavKey) {
        if (backStack.lastOrNull() != route) backStack.add(route)
    }

    fun pop() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }
}

val LocalNavigator = staticCompositionLocalOf<Navigator> {
    error("Navigator is not provided")
}
