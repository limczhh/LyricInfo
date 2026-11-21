package com.mediasession.metadata.debug.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.mediasession.metadata.debug.business.DebugForegroundState
import com.mediasession.metadata.debug.business.MediaSessionRepository
import com.mediasession.metadata.debug.model.MediaSnapshot

/** Receives the explicit, source-only snapshots published by the SystemUI hook. */
class SystemUiSnapshotReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DebugContract.ACTION_SYSTEM_UI_SNAPSHOT) return
        if (!DebugForegroundState.isForeground) return
        val raw = intent.getStringExtra(DebugContract.EXTRA_SNAPSHOT) ?: return
        val snapshot = MediaSnapshot.fromJson(raw) ?: run {
            Log.w(TAG, "Invalid SystemUI snapshot")
            return
        }
        MediaSessionRepository.update(
            snapshot.copy(artwork = intent.getByteArrayExtra(DebugContract.EXTRA_ARTWORK))
        )
    }

    private companion object {
        const val TAG = "SystemUiSnapshotReceiver"
    }
}
