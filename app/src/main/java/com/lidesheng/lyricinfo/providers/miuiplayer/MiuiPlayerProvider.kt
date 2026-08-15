package com.lidesheng.lyricinfo.providers.miuiplayer

import android.media.MediaMetadata
import android.os.Bundle
import android.util.Log
import com.lidesheng.lyricinfo.core.LyricProvider
import com.lidesheng.lyricinfo.core.LyricResult
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import org.json.JSONObject
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

class MiuiPlayerProvider : LyricProvider {

    companion object {
        private const val TAG = "LyricInfo"
        private const val LYRIC_INFO_KEY = "lyricInfo"
        private const val PACKAGE_NAME = "com.miui.player"
        private const val CUSTOM_FIELD_TITLE = "android.media.metadata.CUSTOM_FIELD_TITLE"
    }

    override val packageName = PACKAGE_NAME

    private data class TrackMetadata(
        val songName: String,
        val artist: String,
        val album: String,
        val songId: String
    ) {
        val key: String
            get() = if (songId.isNotEmpty()) {
                "id:$songId"
            } else {
                "meta:$songName|$artist|$album"
            }
    }

    private data class CapturedLyric(
        val metadata: TrackMetadata,
        val result: LyricResult
    )

    private val lyricCache = ConcurrentHashMap<String, CapturedLyric>()
    private val lastCapturedLyric = AtomicReference<CapturedLyric?>(null)
    private val lastSourceTrack = AtomicReference<TrackMetadata?>(null)
    private val hookHandles = mutableListOf<XposedInterface.HookHandle>()
    private var currentMediaId: String? = null
    private var lastParsedSongKey: String? = null
    private var lastMediaSession: Any? = null
    private var lastMediaMetadata: MediaMetadata? = null

