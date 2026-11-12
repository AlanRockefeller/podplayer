/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.discover

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import us.dikarya.podplayer.core.feed.ParseProblem
import us.dikarya.podplayer.core.jobs.refresh.androidFeedParser
import us.dikarya.podplayer.core.net.AddressPolicy
import us.dikarya.podplayer.core.net.BodyLimit
import us.dikarya.podplayer.core.net.FetchResult
import us.dikarya.podplayer.core.net.NetError
import us.dikarya.podplayer.core.net.UrlProblem

/** SPEC §12: a typed feed URL gets a preview before subscribing; SPEC §4: OPML import shows one too. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class FeedPreviewerTest {
    private val requested = mutableListOf<Triple<HttpUrl, BodyLimit, AddressPolicy>>()

    private fun previewer(answer: FetchResult) = FeedPreviewer(
        GateFetch { url, limit, policy ->
            requested += Triple(url, limit, policy)
            answer
        },
        androidFeedParser(),
    )

    @Test
    fun aTypedFeedIsFetchedWithItsOwnHostApprovedAndPreviewed() = runBlocking {
        val result = previewer(success(rss("My Show").toByteArray(), "application/rss+xml")).preview("http://192.168.1.5:8080/feed.xml?key=s3cret")
        val preview = (result as PreviewOutcome.Ready).preview
        assertEquals("My Show", preview.title)
        assertEquals("A Host", preview.author)
        assertEquals(3, preview.episodeCount)
        assertEquals(listOf("Episode 3", "Episode 2", "Episode 1"), preview.latestTitles)
        assertFalse("http is marked not secure", preview.isSecure)
        val (url, limit, policy) = requested.single()
        assertEquals(BodyLimit.FEED, limit)
        assertTrue("the owner typed it, so its host may be private (SPEC §3)", policy.approves("192.168.1.5"))
        assertFalse(policy.approves("10.0.0.1"))
        assertEquals("http://192.168.1.5:8080/feed.xml?key=s3cret", url.toString())
    }

    @Test
    fun anAddressThePolicyRefusesIsNeverFetched() = runBlocking {
        val result = previewer(success(ByteArray(0), null)).preview("feed://example.com/rss")
        assertEquals(PreviewOutcome.Failed(DiscoverProblem.BadUrl(UrlProblem.DisallowedScheme)), result)
        assertTrue(requested.isEmpty())
    }

    @Test
    fun aWebPageIsNotAFeed() = runBlocking {
        val result = previewer(success("<!DOCTYPE html><html><body>Hi</body></html>".toByteArray(), "text/html")).preview("https://example.com/")
        val problem = ((result as PreviewOutcome.Failed).problem as DiscoverProblem.NotAFeed).problem
        assertTrue("$problem", problem is ParseProblem.HtmlPage || problem is ParseProblem.NotAFeed)
    }

    @Test
    fun aNetworkFailureIsReported() = runBlocking {
        val result = previewer(FetchResult.Failure(NetError.HttpStatus(404))).preview("https://example.com/feed")
        assertEquals(PreviewOutcome.Failed(DiscoverProblem.Net(NetError.HttpStatus(404))), result)
    }

    // --- OPML files ---

    private val opml = """<?xml version="1.0"?><opml version="2.0"><head><title>Mine</title></head><body>
        <outline text="One" type="rss" xmlUrl="https://one.example.com/feed"/>
        <outline text="Bad" type="rss" xmlUrl="javascript:alert(1)"/>
        <outline text="Two" type="rss" xmlUrl="https://two.example.com/feed"/>
        </body></opml>"""

    @Test
    fun anOpmlFileIsReadIntoAPreview() = runBlocking {
        val result = OpmlFile.read { ByteArrayInputStream(opml.toByteArray()) } as OpmlOutcome.Ready
        assertEquals(listOf("https://one.example.com/feed", "https://two.example.com/feed"), result.feeds.map { it.feedUrl })
        assertEquals(1, result.skipped)
    }

    @Test
    fun anOversizedOpmlFileIsRefusedWithoutReadingItAll() = runBlocking {
        var read = 0L
        val endless = object : InputStream() {
            override fun read(): Int = 'x'.code.also { read++ }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                b.fill('x'.code.toByte(), off, off + len)
                read += len
                return len
            }
        }
        assertEquals(OpmlOutcome.Failed(DiscoverProblem.FileTooLarge), OpmlFile.read { endless })
        assertTrue("stopped soon after the limit: $read", read <= BodyLimit.OPML.maxBytes + 64 * 1024)
    }

    @Test
    fun anUnreadableOrWrongFileIsAPlainFailure() = runBlocking {
        assertEquals(OpmlOutcome.Failed(DiscoverProblem.FileUnreadable), OpmlFile.read { null })
        assertEquals(OpmlOutcome.Failed(DiscoverProblem.FileUnreadable), OpmlFile.read { throw IOException("gone") })
        assertEquals(OpmlOutcome.Failed(DiscoverProblem.FileUnreadable), OpmlFile.read { throw SecurityException("no") })
        val notOpml = OpmlFile.read { ByteArrayInputStream(rss("x").toByteArray()) }
        assertTrue("$notOpml", (notOpml as OpmlOutcome.Failed).problem is DiscoverProblem.NotOpml)
    }
}
