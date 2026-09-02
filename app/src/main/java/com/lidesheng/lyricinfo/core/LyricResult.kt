package com.lidesheng.lyricinfo.core

/** Structured lyric data for JSON injection. */
data class LyricResult(
    val lyric: String,
    val rawLyric: String? = null,
    val translation: String? = null,
    val roma: String? = null
) {
    /** Prefer the enhanced representation when a consumer needs one lyric lane. */
    fun preferredLane(): String = rawLyric?.takeIf { it.isNotBlank() } ?: lyric
}
