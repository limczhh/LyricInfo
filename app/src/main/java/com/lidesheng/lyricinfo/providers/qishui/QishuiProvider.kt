package com.lidesheng.lyricinfo.providers.qishui

import android.media.MediaMetadata
import android.os.Bundle
import android.util.Log
import com.lidesheng.lyricinfo.core.BaseLyricProvider
import com.lidesheng.lyricinfo.core.LyricResult
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Qishui Music (汽水音乐) lyric provider.
 *
 * Qishui exposes the current playable object before it writes MediaMetadata.
 * This provider uses that object as the primary source for identity and the
 * complete lyric, and keeps the local KRC cache as a fallback.
 *
 * Cache location: {dataDir}/cache/NetCacheLoader/{hashGroup}/{md5Hash}
 * where md5Hash = md5("/luna/track_v2/{mediaId}")
 */
class QishuiProvider : BaseLyricProvider() {

    companion object {
        private const val TAG = "LyricInfo"
        private const val PACKAGE_NAME = "com.luna.music"
    }

    override val packageName = PACKAGE_NAME

    private var dataDir: String? = null
    private val qishuiHookHandles = mutableListOf<XposedInterface.HookHandle>()
    private val metadataCache = ConcurrentHashMap<String, TrackMetadata>()
    private val authoritativeTrackIds = ConcurrentHashMap.newKeySet<String>()
    private val unresolvedMediaIds = ConcurrentHashMap.newKeySet<String>()
    private val missingMediaIdLogged = AtomicBoolean(false)

    private data class RawLyric(
        val type: String,
        val content: String
    )

    override fun onAppLoaded(module: XposedModule, param: PackageLoadedParam) {
        dataDir = param.applicationInfo.dataDir
        super.onAppLoaded(module, param)
        hookCompatMetadata(module, param.defaultClassLoader)
        hookPlayableMetadata(module, param.defaultClassLoader)
        hookTrackLyrics(module, param.defaultClassLoader)
    }

    override fun replaceHooks(
        module: XposedModule,
        param: PackageLoadedParam,
        oldHooks: List<XposedInterface.HookHandle>
    ): List<XposedInterface.HookHandle> {
        val baseHooks = super.replaceHooks(module, param, oldHooks)
        qishuiHookHandles.forEach { it.unhook() }
        qishuiHookHandles.clear()
        hookCompatMetadata(module, param.defaultClassLoader)
        hookPlayableMetadata(module, param.defaultClassLoader)
        hookTrackLyrics(module, param.defaultClassLoader)
        return baseHooks + qishuiHookHandles
    }

    override fun onDestroy() {
        qishuiHookHandles.forEach { it.unhook() }
        qishuiHookHandles.clear()
        metadataCache.clear()
        authoritativeTrackIds.clear()
        unresolvedMediaIds.clear()
        super.onDestroy()
    }

    /**
     * Qishui's Bluetooth lyric controls may write the current line into TITLE.
     * Resolve identity from the app-owned track captured before that write instead.
     */
    override fun resolveTrackMetadata(bundle: Bundle): TrackMetadata? {
        val songId = readMediaId(bundle)
        if (songId.isBlank()) {
            if (missingMediaIdLogged.compareAndSet(false, true)) {
                Log.w(TAG, "[Qishui] No stable Media ID; skip LyricInfo")
            }
            return null
        }
        val cached = metadataCache[songId]
            ?: readCachedMetadata(songId)?.also { metadataCache[songId] = it }
        if (cached == null && unresolvedMediaIds.add(songId)) {
            Log.w(TAG, "[Qishui] No app track for mediaId=$songId; skip LyricInfo")
        }
        return cached
    }

    override fun requestLyric(track: TrackMetadata, logSuffix: String) {
        // Prime Qishui's own cache synchronously so the current metadata event can inject.
        if (!lyricCache.containsKey(track.cacheKey)) {
            readCachedLyric(track.songId)?.let { storeCachedLyric(track, it) }
        }
        super.requestLyric(track, logSuffix)
    }

    override fun fetchLyric(mediaId: String, title: String?, artist: String?): LyricResult? {
        return readCachedLyric(mediaId)
    }

