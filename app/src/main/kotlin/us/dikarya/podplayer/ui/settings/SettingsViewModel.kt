/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import us.dikarya.podplayer.core.data.settings.AppSettings
import us.dikarya.podplayer.core.model.DownloadState
import us.dikarya.podplayer.core.model.PlaybackSettings

data class SettingsUiState(
    val loading: Boolean = true,
    val playback: PlaybackSettings = PlaybackSettings(),
    val wifiOnly: Boolean = true,
    val capBytes: Long = AppSettings.DEFAULT_STORAGE_CAP_BYTES,
    /** Bytes on disk for downloads, finished or partial (what the cap counts, SPEC §9). */
    val usedBytes: Long = 0,
    /** Finished downloads. */
    val downloads: Int = 0,
    val freeBytes: Long? = null,
)

/** Settings › Playback and Downloads and storage (SPEC §9, §10, §12). Every value lives in `AppSettings`. */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModel(private val deps: SettingsDeps) : ViewModel() {
    /** Writes run one at a time in the order tapped, each reading what the one before stored. */
    private val writes = Mutex()

    private class Usage(val usedBytes: Long, val downloads: Int, val freeBytes: Long?)

    private val usage = flow { emit(deps.repositories()) }.flatMapLatest { repos ->
        repos?.media?.observeAll()?.map { files ->
            Usage(files.sumOf { it.bytesDownloaded }, files.count { it.state == DownloadState.Complete }, deps.freeBytes())
        } ?: flowOf(Usage(0, 0, deps.freeBytes()))
    }.flowOn(Dispatchers.IO)

    val state: StateFlow<SettingsUiState> = combine(
        deps.settings.playback,
        deps.settings.downloadsWifiOnly,
        deps.settings.storageCapBytes,
        usage,
    ) { playback, wifiOnly, cap, usage ->
        SettingsUiState(false, playback, wifiOnly, cap, usage.usedBytes, usage.downloads, usage.freeBytes)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), SettingsUiState())

    fun setSpeed(speed: Float) = write { deps.settings.setPlaybackSpeed(speed) }

    fun setSkipBack(seconds: Int) = write { deps.settings.setSkipSeconds(seconds, current().skipForwardSeconds) }

    fun setSkipForward(seconds: Int) = write { deps.settings.setSkipSeconds(current().skipBackSeconds, seconds) }

    fun setRewindAfterPause(enabled: Boolean) = write {
        val now = current()
        deps.settings.setRewindAfterPause(enabled, now.rewindSeconds, now.rewindAfterPauseMinutes)
    }

    fun setRewindSeconds(seconds: Int) = write {
        val now = current()
        deps.settings.setRewindAfterPause(now.rewindAfterPause, seconds, now.rewindAfterPauseMinutes)
    }

    fun setRewindAfterMinutes(minutes: Int) = write {
        val now = current()
        deps.settings.setRewindAfterPause(now.rewindAfterPause, now.rewindSeconds, minutes)
    }

    /** Through the jobs, so downloads already waiting follow the new rule (SPEC §9). */
    fun setWifiOnly(wifiOnly: Boolean) = write { deps.setWifiOnly(wifiOnly) }

    fun setStorageCap(bytes: Long) = write { deps.settings.setStorageCapBytes(bytes) }

    private suspend fun current(): PlaybackSettings = deps.settings.playback.first()

    private fun write(block: suspend () -> Unit) {
        viewModelScope.launch {
            writes.withLock {
                try {
                    block()
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    deps.log.w(TAG, "A setting couldn't be saved", e)
                }
            }
        }
    }

    companion object {
        private const val STOP_AFTER_MS = 5_000L
        private const val TAG = "Settings"

        val SKIP_BACK_CHOICES = listOf(5, 10, 15, 20, 30, 45, 60)
        val SKIP_FORWARD_CHOICES = listOf(10, 15, 20, 30, 45, 60, 90, 120)
        val REWIND_SECONDS_CHOICES = listOf(3, 5, 10, 15, 20, 30, 60)
        val REWIND_AFTER_MINUTES_CHOICES = listOf(1, 5, 10, 15, 30, 60, 120, 240)
        private const val GIB = 1024L * 1024 * 1024
        val STORAGE_CAP_CHOICES = listOf(1, 2, 5, 10, 20, 50, 100, 200).map { it * GIB }
    }
}
