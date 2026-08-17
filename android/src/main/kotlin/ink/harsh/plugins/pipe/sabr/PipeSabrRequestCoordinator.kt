/*
 * Ported from PipePipeClient's SabrRequestCoordinator.
 *   https://codeberg.org/NullPointerException/PipePipeClient
 *   app/src/main/java/org/schabi/newpipe/youtube/SabrRequestCoordinator.java
 * Copyright (C) the PipePipe authors. Licensed under GPL-3.0-or-later.
 * Converted from Java to Kotlin; behaviour unchanged.
 *
 * Drives one logical SABR request until it actually makes progress.
 *
 * A single requestOnce() is not a request — the server legitimately answers
 * with policy-only rounds, backoff instructions, or a demand for a fresh PO
 * token, none of which carry media. Treating one call as one request is why
 * playback died partway through and seeks hung: everything after the first few
 * segments needs this loop.
 *
 * Modification: renamed and repackaged only.
 */
package ink.harsh.plugins.pipe.sabr

import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrRequest
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrSession
import org.schabi.newpipe.extractor.services.youtube.sabr.exception.SabrAttestationException
import org.schabi.newpipe.extractor.services.youtube.sabr.protocol.SabrStreamingResponseReader
import java.io.IOException
import java.io.InterruptedIOException
import java.util.Objects
import java.util.function.BooleanSupplier
import java.util.function.LongConsumer

/** Coordinates protocol-level SABR request recovery for all client consumers. */
class PipeSabrRequestCoordinator(
    session: YoutubeSabrSession,
    attestationRetryHandler: PipeSabrAttestationRetry,
    backoffObserver: LongConsumer?,
) {
    private val session: YoutubeSabrSession = Objects.requireNonNull(session, "session")
    private val attestationRetryHandler: PipeSabrAttestationRetry =
        Objects.requireNonNull(attestationRetryHandler, "attestationRetryHandler")
    private val backoffObserver: LongConsumer = backoffObserver ?: LongConsumer { }
    private var backoffDeadlineNs: Long = 0
    private var noProgressDeadlineNs: Long = 0

    /** Executes a logical request until it produces media. */
    @Throws(IOException::class, ExtractionException::class)
    fun request(
        request: YoutubeSabrRequest,
        consumer: SabrStreamingResponseReader.SegmentConsumer,
    ): YoutubeSabrSession.RequestResult = request(request, consumer, null)

    /**
     * Executes a logical request until it produces progress.
     *
     * `progressChecker` reports whether the response advanced the caller's state;
     * when null, any delivered media segment counts as progress. Server-requested backoff
     * and unproductive responses are tracked separately: backoff freezes accumulate across
     * consecutive backoff requests until a response delivers progress, while unproductive
     * responses accumulate until progress is made. Both are bounded by a continuous 30s
     * budget.
     */
    @Throws(IOException::class, ExtractionException::class)
    fun request(
        request: YoutubeSabrRequest,
        consumer: SabrStreamingResponseReader.SegmentConsumer,
        progressChecker: BooleanSupplier?,
    ): YoutubeSabrSession.RequestResult {
        Objects.requireNonNull(request, "request")
        Objects.requireNonNull(consumer, "consumer")
        while (true) {
            awaitBackoff()
            val result: YoutubeSabrSession.RequestResult
            try {
                result = session.requestOnce(request) { segment ->
                    attestationRetryHandler.onMediaReceived()
                    consumer.accept(segment)
                }
            } catch (error: SabrAttestationException) {
                attestationRetryHandler.prepareRetry(session, error)
                continue
            }

            val progress = if (progressChecker != null) {
                progressChecker.asBoolean
            } else {
                result.segmentCount > 0
            }
            val backoffMs: Long = result.backoffMs.toLong()
            backoffObserver.accept(backoffMs)
            updateBackoffEpisode(progress, backoffMs)
            updateNoProgressEpisode(progress, backoffMs)
            if (progress) {
                return result
            }
            if (result.isDeferred) {
                continue
            }
            sleep(EMPTY_RESPONSE_RETRY_MS)
        }
    }

    @Throws(IOException::class)
    private fun awaitBackoff() {
        while (true) {
            val remainingMs = session.backoffRemainingMs
            backoffObserver.accept(remainingMs)
            if (remainingMs <= 0) {
                return
            }
            throwIfBudgetExceeded(
                backoffDeadlineNs,
                remainingMs,
                "SABR continuous backoff exceeded " + MAX_CONTINUOUS_BACKOFF_MS + "ms",
            )
            sleep(Math.min(remainingMs, EMPTY_RESPONSE_RETRY_MS))
        }
    }

    @Throws(IOException::class)
    private fun updateBackoffEpisode(progress: Boolean, backoffMs: Long) {
        if (progress) {
            backoffDeadlineNs = 0
        }
        if (backoffMs <= 0) {
            return
        }
        if (backoffDeadlineNs == 0L) {
            backoffDeadlineNs = System.nanoTime() + MAX_CONTINUOUS_BACKOFF_MS * 1_000_000L
            return
        }
        throwIfBudgetExceeded(
            backoffDeadlineNs,
            backoffMs,
            "SABR continuous backoff exceeded " + MAX_CONTINUOUS_BACKOFF_MS + "ms",
        )
    }

    @Throws(IOException::class)
    private fun updateNoProgressEpisode(progress: Boolean, backoffMs: Long) {
        if (progress) {
            noProgressDeadlineNs = 0
            return
        }
        if (noProgressDeadlineNs == 0L) {
            noProgressDeadlineNs = System.nanoTime() + MAX_CONTINUOUS_NO_PROGRESS_MS * 1_000_000L
            return
        }
        throwIfBudgetExceeded(
            noProgressDeadlineNs,
            if (backoffMs > 0) backoffMs else EMPTY_RESPONSE_RETRY_MS,
            "SABR continuous no-progress exceeded " + MAX_CONTINUOUS_NO_PROGRESS_MS + "ms",
        )
    }

    companion object {
        private const val EMPTY_RESPONSE_RETRY_MS = 250L
        private const val MAX_CONTINUOUS_BACKOFF_MS = 30_000L
        private const val MAX_CONTINUOUS_NO_PROGRESS_MS = 30_000L

        @Throws(IOException::class)
        private fun throwIfBudgetExceeded(deadlineNs: Long, waitMs: Long, message: String) {
            if (deadlineNs != 0L && waitMs * 1_000_000L > deadlineNs - System.nanoTime()) {
                throw IOException(message)
            }
        }

        @Throws(IOException::class)
        private fun sleep(milliseconds: Long) {
            try {
                Thread.sleep(milliseconds)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                val interrupted = InterruptedIOException("Interrupted during SABR request")
                interrupted.initCause(error)
                throw interrupted
            }
        }
    }
}
