package com.mediasession.metadata.debug

import android.app.Application
import android.annotation.SuppressLint
import android.content.Context
import android.app.ActivityManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Message
import android.os.SystemClock
import android.util.Log
import com.mediasession.metadata.debug.bridge.DebugContract
import com.mediasession.metadata.debug.source.MediaSnapshotFactory
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.lang.reflect.Method
import java.util.WeakHashMap

/** SystemUI-side acquisition path for MediaSession state and lyricInfo. */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
class HookEntry : XposedModule() {
    companion object {
        private const val TAG = "MediaSessionDebug"
        private const val LOG_CHUNK_LENGTH = 800
        private const val MSG_UPDATE_PLAYBACK_STATE = 2
        private const val MSG_UPDATE_METADATA = 3
        private const val MSG_DESTROYED = 8
        private const val FOREGROUND_CACHE_MS = 500L
    }

    private val hookHandles = mutableListOf<XposedInterface.HookHandle>()
    private val callbackControllers = WeakHashMap<Any, MediaController>()
    private val loggedLyricInfo = WeakHashMap<MediaController, String>()
    private val callbackLock = Any()
    private val foregroundLock = Any()
    @Volatile
    private var cachedSystemContext: Context? = null
    @Volatile
    private var cachedForegroundAt: Long = 0L
    @Volatile
    private var cachedForeground: Boolean = false
    private var currentPackageReadyParam: PackageReadyParam? = null

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        Log.d(TAG, "[Module] Loaded in ${param.processName}")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != DebugContract.SYSTEM_UI_PACKAGE) return
        currentPackageReadyParam = param
        installHooks(param.classLoader)
    }

    private fun installHooks(classLoader: ClassLoader) {
        hookHandles.clear()
        synchronized(callbackLock) {
            callbackControllers.clear()
            loggedLyricInfo.clear()
        }

        val mediaControllerClass = MediaController::class.java
        val callbackClass = MediaController.Callback::class.java
        try {
            hookRegisterCallback(
                mediaControllerClass.getDeclaredMethod("registerCallback", callbackClass)
            )
            hookRegisterCallback(
                mediaControllerClass.getDeclaredMethod(
                    "registerCallback",
                    callbackClass,
                    Handler::class.java
                )
            )
            hookUnregisterCallback(
                mediaControllerClass.getDeclaredMethod("unregisterCallback", callbackClass)
            )
            Log.d(TAG, "[Hook] MediaController callback registration")
        } catch (e: Exception) {
            Log.e(TAG, "[Hook] MediaController registration failed", e)
        }

        try {
            val messageHandlerClass = Class.forName(
                "android.media.session.MediaController\$MessageHandler",
                true,
                classLoader
            )
            val handleMessage = messageHandlerClass.getDeclaredMethod(
                "handleMessage",
                Message::class.java
            )
            deoptimize(handleMessage)
            hookHandles += hook(handleMessage).intercept { chain ->
                val message = chain.getArg(0) as? Message
                if (message != null) publishMessage(chain.thisObject, message)
                chain.proceed()
            }
            Log.d(TAG, "[Hook] MediaController.MessageHandler.handleMessage")
        } catch (e: Exception) {
            Log.e(TAG, "[Hook] MediaController.MessageHandler failed", e)
        }

        // Some vendor SystemUI builds dispatch through the callback base method directly.
        try {
            hookCallbackFallback(
                callbackClass.getDeclaredMethod("onMetadataChanged", MediaMetadata::class.java)
            )
            hookCallbackFallback(
                callbackClass.getDeclaredMethod(
                    "onPlaybackStateChanged",
                    PlaybackState::class.java
                )
            )
            hookCallbackDestroyed(callbackClass.getDeclaredMethod("onSessionDestroyed"))
            Log.d(TAG, "[Hook] MediaController.Callback fallback methods")
        } catch (e: Exception) {
            Log.e(TAG, "[Hook] Callback fallback failed", e)
        }
    }

    private fun hookRegisterCallback(method: Method) {
        deoptimize(method)
        hookHandles += hook(method).intercept { chain ->
            val controller = chain.thisObject as? MediaController
            val callback = chain.getArg(0)
            if (controller != null && callback != null) {
                synchronized(callbackLock) { callbackControllers[callback] = controller }
            }
            chain.proceed()
        }
    }

    private fun hookUnregisterCallback(method: Method) {
        deoptimize(method)
        hookHandles += hook(method).intercept { chain ->
            val result = chain.proceed()
            synchronized(callbackLock) { callbackControllers.remove(chain.getArg(0)) }
            result
        }
    }

    private fun hookCallbackFallback(method: Method) {
        deoptimize(method)
        hookHandles += hook(method).intercept { chain ->
            val controller = synchronized(callbackLock) {
                callbackControllers[chain.thisObject]
            }
            if (controller != null) {
                val metadata = chain.getArg(0) as? MediaMetadata
                publish(controller, metadataOverride = metadata)
            }
            chain.proceed()
        }
    }

    private fun hookCallbackDestroyed(method: Method) {
        deoptimize(method)
        hookHandles += hook(method).intercept { chain ->
            val controller = synchronized(callbackLock) {
                callbackControllers[chain.thisObject]
            }
            if (controller != null) publish(controller, destroyed = true)
            chain.proceed()
        }
    }

    private fun publishMessage(handler: Any, message: Message) {
        val callback = findCallback(handler) ?: return
        val controller = synchronized(callbackLock) { callbackControllers[callback] } ?: return
        when (message.what) {
            MSG_UPDATE_PLAYBACK_STATE -> publish(
                controller,
                playbackStateOverride = message.obj as? PlaybackState
            )
            MSG_UPDATE_METADATA -> publish(
                controller,
                metadataOverride = message.obj as? MediaMetadata
            )
            MSG_DESTROYED -> publish(controller, destroyed = true)
        }
    }

    private fun findCallback(handler: Any): Any? {
        var current: Class<*>? = handler.javaClass
        while (current != null) {
            for (field in current.declaredFields) {
                runCatching {
                    field.isAccessible = true
                    val value = field.get(handler)
                    if (value != null && synchronized(callbackLock) {
                            callbackControllers.containsKey(value)
                        }
                    ) return value
                }
            }
            current = current.superclass
        }
        return null
    }

    private fun publish(
        controller: MediaController,
        metadataOverride: MediaMetadata? = null,
        playbackStateOverride: PlaybackState? = null,
        destroyed: Boolean = false
    ) {
        if (!isDebugAppForeground()) return
        val snapshot = runCatching {
            MediaSnapshotFactory.fromController(
                source = DebugContract.SOURCE_SYSTEM_UI,
                controller = controller,
                metadataOverride = metadataOverride,
                playbackStateOverride = playbackStateOverride,
                destroyed = destroyed
            )
        }.getOrElse {
            Log.w(TAG, "[Snapshot] build failed", it)
            return
        }
        Log.d(
            TAG,
            "[Snapshot] ${snapshot.packageName} ${snapshot.title} " +
                "state=${snapshot.playbackState} pos=${snapshot.position}"
        )
        val lyricInfo = snapshot.lyricInfo
        if (lyricInfo != null) {
            val lyricChanged = synchronized(callbackLock) {
                if (loggedLyricInfo[controller] == lyricInfo) {
                    false
                } else {
                    loggedLyricInfo[controller] = lyricInfo
                    true
                }
            }
            if (lyricChanged) logLyricInfo(lyricInfo)
        }
        sendSnapshot(snapshot)
    }

    private fun sendSnapshot(snapshot: com.mediasession.metadata.debug.model.MediaSnapshot) {
        val context = systemContext() ?: return
        runCatching {
            context.sendBroadcast(DebugContract.systemUiSnapshotIntent(snapshot))
        }.onFailure { Log.w(TAG, "[Snapshot] broadcast failed", it) }
    }

    private fun isDebugAppForeground(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - cachedForegroundAt < FOREGROUND_CACHE_MS) return cachedForeground
        synchronized(foregroundLock) {
            if (now - cachedForegroundAt < FOREGROUND_CACHE_MS) return cachedForeground
            val foreground = systemContext()
                ?.getSystemService(ActivityManager::class.java)
                ?.runningAppProcesses
                ?.any { process ->
                    process.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
                        process.pkgList?.contains(DebugContract.DEBUG_PACKAGE) == true
                } == true
            cachedForeground = foreground
            cachedForegroundAt = now
            return foreground
        }
    }

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun systemContext(): Context? {
        cachedSystemContext?.let { return it }
        synchronized(foregroundLock) {
            cachedSystemContext?.let { return it }
            val context = runCatching {
                val activityThread = Class.forName("android.app.ActivityThread")
                val application = activityThread.getDeclaredMethod("currentApplication")
                    .invoke(null) as? Application
                application?.applicationContext
            }.onFailure { Log.w(TAG, "[Context] unavailable", it) }.getOrNull()
            cachedSystemContext = context
            return context
        }
    }

    private fun logLyricInfo(raw: String) {
        val chunks = raw.chunkForLog()
        Log.d(TAG, "[LyricInfo] length=${raw.length}, chunks=${chunks.size}")
        chunks.forEachIndexed { index, chunk ->
            Log.d(TAG, "[LyricInfo ${index + 1}/${chunks.size}] $chunk")
        }
    }

    private fun String.chunkForLog(): List<String> {
        if (isEmpty()) return emptyList()
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < length) {
            var end = minOf(start + LOG_CHUNK_LENGTH, length)
            if (end < length && Character.isHighSurrogate(this[end - 1])) end--
            if (end == start) end = minOf(start + LOG_CHUNK_LENGTH, length)
            chunks += substring(start, end)
            start = end
        }
        return chunks
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean {
        param.setSavedInstanceState(currentPackageReadyParam)
        return true
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        param.oldHookHandles.forEach { it.unhook() }
        hookHandles.clear()
        synchronized(callbackLock) {
            callbackControllers.clear()
            loggedLyricInfo.clear()
        }
        cachedForegroundAt = 0L
        val packageParam = param.savedInstanceState as? PackageReadyParam
        currentPackageReadyParam = packageParam
        if (packageParam?.packageName == DebugContract.SYSTEM_UI_PACKAGE) {
            installHooks(packageParam.classLoader)
        }
    }
}
