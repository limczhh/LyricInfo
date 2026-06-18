package com.lidesheng.lyricinfo.providers.saltplayer

import android.media.MediaMetadata
import android.util.Log
import com.lidesheng.lyricinfo.core.LyricNormalizer
import com.lidesheng.lyricinfo.core.LyricProvider
import com.lidesheng.lyricinfo.core.LyricResult
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import org.json.JSONObject
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.matchers.ClassMatcher
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.query.matchers.MethodsMatcher
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

class SaltPlayerProvider : LyricProvider {

    companion object {
        private const val TAG = "LyricInfo"
        private const val LYRIC_INFO_KEY = "lyricInfo"
        private const val PACKAGE_NAME = "com.salt.music"
    }

    override val packageName = PACKAGE_NAME

    private val lyricCache = ConcurrentHashMap<String, LyricResult>()
    private val lastCapturedLyric = AtomicReference<LyricResult?>(null)

    override fun onAppLoaded(module: XposedModule, param: PackageLoadedParam) {
        Log.i(TAG, "[Hook] ${param.packageName}")
        val classLoader = param.defaultClassLoader

        try {
            DexkitLoader.load()
        } catch (e: Exception) {
            Log.e(TAG, "[SaltPlayer] Failed to load DexKit", e)
            return
        }

        try {
            DexKitBridge.create(param.applicationInfo.sourceDir).use { bridge ->
                hookLyricsConstructor(module, classLoader, bridge)
            }
        } catch (e: Exception) {
            Log.e(TAG, "[SaltPlayer] Failed to hook lyrics", e)
        }

        hookMediaMetadataBuilder(module, classLoader)
    }

    private fun hookLyricsConstructor(
        module: XposedModule,
        classLoader: ClassLoader,
        bridge: DexKitBridge
    ) {
        try {
            hookNewVersion(module, classLoader, bridge)
        } catch (e: Exception) {
            Log.d(TAG, "[SaltPlayer] New version hook failed, trying old version", e)
            hookOldVersion(module, classLoader, bridge)
        }
    }

    /**
     * SaltPlayer >= 11.0.0: find class using string "LyricsDocument(sourceText=",
     * hook (String, List) constructor, arg(0) is raw LRC.
     */
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

        module.hook(constructor).intercept { chain ->
            val rawLrc = chain.getArg(0) as? String ?: return@intercept chain.proceed()
            val normalized = LyricNormalizer.normalize(rawLrc)
            if (normalized != null) {
                val transFormat = detectTranslationFormat(normalized.lyric)
                val result = LyricResult(
                    lyric = normalized.lyric,
                    format = normalized.format,
                    translation = transFormat
                )
                lastCapturedLyric.set(result)
                Log.d(TAG, "[SaltPlayer] Captured lyrics: ${result.lyric.length} chars, format=${result.format}")
            }
            chain.proceed()
        }

        Log.i(TAG, "[SaltPlayer] Hooked new version lyrics constructor")
    }

    /**
     * SaltPlayer < 11.0.0: find class in androidx.core with 5 fields,
     * hook constructor(null, String, String), arg(1) is raw LRC.
     */
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

        module.hook(constructor).intercept { chain ->
            val rawLrc = chain.getArg(1) as? String ?: return@intercept chain.proceed()
            val normalized = LyricNormalizer.normalize(rawLrc)
            if (normalized != null) {
                val transFormat = detectTranslationFormat(normalized.lyric)
                val result = LyricResult(
                    lyric = normalized.lyric,
                    format = normalized.format,
                    translation = transFormat
                )
                lastCapturedLyric.set(result)
                Log.d(TAG, "[SaltPlayer] Captured old-version lyrics: ${result.lyric.length} chars, format=${result.format}")
            }
            chain.proceed()
        }

        Log.i(TAG, "[SaltPlayer] Hooked old version lyrics constructor")
    }

    private fun hookMediaMetadataBuilder(module: XposedModule, classLoader: ClassLoader) {
        try {
            val builderClass = Class.forName(
                "android.media.MediaMetadata\$Builder", false, classLoader
            )
            val buildMethod = builderClass.getDeclaredMethod("build")

            module.hook(buildMethod).intercept { chain ->
                val builder = chain.thisObject
                val bundleField = builder.javaClass.getDeclaredField("mBundle")
                bundleField.isAccessible = true
                val bundle = bundleField.get(builder) as android.os.Bundle

                val mediaId = bundle.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
                val title = bundle.getString(MediaMetadata.METADATA_KEY_TITLE)
                val artist = bundle.getString(MediaMetadata.METADATA_KEY_ARTIST)
                val duration = bundle.getLong(MediaMetadata.METADATA_KEY_DURATION)

                val songKey = mediaId ?: "$title|$artist|$duration".hashCode().toString()

                // Atomically move captured lyric into cache
                val captured = lastCapturedLyric.getAndSet(null)
                if (captured != null) {
                    lyricCache[songKey] = captured
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
                    builder.javaClass.getMethod("putString", String::class.java, String::class.java)
                        .invoke(builder, LYRIC_INFO_KEY, json)
                }

                chain.proceed()
            }

            Log.i(TAG, "[SaltPlayer] Hooked MediaMetadata.Builder.build()")
        } catch (e: Exception) {
            Log.e(TAG, "[SaltPlayer] Failed to hook Builder.build()", e)
        }
    }

    /**
     * Detect translation format from interleaved lyrics.
     * Returns "lrc" or "elrc" if translations exist, "" if not.
     */
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
            // Check translation lines (index 1+) for word-level tags
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
        lastCapturedLyric.set(null)
    }
}
