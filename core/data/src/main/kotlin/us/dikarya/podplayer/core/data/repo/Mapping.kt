/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.data.repo

import us.dikarya.podplayer.core.data.db.EpisodeActionEntity
import us.dikarya.podplayer.core.data.db.EpisodeEntity
import us.dikarya.podplayer.core.data.db.FeedEntity
import us.dikarya.podplayer.core.data.db.MediaFileEntity
import us.dikarya.podplayer.core.data.db.PlaybackRecordEntity
import us.dikarya.podplayer.core.data.db.QueueEntryEntity
import us.dikarya.podplayer.core.model.Enclosure
import us.dikarya.podplayer.core.model.Episode
import us.dikarya.podplayer.core.model.EpisodeAction
import us.dikarya.podplayer.core.model.Feed
import us.dikarya.podplayer.core.model.MediaFile
import us.dikarya.podplayer.core.model.PlaybackRecord
import us.dikarya.podplayer.core.model.QueueEntry

internal fun FeedEntity.toModel() = Feed(
    id = id,
    url = url,
    title = title,
    link = link,
    description = description,
    author = author,
    imageUrl = imageUrl,
    categories = decodeStrings(categoriesJson),
    ownerApprovedHost = ownerApprovedHost,
    subscribedAt = subscribedAt,
    lastRefreshSuccessAt = lastRefreshSuccessAt,
    lastRefreshErrorAt = lastRefreshErrorAt,
    lastRefreshError = lastRefreshError,
    nextRefreshAt = nextRefreshAt,
)

internal fun EpisodeEntity.toModel() = Episode(
    id = id,
    feedId = feedId,
    guid = guid,
    title = title,
    publishedAt = publishedAt,
    enclosure = Enclosure(enclosureUrl, enclosureType, enclosureLength),
    durationMs = durationMs,
    notes = notes,
    imageUrl = imageUrl,
    link = link,
    season = season,
    number = episodeNumber,
    chaptersUrl = chaptersUrl,
    inFeed = inFeed,
)

internal fun QueueEntryEntity.toModel() = QueueEntry(episodeId, position, addedAt)

internal fun PlaybackRecordEntity.toModel() = PlaybackRecord(episodeId, positionMs, durationMs, played, updatedAt)

internal fun EpisodeActionEntity.toModel() =
    EpisodeAction(id, episodeId, kind, positionMs, durationMs, at, deviceId, synced)

internal fun MediaFileEntity.toModel() =
    MediaFile(episodeId, relativePath, state, bytesDownloaded, totalBytes, probedDurationMs, completedAt)
