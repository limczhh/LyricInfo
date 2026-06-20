package com.mediasession.metadata.debug

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this)
        tv.text = buildString {
            appendLine("LyricInfo Debug Module")
            appendLine()
            appendLine("Hooks SystemUI MediaController.Callback")
            appendLine("Dumps all MediaSession metadata changes")
            appendLine()
            appendLine("Filter: adb logcat -s LyricInfoDebug")
        }
        tv.setPadding(32, 32, 32, 32)
        tv.textSize = 16f
        setContentView(tv)
    }
}
