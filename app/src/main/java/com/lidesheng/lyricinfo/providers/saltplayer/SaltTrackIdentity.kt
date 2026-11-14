package com.lidesheng.lyricinfo.providers.saltplayer

internal data class SaltTrackIdentity(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val hasStableId: Boolean = true
)

internal object SaltTrackIdentityPolicy {
    fun isSameTrack(first: SaltTrackIdentity?, second: SaltTrackIdentity?): Boolean {
        if (first == null || second == null) return false

        // Two real media IDs are authoritative. Never fall back to title/artist
        // when both IDs are present, otherwise same-titled songs can cross-bind.
        if (first.hasStableId && second.hasStableId) {
            return first.id == second.id
        }
        if (first.id == second.id && first.id.isNotBlank()) return true

        if (first.title.isBlank() || second.title.isBlank() ||
            !sameText(first.title, second.title)
        ) return false

        return first.artist.isBlank() || second.artist.isBlank() ||
            sameText(first.artist, second.artist)
    }

    private fun sameText(first: String, second: String): Boolean =
        first.replace(Regex("\\s+"), " ").trim()
            .equals(second.replace(Regex("\\s+"), " ").trim(), ignoreCase = true)
}