    private fun readCachedLyric(mediaId: String): LyricResult? {
        // Read from Qishui's local KRC cache.
        val cacheFile = findCacheFile(mediaId)
        if (cacheFile == null || !cacheFile.exists()) {
            Log.d(TAG, "[Qishui] No cache file for mediaId=$mediaId")
            return null
        }

        return try {
            val json = JSONObject(cacheFile.readText())
            val lyric = json.optJSONObject("lyric") ?: return null

            val type = lyric.optString("type", "")
            val content = lyric.optString("content", "")
            if (content.isBlank()) return null

            // Find translation (prefer system language, fallback to first available)
            val translations = lyric.optJSONObject("lang_translations")
            val translationResult = findTranslation(translations)

            val merged = KrcParser.parseAndMerge(type, content, translationResult?.first, translationResult?.second)
            if (merged.isNullOrBlank()) return null

            val hasTranslation = translationResult != null
            Log.d(TAG, "[Qishui] Parsed $type: ${merged.length} chars, translation=$hasTranslation (${translationResult?.first})")

            // Translation lines are always merged as plain LRC (no word-level tags)
            LyricResult(
                lyric = merged,
                format = "elrc",
                translation = if (hasTranslation) "lrc" else ""
            )
        } catch (e: Exception) {
            Log.e(TAG, "[Qishui] Failed to parse cache: mediaId=$mediaId", e)
            null
        }
    }

    private fun readCachedMetadata(mediaId: String): TrackMetadata? {
        val cacheFile = findCacheFile(mediaId) ?: return null
        return runCatching {
            val json = JSONObject(cacheFile.readText())
            val track = json.optJSONObject("track")
                ?: json.optJSONObject("data")?.optJSONObject("track")
                ?: return@runCatching null
            val songName = track.optString("name").trim()
            if (songName.isBlank()) return@runCatching null

            val artists = track.optJSONArray("artists")
            val artist = buildString {
                if (artists != null) {
                    for (index in 0 until artists.length()) {
                        val value = artists.opt(index)
                        val name = when (value) {
                            is JSONObject -> value.optString("name").trim()
                            else -> value?.toString()?.trim().orEmpty()
                        }
                        if (name.isNotBlank()) {
                            if (isNotEmpty()) append(", ")
                            append(name)
                        }
                    }
                }
                if (isEmpty()) append(track.optString("artist").trim())
            }
            val album = track.optJSONObject("album")?.optString("name")?.trim()
                .orEmpty()
                .ifBlank { track.optString("albumName").trim() }

            TrackMetadata(songName, artist, album, mediaId)
        }.getOrElse { error ->
            Log.e(TAG, "[Qishui] Failed to read cached metadata: mediaId=$mediaId", error)
            null
        }
    }

    private fun readMediaId(bundle: Bundle): String {
        val stringId = runCatching {
            bundle.getCharSequence(MediaMetadata.METADATA_KEY_MEDIA_ID)
                ?.toString()
                ?.trim()
                .orEmpty()
        }.getOrDefault("")
        val mediaId = if (stringId.isNotBlank()) {
            stringId
        } else {
            runCatching { bundle.getLong(MediaMetadata.METADATA_KEY_MEDIA_ID) }
                .getOrDefault(0L)
                .takeIf { it > 0L }
                ?.toString()
                .orEmpty()
        }
        return mediaId
    }

    /**
     * Capture the playable object before Qishui builds MediaMetadataCompat.
     * The update method is selected by its semantic parameter/return types, not its name.
     * Qishui has a common RemoteControl implementation plus several platform-specific
     * subclasses, so hook every known implementation present in the APK.
     */
    private fun hookPlayableMetadata(module: XposedModule, classLoader: ClassLoader) {
        try {
            val contextClass = classLoader.loadClass(
                "com.luna.biz.playing.player.remote.control.RemoteControlContext"
            )
            val playableClass = classLoader.loadClass(
                "com.luna.common.player.queue.api.IPlayable"
            )
            val builderClass = classLoader.loadClass(
                "android.support.v4.media.MediaMetadataCompat\$Builder"
            )
            val classNames = listOf(
                "com.luna.biz.playing.player.remote.control.RemoteControl",
                "com.luna.biz.playing.player.remote.control.CoreRemoteControl",
                "com.luna.biz.playing.player.remote.control.BlueToothLyricsRemoteControl",
                "com.luna.biz.playing.player.remote.control.FloatLyricRemoteControl",
                "com.luna.biz.playing.player.remote.control.HarmonyRemoteControl",
                "com.luna.biz.playing.player.remote.control.VivoOriginRemoteControl"
            )
            var hookedCount = 0

            for (className in classNames) {
                val remoteClass = runCatching { classLoader.loadClass(className) }.getOrNull()
                    ?: continue
                val updateMethod = remoteClass.declaredMethods.firstOrNull { method ->
                    method.parameterTypes.size == 2 &&
                        method.parameterTypes[0] == contextClass &&
                        method.parameterTypes[1] == builderClass &&
                        method.returnType == builderClass
                } ?: continue

                module.deoptimize(updateMethod)
                val handle = module.hook(updateMethod).intercept { chain ->
                    try {
                        val context = chain.getArg(0)
                        val playable = context?.let { findPlayable(it, playableClass) }
                        if (playable != null) capturePlayable(playable)
                    } catch (e: Exception) {
                        Log.e(TAG, "[Qishui] ✗ Capture playable metadata", e)
                    }
                    chain.proceed()
                }
                qishuiHookHandles.add(handle)
                hookedCount++
                Log.i(TAG, "[Qishui] ✓ Hooked playable metadata: ${remoteClass.name}")
            }

            if (hookedCount == 0) {
                throw NoSuchMethodException("Qishui RemoteControl metadata update")
            }
        } catch (e: Exception) {
            Log.e(TAG, "[Qishui] ✗ Hook playable metadata source", e)
        }
    }