    override fun onAppLoaded(module: XposedModule, param: PackageLoadedParam) {
        Log.i(TAG, "[Hook] ${param.packageName}")
        val classLoader = param.defaultClassLoader

        hookXiaomiMusicEngine(module, classLoader)
        hookSongInfoMetadata(module, classLoader)
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

    private fun hookXiaomiMusicEngine(module: XposedModule, classLoader: ClassLoader) {
        try {
            // 1. 欺骗蓝牙连接状态，强行激活内部歌词控制器的事件分发
            val remoteLyricCtrlClass = Class.forName("com.tencent.qqmusiccommon.util.music.RemoteLyricController", false, classLoader)
            val bluetoothMethod = remoteLyricCtrlClass.getDeclaredMethod("BluetoothA2DPConnected")
            val bluetoothHandle = module.hook(bluetoothMethod).intercept { chain ->
                chain.proceed() // 执行原方法（可选）
                return@intercept true // 强行返回 true
            }
            hookHandles.add(bluetoothHandle)

            // 2. 拦截获取当前歌词的方法，抓取包含全量歌词的 Lyric 对象
            val lyricClass = Class.forName("com.lyricengine.base.Lyric", false, classLoader)
            val getLyricMethod = remoteLyricCtrlClass.getDeclaredMethod("getLyricSentenceIndex", Long::class.javaPrimitiveType, lyricClass)
            val titleField = lyricClass.getDeclaredField("mTitle").apply { isAccessible = true }
            val artistField = lyricClass.getDeclaredField("mArtist").apply { isAccessible = true }
            val albumField = lyricClass.getDeclaredField("mAlbum").apply { isAccessible = true }
            
            val getLyricHandle = module.hook(getLyricMethod).intercept { chain ->
                val result = chain.proceed() // 执行原方法获得索引

                try {
                    val lyricObj = chain.args[1]
                    if (lyricObj != null) {
                        val title = titleField.get(lyricObj) as? String ?: ""
                        val artist = artistField.get(lyricObj) as? String ?: ""
                        val album = albumField.get(lyricObj) as? String ?: ""
                        val lyricTrack = createTrackMetadata(title, artist, album, null)
                        if (lyricTrack == null) {
                            return@intercept result
                        }
                        val track = mergeTrackMetadata(lastSourceTrack.get(), lyricTrack)
                        lastSourceTrack.set(track)

                        val songKey = track.key
                        
                        // 避免对同一首歌的 Lyric 对象进行重复的反射解析
                        if (songKey != lastParsedSongKey) {
                            lastParsedSongKey = songKey
                            val elrcLyric = buildElrcLyric(lyricObj, lyricClass)
                            if (elrcLyric.isNotEmpty()) {
                                lastCapturedLyric.set(
                                    CapturedLyric(track, LyricResult(elrcLyric, "elrc", ""))
                                )
                                Log.d(TAG, "[MiPlayer] ✓ Parsed full ELRC lyrics for: ${track.songName}")
                                
                                // 主动刷新 MediaSession。字段来自 Lyric/SongInfo，而不是 metadata。
                                val session = lastMediaSession
                                val meta = lastMediaMetadata
                                if (session != null && meta != null) {
                                    try {
                                        val bundleField = meta.javaClass.getDeclaredField("mBundle").apply { isAccessible = true }
                                        val bundle = bundleField.get(meta) as Bundle
                                        injectLyricToBundle(bundle, track)
                                        
                                        val setMetaMethod = session.javaClass.getDeclaredMethod("setMetadata", MediaMetadata::class.java)
                                        setMetaMethod.invoke(session, meta)
                                        Log.d(TAG, "[MiPlayer] ✓ Proactively updated MediaSession")
                                    } catch (e: Exception) {
                                        Log.e(TAG, "[MiPlayer] ✗ Failed to proactively update MediaSession", e)
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "[MiPlayer] ✗ Failed to parse lyric object", e)
                }

                return@intercept result
            }
            hookHandles.add(getLyricHandle)
            Log.i(TAG, "[Hook] ✓ Xiaomi Music Engine")
        } catch (e: Exception) {
            Log.e(TAG, "[Hook] ✗ Xiaomi Music Engine failed", e)
        }
    }

    private fun hookSongInfoMetadata(module: XposedModule, classLoader: ClassLoader) {
        try {
            val remoteCtrlMgrClass = Class.forName("com.tencent.qqmusiccommon.util.music.RemoteControlManager", false, classLoader)
            val songInfoClass = Class.forName("com.tencent.qqmusic.core.song.SongInfo", false, classLoader)
            val updateMetaMethod = remoteCtrlMgrClass.getDeclaredMethod("updataMetaData", songInfoClass, String::class.java)
            val getNameMethod = songInfoClass.getDeclaredMethod("getName")
            val getSingerMethod = songInfoClass.getDeclaredMethod("getSinger")
            val getAlbumMethod = songInfoClass.getDeclaredMethod("getAlbum")
            val getMediaMidMethod = songInfoClass.getDeclaredMethod("getMediaMid")
            val getIdMethod = songInfoClass.getDeclaredMethod("getId")

            val handle = module.hook(updateMetaMethod).intercept { chain ->
                try {
                    val songInfo = chain.args[0]
                    val track = if (songInfo != null) {
                        readSongInfoMetadata(
                            songInfo,
                            getNameMethod,
                            getSingerMethod,
                            getAlbumMethod,
                            getMediaMidMethod,
                            getIdMethod
                        )
                    } else {
                        null
                    }
                    if (track != null) {
                        lastSourceTrack.set(track)
                    }

                    // The second argument is the current notification/status-bar lyric text,
                    // not the complete lyric. Only Lyric.mSentences may populate lyricInfo.
                } catch (e: Exception) {
                    Log.e(TAG, "[MiPlayer] ✗ SongInfo metadata parsing failed", e)
                }
                chain.proceed()
            }
            hookHandles.add(handle)
            Log.i(TAG, "[Hook] ✓ Xiaomi SongInfo metadata")
        } catch (e: Exception) {
            Log.e(TAG, "[Hook] ✗ Xiaomi SongInfo metadata failed", e)
        }
    }

    private fun readSongInfoMetadata(
        songInfo: Any,
        getNameMethod: Method,
        getSingerMethod: Method,
        getAlbumMethod: Method,
        getMediaMidMethod: Method,
        getIdMethod: Method
    ): TrackMetadata? {
        val songName = getNameMethod.invoke(songInfo) as? String
        val artist = getSingerMethod.invoke(songInfo) as? String
        val album = getAlbumMethod.invoke(songInfo) as? String
        val mediaMid = getMediaMidMethod.invoke(songInfo) as? String
        val id = (getIdMethod.invoke(songInfo) as? Long)
            ?.takeIf { it != 0L }
            ?.toString()
        return createTrackMetadata(songName, artist, album, mediaMid.orEmpty().ifBlank { id })
    }

    private fun createTrackMetadata(
        songName: String?,
        artist: String?,
        album: String?,
        songId: String?
    ): TrackMetadata? {
        val cleanSongName = songName?.trim().orEmpty()
        if (cleanSongName.isEmpty()) {
            return null
        }
        return TrackMetadata(
            songName = cleanSongName,
            artist = artist?.trim().orEmpty(),
            album = album?.trim().orEmpty(),
            songId = songId?.trim().orEmpty()
        )
    }

    private fun mergeTrackMetadata(
        primary: TrackMetadata?,
        fallback: TrackMetadata
    ): TrackMetadata {
        if (primary == null || !sameTrack(primary, fallback)) {
            return fallback
        }
        return TrackMetadata(
            songName = primary.songName.ifEmpty { fallback.songName },
            artist = primary.artist.ifEmpty { fallback.artist },
            album = primary.album.ifEmpty { fallback.album },
            songId = primary.songId.ifEmpty { fallback.songId }
        )
    }

    private fun sameTrack(first: TrackMetadata, second: TrackMetadata): Boolean {
        if (first.songId.isNotEmpty() && second.songId.isNotEmpty() && first.songId == second.songId) {
            return true
        }
        if (first.songName.isEmpty() || second.songName.isEmpty() || first.songName != second.songName) {
            return false
        }
        if (first.artist.isNotEmpty() && second.artist.isNotEmpty() && first.artist != second.artist) {
            return false
        }
        return first.album.isEmpty() || second.album.isEmpty() || first.album == second.album
    }

    private fun trackFromBundle(bundle: Bundle): TrackMetadata? {
        val title = bundle.getString(CUSTOM_FIELD_TITLE).takeUnless { it.isNullOrBlank() }
            ?: bundle.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = bundle.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            .takeUnless { it.isNullOrBlank() }
            ?: bundle.getString(MediaMetadata.METADATA_KEY_ARTIST)
        val album = bundle.getString(MediaMetadata.METADATA_KEY_ALBUM)
        val mediaId = bundle.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
        return createTrackMetadata(title, artist, album, mediaId)
    }

    private fun findCachedLyric(bundle: Bundle): CapturedLyric? {
        val sourceTrack = lastSourceTrack.get()
        if (sourceTrack != null && trackFromBundle(bundle)?.let { sameTrack(sourceTrack, it) } == true) {
            return lyricCache[sourceTrack.key]
        }

        val bundleTrack = trackFromBundle(bundle) ?: return null
        return lyricCache.values.firstOrNull { sameTrack(it.metadata, bundleTrack) }
    }

    private fun buildElrcLyric(lyricObj: Any, lyricClass: Class<*>): String {
        val builder = StringBuilder()
        val sentencesField = lyricClass.getDeclaredField("mSentences").apply { isAccessible = true }
        val mSentences = sentencesField.get(lyricObj) as? List<*>

        if (mSentences != null) {
            for (sentence in mSentences) {
                if (sentence == null) continue
                val sClass = sentence.javaClass
                val mText = sClass.getDeclaredField("mText").apply { isAccessible = true }.get(sentence) as? String ?: continue
                val mStartTime = sClass.getDeclaredField("mStartTime").apply { isAccessible = true }.get(sentence) as? Long ?: 0L
                
                val mCharacters = sClass.getDeclaredField("mCharacters").apply { isAccessible = true }.get(sentence) as? List<*>
                
                // 行级时间戳: [mm:ss.SSS]
                builder.append("[${formatTime(mStartTime)}]")
                
                if (!mCharacters.isNullOrEmpty()) {
                    for (charObj in mCharacters) {
                        if (charObj == null) continue
                        val cClass = charObj.javaClass
                        val mStart = cClass.getDeclaredField("mStart").apply { isAccessible = true }.get(charObj) as? Int ?: 0
                        val mEnd = cClass.getDeclaredField("mEnd").apply { isAccessible = true }.get(charObj) as? Int ?: 0
                        val wordStartTime = cClass.getDeclaredField("mStartTime").apply { isAccessible = true }.get(charObj) as? Long ?: 0L
                        
                        val word = try {
                            mText.substring(mStart, mEnd)
                        } catch (e: Exception) {
                            ""
                        }
                        // 词级时间戳: <mm:ss.SSS>
                        builder.append("<${formatTime(wordStartTime)}>$word")
                    }
                } else {
                    // 没有逐字数据，降级为整行文本
                    builder.append(mText)
                }
                builder.append("\n")
            }
        }
        return builder.toString().trimEnd()
    }

    private fun formatTime(ms: Long): String {
        val minutes = ms / 60000
        val seconds = (ms % 60000) / 1000
        val millis = ms % 1000
        return String.format("%02d:%02d.%03d", minutes, seconds, millis)
    }

    private fun hookMediaMetadataBuilder(module: XposedModule, classLoader: ClassLoader) {
        try {
            val builderClass = Class.forName("android.media.MediaMetadata\$Builder", false, classLoader)
            val buildMethod = builderClass.getDeclaredMethod("build")
            val sessionClass = Class.forName("android.media.session.MediaSession", false, classLoader)
            val setMetaMethod = sessionClass.getDeclaredMethod("setMetadata", MediaMetadata::class.java)

            // Hook Builder.build() 并在初始化时注入
            val buildHandle = module.hook(buildMethod).intercept { chain ->
                val builder = chain.thisObject
                val bundleField = builder.javaClass.getDeclaredField("mBundle").apply { isAccessible = true }
                val bundle = bundleField.get(builder) as Bundle

                injectLyricToBundle(bundle)
                chain.proceed()
            }
            hookHandles.add(buildHandle)

            // Hook MediaSession.setMetadata() 并动态注入
            val setMetaHandle = module.hook(setMetaMethod).intercept { chain ->
                val session = chain.thisObject
                val metadata = chain.args[0] as? MediaMetadata
                
                lastMediaSession = session
                lastMediaMetadata = metadata
                
                if (metadata != null) {
                    val bundleField = metadata.javaClass.getDeclaredField("mBundle").apply { isAccessible = true }
                    val bundle = bundleField.get(metadata) as Bundle
                    injectLyricToBundle(bundle)
                }
                chain.proceed()
            }
            hookHandles.add(setMetaHandle)

            Log.i(TAG, "[Hook] ✓ MediaMetadata")
        } catch (e: Exception) {
            Log.e(TAG, "[Hook] ✗ MediaMetadata", e)
        }
    }

    private fun injectLyricToBundle(bundle: Bundle, preferredTrack: TrackMetadata? = null) {
        val captured = lastCapturedLyric.getAndSet(null)
        if (captured != null) {
            lyricCache[captured.metadata.key] = captured
        }

        val pendingTrackMatchesBundle = captured != null &&
            trackFromBundle(bundle)?.let { sameTrack(captured.metadata, it) } == true
        val payload = when {
            preferredTrack != null -> lyricCache[preferredTrack.key]
            pendingTrackMatchesBundle -> lyricCache[captured!!.metadata.key]
            else -> findCachedLyric(bundle)
        }
        if (payload != null) {
            val track = payload.metadata
            if (track.key != currentMediaId) {
                currentMediaId = track.key
                Log.i(TAG, "[Song] ${track.songName} - ${track.artist}")
            }
            val json = JSONObject()
                .put("songName", track.songName)
                .put("artist", track.artist)
                .put("album", track.album)
                .put("songId", track.songId)
                .put("lyric", payload.result.lyric)
                .put("format", payload.result.format)
                .put("translation", payload.result.translation)
                .toString()
            bundle.putString(LYRIC_INFO_KEY, json)
        }
    }

    override fun onDestroy() {
        lyricCache.clear()
        lastCapturedLyric.set(null)
        hookHandles.clear()
        currentMediaId = null
        lastParsedSongKey = null
        lastSourceTrack.set(null)
        lastMediaSession = null
        lastMediaMetadata = null
    }
}
