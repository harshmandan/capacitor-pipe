/*
 * Derived from PipePipeClient's SabrMediaBridge.
 *   https://codeberg.org/NullPointerException/PipePipeClient
 *   app/src/main/java/org/schabi/newpipe/player/datasource/SabrMediaBridge.java
 * Copyright (C) the PipePipe authors. Licensed under GPL-3.0-or-later.
 * Converted from Java to Kotlin.
 *
 * Modifications:
 *  - Transport removed so both the Media3 and loopback-HTTP adapters can
 *    share one instance.
 *  - awaitSegment waits for an in-flight transaction instead of throwing a
 *    pending exception for ExoPlayer to retry.
 *  - Upstream's setSelection (fed from ExoPlayer's live track selection) is
 *    replaced by tracking the last format each transport actually requested a
 *    media segment for, falling back to the bootstrap formats before any
 *    request has named that track. Without a custom MediaSource there is no
 *    selection callback to receive.
 *  - acceptInitialization logs and skips a bad init segment instead of
 *    throwing upstream's IllegalStateException, so one bad track cannot take
 *    the whole session down.
 *  - Server-requested backoff is published to the process-wide PipeSabrBackoff
 *    instead of upstream's persisted SabrBackoffCoordinator.
 *  - Upstream's seedSegments has no equivalent because it is unnecessary here:
 *    its probe runs before the bridge exists, so probe-time media must be
 *    carried across in the spec; our preparation round runs on this bridge and
 *    its consumer already caches media via cacheMedia.
 */
package ink.harsh.plugins.pipe.sabr

import android.util.Log
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrFormatTimeline
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrInfo
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrRequest
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrSession
import org.schabi.newpipe.extractor.services.youtube.sabr.media.SabrMediaSegment
import org.schabi.newpipe.extractor.services.youtube.sabr.protocol.SabrStreamingResponseReader
import java.io.IOException
import java.util.LinkedHashMap
import java.util.Objects
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import java.util.function.BooleanSupplier
import java.util.function.LongConsumer

/**
 * Turns "give me segment N of format F" into SABR transactions.
 *
 * Ported from PipePipe's `SabrMediaBridge`, with the transport removed:
 * it knows nothing about ExoPlayer or HTTP, so both the Media3 adapter and the
 * loopback server can sit on top of the same instance.
 *
 * SABR is a conversation, not a fetch: the server decides what to send based
 * on what the client claims to have buffered, so requests must be serialised and
 * the buffered state must be honest. Everything here follows from that.
 */
