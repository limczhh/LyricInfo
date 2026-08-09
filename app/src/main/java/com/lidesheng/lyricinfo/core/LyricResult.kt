package com.lidesheng.lyricinfo.core

/**
 * Structured lyric data for JSON injection.
 *
 * When [romaji] is non-blank, [lyric] lines sharing a timestamp are ordered as:
 * **translation → original → romaji**.
 *
 * When only translation is present (no romaji), order remains:
 * **original → translation** (legacy / HyperLyric-compatible).
 *
 * @param lyric All lyric lines interleaved by timestamp (roles above)
 * @param format Format of original lines: "lrc", "elrc", or "ttml"
 * @param translation Format of translation lines: "lrc" / "elrc", empty if none
 * @param romaji Format of romaji lines: "lrc" / "elrc", empty if none
 */
data class LyricResult(
    val lyric: String,
    val format: String = "lrc",
    val translation: String = "",
    val romaji: String = "",
)
