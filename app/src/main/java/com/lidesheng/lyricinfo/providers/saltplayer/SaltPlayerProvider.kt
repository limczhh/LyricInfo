package com.lidesheng.lyricinfo.providers.saltplayer

import android.media.MediaMetadata
import android.media.session.MediaSession
import android.os.Handler
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.lidesheng.lyricinfo.core.LyricCacheEntry
import com.lidesheng.lyricinfo.core.LyricFileCache
import com.lidesheng.lyricinfo.core.LyricNormalizer
import com.lidesheng.lyricinfo.core.LyricProvider
import com.lidesheng.lyricinfo.core.LyricResult
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import org.json.JSONObject
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.enums.MatchType
import org.luckypray.dexkit.query.matchers.ClassMatcher
import org.luckypray.dexkit.query.matchers.FieldsMatcher
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.query.matchers.MethodsMatcher
import org.luckypray.dexkit.result.ClassData
import java.io.File
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

class SaltPlayerProvider : LyricProvider {

    private data class SongIdentity(
        val id: String,
        val title: String,
        val artist: String,
        val album: String
    )

    private data class CapturedLyric(
        val songId: String?,
        val result: LyricResult,
        val capturedAt: Long
    )

    companion object {
        private const val TAG = "LyricInfo"
        private const val LYRIC_INFO_KEY = "lyricInfo"
        private const val PACKAGE_NAME = "com.salt.music"
        private const val SALT_SONG_CLASS = "com.salt.music.data.entry.Song"
        private const val CAPTURE_BIND_WINDOW_MS = 1_500L
        private val TIMED_LYRIC_PATTERN = Regex("""\[\d{2}:\d{2}\.\d{2,3}]""")
        private val TRACK_TEXT_WHITESPACE = Regex("\\s+")
        private val REFRESH_DELAYS_MS = longArrayOf(0L, 50L, 150L, 350L)
    }

    override val packageName = PACKAGE_NAME

    private val lyricCache = ConcurrentHashMap<String, LyricResult>()
    private val lastCapturedLyric = AtomicReference<CapturedLyric?>(null)
    private val currentSong = AtomicReference<SongIdentity?>(null)
    private val hookHandles = mutableListOf<XposedInterface.HookHandle>()
    private var fileCache: LyricFileCache? = null
    private var lastLoggedSongId: String? = null
    @Volatile
    private var currentSongChangedAt: Long = 0L
    @Volatile
    private var lastMediaSession: MediaSession? = null
    @Volatile
    private var lastMediaMetadata: MediaMetadata? = null
    private val refreshHandler = Handler(Looper.getMainLooper())

    override fun onAppLoaded(module: XposedModule, param: PackageLoadedParam) {
        Log.i(TAG, "[Hook] ${param.packageName}")
        fileCache = LyricFileCache(File(param.applicationInfo.dataDir, "cache/lyric_info"))
        val classLoader = param.defaultClassLoader

        try {
            DexkitLoader.load()
        } catch (e: Exception) {
            Log.e(TAG, "[SaltPlayer] ✗ DexKit load failed", e)
            return
        }

        try {
            DexKitBridge.create(param.applicationInfo.sourceDir).use { bridge ->
                hookLyricsConstructor(module, classLoader, bridge)
            }
        } catch (e: Exception) {
            Log.e(TAG, "[SaltPlayer] ✗ Hook lyrics failed", e)
        }

        try {
            hookCurrentSong(module, classLoader)
        } catch (e: Exception) {
            Log.e(TAG, "[SaltPlayer] ✗ Hook current Song failed", e)
        }

        hookMediaMetadataBuilder(module, classLoader)
    }

    override fun replaceHooks(
        module: XposedModule,
        param: PackageLoadedParam,
        oldHooks: List<XposedInterface.HookHandle>
    ): List<XposedInterface.HookHandle> {
        oldHooks.forEach { it.unhook() }
        hookHandles.clear()
        onAppLoaded(module, param)
        return hookHandles.toList()
    }