    /**
     * Capture the complete track after Qishui converts GetTrackResponse into TrackLyric.
     * This is the authoritative source for both identity and the full lyric.
     */
    private fun hookTrackLyrics(module: XposedModule, classLoader: ClassLoader) {
        try {
            val builderClass = classLoader.loadClass(
                "com.luna.biz.playing.common.repo.track.builder.TrackLyricsBuilder"
            )
            val trackClass = classLoader.loadClass(
                "com.luna.common.arch.db.entity.Track"
            )
            val responseClass = classLoader.loadClass(
                "com.luna.biz.playing.common.repo.track.net.GetTrackResponse"
            )
            val buildMethod = builderClass.declaredMethods.firstOrNull { method ->
                method.parameterTypes.size == 2 &&
                    method.parameterTypes[0] == trackClass &&
                    method.parameterTypes[1] == responseClass &&
                    method.returnType == Void.TYPE
            } ?: throw NoSuchMethodException("TrackLyricsBuilder(Track, GetTrackResponse)")

            module.deoptimize(buildMethod)
            val handle = module.hook(buildMethod).intercept { chain ->
                val result = chain.proceed()
                try {
                    val track = chain.getArg(0)
                    val responseLyric = invokeNoArg(chain.getArg(1), "getLyric")
                    captureTrack(track, responseLyric, "trackResponse")
                } catch (e: Exception) {
                    Log.e(TAG, "[Qishui] ✗ Capture Track response", e)
                }
                result
            }
            qishuiHookHandles.add(handle)
            Log.i(TAG, "[Qishui] ✓ Hooked TrackLyricsBuilder")
        } catch (e: Exception) {
            Log.e(TAG, "[Qishui] ✗ Hook TrackLyricsBuilder", e)
        }
    }

    private fun findPlayable(context: Any, playableClass: Class<*>): Any? {
        val getter = context.javaClass.methods.firstOrNull { method ->
            method.parameterTypes.isEmpty() && playableClass.isAssignableFrom(method.returnType)
        } ?: return null
        getter.isAccessible = true
        return getter.invoke(context)
    }

    private fun capturePlayable(playable: Any) {
        val playableTrack = invokeNoArg(playable, "getTrack")
        if (playableTrack != null) {
            captureTrack(playableTrack, null, "track")
        }

        val songId = stringValue(invokeNoArg(playable, "getPlayableId"))
        val songName = stringValue(invokeNoArg(playable, "getName"))
        if (songId.isBlank() || songName.isBlank()) return

        val artist = joinNames(invokeNoArg(playable, "getAuthorNames"))
            .ifBlank { stringValue(invokeNoArg(playable, "getNotificationContent")) }
        val album = stringValue(invokeNoArg(playable, "getMediaSessionSubTitle"))
        val track = TrackMetadata(
            songName = songName,
            artist = artist,
            album = album,
            songId = songId
        )
        val appLyric = captureAppLyric(playable)
        storeTrack(track, appLyric, "playable")
    }

    private fun captureTrack(trackObject: Any?, fallbackLyric: Any?, source: String) {
        if (trackObject == null) return

        val songId = stringValue(invokeNoArg(trackObject, "getId"))
        val songName = stringValue(invokeNoArg(trackObject, "getName"))
        if (songId.isBlank() || songName.isBlank()) return

        val artist = joinNames(invokeNoArg(trackObject, "getArtists"))
        val album = stringValue(invokeNoArg(invokeNoArg(trackObject, "getAlbum"), "getName"))
        val track = TrackMetadata(
            songName = songName,
            artist = artist,
            album = album,
            songId = songId
        )
        val lyricObject = invokeNoArg(trackObject, "getTrackLyric") ?: fallbackLyric
        storeTrack(track, captureLyric(lyricObject), source)
    }

