package com.lidesheng.lyricinfo.core

import org.json.JSONObject

/** Encodes the public lyricInfo contract without emitting empty optional lanes. */
internal object LyricInfoSerializer {

    /**
     * Encodes the metadata-only payload used while a lyric request is pending.
     * Keep this on the existing public schema: an empty lyric is safer than
     * inventing a timestamped line or adding a new required status field.
     */
    fun encodePending(
        songName: String?,
        artist: String?,
        songId: String?,
        album: String?,
        songMid: String? = null,
        identity: String? = songId
    ): String? = encodePayload(
        songName = songName,
        artist = artist,
        songId = songId,
        album = album,
        songMid = songMid,
        lyric = "",
        identity = identity
    )

    fun encode(
        songName: String?,
        artist: String?,
        songId: String?,
        album: String?,
        result: LyricResult,
        songMid: String? = null,
        identity: String? = songId
    ): String? {
        val validLyric = LyricNormalizer.cleanRawLyric(result.lyric)
        val stableIdentity = identity?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val publicSongId = songId?.trim()?.takeIf { it.isNotBlank() } ?: stableIdentity
        val validSongName = songName?.trim().orEmpty()
        val validArtist = artist?.trim().orEmpty()
        return JSONObject().apply {
            put("songName", validSongName)
            put("artist", validArtist)
            put("lyric", validLyric)
            putOptional("songId", publicSongId)
            putOptional("songMid", songMid)
            putOptional("album", album)
            putOptional("rawLyric", result.rawLyric?.let(LyricNormalizer::cleanRawLyric))
            putOptional("translation", result.translation?.let(LyricNormalizer::cleanRawLyric))
            putOptional("roma", result.roma?.let(LyricNormalizer::cleanRawLyric))
        }.toString()
    }

    private fun encodePayload(
        songName: String?,
        artist: String?,
        songId: String?,
        album: String?,
        songMid: String?,
        lyric: String,
        identity: String?
    ): String? {
        val stableIdentity = identity?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val publicSongId = songId?.trim()?.takeIf { it.isNotBlank() } ?: stableIdentity
        return JSONObject().apply {
            put("songName", songName?.trim().orEmpty())
            put("artist", artist?.trim().orEmpty())
            put("lyric", lyric)
            putOptional("songId", publicSongId)
            putOptional("songMid", songMid)
            putOptional("album", album)
        }.toString()
    }

    private fun JSONObject.putOptional(key: String, value: String?) {
        value?.trim()?.takeIf { it.isNotBlank() }?.let { put(key, it) }
    }
}