    private fun hookLyricsConstructor(
        module: XposedModule,
        classLoader: ClassLoader,
        bridge: DexKitBridge
    ) {
        try {
            hookNewVersion(module, classLoader, bridge)
        } catch (e: Exception) {
            Log.d(TAG, "[SaltPlayer] New version failed, trying old")
            hookOldVersion(module, classLoader, bridge)
        }
    }

    private fun hookNewVersion(
        module: XposedModule,
        classLoader: ClassLoader,
        bridge: DexKitBridge
    ) {
        val classData = findNewVersionLyricResultClass(bridge)

        val lyricResultClass = classLoader.loadClass(classData.name)
        try {
            hookFinalLyricPublication(
                module = module,
                classLoader = classLoader,
                bridge = bridge,
                lyricResultClassName = classData.name,
                lyricResultClass = lyricResultClass
            )
            return
        } catch (e: Exception) {
            Log.w(
                TAG,
                "[SaltPlayer] Final lyric publication unavailable, falling back to constructor",
                e
            )
        }

        val constructor = lyricResultClass
            .getConstructor(String::class.java, List::class.java)

        val handle = module.hook(constructor).intercept { chain ->
            val rawLrc = chain.getArg(0) as? String ?: return@intercept chain.proceed()
            val normalized = LyricNormalizer.normalize(rawLrc)
            if (normalized != null) {
                val transFormat = detectTranslationFormat(normalized.lyric)
                lastCapturedLyric.set(
                    CapturedLyric(
                        currentSong.get()?.id,
                        LyricResult(normalized.lyric, normalized.format, transFormat),
                        SystemClock.uptimeMillis()
                    )
                )
                Log.d(TAG, "[SaltPlayer] ✓ Captured lyrics")
                refreshMediaSession()
            }
            chain.proceed()
        }
        hookHandles.add(handle)
        Log.i(TAG, "[SaltPlayer] ✓ Hooked new version constructor")
    }

    /** Resolve the result model by its source/scroll enum fields, as Salt's final publisher does. */
    private fun findNewVersionLyricResultClass(bridge: DexKitBridge): ClassData {
        val sourceEnum = findSingleClassUsingStrings(
            bridge,
            "lyric source enum",
            "EMBEDDED",
            "TAG_LYRICS3_V2"
        )
        val scrollEnum = findSingleClassUsingStrings(
            bridge,
            "lyric scroll enum",
            "CAN_SCROLL",
            "NOT_SCROLL"
        )
        return bridge.findClass(FindClass.create()
            .searchPackages("androidx.obf", "androidx.media3")
            .matcher(ClassMatcher.create()
                .fields(FieldsMatcher.create()
                    .addForType(sourceEnum.name)
                    .addForType(scrollEnum.name)
                    .matchType(MatchType.Contains))))
            .single()
    }

    private fun findSingleClassUsingStrings(
        bridge: DexKitBridge,
        description: String,
        vararg strings: String
    ): ClassData {
        return bridge.findClass(FindClass.create()
            .searchPackages("androidx.obf", "androidx.media3")
            .matcher(ClassMatcher.create().usingEqStrings(*strings)))
            .single()
            .also { Log.d(TAG, "[SaltPlayer] Resolved $description: ${it.name}") }
    }

