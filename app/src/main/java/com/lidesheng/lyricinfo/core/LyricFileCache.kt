package com.lidesheng.lyricinfo.core

import android.util.Log
import org.json.JSONObject
import java.io.File

data class LyricCacheEntry(
    val result: LyricResult,
    val songName: String = "",
    val artist: String = "",
    val album: String = "",
    val songId: String = ""
)

internal class LyricFileCache(private val cacheDir: File) {

    fun read(songKey: String): LyricCacheEntry? {
        return try {
            val file = cacheFile(songKey)
            if (!file.exists()) return null
            val json = JSONObject(file.readText())
            val lyric = json.optString("lyric", "").takeIf { it.isNotBlank() } ?: return null
            LyricCacheEntry(
                result = LyricResult(
                    lyric = lyric,
                    format = json.optString("format", "lrc"),
                    translation = json.optString("translation", "lrc")
                ),
                songName = json.optString("songName", ""),
                artist = json.optString("artist", ""),
                album = json.optString("album", ""),
                songId = json.optString("songId", "")
            )
        } catch (e: Exception) {
            Log.w(TAG, "[FileCache] Read failed: $songKey", e)
            null
        }
    }

    fun write(songKey: String, entry: LyricCacheEntry) {
        try {
            if (!cacheDir.exists()) cacheDir.mkdirs()
            val json = JSONObject()
                .put("songName", entry.songName)
                .put("artist", entry.artist)
                .put("album", entry.album)
                .put("songId", entry.songId)
                .put("lyric", entry.result.lyric)
                .put("format", entry.result.format)
                .put("translation", entry.result.translation)
                .toString()
            cacheFile(songKey).writeText(json)
        } catch (e: Exception) {
            Log.w(TAG, "[FileCache] Write failed: $songKey", e)
        }
    }

    private fun cacheFile(songKey: String): File {
        return File(cacheDir, "${songKey.hashCode().toString(16)}.json")
    }

    companion object {
        private const val TAG = "LyricInfo"
    }
}
