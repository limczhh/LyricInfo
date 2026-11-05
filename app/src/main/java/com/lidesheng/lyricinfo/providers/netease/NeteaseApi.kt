package com.lidesheng.lyricinfo.providers.netease

import android.util.Log
import com.lidesheng.lyricinfo.core.LyricNormalizer
import com.lidesheng.lyricinfo.core.LyricResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

internal object NeteaseApi {

    private const val TAG = "LyricInfo"
    private const val BASE_URL = "https://interface.music.163.com/eapi/"

    data class SongMetadata(
        val songId: String,
        val songName: String,
        val artist: String,
        val album: String
    )

    /**
     * Resolves the stable Netease song ID to metadata. This deliberately does
     * not use title or artist from MediaMetadata because Netease can replace
     * those fields with the current Bluetooth lyric sentence.
     */
    fun fetchSongMetadata(musicId: Long): SongMetadata? {
        return try {
            val songIds = JSONArray().put(
                JSONObject().apply {
                    put("id", musicId.toString())
                    put("v", "0")
                }
            )
            val params = JSONObject().put("c", songIds.toString())
            val response = request("v3/song/detail", params.toString())
            val songs = JSONObject(response).optJSONArray("songs") ?: return null
            val song = songs.optJSONObject(0) ?: return null
            val responseId = song.optLong("id", 0L)
            if (responseId != musicId) return null

            val artist = buildString {
                val artists = song.optJSONArray("ar") ?: song.optJSONArray("artists")
                if (artists != null) {
                    for (index in 0 until artists.length()) {
                        val name = artists.optJSONObject(index)
                            ?.optString("name")
                            .orEmpty()
                            .trim()
                        if (name.isNotBlank()) {
                            if (isNotEmpty()) append(", ")
                            append(name)
                        }
                    }
                }
            }.trim()
            val songName = song.optString("name").trim()
            val album = (
                song.optJSONObject("al")?.optString("name")
                    ?: song.optJSONObject("album")?.optString("name")
            ).orEmpty().trim()
            if (songName.isBlank() || artist.isBlank()) return null

            SongMetadata(
                songId = responseId.toString(),
                songName = songName,
                artist = artist,
                album = album
            )
        } catch (e: Exception) {
            Log.e(TAG, "[Netease] Metadata API error: ${e.message}")
            null
        }
    }

    fun fetchLyric(musicId: Long): LyricResult? {
        return try {
            val params = JSONObject().apply {
                put("id", musicId.toString())
                put("cp", false)
                // -1 requests the latest available lane; 0 disables it.
                put("lv", -1)
                put("tv", -1)
                put("rv", -1)
                put("yv", -1)
            }

            val response = request("song/lyric/v1", params.toString())
            val json = JSONObject(response)

            val lrcRaw = json.optJSONObject("lrc")?.optString("lyric")
            val yrcRaw = json.optJSONObject("yrc")?.optString("lyric")
            val tlyricRaw = json.optJSONObject("tlyric")?.optString("lyric")
            val romalrcRaw = json.optJSONObject("romalrc")?.optString("lyric")

            val lrc = lrcRaw?.takeIf { it.isNotBlank() }
            val yrc = yrcRaw?.takeIf { it.isNotBlank() }
            val translation = tlyricRaw?.takeIf { it.isNotBlank() }

            // 优先尝试转换 YRC（逐字歌词）到 elrc 格式，转换失败或无 YRC 时 fallback 到标准 LRC
            val normalized = (if (yrc != null) LyricNormalizer.normalize(yrc) else null)
                ?: (if (lrc != null) LyricNormalizer.normalize(lrc) else null)
                ?: return null

            // Keep translation as an independent lane. It may itself be enhanced LRC.
            val transNormalized = translation?.let { LyricNormalizer.normalize(it) }
            val translationLane = transNormalized?.preferredLane()
            val romaLane = romalrcRaw
                ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                ?.let(LyricNormalizer::normalize)
                ?.preferredLane()
            normalized.copy(
                translation = translationLane?.let {
                    alignTranslationTimestamps(normalized.lyric, it)
                },
                roma = romaLane
            )
        } catch (e: Exception) {
            Log.e(TAG, "[Netease] API error: ${e.message}")
            null
        }
    }