    /**
     * Salt's lyric parser constructs a result before its asynchronous coroutine publishes that
     * result for the current Song. The constructor has no reliable track identity. The final
     * publisher does: Salt re-checks the current Song before returning, and the coroutine object
     * contains both that Song and the LyricResult. Capture at this boundary so auto-next cannot
     * bind the previous callback to the new MediaSession metadata.
     */
    private fun hookFinalLyricPublication(
        module: XposedModule,
        classLoader: ClassLoader,
        bridge: DexKitBridge,
        lyricResultClassName: String,
        lyricResultClass: Class<*>
    ) {
        val publisherData = bridge.findClass(FindClass.create()
            .searchPackages("androidx.obf", "androidx.media3")
            .matcher(ClassMatcher.create()
                .fields(FieldsMatcher.create()
                    .addForType(SALT_SONG_CLASS)
                    .addForType(lyricResultClassName)
                    .matchType(MatchType.Contains))))
            .single()
        val publisherClass = classLoader.loadClass(publisherData.name)
        val invokeSuspend = findInvokeSuspendMethod(publisherClass)
        invokeSuspend.isAccessible = true

        val handle = module.hook(invokeSuspend).intercept { chain ->
            val result = chain.proceed()
            runCatching {
                captureFinalLyricPublication(
                    chain.thisObject,
                    lyricResultClass,
                    result
                )
            }.onFailure { error ->
                Log.w(TAG, "[SaltPlayer] Final lyric publication decode failed", error)
            }
            result
        }
        hookHandles.add(handle)
        Log.i(
            TAG,
            "[SaltPlayer] ✓ Hooked final lyric publication: " +
                "${publisherClass.name}#${invokeSuspend.name}"
        )
    }

    private fun findInvokeSuspendMethod(publisherClass: Class<*>): Method {
        publisherClass.declaredMethods.firstOrNull { method ->
            method.name == "invokeSuspend" &&
                method.parameterTypes.size == 1 &&
                method.parameterTypes[0] == Any::class.java
        }?.let { return it }

        var candidate: Method? = null
        for (method in publisherClass.declaredMethods) {
            if (method.parameterTypes.size != 1 || method.parameterTypes[0] != Any::class.java) {
                continue
            }
            if (candidate != null) {
                error("Ambiguous Salt Player final publisher methods: $candidate and $method")
            }
            candidate = method
        }
        return candidate ?: error("${publisherClass.name}#invokeSuspend(Object) not found")
    }

    private fun captureFinalLyricPublication(
        publisher: Any?,
        lyricResultClass: Class<*>,
        publicationResult: Any?
    ) {
        if (publisher == null || publicationResult == null) return

        val song = findFieldValueOfType(publisher, SALT_SONG_CLASS) ?: return
        val lyricResult = findFieldValueOfType(publisher, lyricResultClass) ?: return
        val songId = readStringProperty(song, "getId").takeIf { it.isNotBlank() } ?: return
        val title = readStringProperty(song, "getTitle")
        val artist = readStringProperty(song, "getArtist")
        val album = readStringProperty(song, "getAlbum")
        val rawLyric = findTimedLyricField(lyricResult)
            .ifBlank { findRawStringField(lyricResult) }
        val normalized = LyricNormalizer.normalize(rawLyric) ?: return
        val capturedAt = SystemClock.uptimeMillis()
        val identity = SongIdentity(songId, title, artist, album)

        val previous = currentSong.getAndSet(identity)
        if (previous?.id != identity.id) {
            currentSongChangedAt = capturedAt
        }
        lastCapturedLyric.set(
            CapturedLyric(
                songId = songId,
                result = normalized.copy(translation = detectTranslationFormat(normalized.lyric)),
                capturedAt = capturedAt
            )
        )
        Log.d(TAG, "[SaltPlayer] ✓ Captured final lyrics: $title - $artist")
        refreshMediaSession()
    }

    private fun findFieldValueOfType(instance: Any, typeName: String): Any? {
        var currentClass: Class<*>? = instance.javaClass
        while (currentClass != null) {
            for (field in currentClass.declaredFields) {
                if (field.type.name != typeName) continue
                field.isAccessible = true
                field.get(instance)?.let { return it }
            }
            currentClass = currentClass.superclass
        }
        return null
    }

