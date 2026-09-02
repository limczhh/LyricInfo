package com.lidesheng.lyricinfo.providers.kugou

import android.annotation.SuppressLint
import android.media.MediaMetadata
import android.os.Bundle
import android.util.Log
import com.lidesheng.lyricinfo.core.BaseLyricProvider
import com.lidesheng.lyricinfo.core.LyricResult
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Kugou Music provider.
 *
 * Kugou can rewrite MediaMetadata TITLE/ARTIST and even publish only the
 * currently visible lyric line when Bluetooth lyrics are enabled. The provider
 * therefore gets identity and display metadata from Kugou's current playback
 * object, then obtains the complete lyric from Kugou's lyric API.
 *
 * If neither the stable-hash disk cache nor Kugou's API can provide a lyric,
 * this provider deliberately leaves the bundle untouched. It does not enable
 * or consume Kugou's native LyricInfo path; no old internal LyricData,
 * private lyric file, or fuzzy title/artist search is used as a source.
 */
@SuppressLint("SoonBlockedPrivateApi")
class KugouProvider : BaseLyricProvider() {

    companion object {
        private const val TAG = "LyricInfo"
        const val PACKAGE_NAME = "com.kugou.android"

        private const val TRACK_CLASS_NAME =
            "com.kugou.framework.service.entity.KGMusicWrapper"
        private const val QUEUE_PLAYER_MANAGER_CLASS_NAME =
            "com.kugou.common.player.manager.QueuePlayerManager"
        private const val CURRENT_MEDIA_METHOD_NAME = "getCurrentMedia"
    }

    override val packageName = PACKAGE_NAME
    override val processNames = listOf(PACKAGE_NAME, "$PACKAGE_NAME:kugou_service")

    private data class KugouTrack(
        val identity: String,
        val hash: String,
        val songName: String,
        val artist: String,
        val album: String
    )

    private data class ApiMetadata(
        val songName: String,
        val artist: String,
        val album: String
    )

    private data class FrameworkTarget(
        val session: Any,
        val metadata: MediaMetadata,
        val identity: String,
        val mediaId: String
    )

    private data class CompatTarget(
        val session: Any,
        val metadata: Any,
        val identity: String,
        val mediaId: String
    )

    private val kugouHookHandles = mutableListOf<XposedInterface.HookHandle>()
    private val trackCache = ConcurrentHashMap<String, KugouTrack>()
    private val apiMetadataCache = ConcurrentHashMap<String, ApiMetadata>()
    private val missingTrackLogged = AtomicBoolean(false)
    private val missingIdentityLogged = AtomicBoolean(false)

    @Volatile
    private var observedCurrentTrack: Any? = null

    @Volatile
    private var frameworkTarget: FrameworkTarget? = null

    @Volatile
    private var compatTarget: CompatTarget? = null

    @Volatile
    private var compatSetMetadataMethod: Method? = null

    private val refreshIdentity = ThreadLocal<String?>()

    override fun onAppLoaded(module: XposedModule, param: PackageLoadedParam) {
        Log.i(TAG, "[Hook] ${param.packageName}")
        resetTrackObservation()

        super.onAppLoaded(module, param)
        installCurrentMediaHook(module, param.defaultClassLoader)
        installCompatMetadataHooks(module, param.defaultClassLoader)
    }

    override fun replaceHooks(
        module: XposedModule,
        param: PackageLoadedParam,
        oldHooks: List<XposedInterface.HookHandle>
    ): List<XposedInterface.HookHandle> {
        val baseHooks = super.replaceHooks(module, param, oldHooks)
        kugouHookHandles.clear()
        resetTrackObservation()
        frameworkTarget = null
        compatTarget = null
        compatSetMetadataMethod = null
        refreshIdentity.remove()

        installCurrentMediaHook(module, param.defaultClassLoader)
        installCompatMetadataHooks(module, param.defaultClassLoader)
        return baseHooks + kugouHookHandles
    }

    override fun onDestroy() {
        kugouHookHandles.forEach { it.unhook() }
        kugouHookHandles.clear()
        trackCache.clear()
        apiMetadataCache.clear()
        resetTrackObservation()
        frameworkTarget = null
        compatTarget = null
        compatSetMetadataMethod = null
        refreshIdentity.remove()
        super.onDestroy()
    }

