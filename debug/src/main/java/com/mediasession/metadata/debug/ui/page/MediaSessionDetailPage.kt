package com.mediasession.metadata.debug.ui.page

import android.media.session.PlaybackState
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.mediasession.metadata.debug.R
import com.mediasession.metadata.debug.business.LyricInfoParser
import com.mediasession.metadata.debug.business.MediaSessionRepository
import com.mediasession.metadata.debug.model.LyricInfoDocument
import com.mediasession.metadata.debug.model.LyricLine
import com.mediasession.metadata.debug.model.MediaSessionRecord
import kotlinx.coroutines.isActive
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.glass.GlassTopAppBarDefaults
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.withFrameNanos

private const val LYRIC_START_INDEX = 5

@Composable
fun MediaSessionDetailPage(sessionKey: String, onBack: () -> Unit) {
    var record by remember(sessionKey) {
        mutableStateOf(MediaSessionRepository.find(sessionKey))
    }
    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()

    DisposableEffect(sessionKey) {
        val observer = object : MediaSessionRepository.Observer {
            override fun onSessionsChanged() {
                record = MediaSessionRepository.find(sessionKey)
            }
        }
        val remove = MediaSessionRepository.addObserver(observer)
        record = MediaSessionRepository.find(sessionKey)
        onDispose { remove() }
    }

    val parsed = remember(
        record?.lyricInfo,
        record?.title,
        record?.artist,
        record?.album
    ) {
        record?.lyricInfo?.let { lyricInfo ->
            LyricInfoParser.parse(
                json = lyricInfo,
                fallbackTitle = record?.title.orEmpty(),
                fallbackArtist = record?.artist.orEmpty(),
                fallbackAlbum = record?.album.orEmpty()
            )
        }
    }

    var clock by remember(sessionKey) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(record?.key, record?.playbackState, record?.positionUpdateTime) {
        clock = SystemClock.elapsedRealtime()
        val current = record
        while (isActive && current?.isAdvancing() == true) {
            clock = SystemClock.elapsedRealtime()
            withFrameNanos { }
        }
    }

    val position = record?.positionAt(clock) ?: 0L
    val activeLineIndex = remember(parsed?.lines, position) {
        parsed?.lines?.indexOfLast { it.begin <= position } ?: -1
    }
    val activeWordIndex = remember(parsed?.lines, activeLineIndex, position) {
        val line = parsed?.lines?.getOrNull(activeLineIndex)
        line?.words?.indexOfFirst { position >= it.begin && position < it.end } ?: -1
    }

    LaunchedEffect(sessionKey, activeLineIndex, parsed?.lines?.size) {
        if (activeLineIndex >= 0) {
            listState.animateScrollToItem(
                index = LYRIC_START_INDEX + activeLineIndex,
                scrollOffset = -120
            )
        }
    }

    val detailTitle = record?.title?.ifBlank { stringResource(R.string.media_session_detail) }
        ?: stringResource(R.string.media_session_detail)

    Scaffold(
        topBar = {
            GlassTopAppBar(
                title = detailTitle,
                largeTitle = detailTitle,
                expandable = true,
                scrollBehavior = scrollBehavior,
                bandBrush = GlassTopAppBarDefaults.bandBrush(MiuixTheme.colorScheme.surface),
                navigationIcon = {
                    GlassIconButton(onClick = onBack) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        },
        contentWindowInsets = WindowInsets(0)
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MiuixTheme.colorScheme.surface)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding(),
                    bottom = innerPadding.calculateBottomPadding() + 16.dp
                )
            ) {
            item(key = "lyric-info-title") {
                SmallTitle(text = stringResource(R.string.lyric_info_section))
            }
            item(key = "lyric-info-card") {
                LyricInfoCard(record = record, parsed = parsed)
            }
            item(key = "media-session-title") {
                SmallTitle(text = stringResource(R.string.media_session_section))
            }
            item(key = "media-session-card") {
                MediaFieldsCard(record = record)
            }
            item(key = "lyrics-title") {
                SmallTitle(text = stringResource(R.string.lyrics_section))
            }

            if (parsed?.lines.isNullOrEmpty()) {
                item(key = "lyrics-empty") {
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp)
                            .fillMaxWidth()
                    ) {
                        Text(
                            text = when {
                                record?.lyricInfo.isNullOrBlank() ->
                                    stringResource(R.string.lyric_info_absent)
                                else -> stringResource(R.string.lyric_info_empty)
                            },
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            } else {
                itemsIndexed(
                    items = parsed.lines,
                    key = { index, line -> "lyric-${line.begin}-$index" }
                ) { index, line ->
                    LyricLineItem(
                        line = line,
                        active = index == activeLineIndex,
                        activeWordIndex = if (index == activeLineIndex) activeWordIndex else -1
                    )
                }
            }

            if (record?.ended == true) {
                item(key = "session-ended") {
                    Text(
                        text = stringResource(R.string.session_ended),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
            }
            }
        }
    }
}

@Composable
private fun LyricInfoCard(record: MediaSessionRecord?, parsed: LyricInfoDocument?) {
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp)
            .fillMaxWidth()
    ) {
        if (record?.lyricInfo.isNullOrBlank()) {
            Text(
                text = stringResource(R.string.lyric_info_absent),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(16.dp)
            )
        } else if (parsed == null) {
            Text(
                text = stringResource(R.string.lyric_info_empty),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(16.dp)
            )
        } else {
            Column(modifier = Modifier.padding(16.dp)) {
                FieldRow(stringResource(R.string.field_song_name), parsed.songName)
                FieldRow(stringResource(R.string.field_artist), parsed.artist)
                FieldRow(stringResource(R.string.field_album), parsed.album)
                FieldRow(stringResource(R.string.field_song_id), parsed.songId)
                FieldRow(stringResource(R.string.field_song_mid), parsed.songMid)
                FieldRow(stringResource(R.string.field_format), parsed.format)
                FieldRow(
                    stringResource(R.string.field_raw_lyric),
                    if (parsed.usedRawLyric) stringResource(R.string.yes)
                    else stringResource(R.string.no)
                )
                FieldRow(
                    stringResource(R.string.field_translation),
                    if (parsed.hasTranslation) stringResource(R.string.yes)
                    else stringResource(R.string.no)
                )
                FieldRow(
                    stringResource(R.string.field_roma),
                    if (parsed.hasRoma) stringResource(R.string.yes)
                    else stringResource(R.string.no)
                )
            }
        }
    }
}