    private fun findFieldValueOfType(instance: Any, type: Class<*>): Any? {
        var currentClass: Class<*>? = instance.javaClass
        while (currentClass != null) {
            for (field in currentClass.declaredFields) {
                if (!type.isAssignableFrom(field.type)) continue
                field.isAccessible = true
                field.get(instance)?.let { return it }
            }
            currentClass = currentClass.superclass
        }
        return null
    }

    private fun readStringProperty(instance: Any, methodName: String): String {
        return runCatching {
            instance.javaClass.getMethod(methodName).invoke(instance) as? String
        }.getOrNull().orEmpty()
    }

    private fun findTimedLyricField(instance: Any): String {
        var currentClass: Class<*>? = instance.javaClass
        while (currentClass != null) {
            for (field in currentClass.declaredFields) {
                if (field.type != String::class.java) continue
                field.isAccessible = true
                val value = field.get(instance) as? String ?: continue
                if (containsTimedLyric(value)) return value
            }
            currentClass = currentClass.superclass
        }
        return ""
    }

    private fun findRawStringField(instance: Any): String {
        var candidate = ""
        var currentClass: Class<*>? = instance.javaClass
        while (currentClass != null) {
            for (field in currentClass.declaredFields) {
                if (field.type != String::class.java) continue
                field.isAccessible = true
                val value = field.get(instance) as? String ?: continue
                if (value.trim().length > candidate.trim().length) candidate = value
            }
            currentClass = currentClass.superclass
        }
        return candidate
    }

    private fun containsTimedLyric(value: String): Boolean =
        TIMED_LYRIC_PATTERN.containsMatchIn(value)

    private fun hookOldVersion(
        module: XposedModule,
        classLoader: ClassLoader,
        bridge: DexKitBridge
    ) {
        val classData = bridge.findClass(FindClass.create()
            .searchPackages("androidx.core")
            .matcher(ClassMatcher.create()
                .fieldCount(5)
                .methods(MethodsMatcher.create().add(
                    MethodMatcher.create().name("<init>")
                        .paramTypes(null, String::class.java, String::class.java)
                ))))
            .single()

        val clazz = classLoader.loadClass(classData.name)
        val constructor = clazz.declaredConstructors.first { c ->
            val params = c.parameterTypes
            params.size == 3 && params[1] == String::class.java && params[2] == String::class.java
        }

        val handle = module.hook(constructor).intercept { chain ->
            val rawLrc = chain.getArg(1) as? String ?: return@intercept chain.proceed()
            val normalized = LyricNormalizer.normalize(rawLrc)
            if (normalized != null) {
                val transFormat = detectTranslationFormat(normalized.lyric)
                lastCapturedLyric.set(
                    CapturedLyric(
                        currentSong.get()?.id,
                        LyricResult(normalized.lyric, normalized.format, transFormat),
                        SystemClock.uptimeMillis()
                    )
                )
                Log.d(TAG, "[SaltPlayer] ✓ Captured lyrics (old)")
                refreshMediaSession()
            }
            chain.proceed()
        }
        hookHandles.add(handle)
        Log.i(TAG, "[SaltPlayer] ✓ Hooked old version constructor")
    }

