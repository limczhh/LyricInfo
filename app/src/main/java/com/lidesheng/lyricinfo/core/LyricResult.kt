package com.lidesheng.lyricinfo.core

/** Structured lyric data for JSON injection. */
data class LyricResult(
    val lyric: String,
    val rawLyric: String? = null,
    val translation: String? = null,
    val roma: String? = null,
    /** Optional second original timeline used as a text bridge; never part of public JSON. */
    val referenceLyric: String? = null
) {
    /** Called when a source is loaded/captured, before it enters a publication cache. */
    internal fun prepare(): PreparedLyric = LyricLaneAligner.prepare(this)

    /** Prefer the enhanced representation when a consumer needs one lyric lane. */
    fun preferredLane(): String = rawLyric?.takeIf { it.isNotBlank() } ?: lyric
}

/** Publication data is prepared explicitly; serialization cannot trigger matching. */
@ConsistentCopyVisibility
data class PreparedLyric internal constructor(
    val source: LyricResult,
    val lyric: String,
    val rawLyric: String?,
    val translation: String?,
    val roma: String?
)
