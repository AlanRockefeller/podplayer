/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

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

    companion object {
        /**
         * The schema version. Bumping it needs: the exported schema file (written by the build), a migration
         * from the previous version in [Migrations.ALL], and a seed-and-check in MigrationTest.
         */
        const val VERSION = 1

        const val FILE_NAME = "podplayer.db"
    }
}

/**
 * Every migration, oldest first. There is never a destructive fallback: a missing migration is a build
 * failure in SchemaHistoryTest, not data loss on the owner's phone. DatabaseOpener backs up first.
 */
object Migrations {
    val ALL: List<Migration> = emptyList()
}
