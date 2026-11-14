package com.lidesheng.lyricinfo.core

import android.util.Log
import org.json.JSONObject
import java.io.File

data class LyricCacheEntry(
    val result: PreparedLyric,
    val songName: String = "",
    val artist: String = "",
    val album: String = "",
    val songId: String = "",
    val songMid: String = ""
)

internal class LyricFileCache(private val cacheDir: File) {

    fun read(songKey: String): LyricCacheEntry? {
        return try {
            val file = cacheFile(songKey)
            if (!file.exists()) return null
            val json = JSONObject(file.readText())
            if (json.optInt(CACHE_VERSION_KEY, -1) != CACHE_VERSION) return null

            val source = json.optJSONObject("source") ?: return null
            val prepared = json.optJSONObject("prepared") ?: return null
            val sourceLyric = source.optString("lyric", "").takeIf { it.isNotBlank() } ?: return null
            val lyric = prepared.optString("lyric", "").takeIf { it.isNotBlank() } ?: return null
            val songName = json.optString("songName", "").trim().takeIf { it.isNotBlank() } ?: return null
            val artist = json.optString("artist", "").trim().takeIf { it.isNotBlank() } ?: return null
            LyricCacheEntry(
                result = PreparedLyric(
                    source = LyricResult(
                        lyric = sourceLyric,
                        rawLyric = source.optionalString("rawLyric"),
                        translation = source.optionalString("translation"),
                        roma = source.optionalString("roma"),
                        referenceLyric = source.optionalString("referenceLyric")
                    ),
                    lyric = lyric,
                    rawLyric = prepared.optionalString("rawLyric"),
                    translation = prepared.optionalString("translation"),
                    roma = prepared.optionalString("roma")
                ),
                songName = songName,
                artist = artist,
                album = json.optString("album", ""),
                songId = json.optString("songId", ""),
                songMid = json.optString("songMid", "")
            )
        } catch (e: Exception) {
            Log.w(TAG, "[FileCache] Read failed: $songKey", e)
            null
        }
    }

    fun write(songKey: String, entry: LyricCacheEntry) {
        try {
            if (!cacheDir.exists()) cacheDir.mkdirs()
            val source = entry.result.source
            val json = JSONObject()
                .put(CACHE_VERSION_KEY, CACHE_VERSION)
                .put("songName", entry.songName)
                .put("artist", entry.artist)
                .put("source", JSONObject().apply {
                    put("lyric", source.lyric)
                    putOptional("rawLyric", source.rawLyric)
                    putOptional("translation", source.translation)
                    putOptional("roma", source.roma)
                    putOptional("referenceLyric", source.referenceLyric)
                })
                .put("prepared", JSONObject().apply {
                    put("lyric", entry.result.lyric)
                    putOptional("rawLyric", entry.result.rawLyric)
                    putOptional("translation", entry.result.translation)
                    putOptional("roma", entry.result.roma)
                })
                .apply {
                    putOptional("songId", entry.songId)
                    putOptional("songMid", entry.songMid)
                    putOptional("album", entry.album)
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
        value?.takeIf { it.isNotBlank() }?.let { put(key, it) }
    }

    private fun JSONObject.optionalString(key: String): String? =
        optString(key, "").takeIf { it.isNotBlank() }

    companion object {
        private const val TAG = "LyricInfo"
        private const val CACHE_VERSION_KEY = "cacheVersion"
        private const val CACHE_VERSION = 7
    }
}
