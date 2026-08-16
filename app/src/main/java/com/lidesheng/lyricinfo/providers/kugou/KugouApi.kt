package com.lidesheng.lyricinfo.providers.kugou

import android.util.Base64
import android.util.Log
import com.lidesheng.lyricinfo.core.LyricResult
import com.lidesheng.lyricinfo.providers.qishui.KrcParser
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.zip.InflaterInputStream

/**
 * Kugou's public metadata and lyric API.
 *
 * Kugou may expose an OGG/quality-specific hash in the media session. That
 * hash is not always the hash accepted by the lyric service, so the request
 * flow first resolves it through the audio-detail endpoint and then searches
 * lyrics with the returned canonical hash and duration.
 */
internal object KugouApi {

    private const val TAG = "LyricInfo"
    private const val AUDIO_DETAIL_URL =
        "https://gateway.kugou.com/v3/album_audio/audio"
    private val SEARCH_URLS = listOf(
        "https://lyrics.kugou.com/search",
        "https://lyrics.kugou.com/v1/search"
    )
    private val DOWNLOAD_URLS = listOf(
        "https://lyrics.kugou.com/download",
        "https://lyrics.kugou.com/v2/download"
    )
    private const val USER_AGENT =
        "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi"

    /** Current and legacy KRC payloads observed in public Kugou clients. */
    private val KRC_XOR_KEYS = listOf(
        byteArrayOf(
            0x40, 0x47, 0x54, 0x4C, 0x59, 0x52, 0x49, 0x43,
            0x40, 0x58, 0x4B, 0x51, 0x5F, 0x53, 0x5A, 0x52
        ),
        byteArrayOf(
            0x40, 0x47, 0x61, 0x77, 0x5E, 0x32, 0x74, 0x47,
            0x51, 0x36, 0x31, 0x2D, 0xCE.toByte(), 0xD2.toByte(), 0x6E, 0x69
        )
    )

    private val KRC_LINE = Regex("""\[\d+,\d+]""")
    private val LRC_LINE = Regex("""\[\d{1,3}:\d{2}(?:\.\d{1,3})?]""")
    private val WORD_TAG = Regex("""<\d+,\d+,\d+>""")

    data class TrackRequest(
        val identity: String,
        val hash: String
    )

    data class Result(
        val songName: String,
        val artist: String,
        val album: String,
        val lyric: LyricResult
    )

    private data class CanonicalTrack(
        val hash: String,
        val songName: String,
        val artist: String,
        val album: String,
        val durationMs: Long
    )

    private data class DownloadedPayload(
        val content: String,
        val type: String,
        val translation: String,
        val translationType: String
    )