    /**
     * Netease's tlyric is returned for the same song and in the same lyric-line
     * order as the primary lane, but its LRC timestamps can use a different
     * timing source. Canonicalize the independent translation lane to the
     * primary line timestamps so consumers can match the lanes exactly.
     *
     * This is intentionally conservative: a count mismatch or invalid time
     * order means that line-order correspondence is not established, so the
     * original translation is kept unchanged.
     */
    private fun alignTranslationTimestamps(original: String, translation: String): String {
        val originalLines = parseTimedLines(original)
        val translationLines = parseTimedLines(translation)
        if (originalLines.isEmpty() || translationLines.isEmpty()) {
            Log.w(
                TAG,
                "[Netease] Keep translation timestamps: missing timed lines " +
                    "(original=${originalLines.size}, translation=${translationLines.size})"
            )
            return translation
        }

        // If the original lyric has leading 00:00 credit lines while translation does not,
        // match translation against effective timed lyric lines.
        val effectiveOriginalLines = if (originalLines.size > translationLines.size) {
            val prefixAuthorCount = originalLines.indexOfFirst { it.timeMs > 0L }
            if (prefixAuthorCount > 0 && (originalLines.size - prefixAuthorCount == translationLines.size)) {
                originalLines.drop(prefixAuthorCount)
            } else {
                originalLines
            }
        } else {
            originalLines
        }

        if (effectiveOriginalLines.size != translationLines.size) {
            Log.w(
                TAG,
                "[Netease] Keep translation timestamps: line count mismatch " +
                    "(original=${effectiveOriginalLines.size}, translation=${translationLines.size})"
            )
            return translation
        }

        if (!isNonDecreasing(effectiveOriginalLines) || !isNonDecreasing(translationLines)) {
            Log.w(TAG, "[Netease] Keep translation timestamps: invalid time order")
            return translation
        }

        val outputLines = translation.lines().toMutableList()
        var changed = 0
        translationLines.forEachIndexed { index, translationLine ->
            val targetTimeMs = effectiveOriginalLines[index].timeMs
            if (translationLine.timeMs == targetTimeMs) return@forEachIndexed

            val line = outputLines[translationLine.lineIndex]
            val timeTag = LRC_LINE_TIME_PATTERN.find(line) ?: return@forEachIndexed
            outputLines[translationLine.lineIndex] = line.replaceRange(
                timeTag.range,
                formatLrcTimestamp(targetTimeMs)
            )
            changed++
        }

        if (changed > 0) {
            Log.i(
                TAG,
                "[Netease] Aligned translation timestamps by line order: " +
                    "lines=${effectiveOriginalLines.size}, changed=$changed"
            )
            return outputLines.joinToString("\n")
        }

        Log.d(TAG, "[Netease] Translation timestamps already aligned")
        return translation
    }

    private data class TimedLine(
        val lineIndex: Int,
        val timeMs: Long
    )

    private val LRC_LINE_TIME_PATTERN = Regex("""\[(\d{1,3}):(\d{2})\.(\d{2,3})]""")

    private fun parseTimedLines(content: String): List<TimedLine> =
        content.lines().mapIndexedNotNull { lineIndex, line ->
            val match = LRC_LINE_TIME_PATTERN.find(line) ?: return@mapIndexedNotNull null
            val minutes = match.groupValues[1].toLongOrNull() ?: return@mapIndexedNotNull null
            val seconds = match.groupValues[2].toLongOrNull() ?: return@mapIndexedNotNull null
            val fraction = match.groupValues[3]
            val millis = fraction.toLongOrNull()?.let {
                if (fraction.length == 2) it * 10 else it
            } ?: return@mapIndexedNotNull null
            TimedLine(
                lineIndex = lineIndex,
                timeMs = minutes * 60_000L + seconds * 1_000L + millis
            )
        }

    private fun isNonDecreasing(lines: List<TimedLine>): Boolean =
        lines.zipWithNext().all { (current, next) -> current.timeMs <= next.timeMs }

    private fun formatLrcTimestamp(timeMs: Long): String {
        val totalSeconds = timeMs / 1_000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        val millis = timeMs % 1_000L
        return String.format(Locale.ROOT, "[%02d:%02d.%03d]", minutes, seconds, millis)
    }

    private fun request(endpoint: String, jsonParams: String): String {
        val url = "$BASE_URL$endpoint"
        val encrypted = NeteaseCrypto.eApiEncrypt("/eapi/$endpoint", jsonParams)
        val paramsStr = encrypted.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }

        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.doOutput = true
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000

        try {
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(paramsStr) }
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw RuntimeException("HTTP $code")
            }
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
