/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

@file:OptIn(UnstableApi::class)

package us.dikarya.podplayer.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp3.Mp3Extractor
import java.io.IOException
import okhttp3.Call
import us.dikarya.podplayer.core.net.BadRedirectException
import us.dikarya.podplayer.core.net.BlockedAddressException
import us.dikarya.podplayer.core.net.DisallowedUrlException
import us.dikarya.podplayer.core.net.TooManyRedirectsException
import us.dikarya.podplayer.core.net.UrlCheck
import us.dikarya.podplayer.core.net.UrlPolicy

/**
 * Turns our items into media sources (SPEC §10). A downloaded file is read from disk with MP3 index seeking;
 * a stream goes through the gated OkHttp client with the feed's address policy (CLAUDE.md: all traffic through
 * `:core:net`, ExoPlayer included). Anything else — an item without our tag, a non-http(s) stream URL — gets a
 * source that fails to open, so it can never be read.
 *
 * [callFactoryFor] maps a feed's owner-approved host (or null) to the gate's call factory for that policy.
 */
class GatedMediaSourceFactory(private val callFactoryFor: (approvedHost: String?) -> Call.Factory) : MediaSource.Factory {
    private var drm: DrmSessionManagerProvider? = null
    private var errorPolicy: LoadErrorHandlingPolicy? = null

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val source = ItemSource.of(mediaItem) ?: return refused(mediaItem, "an item without a source")
        val file = source.file
        if (file != null) {
            // The item's URI is this file (toMediaItem); index seeking makes seeks in VBR MP3s exact.
            return configure(
                ProgressiveMediaSource.Factory(
                    FileDataSource.Factory(),
                    DefaultExtractorsFactory().setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING),
                ),
            ).createMediaSource(mediaItem.buildUpon().setUri(Uri.fromFile(file)).build())
        }
        val url = (UrlPolicy.checkFeedUrl(source.streamUrl) as? UrlCheck.Allowed)?.url
            ?: return refused(mediaItem, "a stream URL the policy refuses")
        val http = WholeBodyDataSource.Factory(OkHttpDataSource.Factory(callFactoryFor(source.approvedHost)))
        return configure(ProgressiveMediaSource.Factory(http, DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)))
            .createMediaSource(mediaItem.buildUpon().setUri(url.toString()).build())
    }

    private fun configure(factory: ProgressiveMediaSource.Factory): ProgressiveMediaSource.Factory = factory.apply {
        drm?.let { setDrmSessionManagerProvider(it) }
        setLoadErrorHandlingPolicy(RefusalIsFinal(errorPolicy ?: DefaultLoadErrorHandlingPolicy()))
    }

    private fun refused(item: MediaItem, why: String): MediaSource =
        configure(ProgressiveMediaSource.Factory { RefusingDataSource(why) }).createMediaSource(item)

    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory = apply { drm = provider }

    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory = apply { errorPolicy = policy }

    override fun getSupportedTypes(): IntArray = intArrayOf(C.CONTENT_TYPE_OTHER)
}

/**
 * A refusal by our URL or address policy is final: retrying can't change the answer. Found on the owner's phone
 * (2026-11-18): a stream on an unapproved private address was retried as a dropped stream, with the "reconnecting"
 * notice. Everything else is left to [delegate].
 */
internal class RefusalIsFinal(private val delegate: LoadErrorHandlingPolicy) : LoadErrorHandlingPolicy by delegate {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
        if (refusalReason(loadErrorInfo.exception) != null) C.TIME_UNSET else delegate.getRetryDelayMsFor(loadErrorInfo)
}

/**
 * Why our policy refused this load, in plain words without the URL (only a host), or null when [error] isn't a
 * refusal (an ordinary network failure, which may be retried).
 */
fun refusalReason(error: Throwable): String? {
    for (cause in generateSequence(error) { it.cause.takeIf { next -> next !== it } }.take(MAX_CAUSES)) {
        when (cause) {
            is BlockedAddressException -> return when (val reason = cause.reason) {
                null -> "${cause.host} has no address it may use"
                else -> "${cause.host} is on a ${reason.name.lowercase()} network address, which only a feed whose address you typed may use"
            }
            is DisallowedUrlException -> return "the address isn't allowed (${cause.problem})"
            is TooManyRedirectsException -> return "more than ${cause.limit} redirects"
            is BadRedirectException -> return "a redirect that isn't allowed"
            is RefusedSourceException -> return cause.message
        }
    }
    return null
}

private const val MAX_CAUSES = 10

/** The player's item can never be opened (no source of ours, or a URL the policy refuses). */
class RefusedSourceException(why: String) : IOException("Refused to open $why")

/** A data source that never opens: the player reports an error and nothing is read. */
private class RefusingDataSource(private val why: String) : DataSource {
    override fun addTransferListener(transferListener: TransferListener) = Unit

    override fun open(dataSpec: DataSpec): Long = throw RefusedSourceException(why)

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = C.RESULT_END_OF_INPUT

    override fun getUri(): Uri? = null

    override fun close() = Unit
}
