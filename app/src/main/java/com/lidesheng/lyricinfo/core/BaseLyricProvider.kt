package com.lidesheng.lyricinfo.core

import android.media.MediaMetadata
import android.os.Bundle
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

abstract class BaseLyricProvider : LyricProvider {

    companion object {
        private const val TAG = "LyricInfo"
        private const val LYRIC_INFO_KEY = "lyricInfo"
    }

    private var currentMediaId: String? = null
    private var lastLoggedKey: String? = null
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "LyricInfo-${javaClass.simpleName}").apply { isDaemon = true }
    }
    protected val lyricCache = ConcurrentHashMap<String, LyricResult>()
    private val fetchingIds = ConcurrentHashMap.newKeySet<String>()
    private var fileCache: LyricFileCache? = null

    override fun onAppLoaded(module: XposedModule, param: PackageLoadedParam) {
        Log.i(TAG, "[Hook] ${param.packageName}")
        val cacheDir = File(param.applicationInfo.dataDir, "cache/lyric_info")
        fileCache = LyricFileCache(cacheDir)
        installHook(module, param.defaultClassLoader)
    }

    private fun installHook(module: XposedModule, classLoader: ClassLoader) {
        try {
            val builderClass = Class.forName(
                "android.media.MediaMetadata\$Builder", false, classLoader
            )
            val buildMethod = builderClass.getDeclaredMethod("build")

            module.hook(buildMethod).intercept { chain ->
                val builder = chain.thisObject
                val bundleField = builder.javaClass.getDeclaredField("mBundle")
                bundleField.isAccessible = true
                val bundle = bundleField.get(builder) as Bundle

                val mediaId = bundle.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
                val title = bundle.getString(MediaMetadata.METADATA_KEY_TITLE)
                val artist = bundle.getString(MediaMetadata.METADATA_KEY_ARTIST)
                val album = bundle.getString(MediaMetadata.METADATA_KEY_ALBUM)
                val duration = bundle.getLong(MediaMetadata.METADATA_KEY_DURATION)
                val durationStr = formatDuration(duration)

                val songKey = mediaId ?: "$title|$artist|$duration".hashCode().toString()
                Log.d(TAG, "[Hook] build() title=$title, songKey=$songKey, current=$currentMediaId")

                if (songKey != currentMediaId) {
                    currentMediaId = songKey
                    lastLoggedKey = null
                    Log.i(TAG, "[Song] $title - $artist | $album | $durationStr")
                    fetchLyricAsync(songKey, title, artist)
                }

                val result = lyricCache[songKey]
                if (result != null) {
                    val json = JSONObject()
                        .put("songName", title ?: "")
                        .put("artist", artist ?: "")
                        .put("songId", mediaId ?: "")
                        .put("lyric", result.lyric)
                        .put("format", result.format)
                        .put("translation", result.translation)
                        .toString()
                    bundle.putString(LYRIC_INFO_KEY, json)
                    if (songKey != lastLoggedKey) {
                        lastLoggedKey = songKey
                        logLyricPreview(result.lyric)
                    }
                }

                chain.proceed()
            }

            Log.i(TAG, "[Hook] Builder.build() installed")
        } catch (e: Exception) {
            Log.e(TAG, "[Hook] Builder.build() Failed", e)
        }

        // Hook MediaSession.setMetadata() for apps using Media3
        try {
            val sessionClass = Class.forName(
                "android.media.session.MediaSession", false, classLoader
            )
            val setMetaMethod = sessionClass.getDeclaredMethod(
                "setMetadata", MediaMetadata::class.java
            )

            module.hook(setMetaMethod).intercept { chain ->
                val metadata = chain.getArg(0) as? MediaMetadata
                if (metadata != null) {
                    val metaBundleField = metadata.javaClass.getDeclaredField("mBundle")
                    metaBundleField.isAccessible = true
                    val bundle = metaBundleField.get(metadata) as Bundle
                    val mediaId = bundle.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
                    val title = bundle.getString(MediaMetadata.METADATA_KEY_TITLE)
                    val artist = bundle.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    val duration = bundle.getLong(MediaMetadata.METADATA_KEY_DURATION)

                    val songKey = mediaId ?: "$title|$artist|$duration".hashCode().toString()
                    Log.d(TAG, "[Hook] setMetadata() title=$title, songKey=$songKey")

                    if (songKey != currentMediaId) {
                        currentMediaId = songKey
                        lastLoggedKey = null
                        Log.i(TAG, "[Song] $title - $artist")
                        fetchLyricAsync(songKey, title, artist)
                    }

                    val result = lyricCache[songKey]
                    if (result != null) {
                        val json = JSONObject()
                            .put("songName", title ?: "")
                            .put("artist", artist ?: "")
                            .put("songId", mediaId ?: "")
                            .put("lyric", result.lyric)
                            .put("format", result.format)
                            .put("translation", result.translation)
                            .toString()
                        // Inject via reflection on the metadata object
                        val extrasField = metadata.javaClass.getDeclaredField("mBundle")
                        extrasField.isAccessible = true
                        val extras = extrasField.get(metadata) as Bundle
                        extras.putString(LYRIC_INFO_KEY, json)
                        if (songKey != lastLoggedKey) {
                            lastLoggedKey = songKey
                            logLyricPreview(result.lyric)
                        }
                    }
                }

                chain.proceed()
            }

            Log.i(TAG, "[Hook] MediaSession.setMetadata() installed")
        } catch (e: Exception) {
            Log.e(TAG, "[Hook] MediaSession.setMetadata() Failed", e)
        }
    }

    private fun logLyricPreview(lyrics: String) {
        val preview = lyrics.lines()
            .filter { it.isNotBlank() }
            .take(5)
            .joinToString(" | ")

        Log.i(TAG, "[Inject] ${lyrics.length} chars | $preview")
    }

    private fun formatDuration(ms: Long): String {
        if (ms <= 0) return "--:--"
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return "%d:%02d".format(min, sec)
    }

    private fun fetchLyricAsync(mediaId: String, title: String?, artist: String?) {
        if (lyricCache.containsKey(mediaId)) return
        if (!fetchingIds.add(mediaId)) return

        executor.execute {
            try {
                // Check file cache first
                val cached = fileCache?.read(mediaId)
                if (cached != null) {
                    lyricCache[mediaId] = cached
                    Log.d(TAG, "[FileCache] $title (${cached.lyric.length} chars)")
                    return@execute
                }

                val result = fetchLyric(mediaId, title, artist)
                if (result != null && result.lyric.isNotBlank()) {
                    lyricCache[mediaId] = result
                    fileCache?.write(mediaId, result)
                    Log.d(TAG, "[Cache] $title (${result.lyric.length} chars)")
                } else {
                    Log.w(TAG, "[Cache] No lyric: $title")
                }
            } catch (e: Exception) {
                Log.e(TAG, "[Fetch] Failed: $title", e)
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
    }
}
