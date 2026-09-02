package com.lidesheng.lyricinfo.core

import org.json.JSONObject

/** Encodes the public lyricInfo contract without emitting empty optional lanes. */
internal object LyricInfoSerializer {

    fun encode(
        songName: String?,
        artist: String?,
        songId: String?,
        album: String?,
        result: LyricResult
    ): String? {
        val validSongName = songName?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val validArtist = artist?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val validLyric = result.lyric.takeIf { it.isNotBlank() } ?: return null

        return JSONObject().apply {
            put("songName", validSongName)
            put("artist", validArtist)
            put("lyric", validLyric)
            putOptional("songId", songId)
            putOptional("album", album)
            putOptional("rawLyric", result.rawLyric)
            putOptional("translation", result.translation)
            putOptional("roma", result.roma)
        }.toString()
    }

    private fun JSONObject.putOptional(key: String, value: String?) {
        value?.trim()?.takeIf { it.isNotBlank() }?.let { put(key, it) }
    }
}
