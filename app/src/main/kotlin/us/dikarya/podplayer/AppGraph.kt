/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer

import android.app.PendingIntent
import android.content.Context
import android.os.storage.StorageManager
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import us.dikarya.podplayer.core.data.DataFiles
import us.dikarya.podplayer.core.data.DataLayer
import us.dikarya.podplayer.core.data.logs.AppLog
import us.dikarya.podplayer.core.data.logs.ExitReasons
import us.dikarya.podplayer.core.data.logs.LogExport
import us.dikarya.podplayer.core.data.settings.AppSettings
import us.dikarya.podplayer.core.data.settings.ThemeMode
import us.dikarya.podplayer.core.imagesandbox.ImageSandbox
import us.dikarya.podplayer.core.jobs.Jobs
import us.dikarya.podplayer.core.jobs.PodplayerWorkerFactory
import us.dikarya.podplayer.core.jobs.refresh.androidFeedParser
import us.dikarya.podplayer.core.net.AddressPolicy
import us.dikarya.podplayer.core.net.NetConfig
import us.dikarya.podplayer.core.net.NetGate
import us.dikarya.podplayer.playback.BluetoothPlayGate
import us.dikarya.podplayer.playback.PlaybackClient
import us.dikarya.podplayer.playback.PlaybackDependencies
import us.dikarya.podplayer.playback.RoomPlaybackStore
import us.dikarya.podplayer.ui.browse.BrowseDeps
import us.dikarya.podplayer.ui.browse.DownloadActions
import us.dikarya.podplayer.ui.browse.NetworkStatus
import us.dikarya.podplayer.ui.browse.Subscriptions
import us.dikarya.podplayer.ui.discover.AppleSearch
import us.dikarya.podplayer.ui.discover.FeedPreviewer
import us.dikarya.podplayer.ui.discover.GateFetch
import us.dikarya.podplayer.ui.settings.SettingsDeps

/**
 * Manual dependency injection: the app's long-lived objects, built once in [PodplayerApp].
 * Building it does no disk or network work, so it is safe on the main thread; [start] begins that work.
 */
class AppGraph(context: Context) {
    private val appContext = context.applicationContext

    /** Lives as long as the process. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Android creates these folders on first use, which is disk work: first touched off the main thread. */
    val files: DataFiles by lazy { DataFiles.from(appContext) }

    /** The rolling, redacted app log (SPEC §13). */
    val log = AppLog(dir = { files.logsDir })

    /** Database, repositories, settings, backups (`:core:data`). The database opens in the background. */
    val data = DataLayer(appContext, appScope, BuildConfig.VERSION_NAME, log, filesProvider = { files })

    val settings: AppSettings get() = data.settings

    /**
     * The saved theme, or null until it has been read. Reading starts here, at process start,
     * so it is usually ready before the first frame (see MainActivity).
     */
    val themeMode: StateFlow<ThemeMode?> = settings.themeMode.stateIn(appScope, SharingStarted.Eagerly, null)

    /**
     * The one gated HTTP client (CLAUDE.md, SPEC §3). Pass this, or a `Call.Factory` from it, to
     * everything that needs the network, including ExoPlayer. Built on first use.
     */
    val netGate: NetGate by lazy { NetGate(NetConfig(appVersion = BuildConfig.VERSION_NAME)) }

    /** Refresh, downloads and the daily backup (`:core:jobs`). Nothing is built until first use. */
    val jobs = Jobs(appContext, data, gate = { netGate }, log = log, scope = appScope)

    val workerFactory: PodplayerWorkerFactory get() = jobs.workerFactory

    /**
     * Artwork (`:core:imagesandbox`): fetched by the gate, decoded only in the isolated sandbox process, shown
     * only from its cache. Built on first use.
     */
    val images: ImageSandbox by lazy {
        ImageSandbox(appContext, gate = { netGate }, log = { log.i("Artwork", it) }, scope = appScope)
    }

    /**
     * CLAUDE.md: never auto-start on Bluetooth connect (the owner's car sends Play when it connects). It watches
     * Bluetooth audio for the life of the process, so it is created in [start], before any service runs.
     */
    val playGate: BluetoothPlayGate by lazy { BluetoothPlayGate.watching(appContext) }

