package com.lidesheng.lyricinfo.providers.saltplayer

internal data class SaltTrackIdentity(
    val id: String,
    val title: String,
    val artist: String,
    val album: String
)

internal object SaltTrackIdentityPolicy {
    fun isSameTrack(first: SaltTrackIdentity?, second: SaltTrackIdentity?): Boolean {
        if (first == null || second == null) return false

        val titleMatches = first.title.isBlank() || second.title.isBlank() ||
            sameText(first.title, second.title)
        val artistMatches = first.artist.isBlank() || second.artist.isBlank() ||
            sameText(first.artist, second.artist)
        if (!titleMatches || !artistMatches) return false

        return first.id == second.id ||
            (first.title.isNotBlank() && second.title.isNotBlank())
    }

    private fun sameText(first: String, second: String): Boolean =
        first.replace(Regex("\\s+"), " ").trim()
            .equals(second.replace(Regex("\\s+"), " ").trim(), ignoreCase = true)
}
