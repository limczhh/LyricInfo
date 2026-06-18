package com.lidesheng.lyricinfo.providers.netease

import android.util.Log
import com.lidesheng.lyricinfo.core.LyricNormalizer
import com.lidesheng.lyricinfo.core.LyricResult
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

internal object NeteaseApi {

    private const val TAG = "LyricInfo"
    private const val BASE_URL = "https://interface.music.163.com/eapi/"

    fun fetchLyric(musicId: Long): LyricResult? {
        return try {
            val params = JSONObject().apply {
                put("id", musicId.toString())
                put("cp", false)
                put("lv", 0); put("tv", 0); put("rv", 0)
                put("yv", 0); put("ytv", 0); put("yrv", 0)
            }

            val response = request("song/lyric/v1", params.toString())
            val json = JSONObject(response)

            val lrcRaw = json.optJSONObject("lrc")?.optString("lyric")
            val yrcRaw = json.optJSONObject("yrc")?.optString("lyric")
            val tlyricRaw = json.optJSONObject("tlyric")?.optString("lyric")

            val lrc = lrcRaw?.takeIf { it.isNotBlank() }
            val translation = tlyricRaw?.takeIf { it.isNotBlank() }

            // yrc 是混合格式：前几行是 JSON（元数据），后面是真正的逐字格式 [ms,dur](ms,dur,text)text
            // 过滤掉 JSON 行，保留逐字格式行
            val yrcFiltered = yrcRaw?.takeIf { it.isNotBlank() }?.let { filterYrcLines(it) }

            // 优先尝试转换 YRC（逐字歌词）到 elrc 格式，其次转换标准 LRC
            val normalized = when {
                yrcFiltered != null -> LyricNormalizer.normalize(yrcFiltered)
                lrc != null -> LyricNormalizer.normalize(lrc)
                else -> return null
            } ?: return null

            // Normalize and merge translation (tlyric is usually standard LRC)
            val transNormalized = translation?.let { LyricNormalizer.normalize(it) }
            val merged = if (transNormalized != null) {
                LyricNormalizer.merge(normalized.lyric, transNormalized.lyric)
            } else {
                normalized.lyric
            }

            LyricResult(lyric = merged, format = normalized.format, translation = transNormalized?.format ?: "")
        } catch (e: Exception) {
            Log.e(TAG, "[Netease] API error: ${e.message}")
            null
        }
    }

    /**
     * 过滤 yrc 内容：跳过 JSON 行（元数据），保留逐字格式行。
     * 网易云 yrc 返回混合格式：
     * - JSON 行：{"t":0,"c":[{"tx":"作词: "},{"tx":"Taylor Swift"}]}
     * - 逐字行：[16440,3640](0,480,0)窗(480,420,0)外...
     */
    private fun filterYrcLines(yrc: String): String? {
        val filtered = yrc.lines().filter { line ->
            val trimmed = line.trim()
            trimmed.isNotBlank() && !trimmed.startsWith("{")
        }
        return filtered.takeIf { it.isNotEmpty() }?.joinToString("\n")
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
