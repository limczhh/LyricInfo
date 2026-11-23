package com.mediasession.metadata.debug.business

import android.content.Context
import androidx.core.content.edit

/** Small, durable UI preferences for the debug activity. */
object DebugPreferences {
    private const val PreferencesName = "debug_preferences"
    private const val LastSelectedDetailTabKey = "last_selected_detail_tab"
    private const val MetadataTabIndex = 0
    private const val LyricsTabIndex = 1

    fun lastSelectedDetailTab(context: Context): Int =
        context.applicationContext
            .getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
            .getInt(LastSelectedDetailTabKey, MetadataTabIndex)
            .coerceIn(MetadataTabIndex, LyricsTabIndex)

    fun setLastSelectedDetailTab(context: Context, tabIndex: Int) {
        context.applicationContext
            .getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
            .edit {
                putInt(
                    LastSelectedDetailTabKey,
                    tabIndex.coerceIn(MetadataTabIndex, LyricsTabIndex)
                )
            }
    }
}
