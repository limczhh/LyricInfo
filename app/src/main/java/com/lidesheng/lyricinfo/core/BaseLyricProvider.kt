package com.lidesheng.lyricinfo.core

import android.annotation.SuppressLint
import android.media.MediaMetadata
import android.os.Bundle
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.io.File
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

@SuppressLint("SoonBlockedPrivateApi")
abstract class BaseLyricProvider : LyricProvider {

    protected data class TrackMetadata(
        val songName: String,
        val artist: String,
        val album: String,
        val songId: String,
        val cacheKey: String = songId,
        val songMid: String = ""
    )

    companion object {
        private const val TAG = "LyricInfo"
        private const val LYRIC_INFO_KEY = "lyricInfo"
    }

    @Volatile
    private var currentMediaId: String? = null
    @Volatile
    private var active = false
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "LyricInfo-${javaClass.simpleName}").apply { isDaemon = true }
    }
    protected val lyricCache = ConcurrentHashMap<String, LyricResult>()
    private val cachedEntries = ConcurrentHashMap<String, LyricCacheEntry>()
    private val requestedIds = ConcurrentHashMap.newKeySet<String>()
    private var fileCache: LyricFileCache? = null
    private val hookHandles = mutableListOf<XposedInterface.HookHandle>()

    private data class FrameworkTarget(
        val session: Any,
        val metadata: MediaMetadata,
        val cacheKey: String
    )

    private data class CompatTarget(
        val session: Any,
        val metadata: Any,
        val bundle: Bundle,
        val setMetadataMethod: Method,
        val cacheKey: String
    )

    @Volatile
    private var frameworkTarget: FrameworkTarget? = null
    @Volatile
    private var compatTarget: CompatTarget? = null
    private val refreshIdentity = ThreadLocal<String?>()

    /**
     * Providers whose source of truth is an online API can disable the generic
     * disk cache. This keeps an API failure from silently becoming a stale lyric
     * fallback while retaining the in-memory request de-duplication.
     */
    protected open val useFileCache: Boolean
        get() = true

    /**
     * Loads a lyric result and its persisted track identity for a stable key.
     * The in-memory entry is checked first, then the provider's disk cache.
     */
    protected fun loadCachedLyric(cacheKey: String): LyricCacheEntry? {
        cachedEntries[cacheKey]?.let { return it }
        lyricCache[cacheKey]?.let { return LyricCacheEntry(it) }
        if (!useFileCache) return null

        return fileCache?.read(cacheKey)?.also { entry ->
            cachedEntries[cacheKey] = entry
            lyricCache[cacheKey] = entry.result
        }
    }

    /** Stores a complete lyric result for current and future offline playback. */
    protected fun storeCachedLyric(track: TrackMetadata, result: LyricResult) {
        val entry = LyricCacheEntry(
            result = result,
            songName = track.songName,
            artist = track.artist,
            album = track.album,
            songId = track.songId,
            songMid = track.songMid
        )
        lyricCache[track.cacheKey] = result
        cachedEntries[track.cacheKey] = entry
        if (useFileCache) {
            fileCache?.write(track.cacheKey, entry)
        }
    }

    override fun onAppLoaded(module: XposedModule, param: PackageLoadedParam) {
        Log.i(TAG, "[Hook] ${param.packageName}")
        active = true
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
                if (!isRefreshReentry()) {
                    invalidateMetadataTargets(track)
                    onMediaMetadataBuilt(bundle, track)
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
                    val refreshing = isRefreshReentry()
                    if (!refreshing) {
                        frameworkTarget = null
                        onMediaSessionMetadataObserved(chain.thisObject, metadata)
                    }
                    val metaBundleField = metadata.javaClass.getDeclaredField("mBundle")
                    metaBundleField.isAccessible = true
                    val bundle = metaBundleField.get(metadata) as Bundle
                    val track = injectLyricInfo(bundle, " (setMetadata)")
                    if (track != null && !refreshing) {
                        rememberFrameworkTarget(chain.thisObject, metadata, track)
                        onMediaSessionTrackResolved(chain.thisObject, metadata, track)
                    }
                }

                chain.proceed()
            }
            hookHandles.add(handle)
            Log.i(TAG, "[Hook] ✓ MediaSession.setMetadata()")

            val releaseMethod = sessionClass.getDeclaredMethod("release")
            module.deoptimize(releaseMethod)
            val releaseHandle = module.hook(releaseMethod).intercept { chain ->
                onMediaSessionReleased(chain.thisObject)
                chain.proceed()
            }
            hookHandles.add(releaseHandle)
            Log.i(TAG, "[Hook] ✓ MediaSession.release()")
        } catch (e: Exception) {
            Log.e(TAG, "[Hook] ✗ MediaSession.setMetadata()", e)
        }
    }

    /**
     * Adds LyricInfo to a metadata bundle before the target media session receives it.
     * Providers with a support-library MediaSession can reuse this path as well.
     */
    protected fun injectLyricInfo(bundle: Bundle, logSuffix: String = ""): TrackMetadata? {
        val track = resolveTrackMetadata(bundle)
        if (track == null) {
            val pendingTrack = resolvePendingTrackMetadata(bundle)
            if (pendingTrack != null) {
                putPendingLyricInfo(bundle, pendingTrack, logSuffix)
            }
            return pendingTrack
        }

        requestLyric(track, logSuffix)

        val result = lyricCache[track.cacheKey]
        if (result != null) {
            putLyricInfo(bundle, track, result, logSuffix)
        } else {
            putPendingLyricInfo(bundle, track, logSuffix)
        }
        return track
    }

    /**
     * Resolves a stable-enough identity for the first metadata event. Providers
     * whose canonical metadata is loaded in another async step can override it
     * without weakening their final lyric identity checks.
     */
    protected open fun resolvePendingTrackMetadata(bundle: Bundle): TrackMetadata? = null

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
    ): Boolean = putSourceResponse(bundle, track, result, logSuffix)

    /** Writes the compatibility-first metadata-only payload for a pending lyric. */
    protected fun putPendingLyricInfo(
        bundle: Bundle,
        track: TrackMetadata,
        logSuffix: String = ""
    ): Boolean = putSourceResponse(bundle, track, null, logSuffix)

    private fun putSourceResponse(
        bundle: Bundle,
        track: TrackMetadata,
        result: LyricResult?,
        logSuffix: String
    ): Boolean {
        val json = if (result == null) {
            LyricInfoSerializer.encodePending(
                songName = track.songName,
                artist = track.artist,
                songId = track.songId,
                album = track.album,
                songMid = track.songMid,
                identity = track.cacheKey
            )
        } else {
            LyricInfoSerializer.encode(
                songName = track.songName,
                artist = track.artist,
                songId = track.songId,
                album = track.album,
                result = result,
                songMid = track.songMid,
                identity = track.cacheKey
            )
        } ?: run {
            Log.w(TAG, "[Inject] skipped source response without identity$logSuffix")
            return false
        }
        if (bundle.getString(LYRIC_INFO_KEY) == json) return false
        bundle.putString(LYRIC_INFO_KEY, json)
        Log.d(
            TAG,
            "[Inject] ${if (result == null) "pending" else "✓"} " +
                "${track.songName.ifBlank { track.cacheKey }}$logSuffix"
        )
        return true
    }

    /**
     * Handles a support-library MediaSession.setMetadata() event. Providers
     * only need to supply the reflected method and its metadata bundle; the
     * async refresh and identity checks stay in the shared core.
     */
    protected fun observeCompatMetadata(
        session: Any,
        metadata: Any,
        bundle: Bundle,
        setMetadataMethod: Method,
        logSuffix: String = ""
    ): TrackMetadata? {
        if (isRefreshReentry()) return null

        compatTarget = null
        val track = injectLyricInfo(bundle, logSuffix)
        if (track != null) {
            rememberCompatTarget(
                session = session,
                metadata = metadata,
                bundle = bundle,
                setMetadataMethod = setMetadataMethod,
                track = track
            )
        }
        return track
    }

    /** Clears only the retained compat target owned by the released session. */
    protected open fun onCompatSessionReleased(session: Any) {
        if (compatTarget?.session === session) {
            compatTarget = null
        }
    }

    /** Clears only targets owned by the released framework session. */
    protected open fun onMediaSessionReleased(session: Any) {
        if (frameworkTarget?.session === session) {
            frameworkTarget = null
        }
        onCompatSessionReleased(session)
    }

    /** Installs the common support-library release hook for Base providers. */
    protected fun hookCompatSessionRelease(
        module: XposedModule,
        sessionClass: Class<*>,
        hookList: MutableList<XposedInterface.HookHandle>,
        providerTag: String
    ) {
        try {
            val releaseMethod = sessionClass.getDeclaredMethod("release")
            module.deoptimize(releaseMethod)
            val handle = module.hook(releaseMethod).intercept { chain ->
                onCompatSessionReleased(chain.thisObject)
                chain.proceed()
            }
            hookList.add(handle)
            Log.i(providerTag, "[Hook] ✓ MediaSessionCompat.release()")
        } catch (e: Exception) {
            Log.e(providerTag, "[Hook] ✗ MediaSessionCompat.release()", e)
        }
    }

    /** Invalidates a compat refresh target when a newer builder identity appears. */
    protected fun invalidateCompatMetadataTarget(track: TrackMetadata?) {
        if (isRefreshReentry()) return
        val target = compatTarget ?: return
        if (track == null || target.cacheKey != track.cacheKey) {
            compatTarget = null
        }
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
        if (!lyricCache.containsKey(songKey)) {
            loadCachedLyric(songKey)
        }
        fetchLyricAsync(track)
    }

    protected fun isProviderActive(): Boolean = active

    protected open fun resolveTrackMetadata(bundle: Bundle): TrackMetadata? {
        val mediaId = readMetadataText(bundle, MediaMetadata.METADATA_KEY_MEDIA_ID).ifBlank {
            runCatching {
                bundle.getLong(MediaMetadata.METADATA_KEY_MEDIA_ID)
                    .takeIf { it > 0L }
                    ?.toString()
                    .orEmpty()
            }.getOrDefault("")
        }
        val songName = readMetadataText(bundle, MediaMetadata.METADATA_KEY_TITLE)
        val artist = readMetadataText(bundle, MediaMetadata.METADATA_KEY_ARTIST)
        val album = readMetadataText(bundle, MediaMetadata.METADATA_KEY_ALBUM)
        val duration = runCatching {
            bundle.getLong(MediaMetadata.METADATA_KEY_DURATION)
        }.getOrDefault(0L)
        if (mediaId.isBlank() && (songName.isBlank() || artist.isBlank())) return null
        val cacheKey = mediaId.ifEmpty { "$songName|$artist|$duration".hashCode().toString() }
        return TrackMetadata(songName, artist, album, mediaId, cacheKey)
    }

    private fun readMetadataText(bundle: Bundle, key: String): String = runCatching {
        bundle.getCharSequence(key)?.toString()?.trim().orEmpty()
    }.getOrDefault("")

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
    protected open fun onLyricAvailable(track: TrackMetadata, result: LyricResult) {
        publishLyricState(track, result)
    }

    /** Publishes the provider-owned source state to the retained media targets. */
    protected fun publishLyricState(track: TrackMetadata, result: LyricResult?) {
        if (!active) return
        refreshFrameworkSession(track, result)
        refreshCompatSession(track, result)
    }

    /** Compatibility alias for providers that capture a complete lyric directly. */
    protected fun publishLyricResult(track: TrackMetadata, result: LyricResult) {
        publishLyricState(track, result)
    }

    /** Publishes an owned-but-empty response after a miss, failure, or pure-music result. */
    protected open fun onLyricUnavailable(track: TrackMetadata) {
        publishLyricState(track, null)
    }

    private fun fetchLyricAsync(track: TrackMetadata) {
        val mediaId = track.cacheKey
        if (!active) return
        if (lyricCache.containsKey(mediaId)) return
        if (!requestedIds.add(mediaId)) return

        executor.execute {
            try {
                if (!active) return@execute
                val cached = loadCachedLyric(mediaId)
                if (cached != null) {
                    if (!active) return@execute
                    onLyricAvailable(track, cached.result)
                    Log.d(TAG, "[Fetch] ✓ File cache: ${track.songName}")
                    return@execute
                }

                val result = fetchLyric(mediaId, track.songName, track.artist)
                if (!active) return@execute
                if (result != null && result.lyric.isNotBlank()) {
                    storeCachedLyric(track, result)
                    onLyricAvailable(track, result)
                    Log.d(TAG, "[Fetch] ✓ API: ${track.songName}")
                } else {
                    onLyricUnavailable(track)
                    Log.w(TAG, "[Fetch] ✗ No lyric: ${track.songName}")
                }
            } catch (e: Exception) {
                onLyricUnavailable(track)
                Log.e(TAG, "[Fetch] ✗ ${track.songName}", e)
            }
        }
    }

    private fun rememberFrameworkTarget(
        session: Any,
        metadata: MediaMetadata,
        track: TrackMetadata
    ) {
        frameworkTarget = FrameworkTarget(
            session = session,
            metadata = metadata,
            cacheKey = track.cacheKey
        )
    }

    private fun invalidateFrameworkTarget(track: TrackMetadata?) {
        val target = frameworkTarget ?: return
        if (track == null || target.cacheKey != track.cacheKey) {
            frameworkTarget = null
        }
    }

    private fun invalidateMetadataTargets(track: TrackMetadata?) {
        invalidateFrameworkTarget(track)
        invalidateCompatMetadataTarget(track)
    }

    private fun isRefreshReentry(): Boolean = refreshIdentity.get() != null

    private fun refreshFrameworkSession(track: TrackMetadata, result: LyricResult?) {
        if (!active) return
        val target = frameworkTarget ?: return
        if (target.cacheKey != track.cacheKey) return

        try {
            val bundleField = target.metadata.javaClass.getDeclaredField("mBundle").apply {
                isAccessible = true
            }
            val bundle = bundleField.get(target.metadata) as Bundle
            if (!active || frameworkTarget !== target) return
            if (!matchesFrameworkTarget(bundle, target.cacheKey)) return
            if (!putSourceResponse(bundle, track, result, " (asyncSession)")) return
            if (!active || frameworkTarget !== target) return

            val setMetadataMethod = target.session.javaClass.getDeclaredMethod(
                "setMetadata",
                MediaMetadata::class.java
            ).apply { isAccessible = true }
            refreshIdentity.set(track.cacheKey)
            try {
                setMetadataMethod.invoke(target.session, target.metadata)
            } finally {
                refreshIdentity.remove()
            }
            Log.i(TAG, "[Refresh] ✓ MediaSession: ${track.songName}")
        } catch (e: Exception) {
            Log.e(TAG, "[Refresh] ✗ MediaSession: ${track.songName}", e)
        }
    }

    private fun rememberCompatTarget(
        session: Any,
        metadata: Any,
        bundle: Bundle,
        setMetadataMethod: Method,
        track: TrackMetadata
    ) {
        compatTarget = CompatTarget(
            session = session,
            metadata = metadata,
            bundle = bundle,
            setMetadataMethod = setMetadataMethod.apply { isAccessible = true },
            cacheKey = track.cacheKey
        )
    }

    private fun refreshCompatSession(track: TrackMetadata, result: LyricResult?) {
        if (!active) return
        val target = compatTarget ?: return
        if (target.cacheKey != track.cacheKey) return
        try {
            if (!active || compatTarget !== target) return
            if (!matchesMetadataTarget(target.bundle, target.cacheKey)) return
            if (!putSourceResponse(target.bundle, track, result, " (asyncCompatSession)")) return
            if (!active || compatTarget !== target) return

            refreshIdentity.set(track.cacheKey)
            try {
                target.setMetadataMethod.invoke(target.session, target.metadata)
            } finally {
                refreshIdentity.remove()
            }
            Log.i(TAG, "[Refresh] ✓ Compat MediaSession: ${track.songName}")
        } catch (e: Exception) {
            Log.e(TAG, "[Refresh] ✗ Compat MediaSession: ${track.songName}", e)
        }
    }

    private fun matchesFrameworkTarget(bundle: Bundle, cacheKey: String): Boolean {
        return matchesMetadataTarget(bundle, cacheKey)
    }

    private fun matchesMetadataTarget(bundle: Bundle, cacheKey: String): Boolean {
        val current = resolveTrackMetadata(bundle)
            ?: resolvePendingTrackMetadata(bundle)
            ?: return false
        return current.cacheKey == cacheKey
    }

    protected abstract fun fetchLyric(mediaId: String, title: String?, artist: String?): LyricResult?

    override fun onDestroy() {
        active = false
        executor.shutdownNow()
        lyricCache.clear()
        cachedEntries.clear()
        requestedIds.clear()
        currentMediaId = null
        frameworkTarget = null
        compatTarget = null
        refreshIdentity.remove()
        hookHandles.clear()
    }
}
