package com.lidesheng.lyricinfo.core

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

interface LyricProvider {
    val packageName: String
    val processNames: List<String>
        get() = listOf(packageName)
    fun onAppLoaded(module: XposedModule, param: PackageLoadedParam)
    fun onDestroy() {}

    /**
     * Replace hooks during hot reload. Called in new code.
     * @param module The new module instance
     * @param param Package loaded param
     * @param oldHooks Old hook handles to replace
     * @return New hook handles created during replacement
     */
    fun replaceHooks(
        module: XposedModule,
        param: PackageLoadedParam,
        oldHooks: List<XposedInterface.HookHandle>
    ): List<XposedInterface.HookHandle> {
        // Default: unhook all old hooks
        oldHooks.forEach { it.unhook() }
        // Re-install hooks
        onAppLoaded(module, param)
        return emptyList()
    }
}
