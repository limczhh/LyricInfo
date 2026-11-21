package com.mediasession.metadata.debug.business

import java.util.concurrent.CopyOnWriteArraySet

/** Process-wide gate for acquisition while the debug Activity is visible. */
object DebugForegroundState {
    private val listeners = CopyOnWriteArraySet<(Boolean) -> Unit>()

    @Volatile
    var isForeground: Boolean = false
        private set

    fun setForeground(foreground: Boolean) {
        if (isForeground == foreground) return
        isForeground = foreground
        listeners.forEach { listener -> listener(foreground) }
    }

    fun addListener(listener: (Boolean) -> Unit): () -> Unit {
        listeners += listener
        listener(isForeground)
        return { listeners -= listener }
    }
}
