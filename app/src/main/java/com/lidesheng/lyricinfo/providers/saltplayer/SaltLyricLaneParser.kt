package com.lidesheng.lyricinfo.providers.saltplayer

import com.lidesheng.lyricinfo.core.LyricNormalizer
import com.lidesheng.lyricinfo.core.LyricResult
import java.util.Locale

/** Splits Salt's mixed same-timestamp lyrics before normalizing each lane independently. */
internal object SaltLyricLaneParser {
    private val LINE_TIME_TAG = Regex(
        """^\s*\[(\d{1,3})[:.](\d{2})(?:[:.](\d{1,3}))?]"""
    )
    private val ALL_TIME_TAGS = Regex(
        """\[(\d{1,3})[:.](\d{2})(?:[:.](\d{1,3}))?]"""
    )
    private val META_LINE = Regex("""^\[[A-Za-z]+\s*:.*]$""")
    private val WHITESPACE = Regex("\\s+")

    private data class SplitLanes(
        val primary: String,
        val translation: String?
    )

    private data class TimedLine(
        val timeMs: Long,
        val text: String
    )

    fun normalize(raw: String): LyricResult? {
        if (raw.isBlank()) return null

        val lanes = split(raw)
        if (!containsTimedLyric(lanes.primary)) return null
        val primary = LyricNormalizer.normalize(lanes.primary) ?: return null
        val translation = lanes.translation
            ?.let(LyricNormalizer::normalize)
            ?.let { filterUsableTranslation(primary.lyric, it.lyric) }

        return primary.copy(translation = translation)
    }

    fun containsTimedLyric(value: String): Boolean = ALL_TIME_TAGS.containsMatchIn(value)

    private fun split(raw: String): SplitLanes {
        val primaryLines = mutableListOf<String>()
        val translationLines = mutableListOf<String>()
        var lastTimestamp: Long? = null

        raw.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isBlank()) return@forEach

            val timestamp = firstTimestamp(line)
            if (timestamp != null && timestamp == lastTimestamp) {
                translationLines += line
                return@forEach
            }

            primaryLines += line
            if (timestamp != null) {
                lastTimestamp = timestamp
            } else if (!META_LINE.matches(line)) {
                lastTimestamp = null
            }
        }

        return SplitLanes(
            primary = primaryLines.joinToString("\n"),
            translation = translationLines.takeIf { it.isNotEmpty() }?.joinToString("\n")
        )
    }

    private fun filterUsableTranslation(primaryLyric: String, translationLyric: String): String? {
        val primaryByTime = parseTimedLines(primaryLyric).groupBy { it.timeMs }
        val valid = parseTimedLines(translationLyric).filter { line ->
            line.text.isNotBlank() &&
                line.text.trim() != "//" &&
                primaryByTime[line.timeMs].orEmpty().none { primary ->
                    comparable(primary.text) == comparable(line.text)
                }
        }
        if (valid.isEmpty()) return null

        return valid.joinToString("\n") { line ->
            "${formatTimestamp(line.timeMs)}${line.text.trim()}"
        }
    }

    private fun parseTimedLines(value: String): List<TimedLine> = value.lineSequence()
        .mapNotNull { rawLine ->
            val line = rawLine.trim()
            val matches = ALL_TIME_TAGS.findAll(line).toList()
            if (matches.isEmpty()) return@mapNotNull null

            val first = matches.first()
            val timestamp = parseTimestamp(first) ?: return@mapNotNull null
            val text = line.substring(matches.last().range.last + 1).trim()
            TimedLine(timestamp, text)
        }
        .toList()

    private fun firstTimestamp(line: String): Long? =
        LINE_TIME_TAG.find(line)?.let(::parseTimestamp)

    private fun parseTimestamp(match: MatchResult): Long? {
        val minutes = match.groupValues[1].toLongOrNull() ?: return null
        val seconds = match.groupValues[2].toLongOrNull() ?: return null
        val fraction = match.groupValues.getOrNull(3).orEmpty()
        val millis = when (fraction.length) {
            1 -> fraction.toLongOrNull()?.times(100)
            2 -> fraction.toLongOrNull()?.times(10)
            3 -> fraction.toLongOrNull()
            else -> 0L
        } ?: return null
        return minutes * 60_000L + seconds * 1_000L + millis
    }

    private fun comparable(value: String): String =
        value.replace(WHITESPACE, " ").trim().lowercase(Locale.ROOT)

    private fun formatTimestamp(timeMs: Long): String {
        val minutes = timeMs / 60_000L
        val seconds = (timeMs % 60_000L) / 1_000L
        val millis = timeMs % 1_000L
        return String.format(Locale.ROOT, "[%02d:%02d.%03d]", minutes, seconds, millis)
    }
}
