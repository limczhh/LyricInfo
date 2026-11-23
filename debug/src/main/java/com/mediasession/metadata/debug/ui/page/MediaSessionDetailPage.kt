package com.mediasession.metadata.debug.ui.page

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mediasession.metadata.debug.R
import com.mediasession.metadata.debug.business.DebugPreferences
import com.mediasession.metadata.debug.business.LyricInfoParser
import com.mediasession.metadata.debug.business.MediaSessionRepository
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassTabRow
import top.yukonga.miuix.kmp.glass.GlassTabRowDefaults
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.ChevronBackward

@Composable
fun MediaSessionDetailPage(sessionKey: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val persistedTabIndex = remember(context) {
        DebugPreferences.lastSelectedDetailTab(context)
    }
    val persistedFullLyricsExpanded = remember(context, sessionKey) {
        DebugPreferences.fullLyricsExpanded(context, sessionKey)
    }
    var record by remember(sessionKey) {
        mutableStateOf(MediaSessionRepository.find(sessionKey))
    }
    var selectedTabIndex by rememberSaveable(sessionKey) {
        mutableIntStateOf(persistedTabIndex)
    }
    var fullLyricsExpanded by rememberSaveable(sessionKey) {
        mutableStateOf(persistedFullLyricsExpanded)
    }
    val metadataListState = rememberLazyListState()
    val lyricsListState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val detailTitle = stringResource(R.string.page_title_media_session_metadata)
    val copySuccessMessage = stringResource(R.string.copy_success)
    val hasLyricInfo = record?.hasLyricInfo() == true
    val activeListState = if (hasLyricInfo && selectedTabIndex == 1) {
        lyricsListState
    } else {
        metadataListState
    }
    val contentScrolled = activeListState.canScrollBackward ||
        scrollBehavior.state.overscrollOffset < -1f
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
            ) ?: LyricInfoParser.empty(
                fallbackTitle = record?.title.orEmpty(),
                fallbackArtist = record?.artist.orEmpty(),
                fallbackAlbum = record?.album.orEmpty()
            )
        }
    }

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

    Scaffold(
        snackbarHost = {
            SnackbarHost(state = snackbarHostState)
        },
        topBar = {
            MediaSessionTopAppBar(
                title = detailTitle,
                isContentScrolled = contentScrolled,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    GlassIconButton(onClick = onBack) {
                        Icon(
                            imageVector = MiuixIcons.Glass.ChevronBackward,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                bottomContent = {
                    if (hasLyricInfo) {
                        GlassTabRow(
                            tabs = listOf(
                                stringResource(R.string.tab_metadata),
                                stringResource(R.string.tab_lyrics)
                            ),
                            selectedIndex = selectedTabIndex,
                            onSelect = { index ->
                                selectedTabIndex = index
                                DebugPreferences.setLastSelectedDetailTab(context, index)
                            },
                            backdrop = null,
                            height = GlassTabRowDefaults.NeutralHeight,
                            colors = GlassTabRowDefaults.neutralColors(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                                .padding(bottom = 12.dp)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                when {
                    !hasLyricInfo || selectedTabIndex == 0 -> {
                        MediaSessionMetadataTab(
                            record = record,
                            parsed = parsed,
                            listState = metadataListState,
                            scrollBehavior = scrollBehavior,
                            topPadding = innerPadding.calculateTopPadding(),
                            bottomPadding = innerPadding.calculateBottomPadding() + 16.dp,
                            onCopy = { key, value ->
                                copyToClipboard(context, key, value)
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        message = copySuccessMessage
                                    )
                                }
                            }
                        )
                    }

                    else -> {
                        MediaSessionLyricsTab(
                            record = record,
                            parsed = parsed,
                            listState = lyricsListState,
                            scrollBehavior = scrollBehavior,
                            topPadding = innerPadding.calculateTopPadding(),
                            bottomPadding = innerPadding.calculateBottomPadding() + 16.dp,
                            fullLyricsExpanded = fullLyricsExpanded,
                            onFullLyricsExpandedChange = { expanded ->
                                fullLyricsExpanded = expanded
                                DebugPreferences.setFullLyricsExpanded(
                                    context = context,
                                    sessionKey = sessionKey,
                                    expanded = expanded
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

private fun copyToClipboard(context: Context, label: String, value: String) {
    context.getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText(label, value))
}
