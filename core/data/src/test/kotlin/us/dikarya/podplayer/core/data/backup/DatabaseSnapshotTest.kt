/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.data.backup

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import us.dikarya.podplayer.core.data.FEED_URL
import us.dikarya.podplayer.core.data.TestClock
import us.dikarya.podplayer.core.data.db.PodDatabase
import us.dikarya.podplayer.core.data.episode
import us.dikarya.podplayer.core.data.openDatabase
import us.dikarya.podplayer.core.data.parsedFeed
import us.dikarya.podplayer.core.data.repo.Repositories
import us.dikarya.podplayer.core.data.repo.SubscribeResult

/** SPEC §7: `VACUUM INTO` where SQLite has it (3.27+, Android 11+), an equivalent copy on Android 10. */
@RunWith(AndroidJUnit4::class)
class DatabaseSnapshotTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: PodDatabase

    @Before
    fun setUp(): Unit = runBlocking {
        db = openDatabase(File(tmp.root, "live.db"))
        val repos = Repositories(db, TestClock()) { "d" }
        val id = (repos.feeds.subscribe(FEED_URL, false) as SubscribeResult.Subscribed).feedId
        // These writes are still in the WAL file, not yet in the main database file.
        repos.feeds.applyRefresh(id, parsedFeed((1..200).map { episode(it) }))
    }

    @After
    fun tearDown() = db.close()

    private fun countEpisodes(file: File): Int =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
            raw.rawQuery("SELECT COUNT(*) FROM episodes", null).use { it.moveToFirst(); it.getInt(0) }
        }

    @Test
    fun bothMethodsGiveACompleteHealthyCopy() {
        for (method in DatabaseSnapshot.Method.entries) {
            val target = File(tmp.root, "snap-$method.db")
            DatabaseSnapshot.take(db.openHelper.writableDatabase, target, method)
            assertEquals(method.name, 200, countEpisodes(target))
            assertTrue(method.name, IntegrityCheck.check(target) is Health.Healthy)
            assertEquals(PodDatabase.VERSION, (IntegrityCheck.check(target) as Health.Healthy).userVersion)
        }
    }

    @Test
    fun theDeviceMethodFollowsTheSqliteVersion() {
        assertEquals(DatabaseSnapshot.Method.VacuumInto, DatabaseSnapshot.methodFor("3.28.0"))
        assertEquals(DatabaseSnapshot.Method.VacuumInto, DatabaseSnapshot.methodFor("3.45.1"))
        assertEquals(DatabaseSnapshot.Method.CheckpointAndCopy, DatabaseSnapshot.methodFor("3.22.0"))
        assertEquals(DatabaseSnapshot.Method.CheckpointAndCopy, DatabaseSnapshot.methodFor("garbage"))
    }
}
