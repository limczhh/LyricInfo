package com.mediasession.metadata.debug.business

import com.mediasession.metadata.debug.model.LyricInfoDocument
import com.mediasession.metadata.debug.model.LyricInfoField
import com.mediasession.metadata.debug.model.LyricInfoFieldPresence
import com.mediasession.metadata.debug.model.LyricLine
import com.mediasession.metadata.debug.model.LyricWord
import org.json.JSONObject
import java.util.ArrayDeque

/** Parses the public lyricInfo payload without coupling parsing to Compose. */
object LyricInfoParser {
    private val lineTime = Regex("""\[(\d{1,3}):(\d{2})[.:](\d{1,3})]""")
    private val wordTime = Regex("""<(\d{1,3}):(\d{2})[.:](\d{1,3})>""")
    private val translationKeys = listOf(
        "translation",
        "translationLyric",
        "translatedLyric",
        "translateLyric",
        "transLyric",
        "trans",
        "lyricTranslation",
        "translationLrc",
        "transLrc"
    )
    private val songMidKeys = listOf("songMid", "songmid", "media_mid")
    private val readmeFields = listOf(
        "songName",
        "artist",
        "album",
        "songId",
        "songMid",
        "lyric",
        "rawLyric",
        "translation",
        "roma"
    )
    private val lyricContentFields = setOf(
        "lyric",
        "rawLyric",
        "translation",
        "translationLyric",
        "translatedLyric",
        "translateLyric",
        "transLyric",
        "trans",
        "lyricTranslation",
        "translationLrc",
        "transLrc",
        "roma"
    )

    private data class ParsedLine(
        val anchor: Long,
        val begin: Long,
        val text: String,
        val words: List<LyricWord> = emptyList()
    )

    fun parse(
        json: String,
        fallbackTitle: String = "",
        fallbackArtist: String = "",
        fallbackAlbum: String = ""
    ): LyricInfoDocument? = runCatching {
        val objectValue = JSONObject(json)
        val lyric = objectValue.optionalText("lyric")
        val rawLyric = objectValue.optionalText("rawLyric")

        val rawLines = rawLyric?.let { parseLyricLines(it, enhanced = true) }.orEmpty()
        val useRaw = rawLines.isNotEmpty()
        val primaryLines = if (useRaw) {
            rawLines
        } else {
            lyric?.let { parseLyricLines(it, enhanced = false) }.orEmpty()
        }

        val translation = translationKeys.asSequence()
            .mapNotNull { key -> objectValue.optionalText(key) }
            .firstOrNull { lineTime.containsMatchIn(it) }
        val roma = objectValue.optionalText("roma")
        val lines = attachLanes(primaryLines, translation, roma)

        val format = objectValue.optionalText("format")
            ?: if (primaryLines.any { it.words.isNotEmpty() }) "elrc" else "lrc"
        LyricInfoDocument(
            songName = objectValue.optionalText("songName") ?: fallbackTitle,
            artist = objectValue.optionalText("artist") ?: fallbackArtist,
            album = objectValue.optionalText("album") ?: fallbackAlbum,
            songId = objectValue.optionalText("songId").orEmpty(),
            songMid = songMidKeys.asSequence()
                .mapNotNull { key -> objectValue.optionalText(key) }
                .firstOrNull()
                .orEmpty(),
            format = format,
            usedRawLyric = useRaw,
            hasTranslation = lines.any { !it.translation.isNullOrBlank() },
            hasRoma = lines.any { !it.roma.isNullOrBlank() },
            lines = lines,
            fields = fieldsOf(objectValue)
        )
    }.getOrNull()

    /** A safe field model used when the payload exists but cannot be parsed as JSON. */
    fun empty(
        fallbackTitle: String = "",
        fallbackArtist: String = "",
        fallbackAlbum: String = ""
    ): LyricInfoDocument = LyricInfoDocument(
        songName = fallbackTitle,
        artist = fallbackArtist,
        album = fallbackAlbum,
        songId = "",
        songMid = "",
        format = "",
        usedRawLyric = false,
        hasTranslation = false,
        hasRoma = false,
        lines = emptyList(),
        fields = readmeFields.map { key ->
            LyricInfoField(
                key = key,
                presence = LyricInfoFieldPresence.ABSENT,
                isLyricContent = key in lyricContentFields
            )
        }
    )

