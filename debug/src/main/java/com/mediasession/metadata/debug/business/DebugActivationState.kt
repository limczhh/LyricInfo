package com.mediasession.metadata.debug.business

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationManagerCompat
import java.util.concurrent.CopyOnWriteArraySet

/** State of the two independent media-session acquisition paths. */
data class DebugActivationSnapshot(
    val lspActive: Boolean = false,
    val notificationListenerEnabled: Boolean = false
) {
    val isWorking: Boolean
        get() = lspActive || notificationListenerEnabled
}

/** Owns capability state used by the UI without coupling it to observations. */
object DebugActivationState {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val observers = CopyOnWriteArraySet<(DebugActivationSnapshot) -> Unit>()

    @Volatile
    private var snapshot = DebugActivationSnapshot()

    fun current(): DebugActivationSnapshot = snapshot

    fun addObserver(observer: (DebugActivationSnapshot) -> Unit): () -> Unit {
        observers += observer
        observer(snapshot)
        return { observers -= observer }
    }

    fun setLspActive(active: Boolean) {
        update { it.copy(lspActive = active) }
    }

    fun refreshNotificationListener(context: Context) {
        val enabled = runCatching {
            NotificationManagerCompat.getEnabledListenerPackages(context)
                .contains(context.packageName)
        }.getOrDefault(false)
        update { it.copy(notificationListenerEnabled = enabled) }
    }

    private fun update(transform: (DebugActivationSnapshot) -> DebugActivationSnapshot) {
        val next = transform(snapshot)
        if (next == snapshot) return
        snapshot = next
        val notify = {
            observers.forEach { observer -> observer(next) }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            notify()
        } else {
            mainHandler.post(notify)
        }
    }
}
