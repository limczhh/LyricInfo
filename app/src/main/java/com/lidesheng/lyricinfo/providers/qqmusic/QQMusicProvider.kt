package com.lidesheng.lyricinfo.providers.qqmusic

import android.util.Log
import com.lidesheng.lyricinfo.core.BaseLyricProvider
import com.lidesheng.lyricinfo.core.LyricResult

class QQMusicProvider : BaseLyricProvider() {

    companion object {
        private const val TAG = "LyricInfo"
        const val PACKAGE_NAME = "com.tencent.qqmusic"
        private const val PLAYER_SERVICE = "$PACKAGE_NAME:QQPlayerService"
    }

    override val packageName = PACKAGE_NAME
    override val processNames = listOf(PACKAGE_NAME, PLAYER_SERVICE)

    override fun fetchLyric(mediaId: String, title: String?, artist: String?): LyricResult? {
        if (mediaId.isBlank()) {
            Log.w(TAG, "[QQMusic] Empty media ID")
            return null
        }
        return QQMusicApi.fetchLyric(mediaId)
    }
}
