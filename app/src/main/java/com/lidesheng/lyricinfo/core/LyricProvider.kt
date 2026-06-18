package com.lidesheng.lyricinfo.core

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

interface LyricProvider {
    val packageName: String
    val processNames: List<String>
        get() = listOf(packageName)
    fun onAppLoaded(module: XposedModule, param: PackageLoadedParam)
    fun onDestroy() {}
}