    fun fetch(track: TrackRequest): Result? {
        return try {
            val canonical = resolveCanonicalTrack(track) ?: run {
                Log.w(TAG, "[Kugou] Cannot resolve canonical track: ${track.identity}")
                return null
            }
            Log.i(
                TAG,
                "[Kugou] Canonical track: ${track.identity} -> " +
                    "${canonical.hash}, ${canonical.songName}, ${canonical.durationMs}ms"
            )

            val searchResponse = search(canonical) ?: return null
            val candidates = collectCandidates(searchResponse)
            val candidate = candidates.firstOrNull() ?: run {
                Log.w(TAG, "[Kugou] No lyric candidate: ${canonical.hash}")
                return null
            }
            Log.i(TAG, "[Kugou] Lyric candidates: ${candidates.size}")

            val candidateType = normalizeType(
                firstString(candidate, "fmt", "contenttype", "contentType", "type")
            )
            val candidateContent = decodePayload(
                firstString(candidate, "content", "lyric")
            )
            val downloaded = if (candidateContent == null) downloadContent(candidate) else null
            val content = candidateContent ?: downloaded?.content
            if (content.isNullOrBlank()) {
                Log.w(TAG, "[Kugou] Lyric payload is empty: ${canonical.hash}")
                return null
            }

            val detectedType = detectType(content)
            val originalType = when {
                detectedType.isNotBlank() -> detectedType
                downloaded?.type.orEmpty().isNotBlank() -> downloaded?.type.orEmpty()
                else -> candidateType
            }
            val translationPayload = decodePayload(
                firstString(
                    candidate,
                    "contentts",
                    "trans_content",
                    "transContent",
                    "translation"
                )
            ) ?: downloaded?.translation
            val translationType = normalizeType(
                firstString(candidate, "trans_contenttype", "transContentType", "transtype")
            ).ifBlank { downloaded?.translationType.orEmpty() }
                .ifBlank { translationPayload?.let(::detectType).orEmpty() }

            val merged = KrcParser.parseAndMerge(
                originalType,
                content,
                translationType,
                translationPayload
            ) ?: return null
            if (merged.isBlank()) return null

            val hasWordTiming = originalType == "krc" && WORD_TAG.containsMatchIn(content)
            val hasTranslation = !translationPayload.isNullOrBlank()
            Result(
                songName = canonical.songName.ifBlank {
                    firstString(candidate, "songname", "songName", "song", "title")
                },
                artist = canonical.artist.ifBlank {
                    firstString(candidate, "singer", "singername", "artist")
                },
                album = canonical.album,
                lyric = LyricResult(
                    lyric = merged,
                    format = if (hasWordTiming) "elrc" else "lrc",
                    translation = if (hasTranslation) {
                        if (translationType == "krc" &&
                            WORD_TAG.containsMatchIn(translationPayload)
                        ) {
                            "elrc"
                        } else {
                            "lrc"
                        }
                    } else {
                        ""
                    }
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "[Kugou] API error", e)
            null
        }
    }

    /**
     * Resolve an exact hash to Kugou's canonical audio hash. There is
     * deliberately no title/artist search here: without a provider identity,
     * binding a similar search result could return another version or another
     * playlist item.
     */
    private fun resolveCanonicalTrack(track: TrackRequest): CanonicalTrack? {
        if (track.hash.isBlank()) {
            Log.w(
                TAG,
                "[Kugou] Missing exact hash; skip title/artist search: ${track.identity}"
            )
            return null
        }
        return fetchAudioDetail(track.hash)
    }

    private fun fetchAudioDetail(hash: String): CanonicalTrack? {
        val clientTime = System.currentTimeMillis() / 1000L
        val data = JSONArray().put(JSONObject().put("hash", hash))
        val body = JSONObject()
            .put("area_code", "1")
            .put("show_privilege", "1")
            .put("show_album_info", "1")
            .put("is_publish", "")
            .put("appid", 1005)
            .put("clientver", 11451)
            .put("mid", "114514")
            .put("dfid", "-")
            .put("clienttime", clientTime)
            .put("key", "OIlwieks28dk2k092lksi2UIkp")
            .put("data", data)

        val raw = postJson(
            AUDIO_DETAIL_URL,
            body.toString(),
            mapOf(
                "KG-THash" to "13a3164",
                "KG-RC" to "1",
                "KG-Fake" to "0",
                "KG-RF" to "00869891",
                "x-router" to "kmr.service.kugou.com"
            )
        ) ?: return null
        val response = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val dataObject = extractAudioData(response) ?: return null
        val audioInfo = dataObject.optJSONObject("audio_info") ?: return null
        val canonicalHash = firstString(audioInfo, "hash", "Hash")
            .ifBlank { hash }
        if (canonicalHash.isBlank()) return null

        return CanonicalTrack(
            hash = canonicalHash,
            songName = firstString(dataObject, "ori_audio_name", "songname", "song_name"),
            artist = firstString(dataObject, "author_name", "singer", "artist"),
            album = firstString(
                dataObject.optJSONObject("album_info") ?: JSONObject(),
                "album_name",
                "name"
            ),
            durationMs = firstString(audioInfo, "timelength", "timelength_128")
                .toLongOrNull()
                ?: 0L
        )
    }

    private fun extractAudioData(response: JSONObject): JSONObject? {
        val data = response.optJSONArray("data") ?: return null
        val first = data.opt(0)
        return when (first) {
            is JSONObject -> first
            is JSONArray -> first.optJSONObject(0)
            else -> null
        }
    }

    private fun search(track: CanonicalTrack): JSONObject? {
        val params = linkedMapOf(
            "ver" to "1",
            "man" to "yes",
            "client" to "pc",
            "keyword" to track.songName,
            "hash" to track.hash
        )
        if (track.durationMs > 0L) {
            params["timelength"] = (track.durationMs / 1000L).toString()
        }

        for (endpoint in SEARCH_URLS) {
            val raw = get(endpoint, params) ?: continue
            val response = runCatching { JSONObject(raw) }.getOrNull() ?: continue
            if (collectCandidates(response).isNotEmpty()) return response
        }
        Log.w(TAG, "[Kugou] Invalid or empty lyric search response: ${track.hash}")
        return null
    }

    private fun collectCandidates(response: JSONObject): List<JSONObject> {
        val result = ArrayList<JSONObject>()

        fun addArray(array: JSONArray?) {
            if (array == null) return
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let(result::add)
            }
        }

        addArray(response.optJSONArray("candidates"))
        addArray(response.optJSONArray("ugccandidates"))
        addArray(response.optJSONArray("ugcs"))
        addArray(response.optJSONArray("results"))
        when (val data = response.opt("data")) {
            is JSONObject -> {
                addArray(data.optJSONArray("candidates"))
                addArray(data.optJSONArray("ugcs"))
            }
            is JSONArray -> addArray(data)
        }
        return result
    }

