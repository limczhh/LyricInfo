package com.mediasession.metadata.debug.model

data class LyricWord(
    val begin: Long,
    val end: Long,
    val text: String
)

data class LyricLine(
    val begin: Long,
    val end: Long,
    val text: String,
    val words: List<LyricWord> = emptyList(),
    val translation: String? = null,
    val roma: String? = null
)

enum class LyricInfoFieldPresence {
    ABSENT,
    EMPTY,
    VALUE
}

data class LyricInfoField(
    val key: String,
    val presence: LyricInfoFieldPresence,
    val value: String? = null,
    val isLyricContent: Boolean = false
)

data class LyricInfoDocument(
    val songName: String,
    val artist: String,
    val album: String,
    val songId: String,
    val songMid: String,
    val format: String,
    val usedRawLyric: Boolean,
    val hasTranslation: Boolean,
    val hasRoma: Boolean,
    val lines: List<LyricLine>,
    val fields: List<LyricInfoField> = emptyList()
)
