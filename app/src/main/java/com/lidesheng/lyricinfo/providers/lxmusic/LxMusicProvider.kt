package com.lidesheng.lyricinfo.providers.lxmusic

import android.annotation.SuppressLint
import android.media.MediaMetadata
import android.os.Bundle
import android.util.Log
import android.util.LruCache
import com.lidesheng.lyricinfo.core.LyricNormalizer
import com.lidesheng.lyricinfo.core.LyricProvider
import com.lidesheng.lyricinfo.core.LyricResult
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import org.json.JSONObject
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicReference

/**
 * LX Music 歌词提供器。
 *
 * LX Music 会聚合多个音乐源，并通过 LyricModule.setLyric()
 * 将歌词传给桌面歌词视图。该提供器 hook 这个调用，对捕获的
 * 歌词做归一化处理，再作为 lyricInfo 注入 MediaMetadata。
 *
 * 不同版本共用相同的 LyricModule 结构：
 * - 主版本：cn.toside.music.mobile
 * - IKunShare：com.ikunshare.music.mobile
 */
@SuppressLint("SoonBlockedPrivateApi")
open class LxMusicProvider(
    override val packageName: String,
    private val lyricModuleClass: String
) : LyricProvider {

    constructor() : this(
        packageName = "cn.toside.music.mobile",
        lyricModuleClass = "cn.toside.music.mobile.lyric.LyricModule"
    )

    companion object {
        private const val TAG = "LyricInfo"
        private const val LYRIC_INFO_KEY = "lyricInfo"
        private const val MAX_LYRIC_CACHE_ENTRIES = 64
    }

    private val lyricCache = LruCache<String, LyricResult>(MAX_LYRIC_CACHE_ENTRIES)
    private val lastCapturedLyric = AtomicReference<LyricResult?>(null)
    private val hookHandles = mutableListOf<XposedInterface.HookHandle>()
    private var lastMediaSession: Any? = null
    private var lastMediaMetadata: MediaMetadata? = null
    @Volatile private var builderBundleField: Field? = null
    @Volatile private var metadataBundleField: Field? = null
    @Volatile private var setMetadataMethod: Method? = null

    override fun onAppLoaded(module: XposedModule, param: PackageLoadedParam) {
        val classLoader = param.defaultClassLoader

        installLyricModuleHook(module, classLoader)
        installMediaMetadataHooks(module, classLoader)
    }

    override fun replaceHooks(
        module: XposedModule,
        param: PackageLoadedParam,
        oldHooks: List<XposedInterface.HookHandle>
    ): List<XposedInterface.HookHandle> {
        oldHooks.forEach { it.unhook() }
        hookHandles.clear()
        builderBundleField = null
        metadataBundleField = null
        setMetadataMethod = null
        onAppLoaded(module, param)
        return hookHandles.toList()
    }

    private fun installLyricModuleHook(module: XposedModule, classLoader: ClassLoader) {
        try {
            val lyricModule = Class.forName(lyricModuleClass, false, classLoader)
            val setLyricMethod = lyricModule.declaredMethods.firstOrNull { it.name == "setLyric" }
                ?: return
            val paramTypes = setLyricMethod.parameterTypes

            val handle = module.hook(setLyricMethod).intercept { chain ->
                val lyric = chain.getArg(0) as? String ?: return@intercept chain.proceed()
                val trans = if (paramTypes.size > 1) chain.getArg(1) as? String else null
                val roma = if (paramTypes.size > 2) chain.getArg(2) as? String else null

                val result = chain.proceed()
                try {
                    handleSetLyric(lyric, trans, roma)
                } catch (e: Exception) {
                    Log.e(TAG, "[LxMusic] setLyric hook error", e)
                }
                result
            }
            hookHandles.add(handle)
        } catch (e: Exception) {
            Log.e(TAG, "[LxMusic] Failed to hook LyricModule.setLyric()", e)
        }
    }

    private fun handleSetLyric(lyric: String, trans: String?, roma: String?) {
        val normalized = LyricNormalizer.normalize(lyric) ?: return
        if (normalized.lyric.isBlank()) return

        val transNormalized = trans?.takeIf { it.isNotBlank() }?.let { LyricNormalizer.normalize(it) }
        val romaNormalized = roma?.takeIf { it.isNotBlank() }?.let { LyricNormalizer.normalize(it) }
        val extras = listOfNotNull(transNormalized, romaNormalized)
            .filter { it.lyric.isNotBlank() }
        val merged = if (extras.isEmpty()) {
            normalized.lyric
        } else {
            LyricNormalizer.merge(normalized.lyric, extras.joinToString("\n") { it.lyric })
        }

        lastCapturedLyric.set(
            LyricResult(
                lyric = merged,
                format = normalized.format,
                translation = transNormalized?.format ?: ""
            )
        )
        refreshMediaSession()
    }

    private fun installMediaMetadataHooks(module: XposedModule, classLoader: ClassLoader) {
        try {
            val builderClass = Class.forName(
                "android.media.MediaMetadata\$Builder", false, classLoader
            )
            val buildMethod = builderClass.getDeclaredMethod("build")

            val handle = module.hook(buildMethod).intercept { chain ->
                try {
                    inject(builderBundle(chain.thisObject))
                } catch (e: Exception) {
                    Log.e(TAG, "[LxMusic] Builder.build() inject failed", e)
                }
                chain.proceed()
            }
            hookHandles.add(handle)
        } catch (e: Exception) {
            Log.e(TAG, "[LxMusic] Failed to hook Builder.build()", e)
        }

        try {
            val sessionClass = Class.forName(
                "android.media.session.MediaSession", false, classLoader
            )
            val setMetaMethod = sessionClass.getDeclaredMethod(
                "setMetadata", MediaMetadata::class.java
            )

            val handle = module.hook(setMetaMethod).intercept { chain ->
                try {
                    val session = chain.thisObject
                    val metadata = chain.getArg(0) as? MediaMetadata
                    if (metadata != null) {
                        lastMediaSession = session
                        lastMediaMetadata = metadata
                        inject(metadataBundle(metadata))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "[LxMusic] MediaSession.setMetadata() inject failed", e)
                }
                chain.proceed()
            }
            hookHandles.add(handle)
        } catch (e: Exception) {
            Log.e(TAG, "[LxMusic] Failed to hook MediaSession.setMetadata()", e)
        }
    }

    private fun refreshMediaSession() {
        val session = lastMediaSession ?: return
        val metadata = lastMediaMetadata ?: return
        try {
            inject(metadataBundle(metadata))
            setMetadataMethod(session).invoke(session, metadata)
        } catch (e: Exception) {
            Log.e(TAG, "[LxMusic] Failed to refresh MediaSession", e)
        }
    }

    private fun inject(bundle: Bundle) {
        val key = songKey(bundle)
        val captured = lastCapturedLyric.getAndSet(null)
        if (captured != null) {
            lyricCache.put(key, captured)
        }

        val result = lyricCache.get(key) ?: return
        val json = JSONObject()
            .put("songName", bundle.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "")
            .put("artist", bundle.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "")
            .put("songId", bundle.getString(MediaMetadata.METADATA_KEY_MEDIA_ID) ?: "")
            .put("lyric", result.lyric)
            .put("format", result.format)
            .put("translation", result.translation)
            .toString()
        bundle.putString(LYRIC_INFO_KEY, json)
    }

    private fun songKey(bundle: Bundle): String {
        val title = bundle.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = bundle.getString(MediaMetadata.METADATA_KEY_ARTIST)
        return "$title|$artist".hashCode().toString()
    }

    private fun builderBundle(builder: Any): Bundle {
        val field = builderBundleField ?: builder.javaClass.getDeclaredField("mBundle").also {
            it.isAccessible = true
            builderBundleField = it
        }
        return field.get(builder) as Bundle
    }

    private fun metadataBundle(metadata: MediaMetadata): Bundle {
        val field = metadataBundleField ?: metadata.javaClass.getDeclaredField("mBundle").also {
            it.isAccessible = true
            metadataBundleField = it
        }
        return field.get(metadata) as Bundle
    }

    private fun setMetadataMethod(session: Any): Method {
        val method = setMetadataMethod ?: session.javaClass.getDeclaredMethod(
            "setMetadata", MediaMetadata::class.java
        ).also {
            setMetadataMethod = it
        }
        return method
    }

    override fun onDestroy() {
        hookHandles.forEach { it.unhook() }
        hookHandles.clear()
        lyricCache.evictAll()
        lastCapturedLyric.set(null)
        lastMediaSession = null
        lastMediaMetadata = null
        builderBundleField = null
        metadataBundleField = null
        setMetadataMethod = null
    }
}