    /**
     * Resolve the current song from QueuePlayerManager's current media object.
     * The media object's exact hash is the only accepted API identity.
     */
    override fun resolveTrackMetadata(bundle: Bundle): TrackMetadata? {
        val trackObject = currentTrackObject()
        if (trackObject == null) {
            if (missingTrackLogged.compareAndSet(false, true)) {
                Log.w(TAG, "[Kugou] No current playback object; skip LyricInfo")
            }
            return null
        }

        val hash = stringValue(invokeNoArg(trackObject, "getHashValue"))
        if (!looksLikeHash(hash)) {
            if (missingIdentityLogged.compareAndSet(false, true)) {
                Log.w(TAG, "[Kugou] Current playback object has no exact hash; skip LyricInfo")
            }
            return null
        }

        val identity = hash
        val sourceSongName = stringValue(invokeNoArg(trackObject, "getTrackName"))
        val sourceArtist = stringValue(invokeNoArg(trackObject, "getArtistName"))
        val cached = loadCachedLyric(identity)
        val cachedMetadata = cached?.let {
            ApiMetadata(
                songName = it.songName,
                artist = it.artist,
                album = it.album
            )
        }
        val apiMetadata = apiMetadataCache[identity] ?: cachedMetadata

        val songName = apiMetadata?.songName?.takeIf { it.isNotBlank() } ?: sourceSongName
        val artist = apiMetadata?.artist?.takeIf { it.isNotBlank() } ?: sourceArtist
        val album = apiMetadata?.album?.takeIf { it.isNotBlank() }.orEmpty()

        if (songName.isBlank() || artist.isBlank()) {
            Log.w(TAG, "[Kugou] Current playback object has incomplete display metadata; identity=$identity")
        }
        val track = KugouTrack(
            identity = identity,
            hash = hash,
            songName = songName,
            artist = artist,
            album = album
        )
        trackCache[identity] = track

        return TrackMetadata(
            songName = songName,
            artist = artist,
            album = album,
            songId = identity,
            cacheKey = identity
        )
    }

    override fun onMediaSessionMetadataObserved(session: Any, metadata: MediaMetadata) {
        if (isRefreshReentry()) return
        frameworkTarget = null
    }

    override fun onMediaMetadataBuilt(bundle: Bundle, track: TrackMetadata?) {
        if (isRefreshReentry()) return

        val identity = track?.cacheKey
        if (identity == null) {
            frameworkTarget = null
            compatTarget = null
            return
        }
        if (frameworkTarget?.identity != identity) frameworkTarget = null
        if (compatTarget?.identity != identity) compatTarget = null
    }

    override fun onMediaSessionTrackResolved(
        session: Any,
        metadata: MediaMetadata,
        track: TrackMetadata
    ) {
        if (isRefreshReentry()) return
        frameworkTarget = FrameworkTarget(
            session = session,
            metadata = metadata,
            identity = track.cacheKey,
            mediaId = readMetadataMediaId(metadata)
        )
    }

    override fun onLyricAvailable(track: TrackMetadata, result: LyricResult) {
        apiMetadataCache[track.cacheKey]?.let { metadata ->
            storeCachedLyric(
                track.copy(
                    songName = metadata.songName.takeIf { it.isNotBlank() } ?: track.songName,
                    artist = metadata.artist.takeIf { it.isNotBlank() } ?: track.artist,
                    album = metadata.album.takeIf { it.isNotBlank() } ?: track.album
                ),
                result
            )
        }
        refreshMediaSessions(track.cacheKey)
    }

    override fun fetchLyric(mediaId: String, title: String?, artist: String?): LyricResult? {
        val track = trackCache[mediaId]
        if (track == null) {
            Log.w(TAG, "[Kugou] No API request context for identity=$mediaId")
            return null
        }

        val result = KugouApi.fetch(
            KugouApi.TrackRequest(
                identity = track.identity,
                hash = track.hash
            )
        ) ?: return null

        if (result.songName.isNotBlank() || result.artist.isNotBlank() || result.album.isNotBlank()) {
            apiMetadataCache[mediaId] = ApiMetadata(
                songName = result.songName,
                artist = result.artist,
                album = result.album
            )
        }
        Log.i(TAG, "[Kugou] API lyric loaded: identity=$mediaId")
        return result.lyric
    }

