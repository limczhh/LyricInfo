package com.mediasession.metadata.debug

import android.app.Application
import com.mediasession.metadata.debug.business.DebugActivationState
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/** Registers the module UI process with the libxposed service. */
class DebugApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                DebugActivationState.setLspActive(true)
            }

            override fun onServiceDied(service: XposedService) {
                DebugActivationState.setLspActive(false)
            }
        })
    }
}
