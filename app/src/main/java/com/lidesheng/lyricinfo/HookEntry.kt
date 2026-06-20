package com.lidesheng.lyricinfo

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import com.lidesheng.lyricinfo.core.LyricProvider
import com.lidesheng.lyricinfo.providers.netease.NeteaseProvider
import com.lidesheng.lyricinfo.providers.qishui.QishuiProvider
import com.lidesheng.lyricinfo.providers.qqmusic.QQMusicProvider
import com.lidesheng.lyricinfo.providers.saltplayer.SaltPlayerProvider

class HookEntry : XposedModule() {

    companion object {
        private const val TAG = "LyricInfo"

        private val providers: List<LyricProvider> = listOf(
            NeteaseProvider(),
            QQMusicProvider(),
            QishuiProvider(),
            SaltPlayerProvider(),
        )
    }

    private lateinit var currentProcessName: String
    private var currentPackageParam: PackageLoadedParam? = null

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        currentProcessName = param.processName
        Log.i(TAG, "[Module] Loaded in ${param.processName}")
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (!param.isFirstPackage) return

        val provider = providers.find { it.packageName == param.packageName }
        if (provider == null) return

        if (provider.processNames.none { currentProcessName.startsWith(it) }) return

        Log.i(TAG, "[Module] ${provider::class.simpleName} -> $currentProcessName")
        currentPackageParam = param
        provider.onAppLoaded(this, param)
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean {
        val state = HashMap<String, Any>()
        state["processName"] = currentProcessName
        currentPackageParam?.let { state["packageParam"] = it }
        param.setSavedInstanceState(state)
        return true
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        val state = param.savedInstanceState as? Map<*, *>
        currentProcessName = param.processName

        val packageParam = state?.get("packageParam") as? PackageLoadedParam
        currentPackageParam = packageParam

        val oldHooks = param.oldHookHandles

        if (packageParam != null) {
            val provider = providers.find { it.packageName == packageParam.packageName }
            if (provider != null) {
                if (provider.processNames.none { currentProcessName.startsWith(it) }) return

                Log.i(TAG, "[Module] Hot reloaded: ${provider::class.simpleName}")
                provider.replaceHooks(this, packageParam, oldHooks)
                return
            }
        }

        oldHooks.forEach { it.unhook() }
    }
}
