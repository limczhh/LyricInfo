package com.lidesheng.lyricinfo.providers.saltplayer

import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import java.lang.ref.WeakReference

/** Tracks Salt's sessions and selects only the active session for the current track. */
internal class SaltMediaSessionRegistry {
    private data class Entry(
        val session: WeakReference<MediaSession>,
        val tag: String,
        var track: SaltTrackIdentity? = null,
        var metadata: MediaMetadata? = null,
        var playbackState: Int = PlaybackState.STATE_NONE,
        var active: Boolean = false,
        var released: Boolean = false
    )

    private val entries = mutableListOf<Entry>()
    private val moduleWrite = ThreadLocal<Boolean>()

    @Synchronized
    fun onConstructed(session: MediaSession, tag: String?) {
        prune()
        if (entry(session) == null) {
            entries += Entry(WeakReference(session), tag.orEmpty())
        }
    }

    @Synchronized
    fun onHostMetadata(
        session: MediaSession,
        track: SaltTrackIdentity?,
        metadata: MediaMetadata?
    ) {
        ensure(session).apply {
            this.track = track
            this.metadata = metadata
        }
    }

    @Synchronized
    fun onPlaybackState(session: MediaSession, state: Int): Int {
        val entry = ensure(session)
        val previous = entry.playbackState
        entry.playbackState = state
        return previous
    }

    @Synchronized
    fun onActive(session: MediaSession, active: Boolean) {
        ensure(session).active = active
    }

    @Synchronized
    fun onReleased(session: MediaSession) {
        entry(session)?.released = true
    }

    @Synchronized
    fun selectUnique(track: SaltTrackIdentity): MediaSession? {
        prune()
        return entries.mapNotNull { candidate ->
            val session = candidate.session.get() ?: return@mapNotNull null
            if (!candidate.released && candidate.active &&
                isPlaybackStateValid(candidate.playbackState) &&
                SaltTrackIdentityPolicy.isSameTrack(candidate.track, track)
            ) {
                session
            } else {
                null
            }
        }.singleOrNull()
    }

    @Synchronized
    fun hostMetadata(session: MediaSession): MediaMetadata? = entry(session)?.metadata

    fun isModuleWrite(): Boolean = moduleWrite.get() == true

    fun <T> withModuleWrite(block: () -> T): T {
        val wasModuleWrite = moduleWrite.get() == true
        moduleWrite.set(true)
        return try {
            block()
        } finally {
            if (wasModuleWrite) moduleWrite.set(true) else moduleWrite.remove()
        }
    }

    @Synchronized
    fun clear() {
        entries.clear()
        moduleWrite.remove()
    }

    @Synchronized
    private fun ensure(session: MediaSession): Entry = entry(session) ?: Entry(
        session = WeakReference(session),
        tag = ""
    ).also(entries::add)

    @Synchronized
    private fun entry(session: MediaSession): Entry? =
        entries.firstOrNull { it.session.get() === session }

    @Synchronized
    private fun prune() {
        entries.removeAll { it.session.get() == null || it.released }
    }

    companion object {
        fun isPlaybackStateValid(state: Int): Boolean = state == PlaybackState.STATE_PLAYING ||
            state == PlaybackState.STATE_PAUSED ||
            state == PlaybackState.STATE_BUFFERING ||
            state == PlaybackState.STATE_CONNECTING ||
            state == PlaybackState.STATE_FAST_FORWARDING ||
            state == PlaybackState.STATE_REWINDING ||
            state == PlaybackState.STATE_SKIPPING_TO_NEXT ||
            state == PlaybackState.STATE_SKIPPING_TO_PREVIOUS ||
            state == PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM
    }
}
