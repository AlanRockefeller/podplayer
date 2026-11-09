/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.jobs.download

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import us.dikarya.podplayer.core.jobs.TestAudio

/** SPEC §9: a download counts only when Media3's extractors recognise audio and read its duration. */
@RunWith(AndroidJUnit4::class)
class AudioProbeTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val probe = AudioProbe()

    private fun fileOf(bytes: ByteArray): File = tmp.newFile().apply { writeBytes(bytes) }

    private fun assertDuration(expectedMs: Long, result: ProbeResult, tolerance: Double = 0.03) {
        val audio = result as? ProbeResult.Audio ?: throw AssertionError("expected audio, got $result")
        val error = kotlin.math.abs(audio.durationMs - expectedMs).toDouble() / expectedMs
        assertTrue("duration ${audio.durationMs} ms, expected about $expectedMs ms", error <= tolerance)
    }

    @Test
    fun mp3DurationIsRead() {
        val result = probe.probe(fileOf(TestAudio.mp3(400)))
        assertDuration(TestAudio.mp3DurationMs(400), result)
        assertEquals(MimeTypes.AUDIO_MPEG, (result as ProbeResult.Audio).mimeType)
    }

    @Test
    fun wavDurationIsRead() {
        assertDuration(2_000, probe.probe(fileOf(TestAudio.wav(2_000))), tolerance = 0.001)
    }

    @Test
    fun randomBytesAreNotRecognised() {
        val bytes = Random(42).nextBytes(200_000)
        assertEquals(ProbeResult.Rejected(ProbeProblem.Unrecognized), probe.probe(fileOf(bytes)))
    }

    @Test
    fun anEmptyFileIsNotRecognised() {
        assertEquals(ProbeResult.Rejected(ProbeProblem.Unrecognized), probe.probe(fileOf(ByteArray(0))))
    }

    @Test
    fun aMissingFileIsUnreadable() {
        assertEquals(ProbeResult.Rejected(ProbeProblem.Unreadable), probe.probe(File(tmp.root, "missing.mp3")))
    }

    @Test
    fun anImageIsNotAudio() {
        assertEquals(ProbeResult.Rejected(ProbeProblem.NotAudio), probe.probe(fileOf(TestAudio.png)))
    }

    @Test
    fun aTruncatedFileIsRefusedWithoutAnException() {
        for (length in listOf(1, 4, 100, 416)) {
            val result = probe.probe(fileOf(TestAudio.mp3(10).copyOf(length)))
            assertTrue("$length bytes: $result", result is ProbeResult.Rejected)
        }
        // One whole frame is real audio, 26 ms of it.
        assertDuration(26, probe.probe(fileOf(TestAudio.mp3(10).copyOf(417))), tolerance = 0.1)
    }

    @Test
    fun aWavHeaderClaimingMoreDataThanThereIsDoesNotThrow() {
        val bytes = TestAudio.wav(500)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(40, Int.MAX_VALUE).putInt(4, Int.MAX_VALUE)
        // Whatever the extractor makes of it, it is a typed result.
        probe.probe(fileOf(bytes))
    }

    @Test
    fun everyPrefixOfAWavFileIsHandled() {
        val bytes = TestAudio.wav(200)
        for (length in 0..bytes.size step 7) probe.probe(fileOf(bytes.copyOf(length)))
    }

    @Test
    fun theTimeLimitStopsAProbe() {
        var now = 0L
        val slow = AudioProbe(timeLimit = 1.seconds) { now.also { now += 600_000_000L } }
        assertEquals(ProbeResult.Rejected(ProbeProblem.TooSlow), slow.probe(fileOf(TestAudio.mp3(400))))
    }

    @Test
    fun aVideoTrackMeansVideo() {
        val tracks = listOf(ProbedTrack(C.TRACK_TYPE_AUDIO, MimeTypes.AUDIO_AAC), ProbedTrack(C.TRACK_TYPE_VIDEO, MimeTypes.VIDEO_H264))
        assertEquals(ProbeResult.Rejected(ProbeProblem.Video), AudioProbe.classify(tracks, 60_000_000L))
    }

    @Test
    fun chapterArtInAnEnhancedPodcastIsNotVideo() {
        val tracks = listOf(ProbedTrack(C.TRACK_TYPE_AUDIO, MimeTypes.AUDIO_AAC), ProbedTrack(C.TRACK_TYPE_VIDEO, MimeTypes.VIDEO_MJPEG))
        assertEquals(ProbeResult.Audio(60_000, MimeTypes.AUDIO_AAC), AudioProbe.classify(tracks, 60_000_000L))
    }

    @Test
    fun noDurationIsRefused() {
        val tracks = listOf(ProbedTrack(C.TRACK_TYPE_AUDIO, MimeTypes.AUDIO_MPEG))
        assertEquals(ProbeResult.Rejected(ProbeProblem.NoDuration), AudioProbe.classify(tracks, C.TIME_UNSET))
        assertEquals(ProbeResult.Rejected(ProbeProblem.NoDuration), AudioProbe.classify(tracks, 0))
    }

    @Test
    fun onlyTextOrMetadataIsNotAudio() {
        val tracks = listOf(ProbedTrack(C.TRACK_TYPE_METADATA, MimeTypes.APPLICATION_ID3))
        assertEquals(ProbeResult.Rejected(ProbeProblem.NotAudio), AudioProbe.classify(tracks, 1_000_000L))
    }
}
