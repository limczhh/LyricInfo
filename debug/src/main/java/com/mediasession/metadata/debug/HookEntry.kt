package com.mediasession.metadata.debug

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.lang.reflect.Field
import java.lang.reflect.Modifier

class HookEntry : XposedModule() {

    companion object {
        private const val TAG = "MediaSessionDebug"
        private const val LYRIC_INFO_KEY = "lyricInfo"
        private const val STRING_PREVIEW_LENGTH = 200
        private const val LOG_CHUNK_LENGTH = 800
    }

    private var currentPackageReadyParam: PackageReadyParam? = null

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        Log.d(TAG, "[Module] Loaded in ${param.processName}")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        Log.d(TAG, "[Module] ${param.packageName}")
        currentPackageReadyParam = param
        hookMediaControllerCallback(this, param.classLoader)
    }

    private fun hookMediaControllerCallback(module: XposedModule, classLoader: ClassLoader) {
        try {
            val callbackClass = Class.forName(
                "android.media.session.MediaController\$Callback", true, classLoader
            )
            val metadataClass = Class.forName(
                "android.media.MediaMetadata", true, classLoader
            )
            val onMetadataChanged = callbackClass.getDeclaredMethod("onMetadataChanged", metadataClass)

            deoptimize(onMetadataChanged)
            module.hook(onMetadataChanged).intercept { chain ->
                val metadata = chain.args[0]
                if (metadata != null) {
                    Log.d(TAG, "══════════════════════════════════════════")
                    Log.d(TAG, "[Metadata] onMetadataChanged")
                    dumpObject(metadata)
                }
                chain.proceed()
            }

            Log.d(TAG, "[Hook] ✓ MediaController.Callback.onMetadataChanged")
        } catch (e: Exception) {
            Log.e(TAG, "[Hook] ✗ MediaController.Callback", e)
        }
    }

    private fun dumpObject(obj: Any, prefix: String = "") {
        try {
            val clazz = obj.javaClass
            Log.d(TAG, "${prefix}Class: ${clazz.name}")

            // 遍历所有字段（包括父类）
            var currentClass: Class<*>? = clazz
            while (currentClass != null && currentClass != Any::class.java) {
                for (field in currentClass.declaredFields) {
                    try {
                        field.isAccessible = true
                        val value = field.get(obj)
                        val fieldName = field.name
                        val fieldType = field.type.simpleName

                        when {
                            value == null -> Log.d(TAG, "${prefix}  $fieldName ($fieldType) = null")
                            value.javaClass.name.contains("Bitmap") -> {
                                try {
                                    val w = value.javaClass.getMethod("getWidth").invoke(value)
                                    val h = value.javaClass.getMethod("getHeight").invoke(value)
                                    Log.d(TAG, "${prefix}  $fieldName ($fieldType) = Bitmap(${w}x${h})")
                                } catch (_: Exception) {
                                    Log.d(TAG, "${prefix}  $fieldName ($fieldType) = Bitmap")
                                }
                            }
                            value is String -> logString(
                                prefix = prefix,
                                name = fieldName,
                                type = fieldType,
                                value = value,
                                dumpFully = fieldName == LYRIC_INFO_KEY
                            )
                            value is android.os.Bundle -> {
                                Log.d(TAG, "${prefix}  $fieldName ($fieldType) = Bundle:")
                                dumpBundle(value, "$prefix    ")
                            }
                            else -> Log.d(TAG, "${prefix}  $fieldName ($fieldType) = $value")
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "${prefix}  ${field.name} = <access error>")
                    }
                }
                currentClass = currentClass.superclass
            }
        } catch (e: Exception) {
            Log.e(TAG, "${prefix}Dump failed", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun dumpBundle(bundle: android.os.Bundle, prefix: String = "") {
        try {
            val keys = bundle.keySet()
            Log.d(TAG, "${prefix}Keys: ${keys.size}")

            for (key in keys) {
                val value = bundle.get(key)
                when {
                    value == null -> Log.d(TAG, "${prefix}  $key = null")
                    value.javaClass.name.contains("Bitmap") -> {
                        try {
                            val w = value.javaClass.getMethod("getWidth").invoke(value)
                            val h = value.javaClass.getMethod("getHeight").invoke(value)
                            Log.d(TAG, "${prefix}  $key = Bitmap(${w}x${h})")
                        } catch (_: Exception) {
                            Log.d(TAG, "${prefix}  $key = Bitmap")
                        }
                    }
                    value is String -> logString(
                        prefix = prefix,
                        name = key,
                        type = value.javaClass.simpleName,
                        value = value,
                        dumpFully = key == LYRIC_INFO_KEY
                    )
                    else -> Log.d(TAG, "${prefix}  $key = $value")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "${prefix}Bundle dump failed", e)
        }
    }

    private fun logString(
        prefix: String,
        name: String,
        type: String,
        value: String,
        dumpFully: Boolean
    ) {
        if (!dumpFully || value.length <= STRING_PREVIEW_LENGTH) {
            if (value.length > STRING_PREVIEW_LENGTH) {
                Log.d(TAG, "${prefix}  $name = ${value.take(STRING_PREVIEW_LENGTH)}... (${value.length} chars)")
            } else {
                Log.d(TAG, "${prefix}  $name ($type) = $value")
            }
            return
        }

        val chunks = value.chunkForLog()
        Log.d(
            TAG,
            "${prefix}  $name ($type) = <full ${value.length} chars, ${chunks.size} chunks>"
        )
        chunks.forEachIndexed { index, chunk ->
            Log.d(TAG, "${prefix}    $name[${index + 1}/${chunks.size}] = $chunk")
        }
        Log.d(TAG, "${prefix}  $name ($type) = </full>")
    }

    private fun String.chunkForLog(): List<String> {
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < length) {
            var end = minOf(start + LOG_CHUNK_LENGTH, length)
            if (end < length && Character.isHighSurrogate(this[end - 1])) {
                end--
            }
            if (end == start) {
                end = minOf(start + LOG_CHUNK_LENGTH, length)
            }
            chunks += substring(start, end)
            start = end
        }
        return chunks
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean {
        Log.d(TAG, "[HotReload] Preparing...")
        param.setSavedInstanceState(currentPackageReadyParam)
        return true
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        val packageParam = param.savedInstanceState as? PackageReadyParam
        currentPackageReadyParam = packageParam

        param.oldHookHandles.forEach { it.unhook() }

        if (packageParam != null) {
            Log.d(TAG, "[HotReload] ✓ Reloaded, reinstalling hooks")
            hookMediaControllerCallback(this, packageParam.classLoader)
        } else {
            Log.d(TAG, "[HotReload] ✓ Reloaded (no package context)")
        }
    }
}
