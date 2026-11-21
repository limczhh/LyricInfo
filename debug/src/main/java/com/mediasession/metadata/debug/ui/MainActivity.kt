package com.mediasession.metadata.debug.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.mediasession.metadata.debug.business.DebugActivationState
import com.mediasession.metadata.debug.business.DebugForegroundState

class MainActivity : ComponentActivity() {
    override fun onStart() {
        super.onStart()
        DebugForegroundState.setForeground(true)
        DebugActivationState.refreshNotificationListener(this)
    }

    override fun onStop() {
        DebugForegroundState.setForeground(false)
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { DebugApp() }
    }
}
