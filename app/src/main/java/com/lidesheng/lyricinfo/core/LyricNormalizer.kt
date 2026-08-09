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
            val leadingText = body.substring(0, tags[0].range.first)
            if (leadingText.isNotBlank()) {
                // Keep the line time tag for leading text
                elrcLine.append(lineTimeTag.replace("[", "<").replace("]", ">")).append(leadingText)
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
     * Merge normalized original / translation / romaji lyrics by timestamp.
     *
     * Output order for each original line:
     * - If [romaji] is present: **translation → original → romaji**
     * - Else if [translation] only: **original → translation** (legacy)
     * - Else: original only
     *
     * Matching uses a 1000ms fuzzy window; secondary tracks get their line-level
     * timestamps rewritten to the original line's timestamp.
     */
    fun merge(
        original: String,
        translation: String? = null,
        romaji: String? = null,
    ): String {
        val transByTime = translation?.takeIf { it.isNotBlank() }
            ?.let { parseLinesByTimestampMs(it) }
            .orEmpty()
        val romaByTime = romaji?.takeIf { it.isNotBlank() }
            ?.let { parseLinesByTimestampMs(it) }
            .orEmpty()

        if (transByTime.isEmpty() && romaByTime.isEmpty()) {
            Log.d("LyricNormalizer", "merge: 无翻译/罗马音，跳过合并")
            return original
        }

        val transTimes = transByTime.keys.sorted()
        val romaTimes = romaByTime.keys.sorted()
        val hasRomaji = romaByTime.isNotEmpty()

        val origCount = original.lines().count { it.isNotBlank() }
        var matchedTrans = 0
        var matchedRoma = 0

        val output = StringBuilder()
        for (line in original.lines()) {
            if (line.isBlank()) {
                output.appendLine(line)
                continue
            }
            val tsMs = extractLineTimestampMs(line)

            val matchedTransLines = if (tsMs >= 0) {
                findClosestLines(transByTime, transTimes, tsMs)?.also { matchedTrans++ }
            } else null
            val matchedRomaLines = if (tsMs >= 0) {
                findClosestLines(romaByTime, romaTimes, tsMs)?.also { matchedRoma++ }
            } else null

            if (hasRomaji) {
                // 翻译 + 原文 + 罗马音
                if (matchedTransLines != null && tsMs >= 0) {
                    for (tLine in matchedTransLines) {
                        output.appendLine(replaceTimestamp(tLine, tsMs))
                    }
                }
                output.appendLine(line)
                if (matchedRomaLines != null && tsMs >= 0) {
                    for (rLine in matchedRomaLines) {
                        output.appendLine(replaceTimestamp(rLine, tsMs))
                    }
                }
            } else {
                // 原文 + 翻译（兼容旧消费者）
                output.appendLine(line)
                if (matchedTransLines != null && tsMs >= 0) {
                    for (tLine in matchedTransLines) {
                        output.appendLine(replaceTimestamp(tLine, tsMs))
                    }
                }
            }
        }

        Log.d(
            "LyricNormalizer",
            "merge: 原文${origCount}行, 翻译匹配${matchedTrans}, 罗马音匹配${matchedRoma}, " +
                "order=${if (hasRomaji) "trans+orig+roma" else "orig+trans"}"
        )
        return output.toString().trimEnd()
    }

    private fun findClosestLines(
        byTime: Map<Long, MutableList<String>>,
        times: List<Long>,
        tsMs: Long,
        windowMs: Long = 1000,
    ): List<String>? {
        if (byTime.isEmpty() || times.isEmpty()) return null
        val closest = times.minByOrNull { kotlin.math.abs(it - tsMs) } ?: return null
        if (kotlin.math.abs(closest - tsMs) > windowMs) return null
        return byTime[closest]
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
