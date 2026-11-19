/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.discover

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import us.dikarya.podplayer.R
import us.dikarya.podplayer.core.feed.notes.RichStyle
import us.dikarya.podplayer.ui.artwork.Artwork
import us.dikarya.podplayer.ui.browse.ArtworkBeside
import us.dikarya.podplayer.ui.browse.CenteredMessage
import us.dikarya.podplayer.ui.browse.EpisodeTitleStyle
import us.dikarya.podplayer.ui.browse.MIN_TITLE_EMS
import us.dikarya.podplayer.ui.browse.OfflineBanner
import us.dikarya.podplayer.ui.browse.durationText
import us.dikarya.podplayer.ui.browse.textRoom
import us.dikarya.podplayer.ui.notes.LinkConfirmDialog
import us.dikarya.podplayer.ui.notes.toAnnotatedString
import us.dikarya.podplayer.ui.theme.PodplayerTheme

/**
 * A search result's show before subscribing (owner, 2026-11-18). [artworkUrl] is the search result's (Apple's) art,
 * so opening a preview contacts only the feed's own host; no host is approved for it.
 */
@Composable
fun ShowPreviewScreen(viewModel: ShowPreviewViewModel, artworkUrl: String?, onOpenShow: (Long) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val online by viewModel.online.collectAsStateWithLifecycle()
    val opened by viewModel.opened.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    LaunchedEffect(opened) {
        opened?.let {
            viewModel.onOpened()
            onOpenShow(it)
        }
    }
    Column(Modifier.fillMaxSize()) {
        OfflineBanner(online)
        when (val s = state) {
            ShowPreviewState.Loading -> Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
            is ShowPreviewState.Failed -> Column(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(resources.message(s.problem), style = MaterialTheme.typography.bodyLarge, color = PodplayerTheme.colors.textSecondary)
                Button(onClick = viewModel::retry) { Text(stringResource(R.string.preview_try_again)) }
            }
            is ShowPreviewState.Ready -> Ready(s, artworkUrl, viewModel)
        }
    }
}

@Composable
private fun Ready(state: ShowPreviewState.Ready, artworkUrl: String?, viewModel: ShowPreviewViewModel) {
    val colors = PodplayerTheme.colors
    val context = LocalContext.current
    val resources = LocalResources.current
    var link by remember { mutableStateOf<RichStyle.Link?>(null) }
    LazyColumn(Modifier.fillMaxSize().testTag(TAG_SHOW_PREVIEW), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val titleStyle = MaterialTheme.typography.titleLarge
                ArtworkBeside(
                    minTextWidth = textRoom(titleStyle.fontSize, MIN_TITLE_EMS),
                    artwork = { Box(Modifier.padding(end = 16.dp, bottom = 8.dp)) { Artwork(artworkUrl, approvedHost = null, size = 96.dp) } },
                ) {
                    Column {
                        Text(state.title ?: stringResource(R.string.untitled_show), style = titleStyle)
                        state.author?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary) }
                        Text(
                            pluralStringResource(R.plurals.discover_episode_count, state.episodes.size, state.episodes.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary,
                        )
                    }
                }
                if (!state.isSecure) Text(stringResource(R.string.not_secure_feed), style = MaterialTheme.typography.bodySmall, color = colors.warning)
                state.description?.let { description ->
                    var expanded by rememberSaveable { mutableStateOf(false) }
                    val text = remember(description, colors) { description.toAnnotatedString(colors, onLink = { link = it }, onTimestamp = null) }
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary,
                        maxLines = if (expanded) Int.MAX_VALUE else DESCRIPTION_LINES,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(stringResource(if (expanded) R.string.show_less else R.string.show_more))
                    }
                }
                Button(onClick = viewModel::subscribe, enabled = !state.subscribing, modifier = Modifier.testTag(TAG_PREVIEW_SUBSCRIBE)) {
                    Text(stringResource(R.string.action_subscribe))
                }
                state.message?.let { problem ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(resources.message(problem), color = colors.error, modifier = Modifier.weight(1f))
                        TextButton(onClick = viewModel::dismissMessage) { Text(stringResource(R.string.ok)) }
                    }
                }
            }
            HorizontalDivider(color = colors.divider)
        }
        if (state.episodes.isEmpty()) item { CenteredMessage(stringResource(R.string.preview_no_episodes)) }
        itemsIndexed(state.episodes) { _, episode ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(episode.title ?: stringResource(R.string.untitled_episode), style = EpisodeTitleStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val meta = buildList {
                    episode.publishedAt?.let { add(DateUtils.formatDateTime(context, it, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)) }
                    episode.durationMs?.takeIf { it > 0 }?.let { add(durationText(resources, it)) }
                }.joinToString(" · ")
                if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            }
        }
    }
    link?.let { LinkConfirmDialog(it) { link = null } }
}

private const val DESCRIPTION_LINES = 3
const val TAG_SHOW_PREVIEW = "show-preview"
const val TAG_PREVIEW_SUBSCRIBE = "show-preview-subscribe"
