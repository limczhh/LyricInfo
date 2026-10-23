package com.lidesheng.lyricinfo.core

import java.util.Locale

/**
 * Normalizes lyrics from various private formats to a standard line LRC lane
 * and, when available, a trusted enhanced original lane.
 *
 * Supported input formats:
 * - Word-level LRC: [mm:ss.xxx]word[mm:ss.xxx]word... (inline timestamps)
 * - YRC (Netease): [startMs,dur](offset,dur,flags)text...
 * - QRC (QQ Music): [startMs,dur]text(wordStart,wordDur)...
 * - Enhanced LRC: already in elrc format with <time>tags
 * - Standard LRC: [mm:ss.xx]text
 */
object LyricNormalizer {

    // [startMs,durationMs] — used by YRC and QRC
    private val MS_LINE_PATTERN = Regex("""^\[(\d+)\s*,\s*(\d+)](.*)$""")
    private val YRC_WORD_PATTERN = Regex(
        """\((\d+)\s*,\s*(\d+)(?:\s*,\s*\d+)?\)(.*?)(?=\(\d+\s*,\s*\d+(?:\s*,\s*\d+)?\)|$)"""
    )
    private val QRC_WORD_PATTERN = Regex("""\((\d+)\s*,\s*(\d+)\)""")
    private val KRC_WORD_PATTERN = Regex("""<\d+\s*,\s*\d+\s*,\s*\d+>""")
    private val ELRC_TAG_PATTERN = Regex("""<\d{1,3}:\d{2}(?:\.\d{1,3})?>""")
    private val YRC_INDICATOR = Regex("""\(\d+\s*,\s*\d+\s*,\s*\d+""")
    // [mm:ss], [mm:ss.x], [mm:ss.xx], or [mm:ss.xxx]
    private val LRC_TIME_TAG = Regex("""\[\d{1,3}:\d{2}(?:\.\d{1,3})?]""")
    private val METADATA_TAG_LINE = Regex("""^\[[A-Za-z][A-Za-z0-9_-]*:.*]$""")

    fun normalize(raw: String): LyricResult? {
        if (raw.isBlank()) return null
        val trimmed = raw.trim().removePrefix("\uFEFF")

        // YRC must be checked before QRC: both share [ms,ms] prefix,
        // but YRC word-tags (offset,dur,flags) also match QRC's (offset,dur) pattern.
        if (isYrc(trimmed)) {
            val elrc = yrcToElrc(trimmed) ?: return null
            return fromEnhanced(preservePrefixMetadata(trimmed, elrc))
        }

        if (isQrc(trimmed)) {
            val elrc = qrcToElrc(trimmed) ?: return null
            return fromEnhanced(preservePrefixMetadata(trimmed, elrc))
        }

        // Word-level LRC: [mm:ss.xxx]word[mm:ss.xxx]word...
        if (isWordLrc(trimmed)) {
            val elrc = wordLrcToElrc(trimmed) ?: return null
            return fromEnhanced(preservePrefixMetadata(trimmed, elrc))
        }

        if (isElrc(trimmed)) {
            return fromEnhanced(trimmed)
        }

        return LyricResult(lyric = trimmed)
    }

    private fun fromEnhanced(enhanced: String): LyricResult? {
        val lineLyric = ELRC_TAG_PATTERN.replace(enhanced, "").trim()
        if (lineLyric.isBlank()) return null

        val hasTimedText = enhanced.lineSequence().any { line ->
            val tags = ELRC_TAG_PATTERN.findAll(line).toList()
            tags.indices.any { index ->
                val textStart = tags[index].range.last + 1
                val textEnd = tags.getOrNull(index + 1)?.range?.first ?: line.length
                line.substring(textStart, textEnd).isNotBlank()
            }
        }

        return LyricResult(
            lyric = lineLyric,
            rawLyric = enhanced.takeIf { hasTimedText }
        )
    }

    private fun isYrc(content: String): Boolean {
        return timedMillisecondLines(content).any { line ->
            YRC_INDICATOR.containsMatchIn(line.substringAfter("]", ""))
        }
    }

