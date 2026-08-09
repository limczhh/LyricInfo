package com.lidesheng.lyricinfo.ui

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Listens to active [MediaController] sessions and extracts `lyricInfo` in real time.
 *
 * Requires a notification-listener [ComponentName] so [MediaSessionManager.getActiveSessions]
 * is permitted on modern Android.
 */
class MediaSessionTracker(
    private val context: Context,
    private val listenerComponent: ComponentName,
    private val listener: Listener,
) {
    interface Listener {
        fun onSessionUpdate(snapshot: SessionSnapshot)
        fun onNoSession()
        fun onError(message: String)
    }

    data class SessionSnapshot(
        val packageName: String,
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
        val positionMs: Long,
        val isPlaying: Boolean,
        val speed: Float,
        val lyricInfoRaw: String?,
        val payload: LyricInfoPayload?,
        val lines: List<LyricLine>,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private var sessionManager: MediaSessionManager? = null
    private var sessionsChangedListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private val controllers = mutableListOf<MediaController>()
    private var tickRunning = false

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            emitActive()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            emitActive()
            updateTick(state)
        }

        override fun onSessionDestroyed() {
            refreshSessions()
        }
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!tickRunning) return
            emitActive()
            val playing = controllers.any {
                it.playbackState?.state == PlaybackState.STATE_PLAYING
            }
            if (playing) {
                mainHandler.postDelayed(this, TICK_MS)
            } else {
                tickRunning = false
            }
        }
    }

    fun start() {
        if (sessionManager != null) return
        try {
            val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val onChange = MediaSessionManager.OnActiveSessionsChangedListener { list ->
                updateControllers(list)
            }
            manager.addOnActiveSessionsChangedListener(onChange, listenerComponent)
            sessionManager = manager
            sessionsChangedListener = onChange
            updateControllers(manager.getActiveSessions(listenerComponent))
            Log.i(TAG, "MediaSession tracker started")
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing notification listener permission", e)
            listener.onError("需要开启通知使用权才能读取 MediaSession")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start tracker", e)
            listener.onError(e.message ?: e.javaClass.simpleName)
        }
    }

    fun stop() {
        stopTick()
        unregisterControllers()
        sessionsChangedListener?.let { l ->
            try {
                sessionManager?.removeOnActiveSessionsChangedListener(l)
            } catch (_: Exception) {
            }
        }
        sessionsChangedListener = null
        sessionManager = null
    }

    fun refreshSessions() {
        val manager = sessionManager ?: return
        try {
            updateControllers(manager.getActiveSessions(listenerComponent))
        } catch (e: SecurityException) {
            listener.onError("通知使用权未授予或已失效")
        } catch (e: Exception) {
            Log.e(TAG, "refreshSessions failed", e)
        }
    }

    private fun updateControllers(list: List<MediaController>?) {
        if (list.isNullOrEmpty()) {
            unregisterControllers()
            stopTick()
            listener.onNoSession()
            return
        }

        val preferred = list.find {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: list.first()

        val same = controllers.singleOrNull()?.sessionToken == preferred.sessionToken
        if (!same) {
            unregisterControllers()
            controllers.add(preferred)
            preferred.registerCallback(controllerCallback, mainHandler)
            Log.i(TAG, "Tracking session: ${preferred.packageName}")
        }

        emitActive()
        updateTick(preferred.playbackState)
    }

    private fun unregisterControllers() {
        for (c in controllers) {
            try {
                c.unregisterCallback(controllerCallback)
            } catch (_: Exception) {
            }
        }
        controllers.clear()
    }

    private fun emitActive() {
        val controller = controllers.find {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: controllers.firstOrNull()

        if (controller == null) {
            listener.onNoSession()
            return
        }

        val metadata = controller.metadata
        val state = controller.playbackState
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().orEmpty()
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)?.trim().orEmpty()
        val album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM)?.trim().orEmpty()
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val position = state?.position ?: 0L
        val speed = state?.playbackSpeed ?: 1f
        val playing = state?.state == PlaybackState.STATE_PLAYING && speed > 0f

        val lyricInfoRaw = extractLyricInfo(metadata)
        val payload = LyricInfoJson.parsePayload(lyricInfoRaw)
        val lines = payload?.let { LyricInfoJson.parseLines(it) }.orEmpty()

        listener.onSessionUpdate(
            SessionSnapshot(
                packageName = controller.packageName.orEmpty(),
                title = title.ifBlank { payload?.songName.orEmpty() },
                artist = artist.ifBlank { payload?.artist.orEmpty() },
                album = album,
                durationMs = duration,
                positionMs = position.coerceAtLeast(0L),
                isPlaying = playing,
                speed = speed,
                lyricInfoRaw = lyricInfoRaw,
                payload = payload,
                lines = lines,
            )
        )
    }

    private fun extractLyricInfo(metadata: MediaMetadata?): String? {
        if (metadata == null) return null
        return try {
            metadata.description?.extras?.getString(KEY_LYRIC_INFO)
                ?: metadata.description?.extras?.getString(KEY_LYRIC_INFO_ALT)
                ?: metadata.getString(KEY_LYRIC_INFO)
                ?: metadata.getString(KEY_LYRIC_INFO_ALT)
                ?: readFromBundleReflection(metadata)
        } catch (e: Exception) {
            Log.w(TAG, "extractLyricInfo failed", e)
            null
        }
    }

    /**
     * LyricInfo module writes into MediaMetadata internal Bundle via reflection;
     * fall back to the same if public getters miss the custom key.
     */
    private fun readFromBundleReflection(metadata: MediaMetadata): String? {
        return try {
            val field = metadata.javaClass.getDeclaredField("mBundle")
            field.isAccessible = true
            val bundle = field.get(metadata) as? android.os.Bundle ?: return null
            bundle.getString(KEY_LYRIC_INFO) ?: bundle.getString(KEY_LYRIC_INFO_ALT)
        } catch (_: Exception) {
            null
        }
    }

    private fun updateTick(state: PlaybackState?) {
        val playing = state?.state == PlaybackState.STATE_PLAYING &&
            (state.playbackSpeed > 0f)
        if (playing) startTick() else stopTick()
    }

    private fun startTick() {
        if (tickRunning) return
        tickRunning = true
        mainHandler.postDelayed(tickRunnable, TICK_MS)
    }

    private fun stopTick() {
        tickRunning = false
        mainHandler.removeCallbacks(tickRunnable)
    }

    companion object {
        private const val TAG = "LyricInfoUI"
        private const val TICK_MS = 200L
        const val KEY_LYRIC_INFO = "lyricInfo"
        const val KEY_LYRIC_INFO_ALT = "lyricinfo"
    }
}
