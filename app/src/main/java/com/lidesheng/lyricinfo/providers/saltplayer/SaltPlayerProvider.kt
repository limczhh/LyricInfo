package com.lidesheng.lyricinfo.providers.saltplayer

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
import org.luckypray.dexkit.query.matchers.ClassMatcher
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.query.matchers.MethodsMatcher
import java.io.File
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
        val result: LyricResult
    )

    companion object {
        private const val TAG = "LyricInfo"
        private const val LYRIC_INFO_KEY = "lyricInfo"
        private const val PACKAGE_NAME = "com.salt.music"
    }

    override val packageName = PACKAGE_NAME

    private val lyricCache = ConcurrentHashMap<String, LyricResult>()
    private val lastCapturedLyric = AtomicReference<CapturedLyric?>(null)
    private val currentSong = AtomicReference<SongIdentity?>(null)
    private val hookHandles = mutableListOf<XposedInterface.HookHandle>()
    private var fileCache: LyricFileCache? = null
    private var lastLoggedSongId: String? = null

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
        val classData = bridge.findClass(FindClass.create()
            .matcher(ClassMatcher.create()
                .usingStrings("LyricsDocument(sourceText=")))
            .single()

        val constructor = classLoader.loadClass(classData.name)
            .getConstructor(String::class.java, List::class.java)

        val handle = module.hook(constructor).intercept { chain ->
            val rawLrc = chain.getArg(0) as? String ?: return@intercept chain.proceed()
            val normalized = LyricNormalizer.normalize(rawLrc)
            if (normalized != null) {
                val transFormat = detectTranslationFormat(normalized.lyric)
                lastCapturedLyric.set(
                    CapturedLyric(
                        currentSong.get()?.id,
                        LyricResult(normalized.lyric, normalized.format, transFormat)
                    )
                )
                Log.d(TAG, "[SaltPlayer] ✓ Captured lyrics")
            }
            chain.proceed()
        }
        hookHandles.add(handle)
        Log.i(TAG, "[SaltPlayer] ✓ Hooked new version constructor")
    }

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
                        LyricResult(normalized.lyric, normalized.format, transFormat)
                    )
                )
                Log.d(TAG, "[SaltPlayer] ✓ Captured lyrics (old)")
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
                    lastCapturedLyric.set(null)
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

            val handle = module.hook(buildMethod).intercept { chain ->
                val identity = currentSong.get() ?: return@intercept chain.proceed()
                val songKey = identity.id
                if (songKey != lastLoggedSongId) {
                    lastLoggedSongId = songKey
                    Log.i(TAG, "[Song] ${identity.title} - ${identity.artist}")
                }

                val captured = lastCapturedLyric.get()
                if (captured != null && captured.songId == songKey &&
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
                if (result != null) {
                    val json = JSONObject()
                        .put("songName", identity.title)
                        .put("artist", identity.artist)
                        .put("album", identity.album)
                        .put("songId", identity.id)
                        .put("lyric", result.lyric)
                        .put("format", result.format)
                        .put("translation", result.translation)
                        .toString()
                    val builder = chain.thisObject
                    builder.javaClass.getMethod("putString", String::class.java, String::class.java)
                        .invoke(builder, LYRIC_INFO_KEY, json)
                    Log.d(TAG, "[Inject] ✓ ${identity.title}")
                }

                chain.proceed()
            }
            hookHandles.add(handle)
            Log.i(TAG, "[Hook] ✓ Builder.build()")
        } catch (e: Exception) {
            Log.e(TAG, "[Hook] ✗ Builder.build()", e)
        }
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
    }
}
