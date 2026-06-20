package com.lidesheng.lyricinfo.lite

import android.annotation.SuppressLint
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

class HookEntry : XposedModule() {

    companion object {
        private const val TAG = "LyricInfoLite"
    }

    private var currentPackageReadyParam: PackageReadyParam? = null
    private val activeHooks = mutableListOf<XposedInterface.HookHandle>()

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        Log.i(TAG, "[Module] Loaded in ${param.processName}")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        Log.i(TAG, "[Module] ${param.packageName}")
        currentPackageReadyParam = param
        installSystemPropertiesHook(param)
    }

    @SuppressLint("PrivateApi")
    private fun installSystemPropertiesHook(param: PackageReadyParam) {
        try {
            val systemPropertiesClass = Class.forName("android.os.SystemProperties", true, param.classLoader)

            val getMethodWith2Args = systemPropertiesClass.getMethod("get", String::class.java, String::class.java)
            deoptimize(getMethodWith2Args)
            activeHooks.add(hook(getMethodWith2Args).intercept { chain ->
                val key = chain.args[0] as? String
                if (key == "ro.build.version.oplus.api") "37" else chain.proceed()
            })

            val getMethodWith1Arg = systemPropertiesClass.getMethod("get", String::class.java)
            deoptimize(getMethodWith1Arg)
            activeHooks.add(hook(getMethodWith1Arg).intercept { chain ->
                val key = chain.args[0] as? String
                if (key == "ro.build.version.oplus.api") "37" else chain.proceed()
            })

            Log.i(TAG, "[Hook] ✓ SystemProperties")
        } catch (e: Exception) {
            Log.e(TAG, "[Hook] ✗ SystemProperties", e)
        }
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean {
        param.setSavedInstanceState(currentPackageReadyParam)
        return true
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        val packageParam = param.savedInstanceState as? PackageReadyParam
        currentPackageReadyParam = packageParam

        param.oldHookHandles.forEach { it.unhook() }
        activeHooks.clear()

        if (packageParam != null) {
            Log.i(TAG, "[Module] Hot reloaded")
            installSystemPropertiesHook(packageParam)
        }
    }
}
