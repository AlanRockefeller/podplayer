/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        FeedEntity::class,
        EpisodeEntity::class,
        MediaFileEntity::class,
        QueueEntryEntity::class,
        PlaybackRecordEntity::class,
        FeedSettingsEntity::class,
        EpisodeActionEntity::class,
        DownloadLogEntity::class,
        RefreshLogEntity::class,
        PositionLogEntity::class,
    ],
    version = PodDatabase.VERSION,
    exportSchema = true,
)
abstract class PodDatabase : RoomDatabase() {
    abstract fun feeds(): FeedDao

    abstract fun episodes(): EpisodeDao

    abstract fun mediaFiles(): MediaFileDao

    abstract fun queue(): QueueDao

    abstract fun playback(): PlaybackDao

    abstract fun feedSettings(): FeedSettingsDao

    abstract fun episodeActions(): EpisodeActionDao

    abstract fun logs(): LogDao

    abstract fun downloadRules(): DownloadRulesDao

    companion object {
        /**
         * The schema version. Bumping it needs: the exported schema file (written by the build), a migration
         * from the previous version in [Migrations.ALL], and a seed-and-check in MigrationTest.
         */
        const val VERSION = 4

        const val FILE_NAME = "podplayer.db"
    }
}

/**
 * Every migration, oldest first. There is never a destructive fallback: a missing migration is a build
 * failure in SchemaHistoryTest, not data loss on the owner's phone. DatabaseOpener backs up first.
 */
object Migrations {
    /** M1-S7: the position log (SPEC §10). No foreign key on purpose; see [PositionLogEntity]. */
    val V1_TO_V2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `position_log` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`episodeId` INTEGER NOT NULL, `at` INTEGER NOT NULL, `fromMs` INTEGER NOT NULL, `toMs` INTEGER NOT NULL, " +
                    "`cause` TEXT NOT NULL, `streaming` INTEGER NOT NULL)",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_position_log_at` ON `position_log` (`at`)")
        }
    }

    /** M1-S9a: starred episodes (SPEC §7). Existing episodes start unstarred. */
    val V2_TO_V3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `episodes` ADD COLUMN `starred` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `episodes` ADD COLUMN `starredAt` INTEGER")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_episodes_starred_starredAt` ON `episodes` (`starred`, `starredAt`)")
        }
    }

    /** M1-S9b: auto-download per show, and which episodes it already queued (SPEC §9). Everything starts off. */
    val V3_TO_V4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `feed_settings` ADD COLUMN `autoDownloadSince` INTEGER")
            db.execSQL("ALTER TABLE `episodes` ADD COLUMN `autoDownloadedAt` INTEGER")
        }
    }

    val ALL: List<Migration> = listOf(V1_TO_V2, V2_TO_V3, V3_TO_V4)
}
