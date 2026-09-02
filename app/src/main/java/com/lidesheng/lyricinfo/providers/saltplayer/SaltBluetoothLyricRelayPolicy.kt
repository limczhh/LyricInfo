package com.lidesheng.lyricinfo.providers.saltplayer

/**
 * Resolves the real track identity when Salt Player exposes its car/Bluetooth lyric relay.
 *
 * Salt may put the current lyric line in TITLE and encode the real artist/title in ARTIST as
 * "artist - title". Newer versions can also retain the stable identity in DISPLAY_TITLE and
 * DISPLAY_SUBTITLE. These fields are used only to match a MediaSession; public lyric metadata
 * still comes from Salt's Song object.
 */
internal object SaltBluetoothLyricRelayPolicy {
    private val RELAY_SEPARATORS = arrayOf(" - ", " – ", " — ")

    internal data class ResolvedIdentity(
        val mediaId: String?,
        val title: String,
        val artist: String,
        val album: String,
        val relay: Boolean,
        val source: String
    )

    internal data class RelayIdentity(
        val title: String,
        val artist: String
    )

    fun resolveFields(
        mediaId: String?,
        title: String?,
        artist: String?,
        displayTitle: String?,
        displaySubtitle: String?,
        album: String?
    ): ResolvedIdentity? {
        val relayIdentity = parseRelayIdentity(artist)
        val stableDisplayTitle = displayTitle?.trim().orEmpty()
        val stableDisplayArtist = displaySubtitle?.trim().orEmpty()

        val resolvedTitle: String
        val resolvedArtist: String
        val relay: Boolean
        val source: String
        if (stableDisplayTitle.isNotEmpty() && stableDisplayArtist.isNotEmpty()) {
            resolvedTitle = stableDisplayTitle
            resolvedArtist = stableDisplayArtist
            relay = relayIdentity != null && !sameText(title, stableDisplayTitle)
            source = "display"
        } else if (relayIdentity != null && !sameText(title, relayIdentity.title)) {
            resolvedTitle = relayIdentity.title
            resolvedArtist = relayIdentity.artist
            relay = true
            source = "relay-artist"
        } else {
            resolvedTitle = title?.trim().orEmpty()
            resolvedArtist = artist?.trim().orEmpty()
            relay = false
            source = "standard"
        }

        if (mediaId.isNullOrBlank() && resolvedTitle.isBlank() &&
            resolvedArtist.isBlank() && album.isNullOrBlank()
        ) {
            return null
        }

        return ResolvedIdentity(
            mediaId = mediaId?.trim()?.takeIf { it.isNotEmpty() },
            title = resolvedTitle,
            artist = resolvedArtist,
            album = album?.trim().orEmpty(),
            relay = relay,
            source = source
        )
    }

    fun parseRelayIdentity(compositeArtist: String?): RelayIdentity? {
        val value = compositeArtist?.trim().orEmpty()
        var separatorIndex = -1
        var matchedSeparator: String? = null
        for (separator in RELAY_SEPARATORS) {
            val candidateIndex = value.indexOf(separator)
            if (candidateIndex > 0 &&
                (separatorIndex < 0 || candidateIndex < separatorIndex)
            ) {
                separatorIndex = candidateIndex
                matchedSeparator = separator
            }
        }
        if (separatorIndex < 0 || matchedSeparator == null) return null

        val artist = value.substring(0, separatorIndex).trim()
        val title = value.substring(separatorIndex + matchedSeparator.length).trim()
        if (artist.isEmpty() || title.isEmpty()) return null
        return RelayIdentity(title = title, artist = artist)
    }

    private fun sameText(first: String?, second: String?): Boolean =
        first?.trim()?.equals(second?.trim(), ignoreCase = true) == true
}
