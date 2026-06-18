package com.lidesheng.lyricinfo.providers.qqmusic

import android.util.Log
import com.lidesheng.lyricinfo.core.LyricNormalizer
import com.lidesheng.lyricinfo.core.LyricResult
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

internal object QQMusicApi {

    private const val TAG = "LyricInfo"
    private const val LYRIC_URL = "https://c.y.qq.com/qqmusic/fcgi-bin/lyric_download.fcg"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

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
                lyric = merged,
                format = normalized.format,
                translation = transNormalized?.format ?: ""
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

        val url = URI.create(LYRIC_URL).toURL()
        val conn = url.openConnection() as HttpURLConnection

        return try {
            conn.apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 10_000
                doOutput = true
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Referer", "https://y.qq.com/")
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }

            conn.outputStream.use { it.write(postData.toByteArray(StandardCharsets.UTF_8)) }

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
