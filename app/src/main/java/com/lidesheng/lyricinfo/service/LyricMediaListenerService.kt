package com.lidesheng.lyricinfo.service

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.util.Log

/**
 * Empty notification listener used as a capability token so the app can call
 * [android.media.session.MediaSessionManager.getActiveSessions].
 *
 * User must enable this service under system Notification access settings.
 */
class LyricMediaListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "Notification listener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.i(TAG, "Notification listener disconnected")
    }

    companion object {
        private const val TAG = "LyricInfoUI"

        fun component(context: Context): ComponentName =
            ComponentName(context, LyricMediaListenerService::class.java)

        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            val cn = component(context).flattenToString()
            val alt = component(context).flattenToShortString()
            return flat.split(':').any { it.equals(cn, true) || it.equals(alt, true) }
        }
    }
}
