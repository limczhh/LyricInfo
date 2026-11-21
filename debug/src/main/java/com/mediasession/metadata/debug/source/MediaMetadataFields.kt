package com.mediasession.metadata.debug.source

import android.media.MediaMetadata
import com.mediasession.metadata.debug.model.MediaMetadataField

/** Converts framework metadata into a serializable, UI-friendly field list. */
fun MediaMetadata.toDisplayFields(): List<MediaMetadataField> {
    return keySet()
        .sorted()
        .map { key ->
            MediaMetadataField(
                key = key,
                value = keyValue(key)
            )
        }
}

private fun MediaMetadata.keyValue(key: String): String? {
    runCatching { getText(key)?.toString() }.getOrNull()?.let { return it }
    runCatching { getBitmap(key) }.getOrNull()?.takeIf { !it.isRecycled }?.let { bitmap ->
        return "Bitmap(${bitmap.width}×${bitmap.height})"
    }
    runCatching { getRating(key) }.getOrNull()?.let { return it.toString() }
    return runCatching { getLong(key).toString() }.getOrDefault("0")
}