    private fun isQrc(content: String): Boolean {
        val lines = timedMillisecondLines(content)
        if (lines.isEmpty()) return false
        if (lines.any { QRC_WORD_PATTERN.containsMatchIn(it.substringAfter("]", "")) }) {
            return true
        }
        return lines.none { KRC_WORD_PATTERN.containsMatchIn(it.substringAfter("]", "")) }
    }

    private fun timedMillisecondLines(content: String): List<String> {
        return content.lineSequence()
            .map { it.trim().removePrefix("\uFEFF") }
            .filter { MS_LINE_PATTERN.matches(it) }
            .toList()
    }

    /** Prefix LRC metadata is part of the source lyric and must survive conversion. */
    internal fun prefixMetadataLines(content: String): List<String> {
        val result = mutableListOf<String>()
        for (rawLine in content.lineSequence()) {
            val line = rawLine.trim().removePrefix("\uFEFF")
            if (line.isBlank()) continue
            if (!METADATA_TAG_LINE.matches(line)) break
            result += line
        }
        return result
    }

    private fun preservePrefixMetadata(source: String, converted: String): String {
        val metadata = prefixMetadataLines(source)
        if (metadata.isEmpty()) return converted
        return (metadata + converted).joinToString("\n")
    }

    private fun isElrc(content: String): Boolean {
        return ELRC_TAG_PATTERN.containsMatchIn(content)
    }

    /**
     * Detect word-level LRC: [mm:ss.xxx]text[mm:ss.xxx]text...
     * A line starts with [mm:ss.xxx], then the text body contains additional [mm:ss.xxx] tags.
     */
    private fun isWordLrc(content: String): Boolean {
        for (line in content.lineSequence()) {
            val trimmed = line.trim()
            val tags = LRC_TIME_TAG.findAll(trimmed).toList()
            if (tags.size < 2) continue
            if (tags.indices.drop(1).any { index ->
                    trimmed.substring(tags[index - 1].range.last + 1, tags[index].range.first)
                        .isNotBlank()
                }
            ) return true
        }
        return false
    }