    /** What the playback service (`:playback`) needs; it is built by Android, so it asks the app for these. */
    val playbackDependencies: PlaybackDependencies by lazy {
        PlaybackDependencies(
            store = RoomPlaybackStore(
                data,
                log,
                fetchArtwork = { url, approvedHost -> images.artworkUri(url, approvedHost) },
            ) { url -> images.cachedArtworkUri(url) },
            callFactoryFor = { host -> netGate.callFactory(if (host == null) AddressPolicy.Default else AddressPolicy.approvingHosts(host)) },
            artwork = images,
            playGate = playGate,
            processScope = appScope,
            sessionActivity = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)?.let {
                PendingIntent.getActivity(appContext, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            },
        )
    }

    /** Our screens' connection to the playback session. Connects on first use. */
    val playback: PlaybackClient by lazy { PlaybackClient(appContext) }

    /** Whether there is a network, for the screens' offline messages. */
    val network: NetworkStatus by lazy { NetworkStatus(appContext) }

    /** What the browsing screens' view models need (M1-S8). Built on first use, on the main thread. */
    val browse: BrowseDeps by lazy {
        val gateFetch = GateFetch { url, limit, policy -> netGate.fetch(url, limit, policy) }
        BrowseDeps(
            repositories = { data.awaitRepositories() },
            settings = settings,
            playback = playback,
            downloads = object : DownloadActions {
                // Not cancelled with the screen that asked: an unsubscribe half done would leave files behind.
                override suspend fun download(episodeId: Long) {
                    withContext(NonCancellable + Dispatchers.IO) { jobs.downloads.enqueue(episodeId) }
                }

                override suspend fun cancel(episodeId: Long) = jobs.downloads.cancel(episodeId)

                override suspend fun delete(episodeId: Long) = withContext(NonCancellable + Dispatchers.IO) { jobs.downloads.delete(episodeId) }

                override suspend fun unsubscribe(feedId: Long) = withContext(NonCancellable + Dispatchers.IO) { jobs.downloads.removeFeed(feedId) }
            },
            refresh = { feedId -> jobs.refreshNow(feedId) },
            online = network.online,
            search = AppleSearch(gateFetch),
            previewer = FeedPreviewer(gateFetch, androidFeedParser()),
            subscriptions = Subscriptions({ data.awaitRepositories() }) { feedId -> jobs.refreshNow(feedId) },
            log = log,
        )
    }

    /** What the settings and logs screens need (M1-S9). Built on first use. */
    val settingsDeps: SettingsDeps by lazy {
        SettingsDeps(
            settings = settings,
            repositories = { data.awaitRepositories() },
            // Not cancelled with the screen: the setting and the waiting downloads must not disagree.
            setWifiOnly = { wifiOnly -> withContext(NonCancellable + Dispatchers.IO) { jobs.setDownloadsWifiOnly(wifiOnly) } },
            // What the system can make room for, counting cache it would clear (lint: UsableSpace).
            freeBytes = {
                runCatching {
                    val storage = appContext.getSystemService(StorageManager::class.java)
                    storage.getAllocatableBytes(storage.getUuidForPath(files.filesDir))
                }.getOrNull()?.takeIf { it > 0 }
            },
            logs = LogExport(log, { files.crashDir }, { data.awaitRepositories() }, BuildConfig.VERSION_NAME),
            exportDir = { File(files.cacheDir, "exports") },
            log = log,
            appVersion = BuildConfig.VERSION_NAME,
        )
    }

    fun start() {
        playGate
        data.start()
        appScope.launch(Dispatchers.IO) {
            log.i("App", "Started version ${BuildConfig.VERSION_NAME}")
            data.device.setLastExitSeenAt(ExitReasons.record(appContext, log, data.device.lastExitSeenAt()))
            jobs.schedule()
            // A restored backup can carry a different Wi-Fi-only choice than the downloads waiting on this phone.
            jobs.downloads.applyNetworkSetting()
        }
    }
}
