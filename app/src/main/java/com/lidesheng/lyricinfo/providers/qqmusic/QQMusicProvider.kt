package com.lidesheng.lyricinfo.providers.qqmusic

import android.media.MediaMetadata
import android.os.Bundle
import android.util.Log
import com.lidesheng.lyricinfo.core.BaseLyricProvider
import com.lidesheng.lyricinfo.core.LyricResult
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class QQMusicProvider : BaseLyricProvider() {

    companion object {
        private const val TAG = "LyricInfo"
        const val PACKAGE_NAME = "com.tencent.qqmusic"
        private const val PLAYER_SERVICE = "$PACKAGE_NAME:QQPlayerService"
    }

    override val packageName = PACKAGE_NAME
    override val processNames = listOf(PACKAGE_NAME, PLAYER_SERVICE)

    private val qqHookHandles = mutableListOf<XposedInterface.HookHandle>()
    private val metadataCache = ConcurrentHashMap<String, TrackMetadata>()
    private val metadataFetchingIds = ConcurrentHashMap.newKeySet<String>()
    private val missingMediaIdLogged = AtomicBoolean(false)
    private val metadataExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "LyricInfo-QQMusicMetadata").apply { isDaemon = true }
    }

    override fun onAppLoaded(module: XposedModule, param: PackageLoadedParam) {
        super.onAppLoaded(module, param)
        hookCompatMetadata(module, param.defaultClassLoader)
    }

    override fun replaceHooks(
        module: XposedModule,
        param: PackageLoadedParam,
        oldHooks: List<XposedInterface.HookHandle>
    ): List<XposedInterface.HookHandle> {
        val baseHooks = super.replaceHooks(module, param, oldHooks)
        qqHookHandles.clear()
        hookCompatMetadata(module, param.defaultClassLoader)
        return baseHooks + qqHookHandles
    }

    override fun onDestroy() {
        qqHookHandles.forEach { it.unhook() }
        qqHookHandles.clear()
        metadataExecutor.shutdownNow()
        metadataCache.clear()
        metadataFetchingIds.clear()
        super.onDestroy()
    }

    override fun resolveTrackMetadata(bundle: Bundle): TrackMetadata? {
        val songId = readMediaId(bundle)
        if (songId.isBlank()) {
            if (missingMediaIdLogged.compareAndSet(false, true)) {
                Log.w(TAG, "[QQMusic] No stable Media ID; skip LyricInfo")
            }
            return null
        }

        metadataCache[songId]?.let { return it }
        loadCachedLyric(songId)?.let { cached ->
            return TrackMetadata(
                songName = cached.songName,
                artist = cached.artist,
                album = cached.album,
                songId = songId
            )
        }
        requestSongMetadata(songId)
        return null
    }

    private fun readMediaId(bundle: Bundle): String {
        val stringId = bundle.getCharSequence(MediaMetadata.METADATA_KEY_MEDIA_ID)
            ?.toString()
            ?.trim()
            .orEmpty()
        val songId = if (stringId.isNotBlank()) {
            stringId
        } else {
            runCatching { bundle.getLong(MediaMetadata.METADATA_KEY_MEDIA_ID) }
                .getOrDefault(0L)
                .takeIf { it > 0L }
                ?.toString()
                .orEmpty()
        }
        return songId.takeIf { it.toLongOrNull()?.let { value -> value > 0L } == true }.orEmpty()
    }

    private fun requestSongMetadata(songId: String) {
        if (metadataCache.containsKey(songId) || !metadataFetchingIds.add(songId)) return

        Log.i(TAG, "[QQMusic] Query song metadata: id=$songId")
        metadataExecutor.execute {
            try {
                val metadata = QQMusicApi.fetchSongMetadata(songId)
                if (metadata == null) {
                    Log.w(TAG, "[QQMusic] No song metadata: id=$songId")
                    return@execute
                }

                val track = TrackMetadata(
                    songName = metadata.songName,
                    artist = metadata.artist,
                    album = metadata.album,
                    songId = metadata.songId
                )
                metadataCache[songId] = track
                Log.i(TAG, "[QQMusic] [Song] ${track.songName} - ${track.artist}")
                requestLyric(track, " (metadata)")
            } catch (e: Exception) {
                Log.e(TAG, "[QQMusic] ✗ Query song metadata: id=$songId", e)
            } finally {
                metadataFetchingIds.remove(songId)
            }
        }
    }

    private fun hookCompatMetadata(module: XposedModule, classLoader: ClassLoader) {
        try {
            val builderClass = classLoader.loadClass(
                "android.support.v4.media.MediaMetadataCompat\$Builder"
            )
            val buildMethod = builderClass.getDeclaredMethod("build")
            val bundleField = builderClass.getDeclaredField("mBundle").apply {
                isAccessible = true
            }
            module.deoptimize(buildMethod)

            val handle = module.hook(buildMethod).intercept { chain ->
                try {
                    val bundle = bundleField.get(chain.thisObject) as Bundle
                    injectLyricInfo(bundle, " (compatBuilder)")
                } catch (e: Exception) {
                    Log.e(TAG, "[QQMusic] ✗ Inject Compat Builder", e)
                }
                chain.proceed()
            }
            qqHookHandles.add(handle)
            Log.i(TAG, "[QQMusic] ✓ Hooked MediaMetadataCompat.Builder.build()")
        } catch (e: Exception) {
            Log.e(TAG, "[QQMusic] ✗ Hook Compat Builder", e)
        }

        try {
            val metadataClass = classLoader.loadClass(
                "android.support.v4.media.MediaMetadataCompat"
            )
            val sessionClass = classLoader.loadClass(
                "android.support.v4.media.session.MediaSessionCompat"
            )
            val setMetadataMethod = sessionClass.getDeclaredMethod(
                "setMetadata",
                metadataClass
            )
            val bundleField = metadataClass.getDeclaredField("mBundle").apply {
                isAccessible = true
            }
            module.deoptimize(setMetadataMethod)

            val handle = module.hook(setMetadataMethod).intercept { chain ->
                try {
                    val metadata = chain.getArg(0)
                    if (metadata != null) {
                        val bundle = bundleField.get(metadata) as Bundle
                        injectLyricInfo(bundle, " (compatSession)")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "[QQMusic] ✗ Inject Compat Session", e)
                }
                chain.proceed()
            }
            qqHookHandles.add(handle)
            Log.i(TAG, "[QQMusic] ✓ Hooked MediaSessionCompat.setMetadata()")
        } catch (e: Exception) {
            Log.e(TAG, "[QQMusic] ✗ Hook Compat Session", e)
        }
    }

    override fun fetchLyric(mediaId: String, title: String?, artist: String?): LyricResult? {
        if (mediaId.isBlank()) {
            Log.w(TAG, "[QQMusic] Empty media ID")
            return null
        }
        return QQMusicApi.fetchLyric(mediaId)
    }
}
