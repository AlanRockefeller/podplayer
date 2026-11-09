/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.jobs

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import java.util.UUID

/**
 * The notification an owner-started download runs under: a foreground service of type `dataSync`
 * (SPEC §9), with progress and a Cancel action. Feed-supplied titles are shown as plain text, cleaned and cut.
 */
class DownloadNotifications(private val context: Context) {
    private var channelReady = false

    private fun ensureChannel() {
        if (channelReady) return
        val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(context.getString(R.string.download_channel_name))
            .setDescription(context.getString(R.string.download_channel_description))
            .setShowBadge(false)
            .build()
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
        channelReady = true
    }

    fun foregroundInfo(workId: UUID, episodeId: Long, title: String?, bytes: Long, total: Long?): ForegroundInfo {
        ensureChannel()
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(context.getString(R.string.download_notification_title))
            .setContentText(clean(title) ?: context.getString(R.string.download_notification_untitled))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                context.getString(R.string.download_notification_cancel),
                WorkManager.getInstance(context).createCancelPendingIntent(workId),
            )
        if (total != null && total > 0) {
            builder.setProgress(PROGRESS_MAX, (bytes * PROGRESS_MAX / total).toInt().coerceIn(0, PROGRESS_MAX), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return ForegroundInfo(notificationId(episodeId), builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val CHANNEL_ID = "downloads"
        private const val PROGRESS_MAX = 1000
        private const val MAX_TITLE = 100

        fun notificationId(episodeId: Long): Int = 10_000 + (episodeId % 1_000_000).toInt()

        internal fun clean(title: String?): String? =
            title?.filter { !it.isISOControl() }?.trim()?.take(MAX_TITLE)?.takeIf { it.isNotEmpty() }
    }
}
