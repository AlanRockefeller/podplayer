/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.queue

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import us.dikarya.podplayer.R
import us.dikarya.podplayer.ui.artwork.Artwork
import us.dikarya.podplayer.ui.browse.CenteredMessage
import us.dikarya.podplayer.ui.browse.EpisodeItem
import us.dikarya.podplayer.ui.browse.LibraryClosed
import us.dikarya.podplayer.ui.browse.Loading
import us.dikarya.podplayer.ui.browse.PlayButton
import us.dikarya.podplayer.ui.browse.EpisodeTitleStyle
import us.dikarya.podplayer.ui.browse.ShowNameLine
import us.dikarya.podplayer.ui.browse.episodeMeta
import us.dikarya.podplayer.ui.theme.PodplayerTheme

fun rowTag(episodeId: Long) = "queue-row-$episodeId"

fun handleTag(episodeId: Long) = "queue-handle-$episodeId"

fun menuTag(episodeId: Long) = "queue-menu-$episodeId"

/**
 * The queue (SPEC §12): a drag handle on every row, and Move up / Move down / Play next / Remove both in each
 * row's menu and as accessibility actions, so the order can be changed without dragging.
 */
@Composable
fun QueueScreen(viewModel: QueueViewModel, onOpenEpisode: (Long) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    when {
        state.loading -> Loading()
        state.closed -> LibraryClosed()
        state.items.isEmpty() -> CenteredMessage(stringResource(R.string.queue_empty))
        else -> QueueList(state.items, viewModel, onOpenEpisode)
    }
}

@Composable
private fun QueueList(items: List<EpisodeItem>, viewModel: QueueViewModel, onOpenEpisode: (Long) -> Unit) {
    val listState = rememberLazyListState()
    val byId = items.associateBy { it.id }
    val databaseOrder = items.map { it.id }
    val drag = remember(listState) { QueueDrag(listState) }
    val latestDatabaseOrder by rememberUpdatedState(databaseOrder)
    val latestViewModel by rememberUpdatedState(viewModel)
    LaunchedEffect(databaseOrder) { drag.databaseChanged() }
    val shown = drag.shown(databaseOrder)
    val latestShown by rememberUpdatedState(shown)

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        items(shown, key = { it }) { id ->
            val item = byId.getValue(id)
            val dragged = id == drag.draggingId
            val rowModifier = Modifier
                .zIndex(if (dragged) 1f else 0f)
                .graphicsLayer { translationY = if (dragged) drag.offset else 0f }
                .let { if (dragged) it else it.animateItem() }
            QueueRow(
                item = item,
                index = shown.indexOf(id),
                count = shown.size,
                dragged = dragged,
                viewModel = viewModel,
                onOpen = { onOpenEpisode(id) },
                modifier = rowModifier,
                handle = Modifier.pointerInput(id) {
                    detectVerticalDragGestures(
                        onDragStart = { drag.start(id, latestShown) },
                        onVerticalDrag = { change, amount ->
                            change.consume()
                            drag.by(amount)
                        },
                        onDragEnd = { drag.end(latestDatabaseOrder, latestViewModel::move) },
                        onDragCancel = { drag.end(latestDatabaseOrder, latestViewModel::move) },
                    )
                },
            )
        }
    }
}

/**
 * A drag in progress. While a finger drags a row, the list shows [order]; the row swaps with a neighbour once it
 * is dragged over half of that neighbour, and the database changes once, on release. This is gesture state only:
 * the queue itself is always the database's.
 */
private class QueueDrag(private val listState: LazyListState) {
    var draggingId by mutableStateOf<Long?>(null)
        private set
    var offset by mutableFloatStateOf(0f)
        private set
    private var order by mutableStateOf<List<Long>?>(null)

    /** What the list shows: the drag's order (rows added or removed meanwhile accounted for), else the database's. */
    fun shown(databaseOrder: List<Long>): List<Long> {
        val local = order ?: return databaseOrder
        val kept = local.filter { it in databaseOrder }
        return kept + databaseOrder.filterNot { it in kept }
    }

    fun databaseChanged() {
        if (draggingId == null) order = null
    }

    fun start(id: Long, shown: List<Long>) {
        order = shown
        draggingId = id
        offset = 0f
    }

