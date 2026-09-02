package com.lidesheng.lyricinfo.providers.qqmusic

import android.util.Log
import com.lidesheng.lyricinfo.core.LyricNormalizer
import com.lidesheng.lyricinfo.core.LyricResult
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

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
     * 获取歌词。返回 LyricResult：
     * - lyric: 原文 + 翻译按时间戳合并（QRC 自动转为 elrc 逐字歌词优先，否则标准 LRC）
     * - format: 歌词格式（elrc 或 lrc）
     * - translation: 翻译行的格式指示（elrc 或 lrc），无翻译时默认 lrc
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

            // Normalize and merge translation
            val transNormalized = translationLrc?.let { LyricNormalizer.normalize(it) }
            val merged = if (transNormalized != null) {
                LyricNormalizer.merge(normalized.lyric, transNormalized.lyric)
            } else {
                normalized.lyric
            }

            LyricResult(
                lyric = merged
            )
        } catch (e: Exception) {
            Log.e(TAG, "[QQMusic] API error: ${e.message}")
            null
        }
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
