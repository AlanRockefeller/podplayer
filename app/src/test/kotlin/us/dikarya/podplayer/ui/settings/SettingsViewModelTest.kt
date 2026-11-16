/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.settings

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import us.dikarya.podplayer.core.data.settings.AppSettings
import us.dikarya.podplayer.core.data.settings.CleanUpSettings
import us.dikarya.podplayer.core.model.DownloadState
import us.dikarya.podplayer.core.model.MediaFile
import us.dikarya.podplayer.core.model.PlaybackSettings
import us.dikarya.podplayer.ui.browse.awaitState
import us.dikarya.podplayer.ui.browse.eventually

/** M1-S9 "settings persist": Playback and Downloads settings, written from the screen and read after a restart. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class SettingsViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var env: SettingsTestEnv? = null

    private fun open(): SettingsTestEnv = SettingsTestEnv(tmp.root).also { env = it }

    @After
    fun tearDown() {
        env?.close()
    }

    @Test
    fun theDefaultsFollowTheSpec() {
        val vm = SettingsViewModel(open().deps)
        val state = awaitState(vm.state) { !it.loading }
        assertEquals(PlaybackSettings(), state.playback)
        assertEquals(true, state.wifiOnly)
        assertEquals(AppSettings.DEFAULT_STORAGE_CAP_BYTES, state.capBytes)
    }

    @Test
    fun everyPlaybackAndDownloadSettingPersistsAcrossARestart() {
        val first = open()
        val vm = SettingsViewModel(first.deps)
        awaitState(vm.state) { !it.loading }
        vm.setSpeed(1.3f)
        vm.setSkipBack(10)
        vm.setSkipForward(45)
        vm.setRewindAfterPause(true)
        vm.setRewindSeconds(20)
        vm.setRewindAfterMinutes(30)
        vm.setWifiOnly(false)
        vm.setStorageCap(5L * GIB)
        val expected = PlaybackSettings(
            speed = 1.3f,
            skipBackSeconds = 10,
            skipForwardSeconds = 45,
            rewindAfterPause = true,
            rewindSeconds = 20,
            rewindAfterPauseMinutes = 30,
        )
        awaitState(vm.state) { it.playback == expected && !it.wifiOnly && it.capBytes == 5L * GIB }
        assertEquals("the change goes through the jobs, which move waiting downloads", listOf(false), first.wifiOnlyChanges)
        first.close()
        env = null

        val restarted = open()
        val settings = restarted.deps.settings
        assertEquals(expected, runBlocking { settings.playback.first() })
        assertFalse(runBlocking { settings.downloadsWifiOnly.first() })
        assertEquals(5L * GIB, runBlocking { settings.storageCapBytes.first() })
        val again = awaitState(SettingsViewModel(restarted.deps).state) { !it.loading }
        assertEquals(expected, again.playback)
    }

    @Test
    fun autoDownloadAndCleanUpDefaultsFollowTheSpec() {
        val state = awaitState(SettingsViewModel(open().deps).state) { !it.loading }
        assertEquals(false, state.autoDownloadNewShows)
        assertEquals("any connection", false, state.autoDownloadWifiOnly)
        assertEquals(CleanUpSettings(playedDays = 3, unplayedDays = 14, keepStarredDownloaded = false), state.cleanUp)
        assertNull(state.autoDownloadStoppedAt)
        assertTrue(3 in SettingsViewModel.PLAYED_DAYS_CHOICES && CleanUpSettings.NEVER in SettingsViewModel.PLAYED_DAYS_CHOICES)
        assertTrue(14 in SettingsViewModel.UNPLAYED_DAYS_CHOICES && CleanUpSettings.NEVER in SettingsViewModel.UNPLAYED_DAYS_CHOICES)
    }

    @Test
    fun autoDownloadAndCleanUpSettingsPersistAcrossARestart() {
        val first = open()
        val vm = SettingsViewModel(first.deps)
        awaitState(vm.state) { !it.loading }
        vm.setAutoDownloadNewShows(true)
        vm.setAutoDownloadWifiOnly(true)
        vm.setPlayedDays(7)
        vm.setUnplayedDays(CleanUpSettings.NEVER)
        vm.setKeepStarredDownloaded(true)
        val expected = CleanUpSettings(7, CleanUpSettings.NEVER, true)
        awaitState(vm.state) { it.autoDownloadNewShows && it.autoDownloadWifiOnly && it.cleanUp == expected }
        assertEquals("through the jobs, which move waiting auto-downloads", listOf(true), first.autoWifiOnlyChanges)
        assertEquals("each change keeps the others", expected, first.cleanUpChanges.last())
        assertEquals(listOf<Boolean>(), first.wifiOnlyChanges)
        first.close()
        env = null

        val again = awaitState(SettingsViewModel(open().deps).state) { !it.loading }
        assertEquals(true, again.autoDownloadNewShows)
        assertEquals(true, again.autoDownloadWifiOnly)
        assertEquals(expected, again.cleanUp)
        assertEquals("the manual setting is untouched", true, again.wifiOnly)
    }

    @Test
    fun theStorageLimitNoteShowsWhileAutoDownloadsAreStopped() = runBlocking {
        val e = open()
        val vm = SettingsViewModel(e.deps)
        awaitState(vm.state) { !it.loading }
        e.browse.data.device.setAutoDownloadStoppedAt(1_234L)
        awaitState(vm.state) { it.autoDownloadStoppedAt == 1_234L }
        e.browse.data.device.setAutoDownloadStoppedAt(null)
        awaitState(vm.state) { it.autoDownloadStoppedAt == null }
        Unit
    }

    @Test
    fun rewindSettingsKeepTheOtherValues() {
        val vm = SettingsViewModel(open().deps)
        awaitState(vm.state) { !it.loading }
        vm.setRewindSeconds(30)
        awaitState(vm.state) { it.playback.rewindSeconds == 30 }
        vm.setRewindAfterPause(true)
        val state = awaitState(vm.state) { it.playback.rewindAfterPause }
        assertEquals(30, state.playback.rewindSeconds)
        assertEquals(PlaybackSettings().rewindAfterPauseMinutes, state.playback.rewindAfterPauseMinutes)
    }

    @Test
    fun storageUsageCountsTheDownloadsOnDisk() = runBlocking {
        val e = open()
        val feed = e.browse.show("Alpha", episodes = 2)
        val (one, two) = e.browse.episodeIds(feed)
        val media = e.browse.repos.media
        media.upsert(MediaFile(one, "media/$feed/$one.mp3", DownloadState.Complete, 300L * MIB, 300L * MIB, 1_000, 1))
        media.upsert(MediaFile(two, "media/$feed/$two.mp3", DownloadState.Paused, 20L * MIB, 100L * MIB, null, null))
        val vm = SettingsViewModel(e.deps)
        val state = awaitState(vm.state) { it.usedBytes == 320L * MIB }
        assertEquals(1, state.downloads)
        assertEquals(50L * GIB, state.freeBytes)
    }

    @Test
    fun theWifiOnlyChangeIsSentOncePerTap() {
        val e = open()
        val vm = SettingsViewModel(e.deps)
        awaitState(vm.state) { !it.loading }
        vm.setWifiOnly(false)
        vm.setWifiOnly(true)
        eventually { e.wifiOnlyChanges.size == 2 }
        assertEquals(listOf(false, true), e.wifiOnlyChanges)
    }

    @Test
    fun storageLimitsReadAsWholeGigabytes() {
        // The owner's phone showed "11 GB" and choices like "5.4 GB" (2026-11-12): the limits were binary
        // gigabytes shown in Android's decimal units. They are decimal now, like "35 GB free on this phone".
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>()
        val labels = SettingsViewModel.STORAGE_CAP_CHOICES.map { storageCapLabel(context, it) }
        assertEquals(listOf("1 GB", "2 GB", "5 GB", "10 GB", "20 GB", "50 GB", "100 GB", "200 GB"), labels)
        assertEquals("10 GB", storageCapLabel(context, AppSettings.DEFAULT_STORAGE_CAP_BYTES))
        assertEquals("an odd value from an old backup still reads sensibly", "1.5 GB", storageCapLabel(context, 1_500_000_000L))
        assertEquals("the default is one of the choices", true, AppSettings.DEFAULT_STORAGE_CAP_BYTES in SettingsViewModel.STORAGE_CAP_CHOICES)
    }

    private companion object {
        const val MIB = 1024L * 1024
        const val GIB = 1024L * MIB
    }
}