    private fun downloadContent(candidate: JSONObject): DownloadedPayload? {
        val id = firstString(candidate, "id", "download_id")
        val accessKey = firstString(candidate, "accesskey", "accessKey")
        if (id.isBlank() || accessKey.isBlank()) return null

        for (endpoint in DOWNLOAD_URLS) {
            for (client in listOf("android", "pc")) {
                val params = linkedMapOf(
                    "ver" to "1",
                    "client" to client,
                    "id" to id,
                    "accesskey" to accessKey,
                    "fmt" to "krc",
                    "charset" to "utf8"
                )
                if (endpoint.contains("/v2/")) {
                    params["clienttime"] = (System.currentTimeMillis() / 1000L).toString()
                }

                val raw = get(endpoint, params) ?: continue
                val payload = runCatching {
                    val response = JSONObject(raw)
                    val data = response.optJSONObject("data") ?: response
                    val contentValue = firstString(data, "content", "lyric")
                    val decodedContent = decodePayload(contentValue) ?: return@runCatching null
                    val translationValue = firstString(
                        data,
                        "contentts",
                        "trans_content",
                        "transContent",
                        "translation"
                    )
                    DownloadedPayload(
                        content = decodedContent,
                        type = normalizeType(
                            firstString(data, "fmt", "contenttype", "contentType", "type")
                        ).ifBlank { detectType(decodedContent) },
                        translation = decodePayload(translationValue).orEmpty(),
                        translationType = normalizeType(
                            firstString(data, "trans_contenttype", "transContentType", "transtype")
                        )
                    )
                }.getOrNull()
                if (payload != null) return payload
            }
        }
        return null
    }

    private fun decodePayload(value: String?): String? {
        val encoded = value?.trim().orEmpty()
        if (encoded.isBlank()) return null
        if (isLyricText(encoded)) return encoded

        val bytes = runCatching { Base64.decode(encoded, Base64.DEFAULT) }
            .recoverCatching { Base64.decode(encoded, Base64.URL_SAFE) }
            .getOrNull()
            ?: return null

        if (bytes.size >= 4 &&
            String(bytes, 0, 4, StandardCharsets.US_ASCII) == "krc1"
        ) {
            return decodeKrc(bytes)
        }
        return String(bytes, StandardCharsets.UTF_8).takeIf(::isLyricText)
    }

    private fun decodeKrc(bytes: ByteArray): String? {
        val encrypted = bytes.copyOfRange(4, bytes.size)
        for (key in KRC_XOR_KEYS) {
            val decrypted = encrypted.copyOf()
            for (index in decrypted.indices) {
                decrypted[index] = (decrypted[index].toInt() xor
                    key[index % key.size].toInt()).toByte()
            }
            val text = runCatching {
                InflaterInputStream(ByteArrayInputStream(decrypted)).use { input ->
                    val output = ByteArrayOutputStream()
                    input.copyTo(output)
                    output.toString(StandardCharsets.UTF_8.name())
                }
            }.getOrNull()
            if (text != null && isLyricText(text)) return text
        }
        Log.w(TAG, "[Kugou] Unable to decode KRC payload")
        return null
    }

    private fun detectType(content: String): String {
        return when {
            KRC_LINE.containsMatchIn(content) -> "krc"
            LRC_LINE.containsMatchIn(content) -> "lrc"
            else -> ""
        }
    }

    private fun normalizeType(type: String): String {
        return when (type.trim().lowercase()) {
            "krc", "krcx" -> "krc"
            "lrc", "elrc" -> "lrc"
            else -> ""
        }
    }

    private fun isLyricText(value: String): Boolean {
        return KRC_LINE.containsMatchIn(value) || LRC_LINE.containsMatchIn(value)
    }

    private fun firstString(objectValue: JSONObject, vararg keys: String): String {
        for (key in keys) {
            val value = objectValue.opt(key)
            when (value) {
                is CharSequence -> value.toString().trim()
                    .takeIf { it.isNotBlank() }
                    ?.let { return it }
                is Number -> return value.toString()
                is JSONObject -> {
                    firstString(value, "content", "value", "name")
                        .takeIf { it.isNotBlank() }
                        ?.let { return it }
                }
                is JSONArray -> {
                    val names = buildString {
                        for (index in 0 until value.length()) {
                            val item = value.opt(index)
                            val name = when (item) {
                                is JSONObject -> firstString(item, "name", "singer", "artist")
                                else -> item?.toString().orEmpty().trim()
                            }
                            if (name.isNotBlank()) {
                                if (isNotEmpty()) append(", ")
                                append(name)
                            }
                        }
                    }
                    if (names.isNotBlank()) return names
                }
            }
        }
        return ""
    }

    private fun get(endpoint: String, params: Map<String, String>): String? {
        val query = params.entries.joinToString("&") { (key, value) ->
            "${key}=${URLEncoder.encode(value, StandardCharsets.UTF_8.name())}"
        }
        val connection = URI.create("$endpoint?$query").toURL()
            .openConnection() as HttpURLConnection
        return try {
            connection.apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Referer", "https://www.kugou.com/")
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "[Kugou] GET $endpoint -> HTTP $code")
                return null
            }
            connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "[Kugou] GET $endpoint failed: ${e.message}")
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun postJson(
        endpoint: String,
        body: String,
        headers: Map<String, String>
    ): String? {
        val connection = URI.create(endpoint).toURL()
            .openConnection() as HttpURLConnection
        return try {
            connection.apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Referer", "https://www.kugou.com/")
                headers.forEach { (key, value) -> setRequestProperty(key, value) }
            }
            OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8).use { it.write(body) }
            val code = connection.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "[Kugou] POST $endpoint -> HTTP $code")
                return null
            }
            connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "[Kugou] POST $endpoint failed: ${e.message}")
            null
        } finally {
            connection.disconnect()
        }
    }
}
