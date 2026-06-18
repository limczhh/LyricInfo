package com.lidesheng.lyricinfo.providers.qishui

import android.util.Log
import com.lidesheng.lyricinfo.core.BaseLyricProvider
import com.lidesheng.lyricinfo.core.LyricResult
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Qishui Music (汽水音乐) lyric provider.
 *
 * Unlike Netease/QQ Music which fetch lyrics via HTTP APIs, Qishui caches
 * downloaded KRC lyric files locally in its cache directory. This provider
 * reads those cached files directly and parses the KRC format.
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

    override fun onAppLoaded(module: XposedModule, param: PackageLoadedParam) {
        dataDir = param.applicationInfo.dataDir
        super.onAppLoaded(module, param)
    }

    override fun fetchLyric(mediaId: String, title: String?, artist: String?): LyricResult? {
        // Read from Qishui's local KRC cache
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
