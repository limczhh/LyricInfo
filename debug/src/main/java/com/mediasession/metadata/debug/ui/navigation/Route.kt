package com.mediasession.metadata.debug.ui.navigation

import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.nav.core.NavKey

@Serializable
sealed interface Route : NavKey {
    @Serializable
    data object Sessions : Route

    @Serializable
    data class Detail(val sessionKey: String) : Route
}
