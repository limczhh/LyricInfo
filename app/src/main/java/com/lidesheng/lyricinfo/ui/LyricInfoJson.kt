package com.lidesheng.lyricinfo.ui

import org.json.JSONObject
import java.util.regex.Pattern

/**
 * Parsed payload from MediaMetadata key `lyricInfo`.
 */
data class LyricInfoPayload(
    val songName: String,
    val artist: String,
    val songId: String,
    val lyric: String,
    val format: String,
    val translation: String,
    val romaji: String,
)

/**
 * One timed lyric unit for UI.
 *
 * When romaji is present, [translation] / [text] / [romaji] map to 翻译 / 原文 / 罗马音.
 */
data class LyricLine(
    val timeMs: Long,
    val text: String,
    val translation: String? = null,
    val romaji: String? = null,
)

object LyricInfoJson {

    private val LINE_TIME = Pattern.compile("^\\[(\\d{2}):(\\d{2})\\.(\\d{2,3})]")
    private val WORD_TAG = Pattern.compile("<\\d{2}:\\d{2}\\.\\d{2,3}>")

    fun parsePayload(raw: String?): LyricInfoPayload? {
        if (raw.isNullOrBlank()) return null
        return try {
            val obj = JSONObject(raw)
            val lyric = obj.optString("lyric", "").trim()
            if (lyric.isBlank()) return null
            LyricInfoPayload(
                songName = obj.optString("songName", ""),
                artist = obj.optString("artist", ""),
                songId = obj.optString("songId", ""),
                lyric = lyric,
                format = obj.optString("format", "lrc").ifBlank { "lrc" },
                translation = obj.optString("translation", ""),
                romaji = obj.optString("romaji", ""),
            )
        } catch (_: Exception) {
            null
        }
    }

    fun parseLines(payload: LyricInfoPayload): List<LyricLine> {
        val hasTranslation = payload.translation.isNotBlank()
        val hasRomaji = payload.romaji.isNotBlank()
        val rawLines = payload.lyric.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (rawLines.isEmpty()) return emptyList()

        val timed = rawLines.mapNotNull { line ->
            val time = extractTimeMs(line) ?: return@mapNotNull null
            TimedRaw(time, line)
        }
        if (timed.isEmpty()) {
            return listOf(LyricLine(0L, stripTags(payload.lyric, payload.format)))
        }

        val result = mutableListOf<LyricLine>()
        var i = 0
        while (i < timed.size) {
            val group = mutableListOf(timed[i])
            var j = i + 1
            while (j < timed.size && timed[j].timeMs == timed[i].timeMs) {
                group.add(timed[j])
                j++
            }

            val line = assignRoles(group, payload, hasTranslation, hasRomaji)
            if (line != null) result.add(line)
            i = j
        }
        return result
    }

    /**
     * Role assignment for same-timestamp lines:
     *
     * | flags              | storage order                         |
     * |--------------------|----------------------------------------|
     * | romaji (+ optional trans) | 翻译? + 原文 + 罗马音              |
     * | translation only   | 原文 + 翻译                            |
     * | neither            | 原文 only                              |
     */
    private fun assignRoles(
        group: List<TimedRaw>,
        payload: LyricInfoPayload,
        hasTranslation: Boolean,
        hasRomaji: Boolean,
    ): LyricLine? {
        val ts = group[0].timeMs
        val stripped = group.map { stripTags(it.raw, guessFormat(it.raw, payload)) }

        return when {
            hasRomaji && hasTranslation && group.size >= 3 -> {
                LyricLine(
                    timeMs = ts,
                    text = stripped[1],
                    translation = stripped[0].takeIf { it.isNotBlank() },
                    romaji = stripped[2].takeIf { it.isNotBlank() },
                )
            }
            hasRomaji && hasTranslation && group.size == 2 -> {
                // Incomplete triple: treat as 翻译 + 原文
                LyricLine(
                    timeMs = ts,
                    text = stripped[1],
                    translation = stripped[0].takeIf { it.isNotBlank() },
                )
            }
            hasRomaji && group.size >= 2 -> {
                // 原文 + 罗马音（无翻译）
                LyricLine(
                    timeMs = ts,
                    text = stripped[0],
                    romaji = stripped[1].takeIf { it.isNotBlank() },
                )
            }
            hasTranslation && group.size >= 2 -> {
                // 原文 + 翻译（旧格式）
                LyricLine(
                    timeMs = ts,
                    text = stripped[0],
                    translation = stripped[1].takeIf { it.isNotBlank() },
                )
            }
            else -> {
                val text = stripped.firstOrNull { it.isNotBlank() } ?: return null
                LyricLine(timeMs = ts, text = text)
            }
        }.takeIf { it.text.isNotBlank() }
    }

    private fun guessFormat(raw: String, payload: LyricInfoPayload): String {
        return if (WORD_TAG.matcher(raw).find()) "elrc" else {
            // Prefer payload.format for main; secondary tracks often lrc
            if (payload.format.equals("elrc", true) && WORD_TAG.matcher(raw).find()) "elrc"
            else "lrc"
        }
    }

    fun indexAt(lines: List<LyricLine>, positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        if (positionMs < lines[0].timeMs) return 0
        var idx = 0
        for (i in lines.indices) {
            if (lines[i].timeMs <= positionMs) idx = i else break
        }
        return idx
    }

    private fun extractTimeMs(raw: String): Long? {
        val m = LINE_TIME.matcher(raw)
        if (!m.find()) return null
        val min = m.group(1)!!.toLong()
        val sec = m.group(2)!!.toLong()
        val frac = m.group(3)!!
        val ms = if (frac.length == 2) frac.toLong() * 10 else frac.toLong()
        return min * 60_000 + sec * 1_000 + ms
    }

    private fun stripTags(raw: String, format: String): String {
        var s = raw.trim()
        s = s.replace(Regex("\\[\\d{2}:\\d{2}\\.\\d{2,3}]"), "")
        // Always strip word tags if present (romaji/elrc mixed)
        s = WORD_TAG.matcher(s).replaceAll("")
        if (format.equals("elrc", ignoreCase = true)) {
            // already stripped
        }
        return s.trim()
    }

    private data class TimedRaw(val timeMs: Long, val raw: String)
}
