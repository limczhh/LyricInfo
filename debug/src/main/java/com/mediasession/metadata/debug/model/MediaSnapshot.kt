package com.mediasession.metadata.debug.model

import android.media.session.PlaybackState
import android.os.SystemClock
import org.json.JSONObject

/** One observation from one MediaSession acquisition path. */
data class MediaSnapshot(
    val source: String,
    val sessionId: String,
    val packageName: String,
    val sessionTag: String,
    val mediaId: String,
    val title: String,
    val artist: String,
    val album: String,
    val artwork: ByteArray? = null,
    val lyricInfo: String?,
    val playbackState: Int,
    val position: Long,
    val positionUpdateTime: Long,
    val playbackSpeed: Float,
    val duration: Long,
    val updatedAt: Long = System.currentTimeMillis(),
    val destroyed: Boolean = false
) {
    fun sourceKey(): String = "$source::$sessionId"

    /** Stable enough across SystemUI and NotificationListener observations. */
    fun logicalKey(): String = if (sessionTag.isNotBlank()) {
        "$packageName::$sessionTag"
    } else if (mediaId.isNotBlank()) {
        "$packageName::media::$mediaId"
    } else {
        "$packageName::$sessionId"
    }

    fun hasLyricInfo(): Boolean = !lyricInfo.isNullOrBlank()

    fun isAdvancing(): Boolean = playbackState == PlaybackState.STATE_PLAYING ||
        playbackState == PlaybackState.STATE_FAST_FORWARDING ||
        playbackState == PlaybackState.STATE_REWINDING

    fun positionAt(now: Long = SystemClock.elapsedRealtime()): Long {
        if (!isAdvancing() || positionUpdateTime <= 0L) {
            return clampPosition(position)
        }

        val speed = playbackSpeed.takeIf { it.isFinite() } ?: 1f
        val elapsed = (now - positionUpdateTime).coerceAtLeast(0L)
        return clampPosition(position + (elapsed * speed).toLong())
    }

    private fun clampPosition(value: Long): Long = value.coerceAtLeast(0L).let { current ->
        if (duration > 0L) current.coerceAtMost(duration) else current
    }

    fun toJson(): String = JSONObject().apply {
        put("source", source)
        put("sessionId", sessionId)
        put("packageName", packageName)
        put("sessionTag", sessionTag)
        put("mediaId", mediaId)
        put("title", title)
        put("artist", artist)
        put("album", album)
        if (lyricInfo == null) put("lyricInfo", JSONObject.NULL) else put("lyricInfo", lyricInfo)
        put("playbackState", playbackState)
        put("position", position)
        put("positionUpdateTime", positionUpdateTime)
        put("playbackSpeed", if (playbackSpeed.isFinite()) playbackSpeed else 1f)
        put("duration", duration)
        put("updatedAt", updatedAt)
        put("destroyed", destroyed)
    }.toString()

    companion object {
        fun fromJson(raw: String): MediaSnapshot? = runCatching {
            val json = JSONObject(raw)
            val lyricInfo = if (json.isNull("lyricInfo")) {
                null
            } else {
                json.optString("lyricInfo").takeIf { it.isNotBlank() }
            }
            MediaSnapshot(
                source = json.optString("source"),
                sessionId = json.optString("sessionId"),
                packageName = json.optString("packageName"),
                sessionTag = json.optString("sessionTag"),
                mediaId = json.optString("mediaId"),
                title = json.optString("title"),
                artist = json.optString("artist"),
                album = json.optString("album"),
                lyricInfo = lyricInfo,
                playbackState = json.optInt("playbackState", PlaybackState.STATE_NONE),
                position = json.optLong("position", 0L),
                positionUpdateTime = json.optLong("positionUpdateTime", 0L),
                playbackSpeed = json.optDouble("playbackSpeed", 1.0).toFloat(),
                duration = json.optLong("duration", 0L),
                updatedAt = json.optLong("updatedAt", System.currentTimeMillis()),
                destroyed = json.optBoolean("destroyed", false)
            ).takeIf { it.source.isNotBlank() && it.sessionId.isNotBlank() }
        }.getOrNull()
    }
}
