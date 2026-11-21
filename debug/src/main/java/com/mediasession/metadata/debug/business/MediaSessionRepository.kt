package com.mediasession.metadata.debug.business

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.mediasession.metadata.debug.model.MediaSessionRecord
import com.mediasession.metadata.debug.model.MediaSnapshot
import java.util.LinkedHashMap
import java.util.concurrent.CopyOnWriteArraySet

/** Owns source observations and publishes one source-independent UI model. */
object MediaSessionRepository {
    private const val TAG = "MediaSessionRepository"
    private const val MAX_SNAPSHOTS = 24

    private val lock = Any()
    private val observations = LinkedHashMap<String, MediaSnapshot>()
    private val observers = CopyOnWriteArraySet<Observer>()
    private val mainHandler = Handler(Looper.getMainLooper())

    interface Observer {
        fun onSessionsChanged()
    }

    fun addObserver(observer: Observer): () -> Unit {
        observers += observer
        return { observers -= observer }
    }

    fun update(snapshot: MediaSnapshot) {
        synchronized(lock) {
            observations[snapshot.sourceKey()] = snapshot
            while (observations.size > MAX_SNAPSHOTS) {
                observations.remove(observations.entries.first().key)
            }
        }
        notifyObservers()
    }

    fun removeSourceSession(source: String, sessionId: String) {
        synchronized(lock) {
            observations.remove("$source::$sessionId")
        }
        notifyObservers()
    }

    fun clearSource(source: String) {
        synchronized(lock) {
            observations.entries.removeIf { it.value.source == source }
        }
        notifyObservers()
    }

    fun sessions(): List<MediaSessionRecord> {
        return synchronized(lock) {
            observations.values
                .groupBy { it.logicalKey() }
                .map { (key, values) -> merge(key, values) }
                .filterNot { it.ended }
                .sortedWith(
                    compareByDescending<MediaSessionRecord> { it.hasLyricInfo() }
                        .thenByDescending { it.updatedAt }
                )
        }
    }

    fun find(key: String): MediaSessionRecord? {
        return synchronized(lock) {
            observations.values
                .groupBy { it.logicalKey() }
                .entries
                .firstOrNull { it.key == key }
                ?.let { merge(it.key, it.value) }
        }
    }

    private fun merge(key: String, values: List<MediaSnapshot>): MediaSessionRecord {
        val ordered = values.sortedByDescending { it.updatedAt }
        val latest = ordered.first()
        val active = ordered.filterNot { it.destroyed }
        val current = active.firstOrNull() ?: latest

        fun pick(value: (MediaSnapshot) -> String): String =
            value(current).takeIf { it.isNotBlank() }
                ?: ordered.firstOrNull { value(it).isNotBlank() }?.let(value)
                ?: ""

        val lyric = ordered.firstOrNull { it.hasLyricInfo() }?.lyricInfo
        return MediaSessionRecord(
            key = key,
            packageName = pick { it.packageName },
            sessionTag = pick { it.sessionTag },
            mediaId = pick { it.mediaId },
            title = pick { it.title },
            artist = pick { it.artist },
            album = pick { it.album },
            metadataFields = ordered.firstOrNull { it.metadataFields.isNotEmpty() }
                ?.metadataFields
                .orEmpty(),
            artwork = ordered.firstOrNull { it.artwork != null }?.artwork,
            lyricInfo = lyric,
            playbackState = current.playbackState,
            position = current.position,
            positionUpdateTime = current.positionUpdateTime,
            playbackSpeed = current.playbackSpeed,
            duration = current.duration,
            updatedAt = latest.updatedAt,
            ended = active.isEmpty()
        )
    }

    private fun notifyObservers() {
        val notify = {
            observers.forEach { observer ->
                runCatching { observer.onSessionsChanged() }
                    .onFailure { Log.w(TAG, "Observer failed", it) }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) notify() else mainHandler.post(notify)
    }
}
