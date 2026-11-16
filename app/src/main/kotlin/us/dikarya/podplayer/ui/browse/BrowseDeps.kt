/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.browse

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import us.dikarya.podplayer.core.data.logs.AppLog
import us.dikarya.podplayer.core.data.repo.Repositories
import us.dikarya.podplayer.core.data.settings.AppSettings
import us.dikarya.podplayer.playback.PlaybackControls
import us.dikarya.podplayer.ui.discover.AppleSearch
import us.dikarya.podplayer.ui.discover.FeedPreviewer
import us.dikarya.podplayer.ui.discover.importlist.Pacer

/** Downloads as the screens use them (`:core:jobs` Downloads in the app, a fake in tests). */
interface DownloadActions {
    /** Owner-started; it waits for Wi-Fi when that setting is on (SPEC §9). */
    suspend fun download(episodeId: Long)

    /** Stops it; the bytes so far stay, so downloading again resumes. */
    suspend fun cancel(episodeId: Long)

    suspend fun delete(episodeId: Long)

    /** Stops the show's downloads, deletes its files, then the subscription. */
    suspend fun unsubscribe(feedId: Long)
}

/**
 * What the browsing screens' view models need, built once in `AppGraph` (manual DI, CLAUDE.md). Playback is only
 * observed and commanded through [playback]; nothing here holds playback state.
 */
class BrowseDeps(
    val repositories: suspend () -> Repositories?,
    val settings: AppSettings,
    val playback: PlaybackControls,
    val downloads: DownloadActions,
    /** A refresh of one feed, or of all for null, when a network is there (WorkManager). */
    val refresh: (feedId: Long?) -> Unit,
    val online: StateFlow<Boolean>,
    val search: AppleSearch,
    val previewer: FeedPreviewer,
    val subscriptions: Subscriptions,
    /** The redacted app log. */
    val log: AppLog,
    /** Spaces a list import's Apple searches (one pacer per import; tests pass one that doesn't wait). */
    val importPacer: () -> Pacer = { Pacer() },
    /** Text shared into the app (ACTION_SEND), waiting for the list importer to take it. */
    val sharedText: MutableStateFlow<String?> = MutableStateFlow(null),
) {
    /** [block] over the open library, or just [closed] when the library couldn't be opened (DataLayer). */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun <T> withRepositories(closed: T, block: (Repositories) -> Flow<T>): Flow<T> =
        flow { emit(repositories()) }.flatMapLatest { repos -> if (repos == null) flowOf(closed) else block(repos) }
}
