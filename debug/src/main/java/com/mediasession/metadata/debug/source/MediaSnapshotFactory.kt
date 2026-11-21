package com.mediasession.metadata.debug.source

import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Build
import java.io.ByteArrayOutputStream
import java.util.WeakHashMap
import com.mediasession.metadata.debug.model.MediaSnapshot

object MediaSnapshotFactory {
    private val artworkCache = WeakHashMap<MediaController, CachedArtwork>()
    private val artworkLock = Any()

    private data class CachedArtwork(
        val metadata: MediaMetadata?,
        val bytes: ByteArray?
    )

    fun fromController(
        source: String,
        controller: MediaController,
        metadataOverride: MediaMetadata? = null,
        playbackStateOverride: PlaybackState? = null,
        destroyed: Boolean = false
    ): MediaSnapshot {
        val metadata = metadataOverride ?: runCatching { controller.metadata }.getOrNull()
        val playbackState = playbackStateOverride
            ?: runCatching { controller.playbackState }.getOrNull()
        val packageName = runCatching { controller.packageName }.getOrDefault("")
        val sessionId = sessionId(controller)
        return MediaSnapshot(
            source = source,
            sessionId = sessionId,
            packageName = packageName,
            sessionTag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching { controller.tag }.getOrDefault("").orEmpty()
            } else {
                ""
            },
            mediaId = metadata.text(MediaMetadata.METADATA_KEY_MEDIA_ID),
            title = metadata.text(MediaMetadata.METADATA_KEY_TITLE).ifBlank {
                metadata.text(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            },
            artist = metadata.text(MediaMetadata.METADATA_KEY_ARTIST),
            album = metadata.text(MediaMetadata.METADATA_KEY_ALBUM),
            artwork = artworkBytes(controller, metadata),
            lyricInfo = metadata?.getString(LYRIC_INFO_KEY)?.takeIf { it.isNotBlank() },
            playbackState = playbackState?.state ?: PlaybackState.STATE_NONE,
            position = playbackState?.position ?: 0L,
            positionUpdateTime = playbackState?.lastPositionUpdateTime ?: 0L,
            playbackSpeed = playbackState?.playbackSpeed ?: 1f,
            duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
            destroyed = destroyed
        )
    }

    fun sessionId(controller: MediaController): String =
        runCatching { controller.sessionToken.toString() }
            .getOrDefault("")
            .takeIf { it.isNotBlank() && it != "null" }
            ?: "${controller.packageName}:${controller.hashCode()}"

    private fun MediaMetadata?.text(key: String): String =
        this?.getText(key)?.toString()?.trim().orEmpty()

    private fun artworkBytes(controller: MediaController, metadata: MediaMetadata?): ByteArray? {
        synchronized(artworkLock) {
            artworkCache[controller]?.takeIf { it.metadata === metadata }?.let { return it.bytes }
        }

        val bytes = metadata?.let { value ->
            val bitmap = runCatching {
                value.getBitmap(MediaMetadata.METADATA_KEY_ART)
                    ?: value.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            }.getOrNull()?.takeIf { !it.isRecycled }
                ?: return@let null
            runCatching {
                val maxEdge = maxOf(bitmap.width, bitmap.height)
                val scale = if (maxEdge > MAX_ARTWORK_EDGE) {
                    MAX_ARTWORK_EDGE.toFloat() / maxEdge.toFloat()
                } else {
                    1f
                }
                val scaled = if (scale < 1f) {
                    Bitmap.createScaledBitmap(
                        bitmap,
                        (bitmap.width * scale).toInt().coerceAtLeast(1),
                        (bitmap.height * scale).toInt().coerceAtLeast(1),
                        true
                    )
                } else {
                    bitmap
                }
                ByteArrayOutputStream().use { output ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, 88, output)
                    output.toByteArray().takeIf { it.size <= MAX_ARTWORK_BYTES }
                }
            }.getOrNull()
        }

        synchronized(artworkLock) {
            artworkCache[controller] = CachedArtwork(metadata, bytes)
        }
        return bytes
    }

    private const val LYRIC_INFO_KEY = "lyricInfo"
    private const val MAX_ARTWORK_EDGE = 192
    private const val MAX_ARTWORK_BYTES = 256 * 1024
}
