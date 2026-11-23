package com.mediasession.metadata.debug.ui.page

import android.media.session.PlaybackState
import android.os.SystemClock
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.sp
import com.mediasession.metadata.debug.R
import com.mediasession.metadata.debug.model.LyricInfoDocument
import com.mediasession.metadata.debug.model.LyricLine
import com.mediasession.metadata.debug.model.LyricWord
import com.mediasession.metadata.debug.model.MediaSessionRecord
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.CollapseList
import top.yukonga.miuix.kmp.icon.glass.ExpandList
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import kotlin.math.roundToInt
import java.util.Locale

private val CardHorizontalPadding = 12.dp
private val CardBottomPadding = 12.dp
private val LyricLineHorizontalPadding = 16.dp
private val FullLyricsListHeight = 330.dp
private val FullLyricsContentTopPadding = 100.dp
private val FullLyricsContentBottomPadding = 40.dp
private const val PlaybackLineViewportPosition = 0.38f
private const val PlaybackScrollMinDurationMillis = 280
private const val PlaybackScrollMaxDurationMillis = 520
private const val PlaybackScrollDistanceDurationFactor = 0.35f

@Composable
fun MediaSessionLyricsTab(
    record: MediaSessionRecord?,
    parsed: LyricInfoDocument?,
    listState: LazyListState,
    scrollBehavior: ScrollBehavior,
    topPadding: androidx.compose.ui.unit.Dp,
    bottomPadding: androidx.compose.ui.unit.Dp,
    fullLyricsExpanded: Boolean,
    onFullLyricsExpandedChange: (Boolean) -> Unit
) {
    var clock by remember(record?.key) { mutableLongStateOf(SystemClock.elapsedRealtime()) }

    LaunchedEffect(record?.key, record?.playbackState, record?.positionUpdateTime, record?.playbackSpeed) {
        clock = SystemClock.elapsedRealtime()
        val current = record
        while (isActive && current?.isAdvancing() == true) {
            clock = SystemClock.elapsedRealtime()
            withFrameNanos { }
        }
    }

    val position = record?.positionAt(clock) ?: 0L
    val lines = parsed?.lines.orEmpty()
    val activeLineIndex = remember(lines, position) {
        lines.indexOfLast { it.begin <= position }
    }
    val activeLine = lines.getOrNull(activeLineIndex)
    val activeWordIndex = remember(lines, activeLineIndex, position) {
        activeLine?.words
            ?.indexOfFirst { position >= it.begin && position < it.end }
            ?: -1
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        contentPadding = PaddingValues(
            top = topPadding + 12.dp,
            bottom = bottomPadding + 24.dp
        ),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        item(key = "lyrics-summary") {
            LyricsSummaryCard(
                record = record,
                parsed = parsed,
                lineCount = lines.size,
                position = position
            )
        }
        item(key = "current-lyric-detail") {
            CurrentLyricDetailCard(
                line = activeLine,
                lineIndex = activeLineIndex,
                activeWordIndex = activeWordIndex
            )
        }
        item(key = "full-lyrics") {
            FullLyricsCard(
                lines = lines,
                activeLineIndex = activeLineIndex,
                expanded = fullLyricsExpanded,
                onExpandedChange = onFullLyricsExpandedChange
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
    lineCount: Int,
    position: Long
) {
    val songTitle = parsed.lyricInfoValue("songName")
        .ifBlank { stringResource(R.string.unknown) }
    val artistAlbum = listOf(
        parsed.lyricInfoValue("artist"),
        parsed.lyricInfoValue("album")
    ).filter { it.isNotBlank() }
        .joinToString(" - ")
        .ifBlank { stringResource(R.string.unknown) }
    val progress = if ((record?.duration ?: 0L) > 0L) {
        (position.toFloat() / record!!.duration.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    Card(
        modifier = Modifier
            .padding(horizontal = CardHorizontalPadding)
            .padding(bottom = CardBottomPadding)
            .fillMaxWidth(),
        colors = CardDefaults.defaultColors()
    ) {
        BasicComponent(
            title = songTitle,
            summary = artistAlbum,
            endActions = {
                Text(
                    text = pluralStringResource(
                        R.plurals.lyrics_line_count,
                        lineCount,
                        lineCount
                    ),
                    color = MiuixTheme.colorScheme.onSurfaceVariantActions,
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
                            text = playbackStateText(record?.playbackState ?: PlaybackState.STATE_NONE),
                            color = MiuixTheme.colorScheme.onSurface,
                            style = MiuixTheme.textStyles.footnote1,
                            textAlign = TextAlign.Center,
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CurrentLyricDetailCard(
    line: LyricLine?,
    lineIndex: Int,
    activeWordIndex: Int
) {
    Card(
        modifier = Modifier
            .padding(horizontal = CardHorizontalPadding)
            .padding(bottom = CardBottomPadding)
            .fillMaxWidth(),
        colors = CardDefaults.defaultColors()
    ) {
        BasicComponent(
            title = line?.text ?: stringResource(R.string.current_lyric_no_line),
            summary = if (line != null) {
                "${formatLyricTimestamp(line.begin)}  —  ${formatLyricTimestamp(line.end)}"
            } else {
                null
            },
            endActions = lineIndex.takeIf { it >= 0 }?.let { index ->
                {
                    Text(
                        text = stringResource(R.string.current_lyric_line, index + 1),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantActions
                    )
                }
            }
        )

        val hasWordTiming = !line?.words.isNullOrEmpty()
        val hasSupplementalLyrics = line?.translation?.isNotBlank() == true ||
            line?.roma?.isNotBlank() == true
        if (line != null && (hasWordTiming || hasSupplementalLyrics)) {
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            BasicComponent {
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (hasWordTiming) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            line.words.forEachIndexed { index, word ->
                                CurrentLyricWord(
                                    word = word,
                                    active = index == activeWordIndex
                                )
                            }
                        }
                    }

                    line.translation?.takeIf { it.isNotBlank() }?.let { translation ->
                        LyricSupplementalLine(
                            text = translation,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(top = if (hasWordTiming) 12.dp else 0.dp)
                        )
                    }
                    line.roma?.takeIf { it.isNotBlank() }?.let { roma ->
                        LyricSupplementalLine(
                            text = roma,
                            color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CurrentLyricWord(word: LyricWord, active: Boolean) {
    val borderColor = if (active) {
        MiuixTheme.colorScheme.primary
    } else {
        MiuixTheme.colorScheme.outline.copy(alpha = 0.7f)
    }
    val durationBackground = if (active) {
        MiuixTheme.colorScheme.primary.copy(alpha = 0.14f)
    } else {
        MiuixTheme.colorScheme.surfaceContainer
    }

    Box(
        modifier = Modifier
            .background(durationBackground, RoundedCornerShape(5.dp))
            .border(1.dp, borderColor, RoundedCornerShape(5.dp))
            .padding(horizontal = 5.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = formatWordDuration(word.end - word.begin),
                style = MiuixTheme.textStyles.footnote2.copy(fontSize = 10.sp),
                color = if (active) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantActions
                }
            )
            Spacer(modifier = Modifier.height(1.dp))
            Text(
                text = word.text,
                style = MiuixTheme.textStyles.footnote1,
                color = if (active) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.onSurface
                }
            )
        }
    }
}

@Composable
private fun FullLyricsCard(
    lines: List<LyricLine>,
    activeLineIndex: Int,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit
) {
    val lyricsListState = rememberLazyListState()
    var autoScrolling by remember { mutableStateOf(false) }
    var userScrolling by remember { mutableStateOf(false) }
    var wasExpanded by remember { mutableStateOf(false) }
    var previousActiveLineIndex by remember { mutableStateOf<Int?>(null) }
    val isolatedNestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset = Offset(x = 0f, y = available.y)

            override suspend fun onPostFling(
                consumed: Velocity,
                available: Velocity
            ): Velocity = Velocity(x = 0f, y = available.y)
        }
    }
    val baseModifier = Modifier
        .padding(horizontal = CardHorizontalPadding)
        .padding(bottom = CardBottomPadding)
        .fillMaxWidth()
    LaunchedEffect(lyricsListState) {
        snapshotFlow { lyricsListState.isScrollInProgress }
            .distinctUntilChanged()
            .collectLatest { scrolling ->
                if (scrolling && !autoScrolling) userScrolling = true
                if (!scrolling) userScrolling = false
            }
    }

    LaunchedEffect(expanded, lines, activeLineIndex) {
        val shouldCenter = expanded && (
            !wasExpanded ||
                (previousActiveLineIndex != null && previousActiveLineIndex != activeLineIndex)
            )
        wasExpanded = expanded
        previousActiveLineIndex = activeLineIndex
        if (
            !shouldCenter ||
            activeLineIndex < 0 ||
            userScrolling
        ) return@LaunchedEffect

        autoScrolling = true
        try {
            lyricsListState.animateItemToPlaybackPosition(activeLineIndex)
        } finally {
            autoScrolling = false
        }
    }

    Card(
        modifier = baseModifier,
        colors = CardDefaults.defaultColors()
    ) {
        BasicComponent(
            title = stringResource(R.string.full_lyrics_title),
            onClick = { onExpandedChange(!expanded) },
            onClickLabel = stringResource(
                if (expanded) R.string.full_lyrics_collapse else R.string.full_lyrics_expand
            ),
            role = Role.Button,
            endActions = {
                Icon(
                    imageVector = if (expanded) {
                        MiuixIcons.Glass.CollapseList
                    } else {
                        MiuixIcons.Glass.ExpandList
                    },
                    contentDescription = stringResource(
                        if (expanded) R.string.full_lyrics_collapse else R.string.full_lyrics_expand
                    )
                )
            }
        )

        if (expanded) {
            HorizontalDivider()
            LazyColumn(
                state = lyricsListState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(FullLyricsListHeight)
                    .nestedScroll(isolatedNestedScrollConnection),
                contentPadding = PaddingValues(
                    top = FullLyricsContentTopPadding,
                    bottom = FullLyricsContentBottomPadding
                ),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                itemsIndexed(
                    items = lines,
                    key = { index, line -> "full-lyric-${line.begin}-$index" }
                ) { index, line ->
                    LyricLineItem(
                        line = line,
                        active = index == activeLineIndex,
                        adjacentToActive = activeLineIndex >= 0 &&
                            kotlin.math.abs(index - activeLineIndex) == 1
                    )
                }
            }
        }
    }
}

@Composable
private fun LyricLineItem(
    line: LyricLine,
    active: Boolean,
    adjacentToActive: Boolean
) {
    val mainStyle = if (active) {
        MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.SemiBold)
    } else {
        MiuixTheme.textStyles.footnote1
    }
    val mainColor = if (active) {
        MiuixTheme.colorScheme.primary
    } else {
        MiuixTheme.colorScheme.onSurface
    }
    val timingColor = if (active) {
        MiuixTheme.colorScheme.primary.copy(alpha = 0.78f)
    } else {
        MiuixTheme.colorScheme.onSurfaceVariantActions
    }
    val timingStyle = if (active) {
        MiuixTheme.textStyles.footnote2
    } else {
        MiuixTheme.textStyles.footnote2.copy(fontSize = 8.sp)
    }
    val lineVerticalPadding = if (active || adjacentToActive) 12.dp else 4.dp
    val inlineSpacing = 4.dp
    val supplementalSpacing = if (active) 8.dp else 6.dp

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = LyricLineHorizontalPadding,
                vertical = lineVerticalPadding
            ),
        horizontalAlignment = Alignment.Start
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = formatLyricTimestamp(line.begin),
                style = timingStyle,
                color = timingColor
            )
            Text(
                text = "  —  ",
                style = timingStyle,
                color = timingColor
            )
            Text(
                text = formatLyricTimestamp(line.end),
                style = timingStyle,
                color = timingColor
            )
        }

        Spacer(modifier = Modifier.height(inlineSpacing))
        Text(
            text = line.text,
            style = mainStyle,
            color = mainColor,
            modifier = Modifier.fillMaxWidth()
        )

        line.translation?.takeIf { it.isNotBlank() }?.let { translation ->
            LyricSupplementalLine(
                text = translation,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = supplementalSpacing)
            )
        }
        line.roma?.takeIf { it.isNotBlank() }?.let { roma ->
            LyricSupplementalLine(
                text = roma,
                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

private suspend fun LazyListState.animateItemToPlaybackPosition(index: Int) {
    if (layoutInfo.visibleItemsInfo.none { it.index == index }) {
        animateScrollToItem(index)
        withFrameNanos { }
    }
    val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val viewportHeight = layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset
    val targetPosition = layoutInfo.viewportStartOffset +
        viewportHeight * PlaybackLineViewportPosition
    val itemCenter = item.offset + item.size / 2f
    val delta = itemCenter - targetPosition
    if (delta != 0f) {
        animateScrollBy(
            value = delta,
            animationSpec = tween(
                durationMillis = playbackScrollDuration(delta),
                easing = FastOutSlowInEasing
            )
        )
    }
}

private fun playbackScrollDuration(delta: Float): Int =
    (PlaybackScrollMinDurationMillis +
        abs(delta) * PlaybackScrollDistanceDurationFactor)
        .roundToInt()
        .coerceIn(
            PlaybackScrollMinDurationMillis,
            PlaybackScrollMaxDurationMillis
        )

@Composable
private fun LyricSupplementalLine(
    text: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.body2,
        color = color,
        modifier = modifier.fillMaxWidth()
    )
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
    return "%02d:%02d".format(Locale.ROOT, totalSeconds / 60L, totalSeconds % 60L)
}

private fun formatLyricTimestamp(value: Long): String {
    val safeValue = value.coerceAtLeast(0L)
    val minutes = safeValue / 60_000L
    val seconds = (safeValue / 1_000L) % 60L
    val milliseconds = safeValue % 1_000L
    return "%02d:%02d.%03d".format(Locale.ROOT, minutes, seconds, milliseconds)
}

private fun formatWordDuration(value: Long): String {
    val duration = value.coerceAtLeast(0L)
    return if (duration < 1_000L) {
        "${duration}ms"
    } else {
        "%.2fs".format(Locale.ROOT, duration / 1_000f)
    }
}

private fun LyricInfoDocument?.lyricInfoValue(key: String): String =
    this?.fields
        ?.firstOrNull { it.key == key }
        ?.value
        ?.trim()
        .orEmpty()
