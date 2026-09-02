package com.lidesheng.lyricinfo.providers.qishui

import com.lidesheng.lyricinfo.core.LyricNormalizer
import com.lidesheng.lyricinfo.core.LyricResult

/**
 * KRC (Kugou/KTV) lyric format parser.
 *
 * KRC format:
 * [startMs,durationMs]text<charOffsetMs,charDurationMs,pitch>text...
 *
 * Word-level timestamps are relative to the line start time.
 * Untagged characters inherit timing from the previous word's end.
 *
 * Output: independent line-level and optional enhanced original lanes.
 */
object KrcParser {

    // Line header: [startMs,durationMs]
    private val LINE_HEADER = Regex("""\[(\d+),(\d+)]""")

    // Word tag: <offsetMs,durationMs,pitch>
    private val WORD_TAG = Regex("""<(\d+),(\d+),\d+>""")

    // Line-level LRC time: [mm:ss.xxx] or [mm:ss.xx]
    private val LRC_TIME_TAG = Regex("""\[\d{2}:\d{2}\.\d{2,3}]""")

    /** Parse one lyric lane without combining it with any other lane. */
    fun parse(type: String?, content: String?): LyricResult? {
        if (content.isNullOrBlank()) return null

        return when (type?.lowercase()) {
            "krc" -> parseKrcResult(content)
            "lrc" -> parseLrcResult(content)
            "elrc" -> LyricNormalizer.normalize(content)
            else -> null
        }
    }

    private fun parseKrcResult(content: String): LyricResult? {
        val lines = parseKrcToLines(content)
        if (lines.isEmpty()) return null

        val lyric = lines.joinToString("\n") { line ->
            "${formatLrcTime(line.startMs)}${line.text}"
        }.trim()
        if (lyric.isBlank()) return null

        val hasTimedWords = lines.any { line ->
            line.words.any { it.timed && it.text.isNotEmpty() }
        }
        val rawLyric = if (hasTimedWords) {
            renderKrc(lines)
        } else {
            null
        }
        return LyricResult(lyric = lyric, rawLyric = rawLyric)
    }

    private fun parseLrcResult(content: String): LyricResult? {
        LyricNormalizer.normalize(content)
            ?.takeIf { it.rawLyric != null }
            ?.let { return it }

        val lines = parseLrcToLines(content)
        if (lines.isEmpty()) return null
        val lyric = lines.joinToString("\n") { line ->
            "${formatLrcTime(line.startMs)}${line.text}"
        }.trim()
        return lyric.takeIf { it.isNotBlank() }?.let { LyricResult(lyric = it) }
    }

    private fun renderKrc(lines: List<ParsedLine>): String {
        return lines.joinToString("\n") { line ->
            buildString {
                append(formatLrcTime(line.startMs))
                for (word in line.words) {
                    if (word.text.isEmpty()) continue
                    if (word.timed) append(formatElrcTime(word.beginMs))
                    append(word.text)
                }
            }
        }.trim()
    }

    private fun parseKrcToLines(content: String?): List<ParsedLine> {
        if (content.isNullOrBlank()) return emptyList()
        val result = mutableListOf<ParsedLine>()

        for (rawLine in content.lineSequence()) {
            if (rawLine.isBlank()) continue

            val headerMatch = LINE_HEADER.find(rawLine) ?: continue
            val startMs = headerMatch.groupValues[1].toLong()
            val body = rawLine.substring(headerMatch.range.last + 1)

            val words = mutableListOf<Word>()
            var pos = 0
            var prevEndMs: Long? = null

            while (pos < body.length) {
                // Skip standalone tags (no preceding text)
                if (body[pos] == '<') {
                    val tagMatch = WORD_TAG.find(body, pos)
                    if (tagMatch != null && tagMatch.range.first == pos) {
                        pos = tagMatch.range.last + 1
                        continue
                    }
                }

                // Accumulate all non-'<' characters as one word
                val wordStart = pos
                while (pos < body.length && body[pos] != '<') {
                    pos++
                }
                val wordText = body.substring(wordStart, pos)

                // Check if followed by a tag
                if (pos < body.length) {
                    val tagMatch = WORD_TAG.find(body, pos)
                    if (tagMatch != null && tagMatch.range.first == pos) {
                        val offsetMs = tagMatch.groupValues[1].toLong()
                        val wordDurMs = tagMatch.groupValues[2].toLong()
                        val wordBeginMs = startMs + offsetMs
                        words.add(Word(wordBeginMs, wordText, timed = true))
                        prevEndMs = wordBeginMs + wordDurMs
                        pos = tagMatch.range.last + 1
                        continue
                    }
                }

                // No tag follows — untagged word
                val inferredBegin = prevEndMs ?: startMs
                words.add(Word(inferredBegin, wordText, timed = false))
            }

            val text = body.replace(WORD_TAG, "")
            result.add(ParsedLine(startMs, text, words))
        }

        return result
    }

    private fun parseLrcToLines(content: String?): List<ParsedLine> {
        if (content.isNullOrBlank()) return emptyList()
        val result = mutableListOf<ParsedLine>()

        for (rawLine in content.lineSequence()) {
            if (rawLine.isBlank()) continue

            val timeMatch = LRC_TIME_TAG.find(rawLine) ?: continue
            val startMs = parseTimestampMs(timeMatch.value)
            if (startMs < 0) continue
            val text = rawLine.substring(timeMatch.range.last + 1)

            result.add(ParsedLine(startMs, text, emptyList()))
        }

        return result
    }

    private fun parseTimestampMs(tag: String): Long {
        val inner = tag.substring(1, tag.length - 1)
        val parts = inner.split(":")
        if (parts.size != 2) return -1
        val minutes = parts[0].toLongOrNull() ?: return -1
        val seconds = parts[1].split(".")
        if (seconds.size != 2) return -1
        val second = seconds[0].toLongOrNull() ?: return -1
        val millis = when (seconds[1].length) {
            2 -> (seconds[1].toLongOrNull() ?: return -1) * 10
            3 -> seconds[1].toLongOrNull() ?: return -1
            else -> return -1
        }
        return minutes * 60000 + second * 1000 + millis
    }

    /** Line-level time: [mm:ss.xxx] */
    private fun formatLrcTime(ms: Long): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        val millis = ms % 1000
        return "[%02d:%02d.%03d]".format(min, sec, millis)
    }

    /** Word-level time: <mm:ss.xxx> */
    private fun formatElrcTime(ms: Long): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        val millis = ms % 1000
        return "<%02d:%02d.%03d>".format(min, sec, millis)
    }

    private data class ParsedLine(
        val startMs: Long,
        val text: String,
        val words: List<Word>
    )

    private data class Word(
        val beginMs: Long,
        val text: String,
        val timed: Boolean
    )
}
