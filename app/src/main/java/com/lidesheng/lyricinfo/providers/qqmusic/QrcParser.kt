package com.lidesheng.lyricinfo.providers.qqmusic

/**
 * QRC-to-LRC converter. Ported from LyricProvider/qrckit.
 * Parses QRC format and converts to standard LRC for lyricInfo injection.
 *
 * QRC format: [startMs,durationMs]text with (wordStartMs,wordDurMs) tags
 * LRC format: [mm:ss.xx]text
 */
internal object QrcParser {

    private val linePattern = Regex("""\[(\d+)\s*,\s*(\d+)]""")
    private val wordTagPattern = Regex("""\(\d+\s*,\s*\d+\)""")

    /**
     * Parse QRC content and convert to LRC format.
     */
    fun toLrc(qrcContent: String?): String? {
        if (qrcContent.isNullOrBlank()) return null
        val text = extractFromXml(qrcContent) ?: qrcContent
        return parseQrcToLrc(text)
    }

    /**
     * Extract QRC content, preserving word-level timing (逐字歌词).
     * Returns the raw QRC format: [startMs,durationMs]text(wordStart,wordDur)...
     */
    fun toRawQrc(qrcContent: String?): String? {
        if (qrcContent.isNullOrBlank()) return null
        return extractFromXml(qrcContent) ?: qrcContent
    }

    /**
     * Extract QRC content from XML LyricContent attribute if present.
     */
    private fun extractFromXml(input: String): String? {
        val pattern = Regex("""LyricContent\s*=\s*"([\s\S]*?)"(?=\s*/?>)""")
        val match = pattern.find(input) ?: return null
        return decodeXmlEntities(match.groupValues[1]).takeIf { it.isNotBlank() }
    }

    private fun parseQrcToLrc(content: String): String? {
        val lineMatches = linePattern.findAll(content).toList()
        if (lineMatches.isEmpty()) return null

        val lrcLines = mutableListOf<String>()

        for (i in lineMatches.indices) {
            val match = lineMatches[i]
            val startMs = match.groupValues[1].toLongOrNull() ?: continue

            val bodyStart = match.range.last + 1
            val bodyEnd = if (i + 1 < lineMatches.size) {
                lineMatches[i + 1].range.first
            } else {
                val lastIdx = content.lastIndexOf(']')
                if (lastIdx > bodyStart) lastIdx else content.length
            }

            if (bodyStart >= bodyEnd) continue

            val rawBody = content.substring(bodyStart, bodyEnd).trim('\n', '\r')
            val cleanText = wordTagPattern.replace(rawBody, "").trim()
            if (cleanText.isEmpty()) continue

            val timeTag = formatLrcTime(startMs)
            lrcLines.add("$timeTag$cleanText")
        }

        return lrcLines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    /**
     * Convert milliseconds to LRC time tag [mm:ss.xx]
     */
    private fun formatLrcTime(ms: Long): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        val centis = (ms % 1000) / 10
        return "[%02d:%02d.%02d]".format(min, sec, centis)
    }

    private fun decodeXmlEntities(input: String): String {
        return input.replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&apos;", "'")
    }
}