    private fun storeTrack(track: TrackMetadata, lyric: LyricResult?, source: String) {
        val authoritative = source == "track" || source == "trackResponse"
        val previous = if (authoritative) {
            authoritativeTrackIds.add(track.songId)
            metadataCache.put(track.songId, track)
        } else if (authoritativeTrackIds.contains(track.songId)) {
            metadataCache[track.songId]
        } else {
            metadataCache.putIfAbsent(track.songId, track)
        }
        val changed = previous != track
        val lyricChanged = lyric != null && lyricCache[track.songId] != lyric
        if (lyric != null && lyricChanged) {
            // Prefer the complete lyric already held by Qishui over its disk cache.
            lyricCache[track.songId] = lyric
        }

        if (changed || lyricChanged) {
            unresolvedMediaIds.remove(track.songId)
            Log.i(
                TAG,
                "[Qishui] [Song] ${track.songName} - ${track.artist} " +
                    "(source=$source, lyric=${if (lyric != null) "app" else "cache"})"
            )
        }
    }

    private fun captureAppLyric(playable: Any): LyricResult? {
        val lyricObject = invokeNoArg(playable, "getLyric")
            ?: invokeNoArg(invokeNoArg(playable, "getTrack"), "getTrackLyric")
            ?: return null
        return captureLyric(lyricObject)
    }

    private fun captureLyric(lyricObject: Any?): LyricResult? {
        val original = extractRawLyric(lyricObject) ?: return null
        val translations = invokeNoArg(lyricObject, "getLangTranslations") as? Map<*, *>
        val translation = findAppTranslation(translations)
        val merged = KrcParser.parseAndMerge(
            original.type,
            original.content,
            translation?.type,
            translation?.content
        ) ?: return null
        if (merged.isBlank()) return null

        return LyricResult(
            lyric = merged,
            format = "elrc",
            translation = if (translation != null) "lrc" else ""
        )
    }

    private fun extractRawLyric(value: Any?): RawLyric? {
        if (value == null) return null
        val content = stringValue(invokeNoArg(value, "getLyric"))
            .ifBlank { stringValue(invokeNoArg(value, "getContent")) }
        if (content.isBlank()) return null

        val typeObject = invokeNoArg(value, "getType") ?: return null
        val type = stringValue(invokeNoArg(typeObject, "getValue"))
            .ifBlank { stringValue(typeObject) }
        if (type.isBlank()) return null
        return RawLyric(type, content)
    }

    private fun findAppTranslation(translations: Map<*, *>?): RawLyric? {
        if (translations.isNullOrEmpty()) return null

        val candidates = translations.entries.mapNotNull { entry ->
            val key = stringValue(invokeNoArg(entry.key, "getValue"))
                .ifBlank { stringValue(entry.key) }
            val lyric = extractRawLyric(entry.value)
            if (key.isBlank() || lyric == null) null else key to lyric
        }
        if (candidates.isEmpty()) return null

        val locale = Locale.getDefault()
        val systemTag = buildString {
            append(locale.language.uppercase())
            if (locale.script.isNotEmpty()) append("-${locale.script.uppercase()}")
            if (locale.country.isNotEmpty()) append("-${locale.country.uppercase()}")
        }

        candidates.firstOrNull { it.first.equals(systemTag, ignoreCase = true) }?.let {
            return it.second
        }

        if (locale.language == "zh") {
            val fallbackHans = "ZH-HANS-${locale.country.uppercase()}"
            candidates.firstOrNull { it.first.equals(fallbackHans, ignoreCase = true) }?.let {
                return it.second
            }
            val fallbackHant = "ZH-HANT-${locale.country.uppercase()}"
            candidates.firstOrNull { it.first.equals(fallbackHant, ignoreCase = true) }?.let {
                return it.second
            }
        }

        candidates.firstOrNull { it.first.startsWith(locale.language, ignoreCase = true) }?.let {
            return it.second
        }
        return candidates.first().second
    }

    private fun invokeNoArg(target: Any?, methodName: String): Any? {
        if (target == null) return null
        return runCatching {
            val method = target.javaClass.methods.firstOrNull {
                it.name == methodName && it.parameterTypes.isEmpty()
            } ?: return@runCatching null
            method.isAccessible = true
            method.invoke(target)
        }.getOrNull()
    }

    private fun stringValue(value: Any?): String {
        return value?.toString()?.trim().orEmpty()
    }

