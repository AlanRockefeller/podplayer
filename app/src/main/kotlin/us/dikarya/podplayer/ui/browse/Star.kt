/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.browse

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.flow.collectLatest
import us.dikarya.podplayer.R
import us.dikarya.podplayer.ui.theme.PodplayerTheme

/** The shell's snackbar host (AppShell provides it); screens show the star Undo bar there. */
val LocalSnackbars = staticCompositionLocalOf { SnackbarHostState() }

/** Stars or unstars at once (SPEC §12: no confirmation, an Undo bar instead). */
@Composable
fun StarButton(item: EpisodeItem, actions: EpisodeActions) {
    val title = item.episode.title.orEmpty()
    IconButton(onClick = { actions.toggleStar(item) }) {
        Icon(
            painterResource(if (item.starred) R.drawable.ic_star else R.drawable.ic_star_border),
            contentDescription = stringResource(if (item.starred) R.string.action_unstar_episode else R.string.action_star_episode, title),
            tint = if (item.starred) PodplayerTheme.colors.accent else PodplayerTheme.colors.textSecondary,
        )
    }
}

/**
 * Shows "Starred" or "Removed from Starred" with Undo after each change [actions] makes. A newer change replaces
 * the bar; Undo puts the old state back exactly (an unstar gets its old star time, so the list order is kept).
 */
@Composable
fun StarUndoBar(actions: EpisodeActions) {
    val snackbars = LocalSnackbars.current
    val resources = LocalResources.current
    LaunchedEffect(actions, snackbars) {
        actions.starChanges.collectLatest { change ->
            val result = snackbars.showSnackbar(
                message = resources.getString(if (change.starred) R.string.starred_done else R.string.unstarred_done),
                actionLabel = resources.getString(R.string.undo),
                withDismissAction = true,
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) actions.undoStar(change)
        }
    }
}
