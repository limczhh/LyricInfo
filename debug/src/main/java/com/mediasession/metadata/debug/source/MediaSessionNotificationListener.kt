package com.mediasession.metadata.debug.source

import android.content.ComponentName
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.mediasession.metadata.debug.bridge.DebugContract
import com.mediasession.metadata.debug.business.DebugForegroundState
import com.mediasession.metadata.debug.business.MediaSessionRepository
import java.util.LinkedHashMap

/** Acquires media sessions through the user-granted NotificationListenerService path. */
class MediaSessionNotificationListener : NotificationListenerService() {
    companion object {
        private const val TAG = "MediaSessionNotification"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val entries = LinkedHashMap<String, MediaController>()
    private val callbacks = LinkedHashMap<String, MediaController.Callback>()
    private var removeForegroundListener: (() -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        removeForegroundListener = DebugForegroundState.addListener { foreground ->
            if (foreground) {
                mainHandler.post(::refresh)
            } else {
                mainHandler.post {
                    unregisterAll()
                    MediaSessionRepository.clearSource(DebugContract.SOURCE_NOTIFICATION)
                }
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        MediaSessionRepository.clearSource(DebugContract.SOURCE_NOTIFICATION)
        if (DebugForegroundState.isForeground) refresh()
    }

    override fun onListenerDisconnected() {
        unregisterAll()
        MediaSessionRepository.clearSource(DebugContract.SOURCE_NOTIFICATION)
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (DebugForegroundState.isForeground) refresh()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (DebugForegroundState.isForeground) refresh()
    }

    override fun onDestroy() {
        removeForegroundListener?.invoke()
        removeForegroundListener = null
        unregisterAll()
        MediaSessionRepository.clearSource(DebugContract.SOURCE_NOTIFICATION)
        super.onDestroy()
    }

    private fun refresh() {
        if (!DebugForegroundState.isForeground) return
        val manager = getSystemService(MediaSessionManager::class.java) ?: return
        val current = runCatching {
            manager.getActiveSessions(
                ComponentName(this, MediaSessionNotificationListener::class.java)
            )
        }.getOrElse {
            Log.w(TAG, "Unable to read active media sessions", it)
            return
        }

        val currentIds = current.mapTo(hashSetOf(), MediaSnapshotFactory::sessionId)
        entries.keys.toList()
            .filterNot { it in currentIds }
            .forEach(::remove)

        current.forEach { controller ->
            val id = MediaSnapshotFactory.sessionId(controller)
            if (entries[id] == null) register(id, controller)
            publish(controller)
        }
    }

    private fun register(id: String, controller: MediaController) {
        val callback = object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: android.media.MediaMetadata?) {
                publish(controller, metadataOverride = metadata)
            }

            override fun onPlaybackStateChanged(state: android.media.session.PlaybackState?) {
                publish(controller, playbackStateOverride = state)
            }

            override fun onSessionDestroyed() {
                remove(id)
            }
        }
        runCatching { controller.registerCallback(callback, mainHandler) }
            .onFailure {
                Log.w(TAG, "Unable to register media callback", it)
                return
            }
        entries[id] = controller
        callbacks[id] = callback
    }

    private fun publish(
        controller: MediaController,
        metadataOverride: android.media.MediaMetadata? = null,
        playbackStateOverride: android.media.session.PlaybackState? = null
    ) {
        if (!DebugForegroundState.isForeground) return
        val snapshot = runCatching {
            MediaSnapshotFactory.fromController(
                source = DebugContract.SOURCE_NOTIFICATION,
                controller = controller,
                metadataOverride = metadataOverride,
                playbackStateOverride = playbackStateOverride
            )
        }.getOrElse {
            Log.w(TAG, "Unable to build media snapshot", it)
            return
        }
        MediaSessionRepository.update(snapshot)
    }

    private fun remove(id: String) {
        val controller = entries.remove(id)
        callbacks.remove(id)?.let { callback ->
            if (controller != null) runCatching { controller.unregisterCallback(callback) }
        }
        MediaSessionRepository.removeSourceSession(DebugContract.SOURCE_NOTIFICATION, id)
    }

    private fun unregisterAll() {
        callbacks.keys.toList().forEach(::remove)
        entries.clear()
        callbacks.clear()
    }
}
