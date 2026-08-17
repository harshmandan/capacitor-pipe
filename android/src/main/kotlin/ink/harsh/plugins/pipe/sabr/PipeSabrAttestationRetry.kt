/*
 * Ported from PipePipeClient's SabrAttestationRetryHandler.
 *   https://codeberg.org/NullPointerException/PipePipeClient
 *   app/src/main/java/org/schabi/newpipe/youtube/SabrAttestationRetryHandler.java
 * Copyright (C) the PipePipe authors. Licensed under GPL-3.0-or-later.
 * Converted from Java to Kotlin; behaviour unchanged.
 *
 * Modification: tokens come from PipeSabrPoTokens rather than the provider
 * singleton directly, so a host-supplied token is honoured where one exists.
 */
package ink.harsh.plugins.pipe.sabr

import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrSession
import org.schabi.newpipe.extractor.services.youtube.sabr.exception.SabrAttestationException
import java.util.Base64

/**
 * PO-token recovery budget for one SABR session.
 *
 * A token minted at session start does not stay valid for the whole session:
 * the server can reject it mid-stream and demand a fresh, content-bound one.
 * Without this the session simply stops producing media partway through —
 * which is exactly how it failed on device, dying around segment 12.
 */
class PipeSabrAttestationRetry(private val videoId: String) {

    private var retriesRemaining = MAX_RETRIES

    /** Mint and inject a token for the next attempt, or give up once the budget is spent. */
    @Synchronized
    @Throws(SabrAttestationException::class)
    fun prepareRetry(
        session: YoutubeSabrSession,
        rejectedTokenError: SabrAttestationException,
    ) {
        if (retriesRemaining == 0) {
            // The integrity token itself is suspect by this point, not just the
            // derived one — force a full re-mint for whatever comes next.
            PipeSabrPoTokens.invalidateMinter()
            throw SabrAttestationException(
                "SABR PO token was rejected after " + MAX_RETRIES +
                    " attestation recovery retries for video=" + videoId,
                rejectedTokenError,
            )
        }

        val attempt = MAX_RETRIES - retriesRemaining + 1
        retriesRemaining--

        try {
            val result = PipeSabrPoTokens.mintForRetry(videoId)
            val token = Base64.getUrlDecoder().decode(result.playerPoToken)
            if (token.isEmpty()) {
                throw IllegalArgumentException("decoded token is empty")
            }
            session.setPoToken(token)
        } catch (error: Exception) {
            throw SabrAttestationException(
                "SABR PO token recovery failed on retry " + attempt + " of " + MAX_RETRIES +
                    " for video=" + videoId + ": " + error.message,
                error,
            )
        }
    }

    /** Media arriving proves the current token works, so the budget resets. */
    @Synchronized
    fun onMediaReceived() {
        retriesRemaining = MAX_RETRIES
    }

    companion object {
        private const val MAX_RETRIES = 3
    }
}
