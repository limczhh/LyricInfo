package com.lidesheng.lyricinfo

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import com.lidesheng.lyricinfo.core.LyricProvider
import com.lidesheng.lyricinfo.providers.netease.NeteaseProvider
import com.lidesheng.lyricinfo.providers.qqmusic.QQMusicProvider
import com.lidesheng.lyricinfo.providers.saltplayer.SaltPlayerProvider

class HookEntry : XposedModule() {

    companion object {
        private const val TAG = "LyricInfo"

        private val providers: List<LyricProvider> = listOf(
            NeteaseProvider(),
            QQMusicProvider(),
            SaltPlayerProvider(),
        )
    }

    private lateinit var currentProcessName: String

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
        provider.onAppLoaded(this, param)
    }
}
