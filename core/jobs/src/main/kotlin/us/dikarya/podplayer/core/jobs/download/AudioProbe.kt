/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.jobs.download

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ForwardingTrackOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

sealed interface ProbeResult {
    /** Audio Media3 can play, [durationMs] long. [mimeType] is the audio track's type as Media3 read it. */
    data class Audio(val durationMs: Long, val mimeType: String?) : ProbeResult

    data class Rejected(val problem: ProbeProblem) : ProbeResult
}

enum class ProbeProblem {
    /** The file couldn't be opened or read. */
    Unreadable,

    /** No extractor recognised the format. */
    Unrecognized,

    /** It has a real video track (SPEC §9: video is refused in M1). */
    Video,

    /** Recognised, but there's no audio in it (an image, subtitles, ...). */
    NotAudio,

    /** Audio, but its length can't be read: damaged or truncated. */
    NoDuration,

    /** The time limit ran out first. */
    TooSlow,
}

/** One track as an extractor announced it. */
internal data class ProbedTrack(val type: Int, val sampleMimeType: String?)

/**
 * Checks a finished download with Media3's own extractors (SPEC §9, CLAUDE.md): pure JVM code, no platform
 * media parser, nothing decoded. A file counts when an extractor recognises it, it has an audio track and no
 * video, and its duration can be read; the MIME type and size the feed declared don't matter.
 *
 * The file is hostile input: every extractor failure is a [ProbeResult.Rejected], never an exception, and
 * the whole probe has a time limit. Reading normally stops at the headers; only when the container gives no
 * duration does it read on (to the end at most) and use the last sample's time.
 */