    /**
     * The first metadata event normally starts the network request before the
     * lyric is ready. Re-submit the same metadata object after the request
     * completes so the current session receives our LyricInfo instead of
     * retaining Kugou's native Bluetooth-lyric payload.
     */
    private fun refreshMediaSessions(identity: String) {
        refreshFrameworkSession(identity)
        refreshCompatSession(identity)
    }

    private fun refreshFrameworkSession(identity: String) {
        val target = frameworkTarget ?: return
        if (target.identity != identity) return
        try {
            val bundleField = target.metadata.javaClass.getDeclaredField("mBundle").apply {
                isAccessible = true
            }
            val bundle = bundleField.get(target.metadata) as Bundle
            if (frameworkTarget !== target) return
            if (!matchesRefreshTarget(bundle, target.mediaId)) return
            if (!writeCachedLyric(bundle, identity, " (asyncSession)")) return
            if (frameworkTarget !== target) return

            val setMetadataMethod = target.session.javaClass.getDeclaredMethod(
                "setMetadata",
                MediaMetadata::class.java
            ).apply { isAccessible = true }
            withRefreshIdentity(identity) {
                setMetadataMethod.invoke(target.session, target.metadata)
            }
            Log.i(TAG, "[Kugou] ✓ Refreshed framework MediaSession: $identity")
        } catch (e: Exception) {
            Log.e(TAG, "[Kugou] ✗ Refresh framework MediaSession", e)
        }
    }

    private fun refreshCompatSession(identity: String) {
        val target = compatTarget ?: return
        if (target.identity != identity) return
        val setMetadataMethod = compatSetMetadataMethod ?: return
        try {
            val bundleField = target.metadata.javaClass.getDeclaredField("mBundle").apply {
                isAccessible = true
            }
            val bundle = bundleField.get(target.metadata) as Bundle
            if (compatTarget !== target) return
            if (!matchesRefreshTarget(bundle, target.mediaId)) return
            if (!writeCachedLyric(bundle, identity, " (asyncCompatSession)")) return
            if (compatTarget !== target) return

            withRefreshIdentity(identity) {
                setMetadataMethod.invoke(target.session, target.metadata)
            }
            Log.i(TAG, "[Kugou] ✓ Refreshed compat MediaSession: $identity")
        } catch (e: Exception) {
            Log.e(TAG, "[Kugou] ✗ Refresh compat MediaSession", e)
        }
    }

    private fun writeCachedLyric(bundle: Bundle, identity: String, logSuffix: String): Boolean {
        val track = trackCache[identity] ?: return false
        val result = lyricCache[identity] ?: return false
        val apiMetadata = apiMetadataCache[identity]
        val metadata = TrackMetadata(
            songName = apiMetadata?.songName?.takeIf { it.isNotBlank() } ?: track.songName,
            artist = apiMetadata?.artist?.takeIf { it.isNotBlank() } ?: track.artist,
            album = apiMetadata?.album?.takeIf { it.isNotBlank() } ?: track.album,
            songId = identity,
            cacheKey = identity
        )
        return putLyricInfo(bundle, metadata, result, logSuffix)
    }

    private fun matchesRefreshTarget(
        bundle: Bundle,
        targetMediaId: String
    ): Boolean {
        val currentMediaId = readMediaId(bundle)
        if (targetMediaId.isNotBlank() &&
            currentMediaId.isNotBlank() &&
            targetMediaId != currentMediaId
        ) {
            Log.d(
                TAG,
                "[Kugou] Skip stale refresh: metadata mediaId changed " +
                    "$targetMediaId -> $currentMediaId"
            )
            return false
        }
        return true
    }

    private fun withRefreshIdentity(identity: String, action: () -> Unit) {
        refreshIdentity.set(identity)
        try {
            action()
        } finally {
            refreshIdentity.remove()
        }
    }

    private fun isRefreshReentry(): Boolean = refreshIdentity.get() != null

    private fun readMetadataMediaId(metadata: MediaMetadata): String {
        return runCatching {
            val bundleField = metadata.javaClass.getDeclaredField("mBundle").apply {
                isAccessible = true
            }
            readMediaId(bundleField.get(metadata) as Bundle)
        }.getOrDefault("")
    }

