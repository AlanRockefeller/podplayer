/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.data.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import us.dikarya.podplayer.core.data.DAY
import us.dikarya.podplayer.core.data.DataState
import us.dikarya.podplayer.core.data.FEED_URL
import us.dikarya.podplayer.core.data.TestClock
import us.dikarya.podplayer.core.data.TestLayers
import us.dikarya.podplayer.core.data.db.MediaFileEntity
import us.dikarya.podplayer.core.data.episode
import us.dikarya.podplayer.core.data.parsedFeed
import us.dikarya.podplayer.core.data.repo.SubscribeResult
import us.dikarya.podplayer.core.data.settings.BackupFailure
import us.dikarya.podplayer.core.data.settings.ThemeMode
import us.dikarya.podplayer.core.model.DownloadState

/** SPEC §7 and the M1-S4 "Done when": a restored backup brings back subscriptions, queue, positions and settings. */
@RunWith(AndroidJUnit4::class)
class BackupRoundTripTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = TestClock()
    private val layers by lazy { TestLayers(tmp.root, clock) }

    @Test
    fun backupThenWipeThenRestoreBringsEverythingBack() = runBlocking {
        // A library in use.
        val first = layers.open()
        val repos = first.awaitRepositories()!!
        val feedId = (repos.feeds.subscribe(FEED_URL, ownerTyped = true) as SubscribeResult.Subscribed).feedId
        val other = (repos.feeds.subscribe("https://other.example.com/rss", false) as SubscribeResult.Subscribed).feedId
        repos.feeds.applyRefresh(feedId, parsedFeed((1..4).map { episode(it) }))
        repos.feeds.applyRefresh(other, parsedFeed(listOf(episode(10, guid = "o1")), title = "Other"))
        val eps = repos.episodes.forFeed(feedId).first().associateBy { it.guid }
        repos.queue.add(eps.getValue("guid-3").id)
        repos.queue.add(eps.getValue("guid-1").id)
        repos.playback.savePosition(eps.getValue("guid-3").id, 754_000L, 1_800_000L)
        repos.playback.setPlayed(eps.getValue("guid-2").id, true)
        repos.feedSettings.setSpeed(feedId, 1.4f)
        first.settings.setThemeMode(ThemeMode.LIGHT)
        val firstDevice = first.device.deviceId()
        first.device.setBackupFolder(layers.ownerFolder.path, "Backups")

        val report = first.backups.backupNow()
        assertNull(report.problem)
        assertEquals(FolderOutcome.Written, report.ownerFolder)
        assertTrue(report.appStorage)
        first.close()

        // A new phone: nothing but the owner's backup folder.
        layers.wipeEverythingButTheOwnerFolder()
        val second = layers.open()
        assertTrue(second.awaitRepositories()!!.feeds.feeds().first().isEmpty())
        assertEquals(ThemeMode.DARK, second.settings.themeMode.first())
        second.device.setBackupFolder(layers.ownerFolder.path, "Backups")
        val candidates = second.backups.candidates()
        assertEquals(1, candidates.size)
        assertEquals(BackupLocation.OwnerFolder, candidates.single().location)
        assertEquals(StageResult.Staged(clock.now), second.restore(candidates.single()))
        second.close()

        // The app restarts and the restore is applied before anything opens the database.
        val third = layers.open()
        val restored = third.awaitRepositories()!!
        val feeds = restored.feeds.feeds().first()
        assertEquals(listOf("A Show", "Other"), feeds.map { it.title })
        assertEquals("feeds.example.com", feeds.first().ownerApprovedHost)
        val back = restored.episodes.forFeed(feedId).first().associateBy { it.guid }
        assertEquals(4, back.size)
        assertEquals(listOf(back.getValue("guid-3").id, back.getValue("guid-1").id), restored.queue.entries().first().map { it.episodeId })
        assertEquals(754_000L, restored.playback.get(back.getValue("guid-3").id)!!.positionMs)
        assertTrue(restored.playback.get(back.getValue("guid-2").id)!!.played)
        assertEquals(1.4f, restored.feedSettings.speed(feedId).first())
        assertEquals(ThemeMode.LIGHT, third.settings.themeMode.first())
        // Device settings are this phone's own and never come from a backup.
        assertNotEquals(firstDevice, third.device.deviceId())
        // Applied once: the next start opens normally.
        third.close()
        val fourth = layers.open()
        assertEquals(2, fourth.awaitRepositories()!!.feeds.feeds().first().size)
        fourth.close()
    }

    @Test
    fun restoredDownloadRecordsWithoutTheirFilesAreDropped() = runBlocking {
        val first = layers.open()
        val repos = first.awaitRepositories()!!
        val feedId = (repos.feeds.subscribe(FEED_URL, false) as SubscribeResult.Subscribed).feedId
        repos.feeds.applyRefresh(feedId, parsedFeed((1..4).map { episode(it) }))
        val ids = repos.episodes.forFeed(feedId).first().map { it.id }
        val db = (first.state.value as DataState.Ready).database
        // One real download; one whose file is missing; one whose stored path escapes the media folder; one whose
        // finished file isn't the size the record says (another phone's download under the same id); and a file
        // that no record owns at all.
        val kept = layers.files.mediaDir.resolve("$feedId/${ids[0]}.mp3").apply { parentFile!!.mkdirs(); writeText("audio") }
        val wrongSize = layers.files.mediaDir.resolve("$feedId/${ids[3]}.mp3").apply { writeText("a different download") }
        val orphan = layers.files.mediaDir.resolve("$feedId/999.mp3").apply { writeText("nobody's") }
        db.mediaFiles().upsert(MediaFileEntity(ids[0], "media/$feedId/${ids[0]}.mp3", DownloadState.Complete, 5, 5, updatedAt = 1))
        db.mediaFiles().upsert(MediaFileEntity(ids[1], "media/$feedId/${ids[1]}.mp3", DownloadState.Complete, 5, 5, updatedAt = 1))
        db.mediaFiles().upsert(MediaFileEntity(ids[3], "media/$feedId/${ids[3]}.mp3", DownloadState.Complete, 5, 5, updatedAt = 1))
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO media_files (episodeId, relativePath, state, bytesDownloaded, updatedAt) VALUES (?, ?, 'Complete', 1, 1)",
            arrayOf<Any>(ids[2], "media/../databases/podplayer.db"),
        )
        val out = ByteArrayOutputStream()
        assertNull(first.backups.exportTo(out))
        assertEquals(StageResult.Staged(clock.now), first.restore { out.toByteArray().inputStream() })
        first.close()

        val second = layers.open()
        second.awaitRepositories()!!
        val rows = (second.state.value as DataState.Ready).database.mediaFiles().all()
        assertEquals(listOf(ids[0]), rows.map { it.episodeId })
        assertTrue(kept.exists())
        assertFalse("a finished download of the wrong size is not this record's file", wrongSize.exists())
        assertFalse("a file no record owns would take space outside the storage cap", orphan.exists())
        second.close()
    }

    @Test
    fun aSafetyBackupIsTakenBeforeARestore() = runBlocking {
        val first = layers.open()
        first.awaitRepositories()!!.feeds.subscribe(FEED_URL, false)
        val out = ByteArrayOutputStream()
        assertNull(first.backups.exportTo(out))
        clock.advance(60_000)
        first.restore { out.toByteArray().inputStream() }
        val local = layers.files.localBackupsDir.list()!!.toList()
        assertTrue(local.toString(), local.any { it.endsWith("-before-restore.zip") })
        first.close()
    }

    @Test
    fun rotationKeepsSevenInTheOwnerFolderAndThreeLocallyAndNeverTouchesOtherFiles() = runBlocking {
        val layer = layers.open()
        layer.awaitRepositories()!!
        layer.device.setBackupFolder(layers.ownerFolder.path, "Backups")
        val unrelated = listOf("holiday.jpg", "podplayer-backup-notes.txt", "podplayer-backup-2020.zip")
        unrelated.forEach { File(layers.ownerFolder, it).writeText("keep me") }
        repeat(10) {
            clock.advance(DAY)
            assertNull(layer.backups.backupNow().problem)
        }
        val ours = layers.ownerFolder.list()!!.filter { BackupNames.parse(it) != null }
        assertEquals(7, ours.size)
        assertTrue(unrelated.all { File(layers.ownerFolder, it).readText() == "keep me" })
        assertEquals(3, layers.files.localBackupsDir.list()!!.count { BackupNames.parse(it) != null })
        // Newest first, and the newest is today's.
        val candidates = layer.backups.candidates()
        assertEquals(clock.now - clock.now % 1000, candidates.first().createdAt)
        assertTrue(candidates.zipWithNext().all { (a, b) -> a.createdAt >= b.createdAt })
        layer.close()
    }

    @Test
    fun dailyBackupRunsOnlyWhenDue() = runBlocking {
        val layer = layers.open()
        layer.awaitRepositories()!!
        assertTrue(layer.backups.backupIfDue() != null)
        clock.advance(3_600_000)
        assertNull(layer.backups.backupIfDue())
        clock.advance(DAY)
        assertTrue(layer.backups.backupIfDue() != null)
        assertEquals(clock.now, layer.device.backupStatusNow().lastBackupAt)
        layer.close()
    }

    @Test
    fun anUnreachableOwnerFolderIsReportedAndTheLocalCopyStillWritten() = runBlocking {
        val layer = layers.open()
        layer.awaitRepositories()!!
        val notAFolder = File(tmp.root, "plain-file").apply { writeText("x") }
        layer.device.setBackupFolder(notAFolder.path, "Gone")
        val report = layer.backups.backupNow()
        assertEquals(FolderOutcome.Failed, report.ownerFolder)
        assertTrue(report.appStorage)
        assertEquals(BackupFailure.FolderUnreachable, layer.device.backupStatusNow().lastFailure)
        // The local copy counts as a backup for the daily schedule.
        assertEquals(clock.now, layer.device.backupStatusNow().lastBackupAt)
        layer.close()
    }
}
