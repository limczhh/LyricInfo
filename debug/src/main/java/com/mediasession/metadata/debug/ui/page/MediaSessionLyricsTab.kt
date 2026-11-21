package com.mediasession.metadata.debug.ui.page

import android.media.session.PlaybackState
import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.mediasession.metadata.debug.R
import com.mediasession.metadata.debug.model.LyricInfoDocument
import com.mediasession.metadata.debug.model.LyricLine
import com.mediasession.metadata.debug.model.MediaSessionRecord
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val LYRIC_ITEM_OFFSET = 1

@Composable
fun MediaSessionLyricsTab(
    record: MediaSessionRecord?,
    parsed: LyricInfoDocument?,
    listState: LazyListState,
    scrollBehavior: ScrollBehavior,
    topPadding: androidx.compose.ui.unit.Dp,
    bottomPadding: androidx.compose.ui.unit.Dp
) {
    var clock by remember(record?.key) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var autoScrolling by remember { mutableStateOf(false) }
    var userScrolling by remember { mutableStateOf(false) }

    LaunchedEffect(record?.key, record?.playbackState, record?.positionUpdateTime, record?.playbackSpeed) {
        clock = SystemClock.elapsedRealtime()
        val current = record
        while (isActive && current?.isAdvancing() == true) {
            clock = SystemClock.elapsedRealtime()
            withFrameNanos { }
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collectLatest { scrolling ->
                if (scrolling && !autoScrolling) userScrolling = true
                if (!scrolling) userScrolling = false
            }
    }

    val position = record?.positionAt(clock) ?: 0L
    val lines = parsed?.lines.orEmpty()
    val activeLineIndex = remember(lines, position) {
        lines.indexOfLast { it.begin <= position }
    }
    val activeWordIndex = remember(lines, activeLineIndex, position) {
        lines.getOrNull(activeLineIndex)?.words
            ?.indexOfFirst { position >= it.begin && position < it.end }
            ?: -1
    }

    LaunchedEffect(record?.key, activeLineIndex, lines.size) {
        if (activeLineIndex >= 0 && !userScrolling) {
            autoScrolling = true
            try {
                listState.animateScrollToItem(
                    index = LYRIC_ITEM_OFFSET + activeLineIndex,
                    scrollOffset = -120
                )
            } finally {
                autoScrolling = false
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        contentPadding = PaddingValues(
            top = topPadding + 12.dp,
            bottom = bottomPadding + 120.dp
        ),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        item(key = "lyrics-summary") {
            LyricsSummaryCard(record = record, parsed = parsed, position = position)
        }
        itemsIndexed(
            items = lines,
            key = { index, line -> "lyric-${line.begin}-$index" }
        ) { index, line ->
            LyricLineItem(
                line = line,
                active = index == activeLineIndex,
                activeWordIndex = if (index == activeLineIndex) activeWordIndex else -1
            )
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

@Composable
private fun LyricsSummaryCard(
    record: MediaSessionRecord?,
    parsed: LyricInfoDocument?,
    position: Long
) {
    val title = parsed?.songName?.ifBlank { record?.title.orEmpty() }
        ?.ifBlank { stringResource(R.string.unknown) }
        ?: stringResource(R.string.unknown)
    val summary = listOf(
        parsed?.artist?.ifBlank { record?.artist.orEmpty() }.orEmpty(),
        parsed?.album?.ifBlank { record?.album.orEmpty() }.orEmpty()
    ).filter { it.isNotBlank() }.joinToString(" - ")
        .ifBlank { stringResource(R.string.unknown) }
    val progress = if ((record?.duration ?: 0L) > 0L) {
        (position.toFloat() / record!!.duration.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 18.dp)
            .fillMaxWidth()
    ) {
        BasicComponent(
            title = title,
            summary = summary,
            endActions = {
                Text(
                    text = playbackStateText(record?.playbackState ?: PlaybackState.STATE_NONE),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.footnote1
                )
            },
            bottomAction = {
                Column(
                    modifier = Modifier.padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = formatDuration(position),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            style = MiuixTheme.textStyles.footnote1,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = formatDuration(record?.duration ?: 0L),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            style = MiuixTheme.textStyles.footnote1,
                            textAlign = TextAlign.End,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
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
            .padding(horizontal = 24.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (line.words.isEmpty()) {
            Text(
                text = line.text,
                style = lyricTextStyle(active),
                color = if (active) MiuixTheme.colorScheme.primary else lineColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            Text(
                text = buildWordText(line, activeWordIndex, lineColor),
                style = lyricTextStyle(active),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
        line.translation?.takeIf { it.isNotBlank() }?.let { translation ->
            Text(
                text = translation,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
            )
        }
        line.roma?.takeIf { it.isNotBlank() }?.let { roma ->
            Text(
                text = roma,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp)
            )
        }
    }
}

@Composable
private fun lyricTextStyle(active: Boolean): TextStyle = MiuixTheme.textStyles.body1.copy(
    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
)

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
    if (value <= 0L) return "00:00"
    val totalSeconds = value / 1000L
    return "%02d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
}
