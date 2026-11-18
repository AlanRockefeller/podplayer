/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.browse

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Pull down to refresh (SPEC §8; owner, 2026-11-18). [onRefresh] asks for the refresh; the spinner shows from the
 * pull until [running] has come and gone, or briefly when the refresh never starts (no network: the offline
 * banner says so). Background refreshes don't show it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PullToRefresh(running: Boolean, onRefresh: () -> Unit, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    var pulled by remember { mutableStateOf(false) }
    val isRunning by rememberUpdatedState(running)
    LaunchedEffect(pulled) {
        if (!pulled) return@LaunchedEffect
        val started = withTimeoutOrNull(START_WAIT_MS) { snapshotFlow { isRunning }.first { it } } != null
        if (started) snapshotFlow { isRunning }.first { !it }
        pulled = false
    }
    PullToRefreshBox(
        isRefreshing = pulled,
        onRefresh = {
            pulled = true
            onRefresh()
        },
        modifier = modifier.testTag(TAG_PULL_TO_REFRESH),
        content = content,
    )
}

const val TAG_PULL_TO_REFRESH = "pull-to-refresh"

/** How long the spinner waits for a refresh to start before it gives up (it may be waiting for a network). */
private const val START_WAIT_MS = 2_000L