    private fun hookCurrentSong(module: XposedModule, classLoader: ClassLoader) {
        val controllerClass = classLoader.loadClass("com.salt.music.service.MusicController")
        val songClass = classLoader.loadClass("com.salt.music.data.entry.Song")
        val primitiveLong = Long::class.javaPrimitiveType
        val boxedLong = Long::class.javaObjectType
        val method = controllerClass.declaredMethods.firstOrNull { candidate ->
            val parameters = candidate.parameterTypes
            candidate.returnType == Void.TYPE &&
                parameters.size == 4 &&
                parameters[0] == songClass &&
                parameters[1] == primitiveLong &&
                parameters[2] == primitiveLong &&
                parameters[3] == boxedLong
        } ?: error("Current Song transition method not found")

        val handle = module.hook(method).intercept { chain ->
            val identity = (chain.getArg(0) as? Any)?.let(::readSongIdentity)
            if (identity != null) {
                val previous = currentSong.getAndSet(identity)
                if (previous?.id != identity.id) {
                    val changedAt = SystemClock.uptimeMillis()
                    currentSongChangedAt = changedAt
                    val pending = lastCapturedLyric.get()
                    if (pending != null &&
                        (pending.songId == null || pending.songId == previous?.id) &&
                        changedAt - pending.capturedAt in 0..CAPTURE_BIND_WINDOW_MS
                    ) {
                        lastCapturedLyric.compareAndSet(
                            pending,
                            pending.copy(songId = identity.id)
                        )
                    }
                    fileCache?.read(identity.id)?.let { cached ->
                        lyricCache[identity.id] = cached.result
                    }
                    lastLoggedSongId = identity.id
                    Log.i(TAG, "[Song] ${identity.title} - ${identity.artist}")
                }
            }
            chain.proceed()
        }
        hookHandles.add(handle)
        Log.i(TAG, "[SaltPlayer] ✓ Hooked current Song transition: ${method.name}")
    }

    private fun readSongIdentity(song: Any): SongIdentity? {
        val id = invokeSongString(song, "getId")?.takeIf { it.isNotEmpty() } ?: return null
        return SongIdentity(
            id = id,
            title = invokeSongString(song, "getTitle").orEmpty(),
            artist = invokeSongString(song, "getArtist").orEmpty(),
            album = invokeSongString(song, "getAlbum").orEmpty()
        )
    }

    private fun invokeSongString(song: Any, methodName: String): String? {
        return runCatching {
            song.javaClass.getMethod(methodName).invoke(song) as? String
        }.getOrNull()
    }

