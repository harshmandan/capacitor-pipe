package ink.harsh.plugins.pipe.sabr

import android.util.Log
import ink.harsh.plugins.pipe.engine.PipePipeEngine
import ink.harsh.plugins.pipe.youtube.PipeBotGuardMinter
import org.schabi.newpipe.extractor.services.youtube.YoutubePoTokenResult
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Function

/**
 * Supplies the Proof-of-Origin tokens SABR requires.
 *
 * The extractor never mints tokens — per upstream, it "never mints a token
 * and never decodes a pixel". A token is produced by running Google's BotGuard
 * challenge in a real JavaScript runtime, exchanging the resulting snapshot with
 * Google's GenerateIT endpoint for an integrity token (~12h lifetime), then
 * deriving a per-video token from it. The signing secret is Google's and never
 * leaves their servers, so there is no offline path.
 *
 * This class is the injection point. A host that can already mint tokens
 * pushes them in with [provide]; the extractor pulls them out through the
 * resolver installed by [install].
 *
 * Tokens are per-video. A resolver that returns the same token for every
 * video will fail attestation on all but the one it was minted for.
 */
object PipeSabrPoTokens {

    private const val TAG = "PipeSabrPoTokens"

    private val BY_VIDEO_ID: MutableMap<String, Entry> = ConcurrentHashMap()

    @Volatile
    private var installed = false

    @Volatile
    private var mintingEnabled = false

    private class Entry(val result: YoutubePoTokenResult, val expiresAtMs: Long) {

        fun isExpired(): Boolean = expiresAtMs > 0 && System.currentTimeMillis() >= expiresAtMs
    }

    /**
     * Install the resolver into the extractor's global hook. Idempotent.
     *
     * **A token is mandatory, not an optimisation.** Verified on
     * device: `YoutubeParsingHelper.createMwebPlayerRequest` declares its
     * `YoutubePoTokenResult` parameter `@Nonnull` and dereferences it
     * for `clientVersion`, `visitorData` and `poToken`. With no
     * token the MWEB request throws [NullPointerException] before any
     * network call — so SABR does not degrade gracefully, it does not start.
     */
    @JvmStatic
    @Synchronized
    fun install() {
        if (installed) {
            return
        }
        PipePipeEngine.setPoTokenResolver(Function { videoId -> resolve(videoId) })
        installed = true
    }

    private fun resolve(videoId: String?): YoutubePoTokenResult? {
        if (videoId == null) {
            return null
        }

        // A host-supplied token wins: if the app went to the trouble of minting
        // one, do not second-guess it with our own WebView.
        val entry = BY_VIDEO_ID[videoId]
        if (entry != null && !entry.isExpired()) {
            return entry.result
        }
        if (entry != null) {
            BY_VIDEO_ID.remove(videoId)
            Log.i(TAG, "Host-supplied PO token for " + videoId + " expired; minting our own")
        }

        if (!mintingEnabled) {
            // Returning null means the caller is about to hit an NPE inside
            // createMwebPlayerRequest. Log loudly: the stack trace alone points
            // at the extractor and gives no hint a token was the problem.
            Log.e(
                TAG,
                "No PO token for " + videoId + " and minting is not initialised. " +
                    "The MWEB/SABR player request requires one and will fail with " +
                    "NullPointerException. Call providePoToken() or enable minting.",
            )
            return null
        }

        return try {
            // Blocking, and slow on a cold start: it loads YouTube's home page,
            // runs the BotGuard challenge in a WebView and exchanges the
            // snapshot for an integrity token. Subsequent videos reuse that for
            // ~12h. Only ever called from a background thread.
            PipeBotGuardMinter.getPlayerPoToken(videoId)
        } catch (e: Throwable) {
            Log.e(TAG, "Could not mint a PO token for " + videoId, e)
            null
        }
    }

    /**
     * Enable BotGuard minting.
     *
     * Without this, only host-supplied tokens work. Requires a Context
     * because minting needs a WebView.
     */
    @JvmStatic
    @Synchronized
    fun enableMinting(context: android.content.Context) {
        if (mintingEnabled) {
            return
        }
        PipeBotGuardMinter.initialize(context.applicationContext)
        mintingEnabled = true
        install()
    }

    /**
     * Mint a token for an attestation retry, bypassing any cached one.
     *
     * The server rejected what we last sent, so a host-supplied token is not
     * reused here — only a freshly derived one can satisfy the challenge.
     */
    @Throws(Exception::class)
    internal fun mintForRetry(videoId: String): YoutubePoTokenResult {
        BY_VIDEO_ID.remove(videoId)
        if (!mintingEnabled) {
            throw IllegalStateException(
                "SABR attestation was rejected and minting is not enabled, so no fresh " +
                    "PO token can be produced for " + videoId,
            )
        }
        return PipeBotGuardMinter.getPlayerPoToken(videoId)
    }

    /**
     * Discard the current integrity token and mint a fresh one.
     *
     * Call when the SABR session reports a protection status the current
     * token cannot satisfy — the token may have been revoked before its stated
     * expiry.
     */
    @JvmStatic
    fun invalidateMinter() {
        if (mintingEnabled) {
            PipeBotGuardMinter.invalidate()
        }
    }

    /**
     * Supply a token for one video.
     *
     * @param ttlMs lifetime; pass 0 for no expiry. Integrity tokens last about
     *              12 hours, so a derived token should not outlive that.
     */
    @JvmStatic
    fun provide(
        videoId: String,
        visitorData: String,
        clientVersion: String,
        playerPoToken: String,
        ttlMs: Long,
    ) {
        val expiresAt = if (ttlMs > 0) System.currentTimeMillis() + ttlMs else 0
        BY_VIDEO_ID[videoId] = Entry(
            YoutubePoTokenResult(visitorData, clientVersion, playerPoToken),
            expiresAt,
        )
        install()
    }

    @JvmStatic
    fun forget(videoId: String) {
        BY_VIDEO_ID.remove(videoId)
    }

    @JvmStatic
    fun clear() {
        BY_VIDEO_ID.clear()
    }

    /** Whether a usable token is held for this video. */
    @JvmStatic
    fun has(videoId: String): Boolean {
        val entry = BY_VIDEO_ID[videoId]
        return entry != null && !entry.isExpired()
    }
}
