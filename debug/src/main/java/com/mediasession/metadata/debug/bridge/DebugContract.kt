package com.mediasession.metadata.debug.bridge

import android.content.ComponentName
import android.content.Intent
import com.mediasession.metadata.debug.model.MediaSnapshot

/** Contract shared by the SystemUI hook, the app process and the UI. */
object DebugContract {
    const val DEBUG_PACKAGE = "com.mediasession.metadata.debug"
    const val SYSTEM_UI_PACKAGE = "com.android.systemui"

    const val SOURCE_SYSTEM_UI = "systemui"
    const val SOURCE_NOTIFICATION = "notification"

    const val ACTION_SYSTEM_UI_SNAPSHOT =
        "com.mediasession.metadata.debug.action.SYSTEM_UI_SNAPSHOT"
    const val EXTRA_SNAPSHOT = "com.mediasession.metadata.debug.extra.SNAPSHOT"
    const val EXTRA_ARTWORK = "com.mediasession.metadata.debug.extra.ARTWORK"

    fun systemUiSnapshotIntent(snapshot: MediaSnapshot): Intent = Intent(
        ACTION_SYSTEM_UI_SNAPSHOT
    ).setComponent(
        ComponentName(
            DEBUG_PACKAGE,
            "$DEBUG_PACKAGE.bridge.SystemUiSnapshotReceiver"
        )
    ).putExtra(EXTRA_SNAPSHOT, snapshot.toJson()).apply {
        snapshot.artwork?.let { putExtra(EXTRA_ARTWORK, it) }
    }
}