class PipeSabrBridge(
    private val session: YoutubeSabrSession,
    val spec: PipeSabrSpec,
    videoId: String,
) {

    /**
     * Serialises transactions. SABR state is per-session and order-dependent —
     * two concurrent requests would report each other's buffered ranges and the
     * server would skip segments neither side has.
     */
    private val transactionLock = ReentrantLock(true)

    private val ahead: MutableMap<SabrSegmentKey, SabrMediaSegment> = LinkedHashMap()
    private val nextSequences: MutableMap<YoutubeSabrInfo.Format, Int> = ConcurrentHashMap()

    @Volatile
    private var audioTimeline: YoutubeSabrFormatTimeline? = null

    @Volatile
    private var videoTimeline: YoutubeSabrFormatTimeline? = null

    @Volatile
    private var stopped = false

    /**
     * The format each track was most recently asked for, standing in for
     * upstream's ExoPlayer-fed Selection.
     *
     * The counterpart named in every request must be the format the player is
     * *actually consuming*: naming the bootstrap format after an ABR switch or
     * an audio-language pick tells the server to keep advancing a track nobody
     * wants — wrong-language segments fill the ahead cache and evict the ones
     * the player needs, and the buffered-through claims stop being honest.
     */
    @Volatile
    private var lastRequestedAudio: YoutubeSabrInfo.Format? = null

    @Volatile
    private var lastRequestedVideo: YoutubeSabrInfo.Format? = null

    /**
     * Drives each request to completion.
     *
     * Never call [YoutubeSabrSession.requestOnce] directly: a single
     * round legitimately returns no media — policy-only responses, backoff, or a
     * demand for a fresh PO token — and the coordinator is what turns those into
     * progress. Bypassing it is why playback previously stopped partway through
     * a video.
     */
    private val coordinator: PipeSabrRequestCoordinator = PipeSabrRequestCoordinator(
        session,
        PipeSabrAttestationRetry(videoId),
        // Published process-wide so a session opened during a server-requested
        // backoff waits it out instead of immediately re-requesting.
        LongConsumer { backoffMs -> PipeSabrBackoff.publish(backoffMs) },
    )

    fun hasTimelines(): Boolean = audioTimeline != null && videoTimeline != null

    fun getTimeline(format: YoutubeSabrInfo.Format): YoutubeSabrFormatTimeline {
        val timeline = if (format.isAudio) audioTimeline else videoTimeline
        if (timeline == null) {
            throw IllegalStateException("SABR timeline not ready: itag=" + format.itag)
        }
        return timeline
    }

    /**
     * Open the session and learn each track's segment timeline.
     *
     * Must complete before a manifest can be produced: durations and segment
     * counts come from the init segments this fetches, not from the player
     * response.
     */
    @Throws(IOException::class, ExtractionException::class)
    fun prepareTimelines(initialPositionMs: Long) {
        val bootstrap = ArrayList<YoutubeSabrInfo.Format>(2)
        val audio = spec.bootstrapAudio()
        val video = spec.bootstrapVideo()
        if (audio != null) {
            bootstrap.add(audio)
        }
        if (video != null) {
            bootstrap.add(video)
        }
        if (bootstrap.isEmpty()) {
            throw ExtractionException("SABR session offers no usable formats")
        }

        transactionLock.lock()
        try {
            // "Progress" here is having the timelines, not having media: the
            // preparation round exists to fetch init segments, and the
            // coordinator would otherwise keep looping on media that never comes.
            val request = YoutubeSabrRequest.preparation(
                Math.max(0L, initialPositionMs),
                bootstrap,
            )
            coordinator.request(
                request,
                SabrStreamingResponseReader.SegmentConsumer { segment ->
                    acceptSegment(segment, audio)
                },
                BooleanSupplier {
                    // Per-track readiness, not `audio == null || video == null`:
                    // that was true for single-track content before any segment
                    // arrived, so a legitimate policy-only or backoff first
                    // round (Gotcha 11) ended the loop with no timeline at all
                    // where a second round would have succeeded.
                    stopped ||
                        ((audio == null || audioTimeline != null) &&
                            (video == null || videoTimeline != null))
                },
            )
        } finally {
            transactionLock.unlock()
        }

        if (!stopped && audioTimeline == null && videoTimeline == null) {
            throw ExtractionException("SABR preparation produced no initialisation segments")
        }
    }

    /**
     * Block until the requested segment is available, driving the session as
     * needed.
     *
     * Diverges deliberately from PipePipe here. Theirs throws a "pending"
     * exception when another thread holds the lock, because ExoPlayer's
     * load-error policy retries. Our callers include an HTTP server whose
     * clients do not retry as gracefully, so we wait for the in-flight
     * transaction and re-check instead.
     */
    @Throws(IOException::class, ExtractionException::class)
    fun awaitSegment(key: SabrSegmentKey): SabrMediaSegment {
        if (!key.isInitialization()) {
            nextSequences[key.format] = key.sequenceNumber
            // A media request is the transport telling us what the player is
            // consuming — the closest thing we have to upstream's setSelection.
            if (key.format.isAudio) {
                lastRequestedAudio = key.format
            } else {
                lastRequestedVideo = key.format
            }
        }

        val deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS

        while (true) {
            throwIfStopped()

            val cached = cachedSegment(key)
            if (cached != null) {
                return cached
            }

            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) {
                throw IOException("Timed out waiting for SABR segment $key")
            }

            var locked = false
            try {
                locked = transactionLock.tryLock(remaining, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Interrupted waiting for SABR segment $key", e)
            }
            if (!locked) {
                throw IOException("Timed out acquiring SABR session for $key")
            }

            try {
                // Another thread may have fetched it while we queued.
                val delivered = cachedSegment(key)
                if (delivered != null) {
                    return delivered
                }
                requestFor(key)
            } finally {
                transactionLock.unlock()
            }
        }
    }

    @Throws(IOException::class, ExtractionException::class)
    private fun requestFor(key: SabrSegmentKey) {
        val requested = key.format
        val playerTimeMs: Long = if (key.isInitialization()) {
            0
        } else {
            Math.max(0L, getTimeline(requested).getStartMs(key.sequenceNumber))
        }

        val tracks = ArrayList<YoutubeSabrRequest.Track>(2)
        tracks.add(trackFor(requested))

        // Keep the other track in the request so the server keeps both in step;
        // a request naming only one track makes the server drop the other. The
        // counterpart is the format that track last actually played — see
        // lastRequestedAudio/Video — with bootstrap only as the pre-request
        // fallback, mirroring upstream's live Selection.
        val counterpart = if (requested.isAudio) {
            lastRequestedVideo ?: spec.bootstrapVideo()
        } else {
            lastRequestedAudio ?: spec.bootstrapAudio()
        }
        if (counterpart != null && counterpart != requested) {
            tracks.add(trackFor(counterpart))
        }

        // Progress is defined as "the segment we are waiting for arrived", not
        // "some media arrived": the server interleaves both tracks, so a round
        // that only advances the counterpart would otherwise look like success
        // and return without the caller's segment.
        coordinator.request(
            YoutubeSabrRequest.playback(playerTimeMs, 1.0f, tracks),
            SabrStreamingResponseReader.SegmentConsumer { segment ->
                acceptSegment(segment, if (requested.isAudio) requested else null)
            },
            BooleanSupplier { stopped || cachedSegment(key) != null },
        )
    }

    private fun trackFor(format: YoutubeSabrInfo.Format): YoutubeSabrRequest.Track {
        val next = nextSequences[format]
        // bufferedThrough is the last sequence we hold contiguously. Claiming
        // more than we have makes the server skip segments we still need.
        val bufferedThrough = if (next == null) 0 else Math.max(0, next - 1)
        var timeline: YoutubeSabrFormatTimeline? = null
        try {
            timeline = getTimeline(format)
        } catch (ignored: IllegalStateException) {
            // Timeline not parsed yet; the session accepts a null timeline.
        }
        return YoutubeSabrRequest.Track.of(format, timeline, bufferedThrough)
    }

    private fun acceptSegment(
        segment: SabrMediaSegment,
        requestedAudio: YoutubeSabrInfo.Format?,
    ) {
        if (stopped) {
            segment.delete()
            return
        }
        val format = formatForSegment(segment, requestedAudio)
        if (format == null) {
            // Unrecognised itag: the server sent a format we did not ask for.
            segment.delete()
            return
        }
        if (segment.header.isInitSegment) {
            acceptInitialization(format, segment)
        } else {
            cacheMedia(format, segment)
        }
    }

    private fun acceptInitialization(
        format: YoutubeSabrInfo.Format,
        segment: SabrMediaSegment,
    ) {
        try {
            val data = segment.data
            spec.putInitializationData(format, data)

            val timeline = YoutubeSabrFormatTimeline.parse(format, data)
            if (format.isAudio) {
                audioTimeline = timeline
            } else {
                videoTimeline = timeline
            }
            Log.d(
                TAG,
                "init segment parsed: itag=" + format.itag +
                    " segments=" + timeline.endSequence,
            )
        } catch (e: Exception) {
            // A bad init segment is fatal for that track, but must not take the
            // whole session down — the other track may still be usable.
            Log.w(TAG, "Invalid SABR initialisation for itag=" + format.itag, e)
        } finally {
            segment.delete()
        }
    }

    private fun cacheMedia(format: YoutubeSabrInfo.Format, segment: SabrMediaSegment) {
        val key = SabrSegmentKey.media(format, segment.header.sequenceNumber.toInt())
        synchronized(ahead) {
            if (stopped) {
                segment.delete()
                return
            }
            val previous = ahead[key]
            if (previous != null) {
                if (previous !== segment) {
                    segment.delete()
                }
                return
            }
            ahead[key] = segment

            if (ahead.size > MAX_AHEAD_SEGMENTS) {
                val iterator = ahead.values.iterator()
                val oldest = iterator.next()
                iterator.remove()
                oldest.delete()
            }
        }
    }

    private fun cachedSegment(key: SabrSegmentKey): SabrMediaSegment? {
        if (key.isInitialization()) {
            // Init data lives in the spec, not the segment cache — it is needed
            // for the whole session and must never be evicted.
            return null
        }
        synchronized(ahead) {
            return ahead[key]
        }
    }

    /**
     * Match an arriving segment to one of our formats.
     *
     * Segments identify themselves by itag plus xtags. itag alone is
     * ambiguous for multi-language audio, where one itag is served once per
     * track, so xtags decides — and when xtags is absent we only accept a
     * unique itag match.
     */
    private fun formatForSegment(
        segment: SabrMediaSegment,
        requestedAudio: YoutubeSabrInfo.Format?,
    ): YoutubeSabrInfo.Format? {
        val itag = segment.header.itag
        val xtags = segment.header.xtags

        for (video in spec.videoFormats) {
            if (video.itag == itag && (xtags == null || Objects.equals(video.xtags, xtags))) {
                return video
            }
        }

        if (requestedAudio != null && requestedAudio.itag == itag &&
            (xtags == null || Objects.equals(requestedAudio.xtags, xtags))
        ) {
            return requestedAudio
        }

        var itagOnlyMatch: YoutubeSabrInfo.Format? = null
        var itagMatches = 0
        for (audio in spec.audioFormats) {
            if (audio.itag == itag && Objects.equals(audio.xtags, xtags)) {
                return audio
            }
            if (audio.itag == itag) {
                itagOnlyMatch = audio
                itagMatches++
            }
        }
        return if (xtags == null && itagMatches == 1) itagOnlyMatch else null
    }

    /** Drop a cached segment once its bytes have been handed to the consumer. */
    fun discard(key: SabrSegmentKey) {
        val segment: SabrMediaSegment?
        synchronized(ahead) {
            segment = ahead.remove(key)
        }
        segment?.delete()
    }

    @Throws(IOException::class)
    private fun throwIfStopped() {
        if (stopped) {
            throw IOException("SABR session is closed")
        }
    }

    /** Release every cached segment. Sessions hold disk spool files, so this matters. */
    fun stop() {
        stopped = true
        synchronized(ahead) {
            for (segment in ahead.values) {
                segment.delete()
            }
            ahead.clear()
        }
    }

    companion object {
        private const val TAG = "PipeSabrBridge"

        /**
         * Cap on segments held ahead of the read position. The session has its own
         * byte budget; this bounds our side so a player that opens many formats
         * cannot grow the map without limit.
         */
        private const val MAX_AHEAD_SEGMENTS = 64

        /** How long a caller waits for an in-flight transaction before giving up. */
        private const val AWAIT_TIMEOUT_MS = 30_000L
    }
}