    private fun fieldsOf(objectValue: JSONObject): List<LyricInfoField> {
        val aliases = (songMidKeys + translationKeys).toSet()
        val known = readmeFields.toSet() + aliases
        val standard = readmeFields.map { key ->
            when (key) {
                "songMid" -> fieldOfAny(objectValue, key, songMidKeys)
                "translation" -> fieldOfAny(objectValue, key, translationKeys)
                else -> fieldOf(objectValue, key)
            }
        }
        val custom = buildList {
            val keys = objectValue.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key !in known) add(fieldOf(objectValue, key))
            }
        }
        return standard + custom
    }

    private fun fieldOfAny(
        objectValue: JSONObject,
        canonicalKey: String,
        keys: List<String>
    ): LyricInfoField {
        val actualKey = keys.firstOrNull { objectValue.has(it) } ?: return fieldOf(objectValue, canonicalKey)
        return fieldOf(objectValue, canonicalKey, actualKey)
    }

    private fun fieldOf(
        objectValue: JSONObject,
        key: String,
        sourceKey: String = key
    ): LyricInfoField {
        if (!objectValue.has(sourceKey)) {
            return LyricInfoField(
                key = key,
                presence = LyricInfoFieldPresence.ABSENT,
                isLyricContent = key in lyricContentFields
            )
        }

        val raw = objectValue.opt(sourceKey)
        val value = raw
            ?.takeUnless { it === JSONObject.NULL }
            ?.toString()
        return LyricInfoField(
            key = key,
            presence = if (value.isNullOrBlank()) {
                LyricInfoFieldPresence.EMPTY
            } else {
                LyricInfoFieldPresence.VALUE
            },
            value = value,
            isLyricContent = key in lyricContentFields
        )
    }

    private fun attachLanes(
        originalLines: List<ParsedLine>,
        translationRaw: String?,
        romaRaw: String?
    ): List<LyricLine> {
        if (originalLines.isEmpty()) return emptyList()
        val translation = laneByTime(translationRaw)
        val roma = laneByTime(romaRaw)
        return originalLines.map { original ->
            val translationLine = translation[original.anchor]?.let { queue ->
                if (queue.isEmpty()) null else queue.removeFirst()
            }
            val romaLine = roma[original.anchor]?.let { queue ->
                if (queue.isEmpty()) null else queue.removeFirst()
            }
            LyricLine(
                begin = original.begin,
                end = original.endForDisplay(),
                text = original.text,
                words = original.words,
                translation = translationLine?.text,
                roma = romaLine?.text
            )
        }
    }

    private fun laneByTime(raw: String?): Map<Long, ArrayDeque<ParsedLine>> {
        if (raw.isNullOrBlank()) return emptyMap()
        val parsed = parseLyricLines(raw, enhanced = wordTime.containsMatchIn(raw))
        return parsed.groupBy { it.anchor }
            .mapValues { (_, lines) -> ArrayDeque(lines) }
    }

    private fun parseLyricLines(raw: String, enhanced: Boolean): List<ParsedLine> {
        if (raw.isBlank()) return emptyList()
        val result = raw.lineSequence()
            .flatMap { line -> parseLine(line, enhanced).asSequence() }
            .filter { it.text.isNotBlank() }
            .sortedBy { it.begin }
            .toMutableList()
        completeLineTiming(result)
        return result
    }

    private fun parseLine(raw: String, enhanced: Boolean): List<ParsedLine> {
        val line = raw.trim().removePrefix("\uFEFF")
        if (line.isBlank()) return emptyList()

        val tags = mutableListOf<MatchResult>()
        var cursor = 0
        while (true) {
            val match = lineTime.find(line, cursor) ?: break
            if (match.range.first != cursor) break
            tags += match
            cursor = match.range.last + 1
        }
        if (tags.isEmpty()) return emptyList()
        val body = line.substring(cursor)
        val words = if (enhanced) parseWords(body) else emptyList()

        return tags.mapNotNull { tag ->
            val lineTimeMs = parseTime(tag)
            if (lineTimeMs < 0L) return@mapNotNull null
            if (words.isEmpty()) {
                body.replace(wordTime, "").trim()
                    .takeIf { it.isNotBlank() }
                    ?.let { ParsedLine(anchor = lineTimeMs, begin = lineTimeMs, text = it) }
            } else {
                val begin = words.first().begin
                val text = words.joinToString(separator = "") { it.text }
                if (text.isBlank()) null
                else if (words.size == 1 && begin == lineTimeMs) {
                    ParsedLine(anchor = lineTimeMs, begin = lineTimeMs, text = text)
                } else {
                    ParsedLine(anchor = lineTimeMs, begin = begin, text = text, words = words)
                }
            }
        }
    }

    private fun parseWords(body: String): List<LyricWord> {
        val matches = wordTime.findAll(body).toList()
        if (matches.isEmpty()) return emptyList()
        return matches.mapIndexedNotNull { index, match ->
            val textStart = match.range.last + 1
            val textEnd = matches.getOrNull(index + 1)?.range?.first ?: body.length
            val text = body.substring(textStart, textEnd)
            val begin = parseTime(match)
            if (begin < 0L || text.isBlank()) null
            else {
                val next = matches.getOrNull(index + 1)?.let(::parseTime)
                LyricWord(begin = begin, end = next ?: begin + 500L, text = text)
            }
        }
    }

    private fun completeLineTiming(lines: MutableList<ParsedLine>) {
        for (index in lines.indices) {
            val current = lines[index]
            val nextBegin = lines.getOrNull(index + 1)?.begin
            val end = nextBegin?.takeIf { it > current.begin } ?: (current.begin + 5000L)
            val words = current.words.mapIndexed { wordIndex, word ->
                val nextWord = current.words.getOrNull(wordIndex + 1)?.begin
                word.copy(end = nextWord ?: maxOf(word.end, end))
            }
            lines[index] = current.copy(words = words)
        }
    }

    private fun ParsedLine.endForDisplay(): Long {
        return words.lastOrNull()?.end?.coerceAtLeast(begin + 1L)
            ?: (begin + 5000L)
    }

    private fun parseTime(match: MatchResult): Long {
        val minutes = match.groupValues[1].toLongOrNull() ?: return -1L
        val seconds = match.groupValues[2].toLongOrNull() ?: return -1L
        if (seconds >= 60L) return -1L
        val fraction = match.groupValues[3].take(3).padEnd(3, '0').toLongOrNull() ?: return -1L
        return minutes * 60_000L + seconds * 1_000L + fraction
    }

    private fun JSONObject.optionalText(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).trim().takeIf { it.isNotBlank() }
}
