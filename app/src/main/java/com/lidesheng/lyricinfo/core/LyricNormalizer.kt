package com.lidesheng.lyricinfo.core

import android.util.Log

/**
 * Normalizes lyrics from various private formats to standard elrc/lrc.
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
    private val MS_LINE_PATTERN = Regex("""\[(\d+)\s*,\s*(\d+)](.+)""")
    private val YRC_WORD_PATTERN = Regex("""\((\d+)\s*,\s*(\d+)(?:,\s*\d+)?\)(.*?)(?=\(|$)""")
    private val QRC_WORD_PATTERN = Regex("""\((\d+)\s*,\s*\d+\)""")
    private val ELRC_TAG_PATTERN = Regex("""<\d{2}:\d{2}\.\d{2,3}>""")
    private val YRC_INDICATOR = Regex("""\(\d+\s*,\s*\d+\s*,\s*\d+""")
    // [mm:ss.xxx] or [mm:ss.xx] — standard LRC time tag
    private val LRC_TIME_TAG = Regex("""\[\d{2}:\d{2}\.\d{2,3}]""")

    fun normalize(raw: String): LyricResult? {
        if (raw.isBlank()) return null
        val trimmed = raw.trim()

        // YRC must be checked before QRC: both share [ms,ms] prefix,
        // but YRC word-tags (offset,dur,flags) also match QRC's (offset,dur) pattern.
        if (isYrc(trimmed)) {
            val elrc = yrcToElrc(trimmed) ?: return null
            return LyricResult(lyric = elrc, format = "elrc")
        }

        if (isQrc(trimmed)) {
            val elrc = qrcToElrc(trimmed) ?: return null
            return LyricResult(lyric = elrc, format = "elrc")
        }

        // Word-level LRC: [mm:ss.xxx]word[mm:ss.xxx]word...
        if (isWordLrc(trimmed)) {
            val elrc = wordLrcToElrc(trimmed) ?: return null
            return LyricResult(lyric = elrc, format = "elrc")
        }

        if (isElrc(trimmed)) {
            return LyricResult(lyric = trimmed, format = "elrc")
        }

        return LyricResult(lyric = trimmed, format = "lrc")
    }

    private fun isYrc(content: String): Boolean {
        val firstLine = firstContentLine(content) ?: return false
        val afterBracket = firstLine.substringAfter("]", "")
        return MS_LINE_PATTERN.containsMatchIn(firstLine) &&
            YRC_INDICATOR.containsMatchIn(afterBracket)
    }

    private fun isQrc(content: String): Boolean {
        val firstLine = firstContentLine(content) ?: return false
        val afterBracket = firstLine.substringAfter("]", "")
        return MS_LINE_PATTERN.containsMatchIn(firstLine) &&
            QRC_WORD_PATTERN.containsMatchIn(afterBracket)
    }

    /** Skip metadata lines like [by:], [offset:0], [ti:xxx] */
    private fun firstContentLine(content: String): String? {
        return content.lineSequence()
            .map { it.trim() }
            .firstOrNull { line ->
                line.isNotBlank() &&
                    !line.startsWith("{") &&
                    !line.matches(Regex("""\[[a-zA-Z].*]"""))
            }
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
            if (trimmed.isBlank()) continue
            val firstTag = LRC_TIME_TAG.find(trimmed) ?: continue
            val afterFirst = trimmed.substring(firstTag.range.last + 1)
            // Check if there's another [mm:ss.xxx] tag in the text body
            if (LRC_TIME_TAG.containsMatchIn(afterFirst)) return true
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

            val lineMatch = MS_LINE_PATTERN.find(trimmed) ?: continue
            val startMs = lineMatch.groupValues[1].toLongOrNull() ?: continue
            val body = lineMatch.groupValues[3]

            val wordMatches = YRC_WORD_PATTERN.findAll(body).toList()
            if (wordMatches.isEmpty()) continue

            val elrcLine = StringBuilder(formatLrcTime(startMs))
            for (wordMatch in wordMatches) {
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
     * Input:  [12345,3000]She(500,200) played(800,300)...
     * Output: [00:12.34] <00:12.84>She <00:13.14>played...
     */
    private fun qrcToElrc(content: String): String? {
        val outputLines = mutableListOf<String>()

        for (line in content.lines()) {
            val trimmed = line.trim()
            if (trimmed.isBlank()) continue

            val lineMatch = MS_LINE_PATTERN.find(trimmed) ?: continue
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

            Log.i("LyricNormalizer", "qrc body: |$body|")
            Log.i("LyricNormalizer", "qrc tags: ${tagMatches.map { "${it.value}@${it.range}" }}")
            Log.i("LyricNormalizer", "qrc firstTagStart=${tagMatches[0].range.first}, text='${body.substring(0, tagMatches[0].range.first)}'")

            val elrcLine = StringBuilder(formatLrcTime(startMs))

            // Text before first tag uses first tag's offset
            val firstTagStart = tagMatches[0].range.first
            if (firstTagStart > 0) {
                val text = body.substring(0, firstTagStart)
                if (text.isNotBlank()) {
                    val offsetMs = tagMatches[0].groupValues[1].toLongOrNull() ?: 0
                    elrcLine.append(formatElrcTime(offsetMs)).append(text)
                }
            }

            // Text after each tag uses next tag's offset (or last tag's for trailing)
            for (i in 0 until tagMatches.size - 1) {
                val textStart = tagMatches[i].range.last + 1
                val textEnd = tagMatches[i + 1].range.first
                val text = body.substring(textStart, textEnd)
                if (text.isNotBlank()) {
                    val offsetMs = tagMatches[i + 1].groupValues[1].toLongOrNull() ?: continue
                    elrcLine.append(formatElrcTime(offsetMs)).append(text)
                }
            }

            // Text after last tag
            val lastEnd = tagMatches.last().range.last + 1
            val trailing = body.substring(lastEnd)
            if (trailing.isNotBlank()) {
                val lastOffsetMs = tagMatches.last().groupValues[1].toLongOrNull() ?: 0
                elrcLine.append(formatElrcTime(lastOffsetMs)).append(trailing)
            }

            val result = elrcLine.toString()
            Log.i("LyricNormalizer", "qrc result: $result")
            outputLines.add(result)
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
            if (tags.isEmpty()) {
                // No inline tags — plain LRC line, keep as-is
                outputLines.add(trimmed)
                continue
            }

            val elrcLine = StringBuilder(lineTimeTag)

            // Text before the first inline tag (if any)
            val leadingText = body.substring(0, tags[0].range.first).trim()
            if (leadingText.isNotEmpty()) {
                // Keep the line time tag for leading text
                elrcLine.append(" ").append(lineTimeTag.replace("[", "<").replace("]", ">")).append(leadingText)
            }

            for (i in tags.indices) {
                val timeTag = tags[i].value  // e.g. "[00:04.714]"
                val elrcTag = timeTag.replace("[", "<").replace("]", ">")  // e.g. "<00:04.714>"
                val textStart = tags[i].range.last + 1
                val textEnd = if (i + 1 < tags.size) tags[i + 1].range.first else body.length
                val text = body.substring(textStart, textEnd)
                if (text.isNotBlank()) {
                    elrcLine.append(elrcTag).append(text)
                }
            }

            outputLines.add(elrcLine.toString())
        }

        return outputLines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    /**
     * Merge normalized original and translation lyrics by timestamp.
     *
     * For each timestamp present in the original, if the translation has lines with the same
     * timestamp, those translation lines are appended immediately after the original lines.
     * Format tags (elrc `<mm:ss.xx>` or lrc) in each line are preserved as-is.
     *
     * @param original  Normalized lyric string (elrc or lrc)
     * @param translation Normalized translation lyric string (elrc or lrc)
     * @return Merged lyric string
     */
    fun merge(original: String, translation: String): String {
        val transByTime = parseLinesByTimestampMs(translation)
        if (transByTime.isEmpty()) {
            Log.d("LyricNormalizer", "merge: 翻译为空，跳过合并")
            return original
        }
        val transTimes = transByTime.keys.sorted()

        val origCount = original.lines().count { it.isNotBlank() }
        val transCount = transTimes.size
        var matched = 0

        val output = StringBuilder()
        for (line in original.lines()) {
            output.appendLine(line)
            val tsMs = extractLineTimestampMs(line)
            if (tsMs < 0) continue
            // 模糊匹配：找时间差在 1000ms 内的翻译
            val closest = transTimes.minByOrNull { kotlin.math.abs(it - tsMs) }
            if (closest != null && kotlin.math.abs(closest - tsMs) <= 1000) {
                val transLines = transByTime[closest]
                if (transLines != null) {
                    matched++
                    for (tLine in transLines) {
                        // 替换翻译行的时间戳为原文的时间戳，确保接收端能匹配
                        val replaced = replaceTimestamp(tLine, tsMs)
                        output.appendLine(replaced)
                    }
                }
            }
        }

        Log.d("LyricNormalizer", "merge: 原文${origCount}行, 翻译${transCount}个时间戳, 匹配${matched}行")
        return output.toString().trimEnd()
    }

    /**
     * Parse translation lines into a map of timestamp(ms) -> list of lines.
     */
    private fun parseLinesByTimestampMs(content: String): Map<Long, MutableList<String>> {
        val result = mutableMapOf<Long, MutableList<String>>()
        for (line in content.lines()) {
            val ts = extractLineTimestampMs(line)
            if (ts >= 0) {
                result.getOrPut(ts) { mutableListOf() }.add(line)
            }
        }
        return result
    }

    /**
     * Extract line-level timestamp as milliseconds (Long).
     */
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

    /**
     * Replace the line-level timestamp in a lyric line with a new value (ms).
     * [00:10.440]翻译 → [00:10.790]翻译 (when newMs = 10790)
     */
    private fun replaceTimestamp(line: String, newMs: Long): String {
        val match = LRC_TIME_TAG.find(line) ?: return line
        val newTag = formatLrcTime(newMs)
        return line.substring(0, match.range.first) + newTag + line.substring(match.range.last + 1)
    }

    /** Line-level: [mm:ss.xxx] */
    private fun formatLrcTime(ms: Long): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        val millis = ms % 1000
        return "[%02d:%02d.%03d]".format(min, sec, millis)
    }

    /** Word-level: <mm:ss.xxx> */
    private fun formatElrcTime(ms: Long): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        val millis = ms % 1000
        return "<%02d:%02d.%03d>".format(min, sec, millis)
    }
}
