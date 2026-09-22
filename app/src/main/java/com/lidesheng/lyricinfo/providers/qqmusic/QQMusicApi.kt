package com.lidesheng.lyricinfo.providers.qqmusic

import android.util.Log
import com.lidesheng.lyricinfo.core.LyricNormalizer
import com.lidesheng.lyricinfo.core.LyricResult
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.regex.Pattern
import kotlin.math.abs

internal object QQMusicApi {

    private const val TAG = "LyricInfo"
    private const val LYRIC_URL = "https://c.y.qq.com/qqmusic/fcgi-bin/lyric_download.fcg"
    private const val SONG_DETAIL_URL =
        "https://c.y.qq.com/v8/fcg-bin/fcg_play_single_song.fcg"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    data class SongMetadata(
        val songId: String,
        val songName: String,
        val artist: String,
        val album: String
    )

    /**
     * Resolves the stable QQ song ID to metadata. This deliberately does not
     * use the title or artist stored in MediaMetadata because QQ may replace
     * them with the current Bluetooth lyric sentence.
     */
    fun fetchSongMetadata(musicId: String): SongMetadata? {
        return try {
            val params = mapOf(
                "songid" to musicId,
                "tpl" to "yqq_song_detail",
                "format" to "jsonp",
                "callback" to "getOneSongInfoCallback"
            )
            val query = params.entries.joinToString("&") { (key, value) ->
                "${key}=${URLEncoder.encode(value, "UTF-8")}"
            }
            val raw = download(
                URI.create("$SONG_DETAIL_URL?$query").toURL(),
                requestMethod = "GET"
            )
            val jsonText = raw.substringAfter('(').substringBeforeLast(')')
            val data = JSONObject(jsonText).optJSONArray("data") ?: return null
            val song = data.optJSONObject(0) ?: return null
            val responseId = song.optLong("id", 0L)
            if (responseId <= 0L || responseId.toString() != musicId) return null

            val singerNames = buildString {
                val singers = song.optJSONArray("singer")
                if (singers != null) {
                    for (index in 0 until singers.length()) {
                        val name = singers.optJSONObject(index)?.optString("name").orEmpty()
                        if (name.isNotBlank()) {
                            if (isNotEmpty()) append(", ")
                            append(name)
                        }
                    }
                }
                if (isEmpty()) append(song.optString("singername"))
            }.trim()
            val songName = song.optString("name").trim()
            val album = song.optJSONObject("album")?.optString("name").orEmpty().trim()
            if (songName.isBlank() || singerNames.isBlank()) return null

            SongMetadata(
                songId = responseId.toString(),
                songName = songName,
                artist = singerNames,
                album = album
            )
        } catch (e: Exception) {
            Log.e(TAG, "[QQMusic] Metadata API error: ${e.message}")
            null
        }
    }

    /**
     * 获取歌词。QRC 逐字数据会同时生成标准原文和可信逐字原文，翻译独立保存。
     */
    fun fetchLyric(musicId: String): LyricResult? {
        return try {
            val raw = downloadRaw(musicId)

            val content = extractCData(raw, "content")
            val decrypted = QrcDecrypter.decrypt(content)
            val rawQrc = QrcParser.toRawQrc(decrypted)
            val lrc = QrcParser.toLrc(decrypted)

            if (lrc.isNullOrBlank()) return null

            // Try to extract translation (contentts tag, usually standard LRC format)
            val translationContent = extractCData(raw, "contentts")
            val decryptedTrans = translationContent?.let { QrcDecrypter.decrypt(it) }
            val translationLrc = decryptedTrans?.takeIf { it.isNotBlank() }

            val rawLyric = rawQrc?.takeIf { it.isNotBlank() } ?: lrc
            val normalized = LyricNormalizer.normalize(rawLyric)
                ?: return null

            // QQ's contentts contains LRC metadata, placeholders, and may omit
            // lines that have no translation. Clean it before matching times.
            val translationLane = translationLrc?.let {
                normalizeTranslation(normalized.lyric, it)
            }
            normalized.copy(translation = translationLane)
        } catch (e: Exception) {
            Log.e(TAG, "[QQMusic] API error: ${e.message}")
            null
        }
    }

