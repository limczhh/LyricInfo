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
                            value is String && value.length > 200 -> {
                                Log.d(TAG, "${prefix}  $fieldName ($fieldType) = ${value.take(200)}... (${value.length} chars)")
                            }
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
                    value is String && value.length > 200 -> {
                        Log.d(TAG, "${prefix}  $key = ${value.take(200)}... (${value.length} chars)")
                    }
                    else -> Log.d(TAG, "${prefix}  $key = $value")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "${prefix}Bundle dump failed", e)
        }
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
