/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.data.db

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import us.dikarya.podplayer.core.data.TestClock
import us.dikarya.podplayer.core.data.appContext
import us.dikarya.podplayer.core.data.repo.Repositories
import us.dikarya.podplayer.core.model.EpisodeActionKind
import us.dikarya.podplayer.core.model.PositionCause

/**
 * Migration scaffolding (SPEC §7, LESSONS "Storage": subscriptions lost on an upgrade).
 *
 * Every schema version ever shipped is rebuilt from its exported schema file in `core/data/schemas/`, filled
 * with that version's seed data, then opened by the current code, which runs every migration and makes Room
 * validate the result. The data must survive. Adding version N+1 therefore needs, or these tests fail:
 * the exported `N+1.json` (the build writes it), a migration N to N+1 in [Migrations.ALL], and an entry for
 * N+1 in [MigrationSeeds]. Keep the old seeds: they are the "saved old databases" LESSONS asks for.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun everyVersionHasAnExportedSchemaAndNothingBeyond() {
        for (version in 1..PodDatabase.VERSION) assertNotNull("schema $version", SchemaFiles.json(version))
        assertNull(SchemaFiles.json(PodDatabase.VERSION + 1))
    }

    @Test
    fun everyVersionHasAMigrationToTheNext() {
        for (from in 1 until PodDatabase.VERSION) {
            assertTrue("no migration from $from", Migrations.ALL.any { it.startVersion == from && it.endVersion == from + 1 })
        }
        assertTrue(Migrations.ALL.all { it.endVersion == it.startVersion + 1 && it.endVersion <= PodDatabase.VERSION })
    }

    @Test
    fun everyVersionHasSeedDataAndChecks() {
        assertEquals((1..PodDatabase.VERSION).toSet(), MigrationSeeds.byVersion.keys)
    }

    @Test
    fun theNewestSchemaFileMatchesTheCode() {
        val file = File(tmp.root, "fresh.db")
        val db = PodDatabases.builder(appContext(), file).build()
        db.openHelper.writableDatabase
        db.close()
        val stored = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
            raw.rawQuery("SELECT identity_hash FROM room_master_table", null).use { it.moveToFirst(); it.getString(0) }
        }
        assertEquals(SchemaFiles.identityHash(PodDatabase.VERSION), stored)
    }

    @Test
    fun everyOldVersionMigratesToTheCurrentOneKeepingItsData() = runBlocking {
        for ((version, seed) in MigrationSeeds.byVersion) {
            val file = File(tmp.root, "v$version.db")
            SchemaFiles.create(file, version)
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
                seed.sql.forEach { raw.execSQL(it) }
            }
            val db = PodDatabases.builder(appContext(), file).build()
            try {
                // Opening runs the migrations; Room then checks the schema and throws if it is wrong.
                db.openHelper.writableDatabase
                seed.check(Repositories(db, TestClock()) { "d" })
            } finally {
                db.close()
            }
        }
    }
}

/** Seed data and checks for each schema version. Never edit an old entry; add one per new version. */
internal object MigrationSeeds {
    class Seed(val sql: List<String>, val check: suspend (Repositories) -> Unit)