    /**
     * QQ's translation payload is not a one-to-one lyric lane. It can contain
     * [ti]/[ar]/[al] metadata, copyright notices, blank timed lines, and "//"
     * placeholders. Match the remaining translation lines monotonically to
     * the primary line timestamps instead of relying on equal line counts.
     */
    private fun normalizeTranslation(original: String, rawTranslation: String): String? {
        val originalLines = parseQqTimedLines(original, filterTranslationNoise = false)
        val translationLines = parseQqTimedLines(rawTranslation, filterTranslationNoise = true)
        if (translationLines.isEmpty()) {
            return LyricNormalizer.normalize(rawTranslation)?.preferredLane()
        }
        if (originalLines.isEmpty()) {
            return translationLines.joinToString("\n") { it.content }
        }

        val output = ArrayList<String>(translationLines.size)
        var originalCursor = 0
        for (translationLine in translationLines) {
            val candidate = originalLines.asSequence()
                .withIndex()
                .dropWhile { it.index < originalCursor }
                .minByOrNull { indexed ->
                    abs(indexed.value.timeMs - translationLine.timeMs)
                }
                ?: run {
                    Log.w(TAG, "[QQMusic] Translation has more lines than primary lyric")
                    return translationLines.joinToString("\n") { it.content }
                }

            val deltaMs = abs(candidate.value.timeMs - translationLine.timeMs)
            if (deltaMs > MAX_TRANSLATION_MATCH_DELTA_MS) {
                Log.w(
                    TAG,
                    "[QQMusic] Translation timestamp match is unreliable: " +
                        "translation=${translationLine.timeMs}, " +
                        "primary=${candidate.value.timeMs}, delta=$deltaMs"
                )
                return translationLines.joinToString("\n") { it.content }
            }

            output += translationLine.content.replaceRange(
                translationLine.timeRange,
                formatQqTimestamp(candidate.value.timeMs)
            )
            originalCursor = candidate.index + 1
        }

        Log.i(
            TAG,
            "[QQMusic] Normalized translation lines: " +
                "source=${parseQqTimedLines(rawTranslation, false).size}, " +
                "kept=${translationLines.size}"
        )
        return output.joinToString("\n").takeIf { it.isNotBlank() }
    }

    private data class QqTimedLine(
        val content: String,
        val timeRange: IntRange,
        val timeMs: Long
    )

    private val QQ_LRC_TIME_PATTERN = Regex(
        """\[(\d{1,3}):(\d{2})(?:\.(\d{1,3}))?]"""
    )
    private val QQ_METADATA_LINE_PATTERN = Regex("""^\s*\[[A-Za-z][^]]*]""")
    private const val MAX_TRANSLATION_MATCH_DELTA_MS = 1_000L

    private fun parseQqTimedLines(
        content: String,
        filterTranslationNoise: Boolean
    ): List<QqTimedLine> = content.lines().mapNotNull { rawLine ->
        val line = rawLine.trim()
        if (line.isBlank()) return@mapNotNull null
        if (filterTranslationNoise && QQ_METADATA_LINE_PATTERN.containsMatchIn(line)) {
            return@mapNotNull null
        }

        val timeTag = QQ_LRC_TIME_PATTERN.find(line) ?: return@mapNotNull null
        val text = line.substring(timeTag.range.last + 1).trim()
        if (filterTranslationNoise && shouldDropTranslationLine(text)) {
            return@mapNotNull null
        }

        val fraction = timeTag.groupValues[3]
        val fractionMs = when (fraction.length) {
            0 -> 0L
            1 -> fraction.toLongOrNull()?.times(100L)
            2 -> fraction.toLongOrNull()?.times(10L)
            3 -> fraction.toLongOrNull()
            else -> null
        } ?: return@mapNotNull null
        val minutes = timeTag.groupValues[1].toLongOrNull()
            ?: return@mapNotNull null
        val seconds = timeTag.groupValues[2].toLongOrNull()
            ?: return@mapNotNull null

        QqTimedLine(
            content = line,
            timeRange = timeTag.range,
            timeMs = minutes * 60_000L + seconds * 1_000L + fractionMs
        )
    }

    private fun shouldDropTranslationLine(text: String): Boolean {
        if (text.isBlank() || text.contains("本翻译作品的著作权")) return true
        val compact = text.filterNot(Char::isWhitespace)
            .replace('／', '/')
            .replace('＼', '\\')
        return compact.isNotEmpty() && compact.all { it == '/' || it == '\\' }
    }

    private fun formatQqTimestamp(timeMs: Long): String {
        val totalSeconds = timeMs / 1_000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        val millis = timeMs % 1_000L
        return String.format(Locale.ROOT, "[%02d:%02d.%03d]", minutes, seconds, millis)
    }

    private fun downloadRaw(musicId: String): String {
        val params = mapOf(
            "version" to "15",
            "miniversion" to "100",
            "lrctype" to "4",
            "musicid" to musicId
        )

        val postData = params.entries.joinToString("&") { (k, v) ->
            "${k}=${URLEncoder.encode(v, "UTF-8")}"
        }

        return download(
            URI.create(LYRIC_URL).toURL(),
            requestMethod = "POST",
            body = postData.toByteArray(StandardCharsets.UTF_8)
        )
    }

    private fun download(
        url: java.net.URL,
        requestMethod: String,
        body: ByteArray? = null
    ): String {
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.apply {
                this.requestMethod = requestMethod
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Referer", "https://y.qq.com/")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                }
            }

            if (body != null) {
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw RuntimeException("HTTP $code")
            }
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun extractCData(xml: String, tagName: String): String? {
        return try {
            val pattern = "<$tagName[^>]*>.*?<!\\[CDATA\\[(.*?)]]>"
            val regex = Pattern.compile(pattern, Pattern.DOTALL)
            val matcher = regex.matcher(xml)
            if (matcher.find()) matcher.group(1)?.trim() else null
        } catch (e: Exception) {
            Log.e(TAG, "[QQMusic] XML parse error")
            null
        }
    }
}
