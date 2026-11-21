package com.mediasession.metadata.debug.ui.page

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mediasession.metadata.debug.R
import com.mediasession.metadata.debug.model.LyricInfoDocument
import com.mediasession.metadata.debug.model.LyricInfoField
import com.mediasession.metadata.debug.model.LyricInfoFieldPresence
import com.mediasession.metadata.debug.model.MediaMetadataField
import com.mediasession.metadata.debug.model.MediaSessionRecord
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun MediaSessionMetadataTab(
    record: MediaSessionRecord?,
    parsed: LyricInfoDocument?,
    listState: LazyListState,
    scrollBehavior: ScrollBehavior,
    topPadding: androidx.compose.ui.unit.Dp,
    bottomPadding: androidx.compose.ui.unit.Dp,
    onCopy: (key: String, value: String) -> Unit
) {
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        contentPadding = PaddingValues(
            top = topPadding,
            bottom = bottomPadding
        )
    ) {
        if (record?.hasLyricInfo() == true && parsed != null) {
            item(key = "lyricinfo-title") {
                SmallTitle(text = stringResource(R.string.lyricinfo_section))
            }
            item(key = "lyricinfo-card") {
                LyricInfoFieldsCard(fields = parsed.fields, onCopy = onCopy)
            }
        }

        if (!record?.metadataFields.isNullOrEmpty()) {
            item(key = "metadata-title") {
                SmallTitle(text = stringResource(R.string.metadata_section))
            }
            item(key = "metadata-card") {
                MediaMetadataFieldsCard(
                    fields = record.metadataFields,
                    onCopy = onCopy
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LyricInfoFieldsCard(
    fields: List<LyricInfoField>,
    onCopy: (key: String, value: String) -> Unit
) {
    val visibleFields = fields.filterNot {
        it.key == "songMid" && it.presence == LyricInfoFieldPresence.ABSENT
    }
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp)
            .fillMaxWidth(),
        colors = CardDefaults.defaultColors()
    ) {
        visibleFields.forEachIndexed { index, field ->
            if (index > 0) {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
            BasicComponent(
                modifier = Modifier.combinedClickable(
                    enabled = field.value?.isNotBlank() == true,
                    onClick = {},
                    onLongClick = {
                        field.value?.takeIf { it.isNotBlank() }?.let { value ->
                            onCopy(field.key, value)
                        }
                    }
                ),
                title = field.key,
                endActions = {
                    Text(
                        text = field.lyricInfoSummary(),
                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                        color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            )
        }
    }
}

@Composable
private fun LyricInfoField.lyricInfoSummary(): String = when (presence) {
    LyricInfoFieldPresence.ABSENT -> stringResource(R.string.no)
    LyricInfoFieldPresence.EMPTY -> stringResource(R.string.null_value)
    LyricInfoFieldPresence.VALUE -> if (isLyricContent) {
        stringResource(R.string.yes)
    } else {
        value.orEmpty()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaMetadataFieldsCard(
    fields: List<MediaMetadataField>,
    onCopy: (key: String, value: String) -> Unit
) {
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp)
            .fillMaxWidth(),
        colors = CardDefaults.defaultColors()
    ) {
        fields.forEachIndexed { index, field ->
            if (index > 0) {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
            val value = field.value?.takeIf { it.isNotBlank() }
            val displayKey = field.displayKey()
            BasicComponent(
                modifier = Modifier.combinedClickable(
                    enabled = value != null,
                    onClick = {},
                    onLongClick = { value?.let { onCopy(displayKey, it) } }
                ),
                content = {
                    Text(
                        text = displayKey,
                        fontSize = MiuixTheme.textStyles.headline1.fontSize,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onBackground
                    )
                    Text(
                        text = value ?: stringResource(R.string.null_value),
                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            )
        }
    }
}

private fun MediaMetadataField.displayKey(): String =
    key.removePrefix("android.media.metadata.")
