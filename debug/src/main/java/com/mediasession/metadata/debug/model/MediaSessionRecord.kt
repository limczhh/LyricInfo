package com.mediasession.metadata.debug.model

import android.media.session.PlaybackState

/** UI-facing merged view of one logical MediaSession. */
data class MediaSessionRecord(
    val key: String,
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
    val updatedAt: Long,
    val ended: Boolean = false
) {
    fun hasLyricInfo(): Boolean = !lyricInfo.isNullOrBlank()

    fun isAdvancing(): Boolean = playbackState == PlaybackState.STATE_PLAYING ||
        playbackState == PlaybackState.STATE_FAST_FORWARDING ||
        playbackState == PlaybackState.STATE_REWINDING

    fun positionAt(now: Long): Long {
        if (!isAdvancing() || positionUpdateTime <= 0L) return clamp(position)
        val speed = playbackSpeed.takeIf { it.isFinite() } ?: 1f
        val elapsed = (now - positionUpdateTime).coerceAtLeast(0L)
        return clamp(position + (elapsed * speed).toLong())
    }

    private fun clamp(value: Long): Long = value.coerceAtLeast(0L).let {
        if (duration > 0L) it.coerceAtMost(duration) else it
    }
}
