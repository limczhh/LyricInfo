package com.lidesheng.lyricinfo.lite

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

class HookEntry : XposedModule() {

    companion object {
        private const val TAG = "LyricInfoLite"
    }

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        Log.i(TAG, "[Module] Loaded in process: ${param.processName}")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        Log.i(TAG, "[Module] Injecting into package: ${param.packageName}")
        try {
            val systemPropertiesClass = Class.forName("android.os.SystemProperties", true, param.classLoader)
            
            // 1. Hook SystemProperties.get(String, String)
            val getMethodWith2Args = systemPropertiesClass.getMethod("get", String::class.java, String::class.java)
            deoptimize(getMethodWith2Args)
            hook(getMethodWith2Args).intercept { chain ->
                val key = chain.args[0] as? String
                if (key == "ro.build.version.oplus.api") {
                    Log.i(TAG, "[Hook] SystemProperties.get(ro.build.version.oplus.api, default) -> return 37")
                    "37"
                } else {
                    chain.proceed()
                }
            }

            // 2. Hook SystemProperties.get(String)
            val getMethodWith1Arg = systemPropertiesClass.getMethod("get", String::class.java)
            deoptimize(getMethodWith1Arg)
            hook(getMethodWith1Arg).intercept { chain ->
                val key = chain.args[0] as? String
                if (key == "ro.build.version.oplus.api") {
                    Log.i(TAG, "[Hook] SystemProperties.get(ro.build.version.oplus.api) -> return 37")
                    "37"
                } else {
                    chain.proceed()
                }
            }
            Log.i(TAG, "[Module] SystemProperties hooks installed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "[Module] Failed to install SystemProperties hooks", e)
        }
    }
}