@OptIn(markerClass = [UnstableApi::class])
class AudioProbe(
    private val timeLimit: Duration = 20.seconds,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    fun probe(file: File): ProbeResult {
        val deadline = nanoTime() + timeLimit.inWholeNanoseconds
        return try {
            RandomAccessFile(file, "r").use { raf -> probe(raf, deadline) }
        } catch (_: TooSlow) {
            ProbeResult.Rejected(ProbeProblem.TooSlow)
        } catch (_: IOException) {
            ProbeResult.Rejected(ProbeProblem.Unreadable)
        } catch (_: RuntimeException) {
            // Extractors throw assorted runtime exceptions on hostile input; none of them is our bug.
            ProbeResult.Rejected(ProbeProblem.Unrecognized)
        }
    }

    private fun probe(raf: RandomAccessFile, deadline: Long): ProbeResult {
        val length = raf.length()
        if (length == 0L) return ProbeResult.Rejected(ProbeProblem.Unrecognized)
        val extractor = sniff(raf, length, deadline) ?: return ProbeResult.Rejected(ProbeProblem.Unrecognized)
        try {
            val output = Output()
            extractor.init(output)
            var input = inputAt(raf, 0, length)
            val seek = PositionHolder()
            while (!output.complete()) {
                checkTime(deadline)
                val result = try {
                    extractor.read(input, seek)
                } catch (_: EOFException) {
                    Extractor.RESULT_END_OF_INPUT
                } catch (_: IOException) {
                    // ParserException and friends: the file is broken.
                    return ProbeResult.Rejected(if (output.tracks.isEmpty()) ProbeProblem.Unrecognized else ProbeProblem.NoDuration)
                }
                when (result) {
                    Extractor.RESULT_CONTINUE -> Unit
                    Extractor.RESULT_SEEK -> {
                        if (seek.position !in 0..length) return ProbeResult.Rejected(ProbeProblem.NoDuration)
                        input = inputAt(raf, seek.position, length)
                    }
                    else -> break
                }
            }
            if (output.tracks.isEmpty()) return ProbeResult.Rejected(ProbeProblem.Unrecognized)
            return classify(output.probedTracks(), output.durationUs())
        } finally {
            extractor.release()
        }
    }

    /** The first extractor that recognises the file, or null. Each one gets a fresh input from the start. */
    private fun sniff(raf: RandomAccessFile, length: Long, deadline: Long): Extractor? {
        val candidates = DefaultExtractorsFactory()
            // MP3, ADTS and AMR without a seek table still get a duration, from their bit rate.
            .setConstantBitrateSeekingEnabled(true)
            .createExtractors()
        var chosen: Extractor? = null
        for (candidate in candidates) {
            if (chosen == null) {
                checkTime(deadline)
                val recognised = try {
                    candidate.sniff(inputAt(raf, 0, length))
                } catch (_: IOException) {
                    false
                } catch (_: RuntimeException) {
                    false
                }
                if (recognised) {
                    chosen = candidate
                    continue
                }
            }
            candidate.release()
        }
        return chosen
    }

    private fun inputAt(raf: RandomAccessFile, position: Long, length: Long): ExtractorInput {
        raf.seek(position)
        val reader = DataReader { buffer, offset, count ->
            if (count == 0) 0 else raf.read(buffer, offset, count).let { if (it < 0) C.RESULT_END_OF_INPUT else it }
        }
        return DefaultExtractorInput(reader, position, length)
    }

    private fun checkTime(deadline: Long) {
        if (nanoTime() - deadline > 0) throw TooSlow()
    }

    private class TooSlow : RuntimeException()

    /** Collects what the extractor announces; sample data is skipped, only sample times are kept. */
    private class Output : ExtractorOutput {
        val tracks = mutableListOf<Track>()
        var tracksEnded = false
        var seekMap: SeekMap? = null

        class Track(val type: Int) : ForwardingTrackOutput(DiscardingTrackOutput()) {
            var format: Format? = null
            var lastSampleUs = C.TIME_UNSET

            override fun format(format: Format) {
                this.format = format
            }

            override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
                if (timeUs > lastSampleUs || lastSampleUs == C.TIME_UNSET) lastSampleUs = timeUs
                super.sampleMetadata(timeUs, flags, size, offset, cryptoData)
            }
        }

        override fun track(id: Int, type: Int): TrackOutput {
            if (tracks.size >= MAX_TRACKS) return DiscardingTrackOutput()
            return Track(type).also { tracks += it }
        }

        override fun endTracks() {
            tracksEnded = true
        }

        override fun seekMap(seekMap: SeekMap) {
            this.seekMap = seekMap
        }

        fun headerDurationUs(): Long = seekMap?.durationUs?.takeIf { it != C.TIME_UNSET && it > 0 } ?: C.TIME_UNSET

        /** Done once the tracks and their formats are known and the container states a duration. */
        fun complete(): Boolean =
            tracksEnded && tracks.isNotEmpty() && tracks.all { it.format != null } && headerDurationUs() != C.TIME_UNSET

        fun durationUs(): Long {
            val header = headerDurationUs()
            if (header != C.TIME_UNSET) return header
            return tracks.filter { it.type == C.TRACK_TYPE_AUDIO }.maxOfOrNull { it.lastSampleUs } ?: C.TIME_UNSET
        }

        fun probedTracks(): List<ProbedTrack> = tracks.map { ProbedTrack(it.type, it.format?.sampleMimeType) }

        private companion object {
            const val MAX_TRACKS = 64
        }
    }

    companion object {
        /**
         * The verdict on what the extractor found. A video track whose samples are JPEG pictures is chapter
         * art (enhanced podcasts), not video.
         */
        internal fun classify(tracks: List<ProbedTrack>, durationUs: Long): ProbeResult {
            val video = tracks.any { it.type == C.TRACK_TYPE_VIDEO && it.sampleMimeType != MimeTypes.VIDEO_MJPEG }
            if (video) return ProbeResult.Rejected(ProbeProblem.Video)
            val audio = tracks.firstOrNull { it.type == C.TRACK_TYPE_AUDIO } ?: return ProbeResult.Rejected(ProbeProblem.NotAudio)
            if (durationUs == C.TIME_UNSET || durationUs <= 0) return ProbeResult.Rejected(ProbeProblem.NoDuration)
            return ProbeResult.Audio(durationUs / 1000, audio.sampleMimeType)
        }
    }
}
