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

    @Volatile
    private var currentMediaId: String? = null
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "LyricInfo-${javaClass.simpleName}").apply { isDaemon = true }
    }
    protected val lyricCache = ConcurrentHashMap<String, LyricResult>()
    private val fetchingIds = ConcurrentHashMap.newKeySet<String>()
    private var fileCache: LyricFileCache? = null
    private val hookHandles = mutableListOf<XposedInterface.HookHandle>()

    /**
     * Providers whose source of truth is an online API can disable the generic
     * disk cache. This keeps an API failure from silently becoming a stale lyric
     * fallback while retaining the in-memory request de-duplication.
     */
    protected open val useFileCache: Boolean
        get() = true

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

                val track = injectLyricInfo(bundle)
                onMediaMetadataBuilt(bundle, track)

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
                    onMediaSessionMetadataObserved(chain.thisObject, metadata)
                    val metaBundleField = metadata.javaClass.getDeclaredField("mBundle")
                    metaBundleField.isAccessible = true
                    val bundle = metaBundleField.get(metadata) as Bundle
                    val track = injectLyricInfo(bundle, " (setMetadata)")
                    if (track != null) {
                        onMediaSessionTrackResolved(chain.thisObject, metadata, track)
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

    /**
     * Adds LyricInfo to a metadata bundle before the target media session receives it.
     * Providers with a support-library MediaSession can reuse this path as well.
     */
    protected fun injectLyricInfo(bundle: Bundle, logSuffix: String = ""): TrackMetadata? {
        val track = resolveTrackMetadata(bundle) ?: return null
        requestLyric(track, logSuffix)

        val result = lyricCache[track.cacheKey] ?: return track
        putLyricInfo(bundle, track, result, logSuffix)
        return track
    }

    /**
     * Writes a known result without resolving the current playback object
     * again. Providers that refresh a retained MediaMetadata asynchronously
     * must use this overload so an old result cannot be paired with a newer
     * playback object.
     */
    protected fun putLyricInfo(
        bundle: Bundle,
        track: TrackMetadata,
        result: LyricResult,
        logSuffix: String = ""
    ) {
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
        Log.d(TAG, "[Inject] ✓ ${track.songName}$logSuffix")
    }

    /**
     * Starts lyric loading for a trusted track identity. Providers may call this
     * after resolving metadata asynchronously from a stable source such as an ID.
     */
    protected open fun requestLyric(track: TrackMetadata, logSuffix: String = "") {
        val songKey = track.cacheKey
        if (songKey != currentMediaId) {
            currentMediaId = songKey
            Log.i(TAG, "[Song] ${track.songName} - ${track.artist}$logSuffix")
        }
        fetchLyricAsync(track)
    }

    protected open fun resolveTrackMetadata(bundle: Bundle): TrackMetadata? {
        val mediaId = bundle.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty()
        val songName = bundle.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = bundle.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        val album = bundle.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
        val duration = bundle.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val cacheKey = mediaId.ifEmpty { "$songName|$artist|$duration".hashCode().toString() }
        return TrackMetadata(songName, artist, album, mediaId, cacheKey)
    }

    /**
     * Called when a framework MediaSession receives metadata. Providers that
     * load data asynchronously may retain the session and refresh it after the
     * lyric result becomes available.
     */
    protected open fun onMediaSessionMetadataObserved(session: Any, metadata: MediaMetadata) = Unit

    /**
     * Called after a framework MediaMetadata.Builder has been inspected. A
     * provider can use this event to invalidate an older retained refresh
     * target as soon as a newer track starts being built.
     */
    protected open fun onMediaMetadataBuilt(bundle: Bundle, track: TrackMetadata?) = Unit

    /**
     * Called after the framework metadata has been resolved to a provider
     * track. This lets a provider associate its retained MediaSession with the
     * exact stable identity, preventing an old async result from refreshing a
     * newer song.
     */
    protected open fun onMediaSessionTrackResolved(
        session: Any,
        metadata: MediaMetadata,
        track: TrackMetadata
    ) = Unit

    /**
     * Called after a lyric has been loaded into the in-memory cache. The
     * callback also runs for a disk-cache hit, so a provider can use one path
     * for both synchronous and asynchronous sources.
     */
    protected open fun onLyricAvailable(track: TrackMetadata, result: LyricResult) = Unit

    private fun fetchLyricAsync(track: TrackMetadata) {
        val mediaId = track.cacheKey
        if (lyricCache.containsKey(mediaId)) return
        if (!fetchingIds.add(mediaId)) return

        executor.execute {
            try {
                val cached = if (useFileCache) fileCache?.read(mediaId) else null
                if (cached != null) {
                    lyricCache[mediaId] = cached
                    onLyricAvailable(track, cached)
                    Log.d(TAG, "[Fetch] ✓ File cache: ${track.songName}")
                    return@execute
                }

                val result = fetchLyric(mediaId, track.songName, track.artist)
                if (result != null && result.lyric.isNotBlank()) {
                    lyricCache[mediaId] = result
                    if (useFileCache) {
                        fileCache?.write(mediaId, result)
                    }
                    onLyricAvailable(track, result)
                    Log.d(TAG, "[Fetch] ✓ API: ${track.songName}")
                } else {
                    Log.w(TAG, "[Fetch] ✗ No lyric: ${track.songName}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "[Fetch] ✗ ${track.songName}", e)
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
