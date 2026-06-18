package com.lidesheng.lyricinfo.providers.saltplayer

object DexkitLoader {

    @Volatile
    private var loaded = false

    fun load() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            System.loadLibrary("dexkit")
            loaded = true
        }
    }
}