    private fun installCompatMetadataHooks(module: XposedModule, classLoader: ClassLoader) {
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
                    val track = injectLyricInfo(bundle, " (compatBuilder)")
                    onMediaMetadataBuilt(bundle, track)
                } catch (e: Exception) {
                    Log.e(TAG, "[Kugou] ✗ Inject Compat Builder", e)
                }
                chain.proceed()
            }
            kugouHookHandles.add(handle)
            Log.i(TAG, "[Kugou] ✓ Hooked MediaMetadataCompat.Builder.build()")
        } catch (e: Exception) {
            Log.e(TAG, "[Kugou] ✗ Hook Compat Builder", e)
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
            compatSetMetadataMethod = setMetadataMethod.apply { isAccessible = true }
            val bundleField = metadataClass.getDeclaredField("mBundle").apply {
                isAccessible = true
            }
            module.deoptimize(setMetadataMethod)

            val handle = module.hook(setMetadataMethod).intercept { chain ->
                try {
                    if (!isRefreshReentry()) {
                        val metadata = chain.getArg(0)
                        compatTarget = null
                        if (metadata != null) {
                            val bundle = bundleField.get(metadata) as Bundle
                            val track = injectLyricInfo(bundle, " (compatSession)")
                            if (track != null) {
                                compatTarget = CompatTarget(
                                    session = chain.thisObject,
                                    metadata = metadata,
                                    identity = track.cacheKey,
                                    mediaId = readMediaId(bundle)
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "[Kugou] ✗ Inject Compat Session", e)
                }
                chain.proceed()
            }
            kugouHookHandles.add(handle)
            Log.i(TAG, "[Kugou] ✓ Hooked MediaSessionCompat.setMetadata()")
        } catch (e: Exception) {
            Log.e(TAG, "[Kugou] ✗ Hook Compat Session", e)
        }
    }

    private fun currentTrackObject(): Any? {
        return observedCurrentTrack
    }

    /**
     * QueuePlayerManager.getCurrentMedia() is the single current-media source.
     * Observe its KGMusicWrapper result before MediaSession metadata is built.
     */
    private fun installCurrentMediaHook(module: XposedModule, loader: ClassLoader) {
        runCatching {
            val wrapperClass = Class.forName(TRACK_CLASS_NAME, false, loader)
            val managerClass = Class.forName(
                QUEUE_PLAYER_MANAGER_CLASS_NAME,
                false,
                loader
            )
            val method = (managerClass.declaredMethods.asSequence() + managerClass.methods.asSequence())
                .distinctBy { it.name + it.parameterTypes.contentToString() }
                .firstOrNull {
                    it.name == CURRENT_MEDIA_METHOD_NAME && it.parameterTypes.isEmpty()
                }
                ?: run {
                    Log.w(TAG, "[Kugou] QueuePlayerManager.getCurrentMedia() not found")
                    return
                }

            method.isAccessible = true
            module.deoptimize(method)
            val handle = module.hook(method).intercept { chain ->
                val result = chain.proceed()
                observedCurrentTrack = result?.takeIf(wrapperClass::isInstance)
                result
            }
            kugouHookHandles.add(handle)
            Log.i(TAG, "[Kugou] ✓ Hooked QueuePlayerManager.getCurrentMedia()")
        }.onFailure { error ->
            Log.e(TAG, "[Kugou] ✗ Hook current media", error)
        }
    }

    private fun readMediaId(bundle: Bundle): String {
        val stringId = bundle.getCharSequence(MediaMetadata.METADATA_KEY_MEDIA_ID)
            ?.toString()
            ?.trim()
            .orEmpty()
        if (stringId.isNotBlank()) return stringId

        return runCatching { bundle.getLong(MediaMetadata.METADATA_KEY_MEDIA_ID) }
            .getOrDefault(0L)
            .takeIf { it > 0L }
            ?.toString()
            .orEmpty()
    }

    private fun invokeNoArg(target: Any?, methodName: String): Any? {
        if (target == null) return null
        return runCatching {
            val method = target.javaClass.methods.firstOrNull {
                it.name == methodName && it.parameterTypes.isEmpty()
            } ?: target.javaClass.declaredMethods.firstOrNull {
                it.name == methodName && it.parameterTypes.isEmpty()
            } ?: return@runCatching null
            method.isAccessible = true
            method.invoke(target)
        }.getOrNull()
    }

    private fun stringValue(value: Any?): String {
        return value?.toString()?.trim().orEmpty()
    }

    private fun looksLikeHash(value: String): Boolean {
        return value.length == 32 && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }

    private fun resetTrackObservation() {
        observedCurrentTrack = null
        missingTrackLogged.set(false)
        missingIdentityLogged.set(false)
    }

}
