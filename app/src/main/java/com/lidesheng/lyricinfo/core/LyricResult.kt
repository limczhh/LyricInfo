package com.lidesheng.lyricinfo.core

/**
 * Structured lyric data for JSON injection.
 *
 * @param lyric All lyrics (original + translation interleaved by timestamp)
 * @param format Format of lyrics: "lrc", "elrc", or "ttml"
 * @param translation Format indicator for translation lines: "lrc" or "elrc"
 */
data class LyricResult(
    val lyric: String,
    val format: String = "lrc",
    val translation: String = "lrc"
)
