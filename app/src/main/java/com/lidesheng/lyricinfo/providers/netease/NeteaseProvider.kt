package com.lidesheng.lyricinfo.providers.netease

import android.util.Log
import com.lidesheng.lyricinfo.core.BaseLyricProvider
import com.lidesheng.lyricinfo.core.LyricResult

class NeteaseProvider : BaseLyricProvider() {

    companion object {
        private const val TAG = "LyricInfo"
        const val PACKAGE_NAME = "com.netease.cloudmusic"
        const val HONOR_PACKAGE_NAME = "com.hihonor.cloudmusic"
    }

    override val packageName = PACKAGE_NAME
    override val processNames = listOf(PACKAGE_NAME, "$PACKAGE_NAME:play", HONOR_PACKAGE_NAME)

    override fun fetchLyric(mediaId: String, title: String?, artist: String?): LyricResult? {
        val id = mediaId.toLongOrNull()
        if (id == null) {
            Log.w(TAG, "[Netease] Invalid ID: $mediaId")
            return null
        }
        return NeteaseApi.fetchLyric(id)
    }
}
