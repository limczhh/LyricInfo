package com.mediasession.metadata.debug.model

/** A display-safe value from MediaMetadata. Binary values are represented by a descriptor. */
data class MediaMetadataField(
    val key: String,
    val value: String?
)
