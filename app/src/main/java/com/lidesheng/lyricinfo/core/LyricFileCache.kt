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
            if (json.optInt(CACHE_VERSION_KEY, -1) != CACHE_VERSION) return null

            val lyric = json.optString("lyric", "").takeIf { it.isNotBlank() } ?: return null
            val songName = json.optString("songName", "").trim().takeIf { it.isNotBlank() } ?: return null
            val artist = json.optString("artist", "").trim().takeIf { it.isNotBlank() } ?: return null
            LyricCacheEntry(
                result = LyricResult(
                    lyric = lyric,
                    rawLyric = json.optString("rawLyric", "").takeIf { it.isNotBlank() },
                    translation = json.optString("translation", "").takeIf { it.isNotBlank() },
                    roma = json.optString("roma", "").takeIf { it.isNotBlank() }
                ),
                songName = songName,
                artist = artist,
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
                .put(CACHE_VERSION_KEY, CACHE_VERSION)
                .put("songName", entry.songName)
                .put("artist", entry.artist)
                .put("lyric", entry.result.lyric)
                .apply {
                    putOptional("songId", entry.songId)
                    putOptional("album", entry.album)
                    putOptional("rawLyric", entry.result.rawLyric)
                    putOptional("translation", entry.result.translation)
                    putOptional("roma", entry.result.roma)
                }
                .toString()
            cacheFile(songKey).writeText(json)
        } catch (e: Exception) {
            Log.w(TAG, "[FileCache] Write failed: $songKey", e)
        }
    }

    private fun cacheFile(songKey: String): File {
        return File(cacheDir, "${songKey.hashCode().toString(16)}.json")
    }

    private fun JSONObject.putOptional(key: String, value: String?) {
        value?.trim()?.takeIf { it.isNotBlank() }?.let { put(key, it) }
    }

    companion object {
        private const val TAG = "LyricInfo"
        private const val CACHE_VERSION_KEY = "cacheVersion"
        private const val CACHE_VERSION = 3
    }
}
