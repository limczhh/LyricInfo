package com.lidesheng.lyricinfo.providers.qqmusic

import android.util.Log
import com.lidesheng.lyricinfo.core.BaseLyricProvider
import com.lidesheng.lyricinfo.core.LyricResult
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.util.concurrent.atomic.AtomicReference

class QQMusicProvider : BaseLyricProvider() {

    companion object {
        private const val TAG = "LyricInfo"
        const val PACKAGE_NAME = "com.tencent.qqmusic"
        private const val PLAYER_SERVICE = "$PACKAGE_NAME:QQPlayerService"
    }

    override val packageName = PACKAGE_NAME
    override val processNames = listOf(PACKAGE_NAME, PLAYER_SERVICE)

    private val currentTrack = AtomicReference<TrackMetadata?>(null)
    private val qqHookHandles = mutableListOf<XposedInterface.HookHandle>()

    override fun onAppLoaded(module: XposedModule, param: PackageLoadedParam) {
        super.onAppLoaded(module, param)
        hookCurrentSong(module, param.defaultClassLoader)
    }

    override fun replaceHooks(
        module: XposedModule,
        param: PackageLoadedParam,
        oldHooks: List<XposedInterface.HookHandle>
    ): List<XposedInterface.HookHandle> {
        val baseHooks = super.replaceHooks(module, param, oldHooks)
        qqHookHandles.clear()
        hookCurrentSong(module, param.defaultClassLoader)
        return baseHooks + qqHookHandles
    }

    override fun onDestroy() {
        qqHookHandles.forEach { it.unhook() }
        qqHookHandles.clear()
        currentTrack.set(null)
        super.onDestroy()
    }

    override fun resolveTrackMetadata(bundle: android.os.Bundle): TrackMetadata {
        return currentTrack.get() ?: super.resolveTrackMetadata(bundle)
    }

    private fun hookCurrentSong(module: XposedModule, classLoader: ClassLoader) {
        try {
            val controllerClass = classLoader.loadClass(
                "com.tencent.qqmusicplayerprocess.servicenew.mediasession.s"
            )
            val songInfoClass = classLoader.loadClass(
                "com.tencent.qqmusicplayerprocess.songinfo.SongInfo"
            )
            val method = controllerClass.getDeclaredMethod("v", songInfoClass)

            val handle = module.hook(method).intercept { chain ->
                val songInfo = chain.getArg(0)
                if (songInfo == null) {
                    currentTrack.set(null)
                } else {
                    val track = readTrackMetadata(songInfo)
                    if (track != null) {
                        val previous = currentTrack.getAndSet(track)
                        if (previous?.songId != track.songId) {
                            Log.i(TAG, "[QQMusic] [Song] ${track.songName} - ${track.artist}")
                        }
                    } else {
                        currentTrack.set(null)
                    }
                }
                chain.proceed()
            }
            qqHookHandles.add(handle)
            Log.i(TAG, "[QQMusic] ✓ Hooked current SongInfo: ${method.name}")
        } catch (e: Exception) {
            Log.e(TAG, "[QQMusic] ✗ Hook current SongInfo", e)
        }
    }

    private fun readTrackMetadata(songInfo: Any): TrackMetadata? {
        return try {
            val songId = (songInfo.javaClass.getMethod("x2").invoke(songInfo) as? Number)
                ?.toLong()
                ?.takeIf { it != 0L }
                ?.toString()
                ?: return null
            TrackMetadata(
                songName = invokeString(songInfo, "Z2").orEmpty(),
                artist = invokeString(songInfo, "E3").orEmpty(),
                album = invokeString(songInfo, "u1").orEmpty(),
                songId = songId
            )
        } catch (e: Exception) {
            Log.e(TAG, "[QQMusic] ✗ Read SongInfo", e)
            null
        }
    }

    private fun invokeString(songInfo: Any, methodName: String): String? {
        return songInfo.javaClass.getMethod(methodName).invoke(songInfo) as? String
    }

    override fun fetchLyric(mediaId: String, title: String?, artist: String?): LyricResult? {
        if (mediaId.isBlank()) {
            Log.w(TAG, "[QQMusic] Empty media ID")
            return null
        }
        return QQMusicApi.fetchLyric(mediaId)
    }
}