    fun by(amount: Float) {
        val id = draggingId ?: return
        val current = order ?: return
        offset += amount
        val index = current.indexOf(id)
        if (index < 0) return
        val visible = listState.layoutInfo.visibleItemsInfo
        val neighbourIndex = if (offset > 0) index + 1 else index - 1
        val neighbourId = current.getOrNull(neighbourIndex) ?: return
        val neighbour = visible.firstOrNull { it.key == neighbourId } ?: return
        if (kotlin.math.abs(offset) <= neighbour.size / 2f) return
        order = current.toMutableList().apply {
            removeAt(index)
            add(neighbourIndex, id)
        }
        // The row's slot moved by the neighbour's height, so the finger's offset from it shrinks by as much.
        offset += if (offset > 0) -neighbour.size else neighbour.size
    }

    fun end(databaseOrder: List<Long>, move: (Long, Int) -> Unit) {
        val id = draggingId ?: return
        val target = order?.indexOf(id) ?: -1
        draggingId = null
        offset = 0f
        if (target >= 0 && target != databaseOrder.indexOf(id)) move(id, target) else order = null
    }
}

@Composable
private fun QueueRow(
    item: EpisodeItem,
    index: Int,
    count: Int,
    dragged: Boolean,
    viewModel: QueueViewModel,
    onOpen: () -> Unit,
    modifier: Modifier,
    handle: Modifier,
) {
    val colors = PodplayerTheme.colors
    val title = item.episode.title ?: stringResource(R.string.untitled_episode)
    val moveUp = stringResource(R.string.queue_move_up)
    val moveDown = stringResource(R.string.queue_move_down)
    val playNext = stringResource(R.string.queue_play_next)
    val remove = stringResource(R.string.queue_remove)
    val id = item.id
    val actions = buildList {
        if (index > 0) add(CustomAccessibilityAction(moveUp) { viewModel.moveUp(id); true })
        if (index < count - 1) add(CustomAccessibilityAction(moveDown) { viewModel.moveDown(id); true })
        if (!item.loaded) add(CustomAccessibilityAction(playNext) { viewModel.playNext(id); true })
        add(CustomAccessibilityAction(remove) { viewModel.remove(id); true })
    }
    Column(
        modifier
            .fillMaxWidth()
            .background(if (dragged) colors.surfaceElevated else colors.background)
            .testTag(rowTag(id))
            .semantics { customActions = actions }
            .clickable(role = Role.Button, onClick = onOpen),
    ) {
        Row(Modifier.padding(end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            val dragDescription = stringResource(R.string.queue_drag, title)
            Box(
                Modifier.size(48.dp).testTag(handleTag(id)).semantics { contentDescription = dragDescription }.then(handle),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_drag_handle), contentDescription = null, tint = colors.textSecondary)
            }
            Artwork(item.imageUrl, item.approvedHost, 48.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                if (item.loaded) {
                    Text(stringResource(R.string.queue_now_playing), style = MaterialTheme.typography.labelSmall, color = colors.accent)
                }
                ShowNameLine(item.showTitle)
                Text(title, style = EpisodeTitleStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(episodeMeta(item, withShow = false), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            PlayButton(item, viewModel.actions)
            RowMenu(item, title, index, count, viewModel)
        }
        HorizontalDivider(color = colors.divider)
    }
}

@Composable
private fun RowMenu(item: EpisodeItem, title: String, index: Int, count: Int, viewModel: QueueViewModel) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag(menuTag(item.id))) {
            Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.queue_more, title))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            fun pick(action: () -> Unit) {
                open = false
                action()
            }
            if (index > 0) DropdownMenuItem(text = { Text(stringResource(R.string.queue_move_up)) }, onClick = { pick { viewModel.moveUp(item.id) } })
            if (index < count - 1) DropdownMenuItem(text = { Text(stringResource(R.string.queue_move_down)) }, onClick = { pick { viewModel.moveDown(item.id) } })
            if (!item.loaded) DropdownMenuItem(text = { Text(stringResource(R.string.queue_play_next)) }, onClick = { pick { viewModel.playNext(item.id) } })
            DropdownMenuItem(text = { Text(stringResource(R.string.queue_remove)) }, onClick = { pick { viewModel.remove(item.id) } })
        }
    }
}
