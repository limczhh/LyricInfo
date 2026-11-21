package com.mediasession.metadata.debug.ui.page

import android.graphics.BitmapFactory
import android.media.session.PlaybackState
import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mediasession.metadata.debug.R
import com.mediasession.metadata.debug.business.DebugActivationSnapshot
import com.mediasession.metadata.debug.business.DebugActivationState
import com.mediasession.metadata.debug.business.MediaSessionRepository
import com.mediasession.metadata.debug.model.MediaSessionRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Album
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Locale

@Composable
fun MediaSessionsPage(onOpen: (String) -> Unit) {
    var sessions by remember { mutableStateOf(MediaSessionRepository.sessions()) }
    var activation by remember { mutableStateOf(DebugActivationState.current()) }
    var clock by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()
    val pageTitle = stringResource(R.string.page_title_media_session_reader)
    val contentScrolled = listState.canScrollBackward ||
        scrollBehavior.state.overscrollOffset < -1f

    DisposableEffect(Unit) {
        val observer = object : MediaSessionRepository.Observer {
            override fun onSessionsChanged() {
                sessions = MediaSessionRepository.sessions()
            }
        }
        val remove = MediaSessionRepository.addObserver(observer)
        sessions = MediaSessionRepository.sessions()
        onDispose { remove() }
    }

    DisposableEffect(Unit) {
        val remove = DebugActivationState.addObserver { activation = it }
        onDispose { remove() }
    }

    LaunchedEffect(sessions.map { listOf(it.key, it.playbackState, it.positionUpdateTime) }) {
        clock = SystemClock.elapsedRealtime()
        while (isActive && sessions.any { it.isAdvancing() }) {
            clock = SystemClock.elapsedRealtime()
            withFrameNanos { }
        }
    }

    Scaffold(
        topBar = {
            MediaSessionTopAppBar(
                title = pageTitle,
                isContentScrolled = contentScrolled,
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
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
                item(key = "work-mode-card") {
                    WorkModeCard(activation = activation)
                }

                sessions.groupBy { it.packageName }.forEach { (packageName, packageSessions) ->
                    item(key = "package-$packageName") {
                        SmallTitle(
                            text = packageName.ifBlank { stringResource(R.string.unknown) }
                        )
                    }
                    items(
                        items = packageSessions,
                        key = { it.key }
                    ) { session ->
                        MediaSessionCard(
                            session = session,
                            now = clock,
                            onClick = { onOpen(session.key) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkModeCard(activation: DebugActivationSnapshot) {
    val active = activation.isWorking
    val source = when {
        activation.lspActive ->
            stringResource(R.string.work_mode_lsp)
        activation.notificationListenerEnabled ->
            stringResource(R.string.work_mode_notification_listener)
        else -> stringResource(R.string.work_mode_no_permission)
    }
    val title = if (active) {
        stringResource(R.string.work_mode_working)
    } else {
        stringResource(R.string.work_mode_not_active)
    }
    val colors = if (active) {
        val dark = isSystemInDarkTheme()
        CardDefaults.defaultColors(
            color = if (dark) ACTIVE_WORK_DARK else ACTIVE_WORK_LIGHT,
            contentColor = if (dark) ACTIVE_CONTENT_DARK else ACTIVE_CONTENT_LIGHT
        )
    } else {
        CardDefaults.defaultColors(
            color = MiuixTheme.colorScheme.errorContainer,
            contentColor = MiuixTheme.colorScheme.onErrorContainer
        )
    }

    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp)
            .fillMaxWidth(),
        colors = colors
    ) {
        BasicComponent(
            title = title,
            titleColor = BasicComponentDefaults.titleColor(color = colors.contentColor),
            summary = source,
            summaryColor = BasicComponentDefaults.summaryColor(
                color = colors.contentColor.copy(alpha = 0.72f)
            )
        )
    }
}

@Composable
private fun MediaSessionCard(session: MediaSessionRecord, now: Long, onClick: () -> Unit) {
    val position = session.positionAt(now)
    val progress = if (session.duration > 0L) {
        (position.toFloat() / session.duration.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val summary = listOf(session.artist, session.album)
        .filter { it.isNotBlank() }
        .joinToString(" - ")
        .ifBlank { stringResource(R.string.unknown) }

    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp)
            .fillMaxWidth(),
        showIndication = true,
        onClick = onClick
    ) {
        BasicComponent(
            startAction = {
                ArtworkThumbnail(
                    artwork = session.artwork,
                    modifier = Modifier.padding(end = 8.dp)
                )
            },
            endActions = {
                if (session.hasLyricInfo()) {
                    Text(
                        text = stringResource(R.string.lyric_info_available),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MiuixTheme.colorScheme.primary,
                        style = MiuixTheme.textStyles.footnote2
                    )
                }
            },
            bottomAction = {
                ProgressBlock(
                    modifier = Modifier.padding(top = 12.dp),
                    position = position,
                    duration = session.duration,
                    progress = progress,
                    playbackState = session.playbackState
                )
            },
            content = {
                Text(
                    text = session.title.ifBlank { stringResource(R.string.unknown) },
                    fontSize = MiuixTheme.textStyles.headline1.fontSize,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MiuixTheme.colorScheme.onSurface
                )
                Text(
                    text = summary,
                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        )
    }
}

@Composable
private fun ArtworkThumbnail(artwork: ByteArray?, modifier: Modifier = Modifier) {
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, artwork) {
        value = withContext(Dispatchers.Default) {
            artwork?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
        }
    }
    val shape = RoundedCornerShape(8.dp)
    val imageModifier = modifier
        .size(40.dp)
        .clip(shape)
    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = imageModifier
        )
    } else {
        Box(
            modifier = imageModifier.background(
                MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.15f)
            ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = MiuixIcons.Album,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun ProgressBlock(
    modifier: Modifier = Modifier,
    position: Long,
    duration: Long,
    progress: Float,
    playbackState: Int
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth()
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            ProgressTimeText(formatPreciseTime(position), Modifier.weight(1f))
            ProgressTimeText(
                text = playbackStateText(playbackState),
                modifier = Modifier.weight(1f),
                color = MiuixTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            ProgressTimeText(
                text = formatPreciseTime(duration),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End
            )
        }
    }
}

@Composable
private fun ProgressTimeText(
    text: String,
    modifier: Modifier,
    color: androidx.compose.ui.graphics.Color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    textAlign: TextAlign = TextAlign.Start
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        style = MiuixTheme.textStyles.footnote1,
        textAlign = textAlign,
        maxLines = 1
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

private fun formatPreciseTime(value: Long): String {
    if (value <= 0L) return "00:00"
    val minutes = value / 60_000L
    val seconds = (value / 1_000L) % 60L
    return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
}

private val ACTIVE_WORK_LIGHT = androidx.compose.ui.graphics.Color(0xFFE3F3E6)
private val ACTIVE_WORK_DARK = androidx.compose.ui.graphics.Color(0xFF234B31)
private val ACTIVE_CONTENT_LIGHT = androidx.compose.ui.graphics.Color(0xFF1B5E20)
private val ACTIVE_CONTENT_DARK = androidx.compose.ui.graphics.Color(0xFFD5F3DC)