    private fun joinNames(value: Any?): String {
        val iterable = value as? Iterable<*> ?: return ""
        return iterable.mapNotNull { objectName(it).takeIf { name -> name.isNotBlank() } }
            .joinToString(", ")
    }

    private fun objectName(value: Any?): String {
        if (value == null) return ""
        if (value is CharSequence) return value.toString().trim()
        return stringValue(invokeNoArg(value, "getName"))
            .ifBlank { stringValue(invokeNoArg(value, "getArtistName")) }
            .ifBlank { stringField(value, "name") }
    }

    private fun stringField(target: Any, fieldName: String): String {
        return runCatching {
            target.javaClass.fields.firstOrNull { it.name == fieldName }
                ?.get(target)
                ?.toString()
                ?.trim()
                .orEmpty()
        }.getOrDefault("")
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
                    Log.e(TAG, "[Qishui] ✗ Inject Compat Builder", e)
                }
                chain.proceed()
            }
            qishuiHookHandles.add(handle)
            Log.i(TAG, "[Qishui] ✓ Hooked MediaMetadataCompat.Builder.build()")
        } catch (e: Exception) {
            Log.e(TAG, "[Qishui] ✗ Hook Compat Builder", e)
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
                    Log.e(TAG, "[Qishui] ✗ Inject Compat Session", e)
                }
                chain.proceed()
            }
            qishuiHookHandles.add(handle)
            Log.i(TAG, "[Qishui] ✓ Hooked MediaSessionCompat.setMetadata()")
        } catch (e: Exception) {
            Log.e(TAG, "[Qishui] ✗ Hook Compat Session", e)
        }
    }

    /**
     * Find translation from lang_translations JSON.
     * Matches by system locale, with Chinese script fallback.
     * Returns Pair(type, content) or null.
     */
    private fun findTranslation(translations: JSONObject?): Pair<String, String>? {
        if (translations == null || translations.length() == 0) return null

        val keys = translations.keys().asSequence().toList()
        if (keys.isEmpty()) return null

        val locale = java.util.Locale.getDefault()
        val systemTag = buildString {
            append(locale.language.uppercase())
            if (locale.script.isNotEmpty()) append("-${locale.script.uppercase()}")
            if (locale.country.isNotEmpty()) append("-${locale.country.uppercase()}")
        }

        // Exact match
        keys.firstOrNull { it.equals(systemTag, ignoreCase = true) }?.let { key ->
            extractTranslation(translations, key)?.let { return it }
        }

        // Chinese script fallback
        if (locale.language == "zh") {
            val fallbackHans = "ZH-HANS-${locale.country.uppercase()}"
            keys.firstOrNull { it.equals(fallbackHans, ignoreCase = true) }?.let { key ->
                extractTranslation(translations, key)?.let { return it }
            }
            val fallbackHant = "ZH-HANT-${locale.country.uppercase()}"
            keys.firstOrNull { it.equals(fallbackHant, ignoreCase = true) }?.let { key ->
                extractTranslation(translations, key)?.let { return it }
            }
        }

        // Fuzzy match on language prefix
        keys.firstOrNull { it.startsWith(locale.language, ignoreCase = true) }?.let { key ->
            extractTranslation(translations, key)?.let { return it }
        }

        // Fallback to first available translation
        return extractTranslation(translations, keys.first())
    }

    private fun extractTranslation(translations: JSONObject, key: String): Pair<String, String>? {
        val obj = translations.optJSONObject(key) ?: return null
        val type = obj.optString("type", "")
        val content = obj.optString("content", "")
        if (type.isBlank() || content.isBlank()) return null
        return type to content
    }

    /**
     * Search for the cached lyric file in the NetCacheLoader directory.
     * Files are organized in hash-named subdirectories.
     */
    private fun findCacheFile(mediaId: String): File? {
        val dir = File(dataDir ?: return null, "cache/NetCacheLoader")
        if (!dir.isDirectory) return null

        val fileName = calculateCacheFileName(mediaId)

        return try {
            var target: File? = null
            for (subDir in dir.listFiles() ?: emptyArray()) {
                if (!subDir.isDirectory) continue
                val file = File(subDir, fileName)
                if (file.exists()) {
                    target = file
                    break
                }
            }
            target
        } catch (e: Exception) {
            Log.e(TAG, "[Qishui] findCacheFile failed: mediaId=$mediaId", e)
            null
        }
    }

    private fun calculateCacheFileName(mediaId: String): String {
        return md5("/luna/track_v2/$mediaId")
    }

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5")
        val bytes = digest.digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
