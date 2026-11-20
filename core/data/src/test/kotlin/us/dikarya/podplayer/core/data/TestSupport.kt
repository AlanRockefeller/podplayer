/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import us.dikarya.podplayer.core.data.backup.BackupLimits
import us.dikarya.podplayer.core.data.backup.FileBackupFolder
import us.dikarya.podplayer.core.data.db.PodDatabase
import us.dikarya.podplayer.core.data.db.PodDatabases
import us.dikarya.podplayer.core.data.logs.AppLog
import us.dikarya.podplayer.core.feed.FeedFormat
import us.dikarya.podplayer.core.feed.ParsedEnclosure
import us.dikarya.podplayer.core.feed.ParsedEpisode
import us.dikarya.podplayer.core.feed.ParsedFeed

internal fun appContext(): Context = ApplicationProvider.getApplicationContext()

/** A clock the test moves by hand. */
internal class TestClock(var now: Long = 1_793_000_000_000L) : () -> Long {
    override fun invoke(): Long = now

    fun advance(ms: Long) {
        now += ms
    }
}

internal const val DAY = 24L * 3600 * 1000

/** A real file-backed database (WAL, foreign keys, the production factory), as the app opens it. */
internal fun openDatabase(file: File): PodDatabase = PodDatabases.builder(appContext(), file).build()

internal fun episode(
    n: Int,
    guid: String? = "guid-$n",
    title: String? = "Episode $n",
    url: String = "https://cdn.example.com/audio/$n.mp3?token=SECRET$n",
    publishedAt: Long? = 1_790_000_000_000L + n * DAY,
    notes: String? = "<p>Notes for $n</p>",
) = ParsedEpisode(
    guid = guid,
    title = title,
    publishedAt = publishedAt,
    enclosure = ParsedEnclosure(url, "audio/mpeg", 1_000_000L + n),
    durationMs = 1_800_000L,
    notes = notes,
)

internal fun parsedFeed(episodes: List<ParsedEpisode>, title: String = "A Show") = ParsedFeed(
    format = FeedFormat.Rss,
    title = title,
    link = "https://show.example.com/",
    description = "<p>About the show</p>",
    author = "Someone",
    imageUrl = "https://show.example.com/art.jpg",
    categories = listOf("Technology", "News"),
    episodes = episodes,
)

internal const val FEED_URL = "https://feeds.example.com/show.xml?token=SECRETTOKEN"

/**
 * Builds [DataLayer]s over one app-data folder, as successive app starts would. Each [open] is a new process
 * start; [DataLayer.close] ends it. The owner's backup folder is a plain directory standing in for the
 * Storage Access Framework tree.
 */
internal class TestLayers(private val root: File, private val clock: TestClock) {
    val files = DataFiles.under(File(root, "app"))
    val ownerFolder: File = File(root, "owner").apply { mkdirs() }
    val log = AppLog({ File(root, "logs") }, clock, echo = false)

    fun open(limits: BackupLimits = BackupLimits(), targetVersion: Int = PodDatabase.VERSION): DataLayer {
        val layer = DataLayer(
            backupLimits = limits,
            targetVersion = targetVersion,
            context = appContext(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            appVersion = "9.9.9-test",
            log = log,
            clock = clock,
            filesProvider = { files },
            ownerFolder = { FileBackupFolder(File(it)) },
        )
        layer.start()
        return layer
    }

    fun wipeEverythingButTheOwnerFolder() {
        File(root, "app").deleteRecursively()
    }
}
