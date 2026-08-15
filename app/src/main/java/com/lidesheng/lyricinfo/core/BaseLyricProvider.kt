package com.lidesheng.lyricinfo.core

import android.annotation.SuppressLint
import android.media.MediaMetadata
import android.os.Bundle
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

@SuppressLint("SoonBlockedPrivateApi")
abstract class BaseLyricProvider : LyricProvider {

    protected data class TrackMetadata(
        val songName: String,
        val artist: String,
        val album: String,
        val songId: String,
        val cacheKey: String = songId
    )

    companion object {
        private const val TAG = "LyricInfo"
        private const val LYRIC_INFO_KEY = "lyricInfo"
    }

    private var currentMediaId: String? = null
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "LyricInfo-${javaClass.simpleName}").apply { isDaemon = true }
    }
    protected val lyricCache = ConcurrentHashMap<String, LyricResult>()
    private val fetchingIds = ConcurrentHashMap.newKeySet<String>()
    private var fileCache: LyricFileCache? = null
    private val hookHandles = mutableListOf<XposedInterface.HookHandle>()

    override fun onAppLoaded(module: XposedModule, param: PackageLoadedParam) {
        Log.i(TAG, "[Hook] ${param.packageName}")
        val cacheDir = File(param.applicationInfo.dataDir, "cache/lyric_info")
        fileCache = LyricFileCache(cacheDir)
        installHook(module, param.defaultClassLoader)
    }

    override fun replaceHooks(
        module: XposedModule,
        param: PackageLoadedParam,
        oldHooks: List<XposedInterface.HookHandle>
    ): List<XposedInterface.HookHandle> {
        oldHooks.forEach { it.unhook() }
        hookHandles.clear()
        installHook(module, param.defaultClassLoader)
        return hookHandles.toList()
    }

    private fun installHook(module: XposedModule, classLoader: ClassLoader) {
        try {
            val builderClass = Class.forName(
                "android.media.MediaMetadata\$Builder", false, classLoader
            )
            val buildMethod = builderClass.getDeclaredMethod("build")

            val handle = module.hook(buildMethod).intercept { chain ->
                val builder = chain.thisObject
                val bundleField = builder.javaClass.getDeclaredField("mBundle")
                bundleField.isAccessible = true
                val bundle = bundleField.get(builder) as Bundle

                val track = resolveTrackMetadata(bundle)
                val songKey = track.cacheKey

                if (songKey != currentMediaId) {
                    currentMediaId = songKey
                    Log.i(TAG, "[Song] ${track.songName} - ${track.artist}")
                    fetchLyricAsync(songKey, track.songName, track.artist)
                }

                val result = lyricCache[songKey]
                if (result != null) {
                    val json = JSONObject()
                        .put("songName", track.songName)
                        .put("artist", track.artist)
                        .put("album", track.album)
                        .put("songId", track.songId)
                        .put("lyric", result.lyric)
                        .put("format", result.format)
                        .put("translation", result.translation)
                        .toString()
                    bundle.putString(LYRIC_INFO_KEY, json)
                    Log.d(TAG, "[Inject] ✓ ${track.songName}")
                }

                chain.proceed()
            }
            hookHandles.add(handle)
            Log.i(TAG, "[Hook] ✓ Builder.build()")
        } catch (e: Exception) {
            Log.e(TAG, "[Hook] ✗ Builder.build()", e)
        }

        try {
            val sessionClass = Class.forName(
                "android.media.session.MediaSession", false, classLoader
            )
            val setMetaMethod = sessionClass.getDeclaredMethod(
                "setMetadata", MediaMetadata::class.java
            )

            val handle = module.hook(setMetaMethod).intercept { chain ->
                val metadata = chain.getArg(0) as? MediaMetadata
                if (metadata != null) {
                    val metaBundleField = metadata.javaClass.getDeclaredField("mBundle")
                    metaBundleField.isAccessible = true
                    val bundle = metaBundleField.get(metadata) as Bundle
                    val track = resolveTrackMetadata(bundle)
                    val songKey = track.cacheKey

                    if (songKey != currentMediaId) {
                        currentMediaId = songKey
                        Log.i(TAG, "[Song] ${track.songName} - ${track.artist} (setMetadata)")
                        fetchLyricAsync(songKey, track.songName, track.artist)
                    }

                    val result = lyricCache[songKey]
                    if (result != null) {
                        val json = JSONObject()
                            .put("songName", track.songName)
                            .put("artist", track.artist)
                            .put("album", track.album)
                            .put("songId", track.songId)
                            .put("lyric", result.lyric)
                            .put("format", result.format)
                            .put("translation", result.translation)
                            .toString()
                        val extrasField = metadata.javaClass.getDeclaredField("mBundle")
                        extrasField.isAccessible = true
                        val extras = extrasField.get(metadata) as Bundle
                        extras.putString(LYRIC_INFO_KEY, json)
                        Log.d(TAG, "[Inject] ✓ ${track.songName} (setMetadata)")
                    }
                }

                chain.proceed()
            }
            hookHandles.add(handle)
            Log.i(TAG, "[Hook] ✓ MediaSession.setMetadata()")
        } catch (e: Exception) {
            Log.e(TAG, "[Hook] ✗ MediaSession.setMetadata()", e)
        }
    }

    protected open fun resolveTrackMetadata(bundle: Bundle): TrackMetadata {
        val mediaId = bundle.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty()
        val songName = bundle.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = bundle.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        val album = bundle.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
        val duration = bundle.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val cacheKey = mediaId.ifEmpty { "$songName|$artist|$duration".hashCode().toString() }
        return TrackMetadata(songName, artist, album, mediaId, cacheKey)
    }

    private fun fetchLyricAsync(mediaId: String, title: String?, artist: String?) {
        if (lyricCache.containsKey(mediaId)) return
        if (!fetchingIds.add(mediaId)) return

        executor.execute {
            try {
                val cached = fileCache?.read(mediaId)
                if (cached != null) {
                    lyricCache[mediaId] = cached
                    Log.d(TAG, "[Fetch] ✓ File cache: $title")
                    return@execute
                }

                val result = fetchLyric(mediaId, title, artist)
                if (result != null && result.lyric.isNotBlank()) {
                    lyricCache[mediaId] = result
                    fileCache?.write(mediaId, result)
                    Log.d(TAG, "[Fetch] ✓ API: $title")
                } else {
                    Log.w(TAG, "[Fetch] ✗ No lyric: $title")
                }
            } catch (e: Exception) {
                Log.e(TAG, "[Fetch] ✗ $title", e)
            } finally {
                fetchingIds.remove(mediaId)
            }
        }
    }

    protected abstract fun fetchLyric(mediaId: String, title: String?, artist: String?): LyricResult?

    override fun onDestroy() {
        executor.shutdownNow()
        lyricCache.clear()
        fetchingIds.clear()
        currentMediaId = null
        hookHandles.clear()
    }
}
