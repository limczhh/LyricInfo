package com.lidesheng.lyricinfo.providers.lxmusic

/**
 * IKun Music 是 LX Music 的变体。
 * 包名：com.ikunshare.music.mobile
 * LyricModule：com.ikunshare.music.mobile.lyric.LyricModule
 */
class IKunMusicProvider : LxMusicProvider(
    packageName = "com.ikunshare.music.mobile",
    lyricModuleClass = "com.ikunshare.music.mobile.lyric.LyricModule"
)
