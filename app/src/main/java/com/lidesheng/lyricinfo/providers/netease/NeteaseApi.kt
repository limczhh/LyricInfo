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
                translation = translationLane,
                roma = romaLane,
                // Preserve both originals for the shared LRC -> YRC text bridge.
                referenceLyric = lrc?.let(LyricNormalizer::normalize)?.lyric
            )
        } catch (e: Exception) {
            Log.e(TAG, "[Netease] API error: ${e.message}")
            null
        }
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