    private fun hookMediaMetadataBuilder(module: XposedModule, classLoader: ClassLoader) {
        try {
            val builderClass = Class.forName(
                $$"android.media.MediaMetadata$Builder", false, classLoader
            )
            val buildMethod = builderClass.getDeclaredMethod("build")
            module.deoptimize(buildMethod)

            val handle = module.hook(buildMethod).intercept { chain ->
                runCatching {
                    val builder = chain.thisObject
                    injectLyricInfo(builderBundle(builder))
                }.onFailure { error ->
                    Log.w(TAG, "[SaltPlayer] Builder.build() inject failed", error)
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
            val setMetadataMethod = sessionClass.getDeclaredMethod(
                "setMetadata", MediaMetadata::class.java
            )
            module.deoptimize(setMetadataMethod)
            val handle = module.hook(setMetadataMethod).intercept { chain ->
                val session = chain.thisObject
                val metadata = chain.getArg(0) as? MediaMetadata
                lastMediaSession = session as? MediaSession
                if (metadata != null) {
                    lastMediaMetadata = metadata
                    runCatching {
                        injectLyricInfo(metadataBundle(metadata))
                    }.onFailure { error ->
                        Log.w(TAG, "[SaltPlayer] MediaSession.setMetadata() inject failed", error)
                    }
                } else {
                    lastMediaMetadata = null
                }
                chain.proceed()
            }
            hookHandles.add(handle)
            Log.i(TAG, "[Hook] ✓ MediaSession.setMetadata()")
        } catch (e: Exception) {
            Log.e(TAG, "[Hook] ✗ MediaSession.setMetadata()", e)
        }

        hookCompatMediaMetadata(module, classLoader)
    }

    /**
     * SaltPlayer's support-media classes are R8-shrunk, so the usual compat
     * Builder/Session method names are not present in this APK. Its stable
     * compat entry point is MediaMetadataCompat(Bundle), which copies the
     * bundle before media3 submits the resulting framework metadata.
     */
    private fun hookCompatMediaMetadata(module: XposedModule, classLoader: ClassLoader) {
        try {
            val metadataClass = classLoader.loadClass(
                "android.support.v4.media.MediaMetadataCompat"
            )
            val constructor = metadataClass.getDeclaredConstructor(Bundle::class.java)
            val handle = module.hook(constructor).intercept { chain ->
                runCatching {
                    val bundle = chain.getArg(0) as? Bundle
                    if (bundle != null) injectLyricInfo(bundle)
                }.onFailure { error ->
                    Log.w(TAG, "[SaltPlayer] Compat metadata inject failed", error)
                }
                chain.proceed()
            }
            hookHandles.add(handle)
            Log.i(TAG, "[Hook] ✓ MediaMetadataCompat(Bundle)")
        } catch (e: Exception) {
            Log.w(TAG, "[Hook] Compat metadata constructor unavailable", e)
        }
    }

    private fun refreshMediaSession() {
        REFRESH_DELAYS_MS.forEach { delayMs ->
            refreshHandler.postDelayed({ refreshMediaSessionNow() }, delayMs)
        }
    }

    private fun refreshMediaSessionNow() {
        val session = lastMediaSession ?: return
        val metadata = lastMediaMetadata ?: return
        try {
            val bundle = metadataBundle(metadata)
            val metadataIdentity = resolveBuildIdentity(bundle) ?: return
            val captured = lastCapturedLyric.get()
            if (captured != null && !capturedMatches(captured, metadataIdentity) &&
                lyricCache[metadataIdentity.id] == null
            ) {
                return
            }
            if (!injectLyricInfo(bundle)) return

            // Rebuild the object after changing its backing Bundle. This makes the
            // post-lyrics refresh visible to MediaSession and its remote controllers.
            val refreshedMetadata = MediaMetadata.Builder(metadata).build()
            lastMediaMetadata = refreshedMetadata
            session.setMetadata(refreshedMetadata)
            Log.d(TAG, "[SaltPlayer] ✓ Refreshed MediaSession metadata")
        } catch (e: Exception) {
            Log.w(TAG, "[SaltPlayer] ✗ Refresh MediaSession metadata failed", e)
        }
    }

    private fun injectLyricInfo(bundle: Bundle): Boolean {
        val identity = resolveBuildIdentity(bundle)
        if (identity == null) {
            bundle.remove(LYRIC_INFO_KEY)
            return false
        }

        val songKey = identity.id
        if (songKey != lastLoggedSongId) {
            lastLoggedSongId = songKey
            Log.i(TAG, "[Song] ${identity.title} - ${identity.artist}")
        }

        if (!lyricCache.containsKey(songKey)) {
            fileCache?.read(songKey)?.let { cached ->
                lyricCache[songKey] = cached.result
            }
        }

        val captured = lastCapturedLyric.get()
        if (captured != null && capturedMatches(captured, identity) &&
            lastCapturedLyric.compareAndSet(captured, null)
        ) {
            lyricCache[songKey] = captured.result
            fileCache?.write(
                songKey,
                LyricCacheEntry(
                    result = captured.result,
                    songName = identity.title,
                    artist = identity.artist,
                    album = identity.album,
                    songId = identity.id
                )
            )
        }

        val result = lyricCache[songKey]
        if (result == null) {
            // Lyrics are captured asynchronously. A copied metadata Bundle may still carry
            // the previous track's JSON; keep it until this track's result is ready. The
            // consumer validates the JSON identity against the top-level metadata and will
            // reject that stale value instead of rendering it for the new track.
            return false
        }

        val json = JSONObject()
            .put("songName", identity.title)
            .put("artist", identity.artist)
            .put("album", identity.album)
            .put("songId", identity.id)
            .put("lyric", result.lyric)
            .put("format", result.format)
            .put("translation", result.translation)
            .toString()
        bundle.putString(LYRIC_INFO_KEY, json)
        Log.d(TAG, "[Inject] ✓ ${identity.title}")
        return true
    }

    private fun capturedMatches(captured: CapturedLyric, identity: SongIdentity): Boolean {
        val currentIdentity = currentSong.get()
        if (currentIdentity == null) return captured.songId == identity.id
        if (captured.songId != null && captured.songId != currentIdentity.id) return false
        if (!sameTrack(currentIdentity, identity)) return false
        return currentSongChangedAt == 0L || captured.capturedAt >= currentSongChangedAt
    }

    private fun resolveBuildIdentity(bundle: Bundle): SongIdentity? {
        val metadataIdentity = readMetadataIdentity(bundle)
        val currentIdentity = currentSong.get()
        if (metadataIdentity == null) return currentIdentity
        if (currentIdentity == null || !sameTrack(currentIdentity, metadataIdentity)) {
            return metadataIdentity
        }
        return currentIdentity.copy(
            title = metadataIdentity.title.ifBlank { currentIdentity.title },
            artist = metadataIdentity.artist.ifBlank { currentIdentity.artist },
            album = metadataIdentity.album.ifBlank { currentIdentity.album }
        )
    }

    private fun readMetadataIdentity(bundle: Bundle): SongIdentity? {
        val mediaId = bundle.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty()
        val title = bundle.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = bundle.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        val album = bundle.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
        if (mediaId.isBlank() && title.isBlank() && artist.isBlank() && album.isBlank()) {
            return null
        }
        val fallbackKey = "$title|$artist|${bundle.getLong(MediaMetadata.METADATA_KEY_DURATION)}"
            .hashCode()
            .toString()
        return SongIdentity(
            id = mediaId.ifBlank { fallbackKey },
            title = title,
            artist = artist,
            album = album
        )
    }

    private fun sameTrack(first: SongIdentity, second: SongIdentity): Boolean {
        val titleMatches = first.title.isBlank() || second.title.isBlank() ||
            sameTrackText(first.title, second.title)
        val artistMatches = first.artist.isBlank() || second.artist.isBlank() ||
            sameTrackText(first.artist, second.artist)
        if (!titleMatches || !artistMatches) return false
        return first.id == second.id ||
            (first.title.isNotBlank() && second.title.isNotBlank())
    }

    private fun sameTrackText(first: String, second: String): Boolean =
        first.replace(TRACK_TEXT_WHITESPACE, " ").trim()
            .equals(second.replace(TRACK_TEXT_WHITESPACE, " ").trim(), ignoreCase = true)

    private fun builderBundle(builder: Any): Bundle {
        val field = builder.javaClass.getDeclaredField("mBundle").apply { isAccessible = true }
        return field.get(builder) as Bundle
    }

    private fun metadataBundle(metadata: MediaMetadata): Bundle {
        val field = metadata.javaClass.getDeclaredField("mBundle").apply { isAccessible = true }
        return field.get(metadata) as Bundle
    }

    private fun detectTranslationFormat(lyrics: String): String {
        val lrcTag = Regex("""\[\d{2}:\d{2}\.\d{2,3}]""")
        val elrcTag = Regex("""<\d{2}:\d{2}\.\d{2,3}>""")
        val groups = LinkedHashMap<String, MutableList<String>>()

        for (line in lyrics.lines()) {
            val tag = lrcTag.find(line)?.value ?: continue
            groups.getOrPut(tag) { mutableListOf() }.add(line)
        }

        for ((_, lines) in groups) {
            if (lines.size < 2) continue
            for (i in 1 until lines.size) {
                val body = lines[i].substringAfter("]")
                if (elrcTag.containsMatchIn(body)) return "elrc"
            }
            return "lrc"
        }
        return ""
    }

    override fun onDestroy() {
        lyricCache.clear()
        fileCache = null
        lastCapturedLyric.set(null)
        currentSong.set(null)
        hookHandles.clear()
        lastLoggedSongId = null
        currentSongChangedAt = 0L
        lastMediaSession = null
        lastMediaMetadata = null
        refreshHandler.removeCallbacksAndMessages(null)
    }
}
