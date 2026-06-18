package com.lidesheng.lyricinfo.providers.qishui

import android.util.Log

/**
 * KRC (Kugou/KTV) lyric format parser.
 *
 * KRC format:
 * [startMs,durationMs]text<charOffsetMs,charDurationMs,pitch>text...
 *
 * Word-level timestamps are relative to the line start time.
 * Untagged characters inherit timing from the previous word's end.
 *
 * Output: Enhanced LRC (elrc) format with optional merged translations.
 */
object KrcParser {

    private const val TAG = "KrcParser"

    // Line header: [startMs,durationMs]
    private val LINE_HEADER = Regex("""\[(\d+),(\d+)]""")

    // Word tag: <offsetMs,durationMs,pitch>
    private val WORD_TAG = Regex("""<(\d+),(\d+),\d+>""")

    // Line-level LRC time: [mm:ss.xxx] or [mm:ss.xx]
    private val LRC_TIME_TAG = Regex("""\[\d{2}:\d{2}\.\d{2,3}]""")

    /**
     * Parse lyrics and merge with translation, returning elrc format.
     * Supports both KRC and LRC types for original and translation.
     *
     * @param type Original lyric type ("krc" or "lrc")
     * @param content Raw lyric content
     * @param transType Translation lyric type ("krc" or "lrc", optional)
     * @param transContent Raw translation content (optional)
     * @return Merged elrc string, or null if parsing fails
     */
    fun parseAndMerge(type: String?, content: String?, transType: String?, transContent: String?): String? {
        if (content.isNullOrBlank()) return null

        val original = when (type?.lowercase()) {
            "krc" -> parseKrcToElrc(content)
            "lrc" -> parseLrcToElrc(content)
            else -> return null
        } ?: return null

        if (transContent.isNullOrBlank()) return original

        val transLines = when (transType?.lowercase()) {
            "krc" -> parseKrcToLines(transContent)
            "lrc" -> parseLrcToLines(transContent)
            else -> emptyList()
        }
        if (transLines.isEmpty()) return original

        return merge(original, transLines)
    }

    /**
     * Parse KRC content to elrc format string.
     */
    fun parseKrcToElrc(content: String?): String? {
        val lines = parseKrcToLines(content)
        if (lines.isEmpty()) return null

        val output = StringBuilder()
        for (line in lines) {
            output.append(formatLrcTime(line.startMs))
            for (word in line.words) {
                if (word.text.isNotEmpty()) {
                    output.append(formatElrcTime(word.beginMs))
                        .append(word.text)
                }
            }
            output.append('\n')
        }
        return output.toString().trimEnd()
    }

    /**
     * Parse LRC content to elrc format string (no word-level tags).
     */
    private fun parseLrcToElrc(content: String?): String? {
        val lines = parseLrcToLines(content)
        if (lines.isEmpty()) return null

        val output = StringBuilder()
        for (line in lines) {
            output.append(formatLrcTime(line.startMs))
                .append(line.text)
                .append('\n')
        }
        return output.toString().trimEnd()
    }

    private fun parseKrcToLines(content: String?): List<ParsedLine> {
        if (content.isNullOrBlank()) return emptyList()
        val result = mutableListOf<ParsedLine>()

        for (rawLine in content.lineSequence()) {
            if (rawLine.isBlank()) continue

            val headerMatch = LINE_HEADER.find(rawLine) ?: continue
            val startMs = headerMatch.groupValues[1].toLong()
            val durationMs = headerMatch.groupValues[2].toLong()
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
                        words.add(Word(wordBeginMs, wordDurMs, wordText))
                        prevEndMs = wordBeginMs + wordDurMs
                        pos = tagMatch.range.last + 1
                        continue
                    }
                }

                // No tag follows — untagged word
                val inferredBegin = prevEndMs ?: startMs
                words.add(Word(inferredBegin, 0L, wordText))
            }

            val text = body.replace(WORD_TAG, "")
            result.add(ParsedLine(startMs, durationMs, text, words))
        }

        return result
    }

    private fun parseLrcToLines(content: String?): List<ParsedLine> {
        if (content.isNullOrBlank()) return emptyList()
        val result = mutableListOf<ParsedLine>()

        for (rawLine in content.lineSequence()) {
            if (rawLine.isBlank()) continue

            val timeMatch = LRC_TIME_TAG.find(rawLine) ?: continue
            val startMs = extractLineTimestampMs(timeMatch.value)
            if (startMs < 0) continue
            val text = rawLine.substring(timeMatch.range.last + 1)

            result.add(ParsedLine(startMs, 0L, text, emptyList()))
        }

        return result
    }

    /**
     * Merge original elrc lyrics with translation lines.
     * Translation lines are inserted after their matching original line (within 50ms).
     * Duplicate translations (same text as original) are skipped.
     */
    private fun merge(original: String, transLines: List<ParsedLine>): String {
        val transTimes = transLines.map { it.startMs }.sorted()
        var matched = 0
        val output = StringBuilder()

        for (line in original.lines()) {
            output.appendLine(line)
            val tsMs = extractLineTimestampMs(line)
            if (tsMs < 0) continue

            val closest = transTimes.minByOrNull { kotlin.math.abs(it - tsMs) }
            if (closest == null || kotlin.math.abs(closest - tsMs) > 50) continue

            val transLine = transLines.firstOrNull { it.startMs == closest } ?: continue
            // Strip word-level tags before comparing to detect duplicate translations
            val originalText = line.substringAfter("]").replace(WORD_TAG, "")
            if (transLine.text == originalText) continue

            matched++
            output.appendLine("${formatLrcTime(tsMs)}${transLine.text}")
        }

        Log.d(TAG, "merge: original ${original.lines().count { it.isNotBlank() }} lines, " +
            "translation ${transLines.size} lines, matched $matched")
        return output.toString().trimEnd()
    }

    private fun extractLineTimestampMs(line: String): Long {
        val match = LRC_TIME_TAG.find(line) ?: return -1
        val tag = match.value
        val inner = tag.substring(1, tag.length - 1)
        val parts = inner.split(":")
        if (parts.size != 2) return -1
        val min = parts[0].toLongOrNull() ?: return -1
        val secParts = parts[1].split(".")
        if (secParts.size != 2) return -1
        val sec = secParts[0].toLongOrNull() ?: return -1
        val ms = when (secParts[1].length) {
            2 -> (secParts[1].toLongOrNull() ?: 0) * 10
            3 -> secParts[1].toLongOrNull() ?: 0
            else -> 0
        }
        return min * 60000 + sec * 1000 + ms
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
        val durationMs: Long,
        val text: String,
        val words: List<Word>
    )

    private data class Word(
        val beginMs: Long,
        val durationMs: Long,
        val text: String
    )
}