@Composable
private fun MediaFieldsCard(record: MediaSessionRecord?) {
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp)
            .fillMaxWidth()
    ) {
        if (record == null) {
            Text(
                text = stringResource(R.string.no_sessions),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(16.dp)
            )
        } else {
            val position = record.positionAt(SystemClock.elapsedRealtime())
            Column(modifier = Modifier.padding(16.dp)) {
                FieldRow(stringResource(R.string.field_package), record.packageName)
                FieldRow(stringResource(R.string.field_session_tag), record.sessionTag)
                FieldRow(stringResource(R.string.field_media_id), record.mediaId)
                FieldRow(
                    stringResource(R.string.field_playback_state),
                    playbackStateText(record.playbackState)
                )
                FieldRow(
                    stringResource(R.string.field_progress),
                    "${formatDuration(position)} / ${formatDuration(record.duration)}"
                )
                FieldRow(stringResource(R.string.field_duration), formatDuration(record.duration))
                FieldRow(
                    stringResource(R.string.field_speed),
                    "%.2fx".format(record.playbackSpeed)
                )
            }
        }
    }
}

@Composable
private fun FieldRow(label: String, value: String) {
    if (value.isBlank()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.body2
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = value,
            color = MiuixTheme.colorScheme.onSurface,
            style = MiuixTheme.textStyles.body1
        )
    }
}

@Composable
private fun LyricLineItem(line: LyricLine, active: Boolean, activeWordIndex: Int) {
    val lineColor = if (active) {
        MiuixTheme.colorScheme.onSurface
    } else {
        MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 10.dp)
    ) {
        if (line.words.isEmpty()) {
            Text(
                text = line.text,
                style = MiuixTheme.textStyles.body1.copy(
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
                ),
                color = if (active) MiuixTheme.colorScheme.primary else lineColor
            )
        } else {
            Text(
                text = buildWordText(line, activeWordIndex, lineColor),
                style = MiuixTheme.textStyles.body1.copy(
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
                )
            )
        }
        line.translation?.takeIf { it.isNotBlank() }?.let { translation ->
            Text(
                text = translation,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        line.roma?.takeIf { it.isNotBlank() }?.let { roma ->
            Text(
                text = roma,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
private fun buildWordText(
    line: LyricLine,
    activeWordIndex: Int,
    defaultColor: Color
): AnnotatedString = buildAnnotatedString {
    line.words.forEachIndexed { index, word ->
        val color = if (index == activeWordIndex) {
            MiuixTheme.colorScheme.primary
        } else {
            defaultColor
        }
        withStyle(SpanStyle(color = color)) { append(word.text) }
    }
}

@Composable
private fun playbackStateText(state: Int): String = when (state) {
    PlaybackState.STATE_PLAYING -> stringResource(R.string.playing)
    PlaybackState.STATE_PAUSED -> stringResource(R.string.paused)
    PlaybackState.STATE_BUFFERING -> stringResource(R.string.buffering)
    PlaybackState.STATE_CONNECTING -> stringResource(R.string.connecting)
    PlaybackState.STATE_NONE,
    PlaybackState.STATE_STOPPED,
    PlaybackState.STATE_ERROR -> stringResource(R.string.not_playing)
    else -> stringResource(R.string.unknown)
}

private fun formatDuration(value: Long): String {
    if (value <= 0L) return "--:--"
    val totalSeconds = value / 1000L
    return "%d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
}