    val byVersion: Map<Int, Seed> = mapOf(
        1 to Seed(
            sql = listOf(
                "INSERT INTO feeds (id, url, title, subscribedAt, consecutiveFailures) VALUES (1, 'https://f.example.com/rss', 'Show', 1, 0)",
                "INSERT INTO episodes (id, feedId, guid, title, publishedAt, firstSeenAt, enclosureUrl, fingerprint, inFeed) " +
                    "VALUES (10, 1, 'g1', 'One', 100, 100, 'https://f.example.com/1.mp3', 'fp', 1)",
                "INSERT INTO episodes (id, feedId, guid, title, publishedAt, firstSeenAt, enclosureUrl, fingerprint, inFeed) " +
                    "VALUES (11, 1, 'g2', 'Two', 200, 200, 'https://f.example.com/2.mp3', 'fp', 0)",
                "INSERT INTO queue_entries (episodeId, position, addedAt) VALUES (11, 0, 5)",
                "INSERT INTO playback_records (episodeId, positionMs, durationMs, played, updatedAt) VALUES (10, 4242, 9000, 0, 7)",
                "INSERT INTO feed_settings (feedId, playbackSpeed) VALUES (1, 1.5)",
            ),
            check = { repos ->
                assertEquals(listOf("Show"), repos.feeds.feeds().first().map { it.title })
                assertEquals(listOf(11L), repos.queue.entries().first().map { it.episodeId })
                assertEquals(4242L, repos.playback.get(10)!!.positionMs)
                assertEquals(false, repos.episodes.get(11)!!.inFeed)
                assertEquals(1.5f, repos.feedSettings.speed(1).first())
                // Version 2 added the position log; a migrated database starts with it empty.
                assertEquals(emptyList<Any>(), repos.logs.positionLog(10).first())
            },
        ),
        2 to Seed(
            sql = listOf(
                "INSERT INTO feeds (id, url, title, subscribedAt, consecutiveFailures) VALUES (1, 'https://f.example.com/rss', 'Show', 1, 0)",
                "INSERT INTO episodes (id, feedId, guid, title, publishedAt, firstSeenAt, enclosureUrl, fingerprint, inFeed) " +
                    "VALUES (10, 1, 'g1', 'One', 100, 100, 'https://f.example.com/1.mp3', 'fp', 1)",
                "INSERT INTO position_log (id, episodeId, at, fromMs, toMs, cause, streaming) VALUES (1, 10, 50, 0, 61000, 'Restore', 0)",
                // An episode id with no episode row: the log has no foreign key, so it outlives an unsubscribe.
                "INSERT INTO position_log (id, episodeId, at, fromMs, toMs, cause, streaming) VALUES (2, 999, 60, 5000, 1000, 'Unexplained', 1)",
            ),
            check = { repos ->
                val log = repos.logs.positionLog(10).first()
                assertEquals(listOf(999L, 10L), log.map { it.episodeId })
                assertEquals(PositionCause.Unexplained, log.first().cause)
                assertEquals(true, log.first().streaming)
                // Version 3 added stars; every migrated episode starts unstarred.
                assertEquals(false, repos.episodes.get(10)!!.starred)
                assertEquals(emptyList<Any>(), repos.starred.starred().first())
            },
        ),
        3 to Seed(
            sql = listOf(
                "INSERT INTO feeds (id, url, title, subscribedAt, consecutiveFailures) VALUES (1, 'https://f.example.com/rss', 'Show', 1, 0)",
                "INSERT INTO episodes (id, feedId, guid, title, publishedAt, firstSeenAt, enclosureUrl, fingerprint, inFeed, starred, starredAt) " +
                    "VALUES (10, 1, 'g1', 'One', 100, 100, 'https://f.example.com/1.mp3', 'fp', 1, 1, 500)",
                "INSERT INTO episodes (id, feedId, guid, title, publishedAt, firstSeenAt, enclosureUrl, fingerprint, inFeed, starred, starredAt) " +
                    "VALUES (11, 1, 'g2', 'Two', 200, 200, 'https://f.example.com/2.mp3', 'fp', 1, 1, 900)",
                "INSERT INTO episodes (id, feedId, guid, title, publishedAt, firstSeenAt, enclosureUrl, fingerprint, inFeed) " +
                    "VALUES (12, 1, 'g3', 'Three', 300, 300, 'https://f.example.com/3.mp3', 'fp', 1)",
                "INSERT INTO episode_actions (id, episodeId, kind, at, deviceId, synced) VALUES (1, 11, 'Starred', 900, 'd', 0)",
            ),
            check = { repos ->
                assertEquals(listOf(11L, 10L), repos.starred.starred().first().map { it.id })
                assertEquals(500L, repos.episodes.get(10)!!.starredAt)
                assertEquals(false, repos.episodes.get(12)!!.starred)
                assertEquals(listOf(EpisodeActionKind.Starred), repos.actions.unsynced(10).map { it.kind })
            },
        ),
    )
}

/** Reads the exported Room schemas (on the test classpath, see core/data/build.gradle.kts). */
internal object SchemaFiles {
    private fun path(version: Int) = "/${PodDatabase::class.java.name}/$version.json"

    fun json(version: Int): JsonObject? =
        SchemaFiles::class.java.getResourceAsStream(path(version))?.use { Json.parseToJsonElement(it.readBytes().decodeToString()).jsonObject }

    fun identityHash(version: Int): String = json(version)!!["database"]!!.jsonObject["identityHash"]!!.jsonPrimitive.content

    /** Builds the database exactly as that version of the app would have created it. */
    fun create(file: File, version: Int) {
        val database = json(version)!!["database"]!!.jsonObject
        assertEquals(version, database["version"]!!.jsonPrimitive.int)
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY).use { raw ->
            for (entity in database["entities"]!!.jsonArray.map { it.jsonObject }) {
                val table = entity["tableName"]!!.jsonPrimitive.content
                raw.execSQL(entity["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                for (index in (entity["indices"] as? JsonArray).orEmpty().map { it.jsonObject }) {
                    raw.execSQL(
                        index["createSql"]!!.jsonPrimitive.content
                            .replace("\${TABLE_NAME}", table)
                            .replace("\${INDEX_NAME}", (index["name"] as JsonPrimitive).content),
                    )
                }
            }
            for (query in database["setupQueries"]!!.jsonArray) raw.execSQL(query.jsonPrimitive.content)
            raw.version = version
        }
    }
}
