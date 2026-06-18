package com.lidesheng.lyricinfo.core

import android.util.Log
import org.json.JSONObject
import java.io.File

internal class LyricFileCache(private val cacheDir: File) {

    fun read(songKey: String): LyricResult? {
        return try {
            val file = cacheFile(songKey)
            if (!file.exists()) return null
            val json = JSONObject(file.readText())
            val lyric = json.optString("lyric", "").takeIf { it.isNotBlank() } ?: return null
            LyricResult(
                lyric = lyric,
                format = json.optString("format", "lrc"),
                translation = json.optString("translation", "lrc")
            )
        } catch (e: Exception) {
            Log.w(TAG, "[FileCache] Read failed: $songKey", e)
            null
        }
    }

    fun write(songKey: String, result: LyricResult) {
        try {
            if (!cacheDir.exists()) cacheDir.mkdirs()
            val json = JSONObject()
                .put("lyric", result.lyric)
                .put("format", result.format)
                .put("translation", result.translation)
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