    /**
     * YRC (Netease) -> Enhanced LRC.
     *
     * YRC word offsets are absolute timestamps from song start, not relative to line start.
     * Input:  [0,23600](23600,480,0)好(24080,390,0)多...
     * Output: [00:00.000] <00:23.600>好 <00:24.080>多...
     */
    private fun yrcToElrc(content: String): String? {
        val outputLines = mutableListOf<String>()

        for (line in content.lines()) {
            val trimmed = line.trim()
            if (trimmed.isBlank() || trimmed.startsWith("{")) continue

            val lineMatch = MS_LINE_PATTERN.matchEntire(trimmed) ?: continue
            val startMs = lineMatch.groupValues[1].toLongOrNull() ?: continue
            val body = lineMatch.groupValues[3]

            val wordMatches = YRC_WORD_PATTERN.findAll(body).toList()
            if (wordMatches.isEmpty()) {
                val text = body.trim()
                if (text.isNotEmpty()) outputLines.add("${formatLrcTime(startMs)}$text")
                continue
            }

            val elrcLine = StringBuilder(formatLrcTime(startMs))
            for (wordMatch in wordMatches) {
                // YRC word timestamps are absolute song positions, unlike KRC offsets.
                val absoluteMs = wordMatch.groupValues[1].toLongOrNull() ?: continue
                val text = wordMatch.groupValues[3]
                if (text.isNotEmpty()) {
                    elrcLine.append(formatElrcTime(absoluteMs))
                        .append(text)
                }
            }

            outputLines.add(elrcLine.toString())
        }

        return outputLines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    /**
     * QRC (QQ Music) -> Enhanced LRC.
     *
     * QRC word timestamps are absolute song positions. A timestamp follows
     * the word it describes, so each text segment is paired with the next tag.
     */
    private fun qrcToElrc(content: String): String? {
        val outputLines = mutableListOf<String>()

        for (line in content.lines()) {
            val trimmed = line.trim()
            if (trimmed.isBlank()) continue

            val lineMatch = MS_LINE_PATTERN.matchEntire(trimmed) ?: continue
            val startMs = lineMatch.groupValues[1].toLongOrNull() ?: continue
            val body = lineMatch.groupValues[3]

            val tagMatches = QRC_WORD_PATTERN.findAll(body).toList()
            if (tagMatches.isEmpty()) {
                val cleanText = body.trim()
                if (cleanText.isNotEmpty()) {
                    outputLines.add("${formatLrcTime(startMs)}$cleanText")
                }
                continue
            }

            val elrcLine = StringBuilder(formatLrcTime(startMs))
            var textStart = 0
            for (tag in tagMatches) {
                val text = body.substring(textStart, tag.range.first)
                if (text.isNotEmpty()) {
                    val wordStartMs = tag.groupValues[1].toLongOrNull() ?: startMs
                    if (text.isNotBlank()) elrcLine.append(formatElrcTime(wordStartMs))
                    elrcLine.append(text)
                }
                textStart = tag.range.last + 1
            }
            elrcLine.append(body.substring(textStart))
            outputLines.add(elrcLine.toString())
        }

        return outputLines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    /**
     * Word-level LRC -> Enhanced LRC.
     *
     * Input:  [00:04.413]编[00:04.714]曲[00:05.034]：[00:05.034]芊[00:05.314]芊[00:05.706]
     * Output: [00:04.413] <00:04.714>编 <00:04.714>曲 <00:05.034>：...
     */
    private fun wordLrcToElrc(content: String): String? {
        val outputLines = mutableListOf<String>()

        for (line in content.lines()) {
            val trimmed = line.trim()
            if (trimmed.isBlank()) continue

            val firstTag = LRC_TIME_TAG.find(trimmed) ?: continue
            val lineTimeTag = firstTag.value  // e.g. "[00:04.413]"
            val body = trimmed.substring(firstTag.range.last + 1)

            // Find all inline [mm:ss.xxx] tags with their positions
            val tags = LRC_TIME_TAG.findAll(body).toList()
            if (tags.size == 1 && tags[0].value == lineTimeTag) {
                val text = body.substring(0, tags[0].range.first)
                val lastLineTime = outputLines.lastOrNull()?.substringBefore("]")?.plus("]") ?: lineTimeTag
                outputLines.add("$lastLineTime$text")
                continue
            }
            if (tags.isEmpty()) {
                // No inline tags — plain LRC line, keep as-is
                outputLines.add(trimmed)
                continue
            }

            val elrcLine = StringBuilder(lineTimeTag)

            // Text before the first inline tag (if any)
            val leadingText = body.substring(0, tags[0].range.first)
            if (leadingText.isNotBlank()) {
                // Keep the line time tag for leading text
                elrcLine.append(lineTimeTag.replace("[", "<").replace("]", ">")).append(leadingText)
            } else {
                // Preserve a separator without making it a timed word of its own.
                elrcLine.append(leadingText)
            }

            for (i in tags.indices) {
                val timeTag = tags[i].value  // e.g. "[00:04.714]"
                val elrcTag = timeTag.replace("[", "<").replace("]", ">")  // e.g. "<00:04.714>"
                val textStart = tags[i].range.last + 1
                val textEnd = if (i + 1 < tags.size) tags[i + 1].range.first else body.length
                val text = body.substring(textStart, textEnd)
                if (text.isNotBlank()) {
                    elrcLine.append(elrcTag)
                }
                elrcLine.append(text)
            }

            outputLines.add(elrcLine.toString())
        }

        return outputLines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    /** Line-level: [mm:ss.xxx] */
    private fun formatLrcTime(ms: Long): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        val millis = ms % 1000
        return String.format(Locale.ROOT, "[%02d:%02d.%03d]", min, sec, millis)
    }

    /** Word-level: <mm:ss.xxx> */
    private fun formatElrcTime(ms: Long): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        val millis = ms % 1000
        return String.format(Locale.ROOT, "<%02d:%02d.%03d>", min, sec, millis)
    }
}
