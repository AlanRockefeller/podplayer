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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import us.dikarya.podplayer.core.data.settings.AppSettings
import us.dikarya.podplayer.core.data.settings.CleanUpSettings
import us.dikarya.podplayer.core.data.settings.RefreshSchedule
import us.dikarya.podplayer.core.model.DownloadState
import us.dikarya.podplayer.core.model.PlaybackSettings
import us.dikarya.podplayer.playback.SkipIntervals

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
    /** SPEC §9: "Download new episodes" for shows subscribed from now on. */
    val autoDownloadNewShows: Boolean = false,
    /** Auto-downloads' own network rule; false is any connection (the default). */
    val autoDownloadWifiOnly: Boolean = false,
    val cleanUp: CleanUpSettings = CleanUpSettings(),
    /** When auto-downloads stopped at the storage limit, or null while they run. */
    val autoDownloadStoppedAt: Long? = null,
    /** Bluetooth audio devices this phone has connected, and whether their automatic Play is ignored. */
    val bluetoothDevices: List<BluetoothDeviceUi> = emptyList(),
    /** How often shows are checked for new episodes in the background (owner, 2026-11-18). */
    val refresh: RefreshSchedule = RefreshSchedule(),
    /** When a show was last checked successfully (the latest of all), or null before any check. */
    val lastRefreshAt: Long? = null,
    /** When the next background check is due, or null when none is scheduled. */
    val nextRefreshAt: Long? = null,
)

data class BluetoothDeviceUi(val name: String, val ignoreAutoPlay: Boolean)

/** Settings › Playback and Downloads and storage (SPEC §9, §10, §12). Every value lives in `AppSettings`. */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModel(private val deps: SettingsDeps) : ViewModel() {
    /** Writes run one at a time in the order tapped, each reading what the one before stored. */
    private val writes = Mutex()

    private class Usage(val usedBytes: Long, val downloads: Int, val freeBytes: Long?)

    private val usage = deps.library.flatMapLatest { repos ->
        repos?.media?.observeAll()?.map { files ->
            Usage(files.sumOf { it.bytesDownloaded }, files.count { it.state == DownloadState.Complete }, deps.freeBytes())
        } ?: flowOf(Usage(0, 0, deps.freeBytes()))
    }.flowOn(Dispatchers.IO)

    private val devices = combine(deps.settings.bluetoothDevices, deps.settings.ignoreAutoPlayDevices) { seen, ignored ->
        // A ticked device stays listed even if the seen list was trimmed, so it can be unticked.
        (seen + ignored.filter { it !in seen }).map { BluetoothDeviceUi(it, it in ignored) }
    }

    /** Auto-download settings and the Bluetooth devices, grouped: `combine` takes at most five flows typed. */
    private class More(
        val newShows: Boolean,
        val wifiOnly: Boolean,
        val cleanUp: CleanUpSettings,
        val stoppedAt: Long?,
        val devices: List<BluetoothDeviceUi>,
        val refresh: RefreshInfo,
    )

    private class RefreshInfo(val schedule: RefreshSchedule, val lastAt: Long?, val nextAt: Long?)

    /** LESSONS "Refresh": show the last and next refresh. */
    private val lastRefreshAt = deps.library.flatMapLatest { repos ->
        repos?.feeds?.feeds()?.map { feeds -> feeds.mapNotNull { it.lastRefreshSuccessAt }.maxOrNull() } ?: flowOf(null)
    }

    private val refreshInfo = combine(deps.settings.refreshSchedule, lastRefreshAt, deps.nextRefreshAt) { schedule, last, next ->
        RefreshInfo(schedule, last, next)
    }

    private val devicesAndRefresh = combine(devices, refreshInfo) { devices, refresh -> devices to refresh }

    private val more = combine(
        deps.settings.autoDownloadNewShows,
        deps.settings.autoDownloadWifiOnly,
        deps.settings.cleanUp,
        deps.autoDownloadStoppedAt,
        devicesAndRefresh,
    ) { newShows, wifiOnly, cleanUp, stoppedAt, (devices, refresh) -> More(newShows, wifiOnly, cleanUp, stoppedAt, devices, refresh) }

    val state: StateFlow<SettingsUiState> = combine(
        deps.settings.playback,
        deps.settings.downloadsWifiOnly,
        deps.settings.storageCapBytes,
        usage,
        more,
    ) { playback, wifiOnly, cap, usage, more ->
        SettingsUiState(
            false, playback, wifiOnly, cap, usage.usedBytes, usage.downloads, usage.freeBytes,
            more.newShows, more.wifiOnly, more.cleanUp, more.stoppedAt, more.devices,
            refresh = more.refresh.schedule, lastRefreshAt = more.refresh.lastAt, nextRefreshAt = more.refresh.nextAt,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), SettingsUiState())

    /** Per device, opt-in (owner, 2026-11-15): ignore the automatic Play a car sends when it connects. */
    fun setIgnoreAutoPlay(deviceName: String, ignore: Boolean) = write { deps.settings.setIgnoreAutoPlay(deviceName, ignore) }

    fun setSpeed(speed: Float) = write { deps.settings.setPlaybackSpeed(speed) }

    fun setRefreshOnWifi(minutes: Int) = write {
        deps.settings.setRefreshSchedule(deps.settings.refreshSchedule.first().copy(wifiMinutes = minutes))
    }

    /** null: never on mobile data. */
    fun setRefreshOnMobile(minutes: Int?) = write {
        deps.settings.setRefreshSchedule(deps.settings.refreshSchedule.first().copy(mobileMinutes = minutes))
    }

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

    fun setAutoDownloadNewShows(on: Boolean) = write { deps.settings.setAutoDownloadNewShows(on) }

    /** Through the jobs, so auto-downloads already waiting follow the new rule (SPEC §9). */
    fun setAutoDownloadWifiOnly(wifiOnly: Boolean) = write { deps.setAutoDownloadWifiOnly(wifiOnly) }

    fun setPlayedDays(days: Int) = write { deps.setCleanUp(deps.settings.cleanUp.first().copy(playedDays = days)) }

    fun setUnplayedDays(days: Int) = write { deps.setCleanUp(deps.settings.cleanUp.first().copy(unplayedDays = days)) }

    fun setKeepStarredDownloaded(keep: Boolean) = write { deps.setCleanUp(deps.settings.cleanUp.first().copy(keepStarredDownloaded = keep)) }

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

        /** Exactly the intervals with a numbered lock-screen icon (`SkipIntervals`). */
        val SKIP_BACK_CHOICES = SkipIntervals.BACK_SECONDS
        val SKIP_FORWARD_CHOICES = SkipIntervals.FORWARD_SECONDS
        val REWIND_SECONDS_CHOICES = listOf(3, 5, 10, 15, 20, 30, 60)
        val REWIND_AFTER_MINUTES_CHOICES = listOf(1, 5, 10, 15, 30, 60, 120, 240)
        val STORAGE_CAP_CHOICES = listOf(1, 2, 5, 10, 20, 50, 100, 200).map { it * AppSettings.GB }

        /** Days; [CleanUpSettings.NEVER] first. */
        val PLAYED_DAYS_CHOICES = listOf(CleanUpSettings.NEVER, 1, 2, 3, 7, 14, 30)
        val UNPLAYED_DAYS_CHOICES = listOf(CleanUpSettings.NEVER, 7, 14, 30, 60, 90)
    }
}
