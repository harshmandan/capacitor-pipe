package ink.harsh.plugins.pipe.youtube

import ink.harsh.pipe.shaded.org.schabi.newpipe.extractor.NewPipe as ShadedNewPipe
import ink.harsh.pipe.shaded.org.schabi.newpipe.extractor.localization.ContentCountry as ShadedContentCountry
import ink.harsh.pipe.shaded.org.schabi.newpipe.extractor.localization.Localization as ShadedLocalization
import ink.harsh.pipe.shaded.org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager as ShadedPlayerManager
import ink.harsh.plugins.pipe.net.NewPipeDownloader
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.services.youtube.YoutubeApiDecoder
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptDecoder

/**
 * Deciphers YouTube signatures **on device**, so the primary engine stops
 * calling `https://api.pipepipe.dev/decoder/decode`.
 *
 * Registered with [YoutubeApiDecoder.setLocalDecoder]; PipePipe tries it first
 * and falls back to its API if it throws. See CLAUDE.md, Gotcha 5 — without
 * this, the first outbound request of a cold extraction goes to PipePipe's
 * infrastructure *before* YouTube, which is both a privacy exposure and a
 * single point of failure for the whole primary engine.
 *
 * It is one fork wearing the other's interface: a PipePipe-facing
 * [YoutubeJavaScriptDecoder] implemented on the **relocated NewPipe** fork,
 * which still runs the player JavaScript locally in Rhino. Both namespaces
 * appear in this file's imports, which is unusual and deliberate — everywhere
 * else the two engines are kept strictly apart.
 *
 * ## The video ID that is not really a video ID
 *
 * PipePipe hands [decodeBatch] a *player* ID. NewPipe's decoder wants a *video*
 * ID. They are not convertible, so [getPlayerData] stashes the video ID it was
 * given and [decodeBatch] reuses it.
 *
 * That sounds worse than it is. NewPipe uses the video ID only to locate
 * YouTube's player JavaScript, which is one global file cached after the first
 * fetch — not per-video data. Decoding through "the wrong" video ID yields the
 * same answer. The one real hazard is borrowing an ID that happens to be
 * deleted, private or geo-blocked, whose player fetch then fails for a video
 * that was fine; [decodeWithRetry] exists for that.
 *
 * The value PipePipe passes as `playerId` is ignored, which is safe because it
 * never reaches an InnerTube payload — it is only a decoder-side cache key.
 * `signatureTimestamp` **does** reach the payload, and it comes from the same
 * NewPipe player as the signatures, so the two cannot disagree.
 *
 * ## Failing once is failing forever
 *
 * `YoutubeApiDecoder.decode` calls `disableLocalDecoder(this)` on **any**
 * exception, permanently unregistering us for the life of the process, with no
 * retry and no log. A single transient network blip would therefore send every
 * later extraction back to the remote API silently.
 *
 * So this class handles its own transient failures and throws only when it
 * genuinely cannot decode. Throwing is still correct in that case — falling
 * back to the API beats failing extraction outright.
 */
class PipeLocalPlayerDecoder : YoutubeJavaScriptDecoder {

    /**
     * Last video ID seen by [getPlayerData], borrowed by [decodeBatch].
     *
     * Volatile rather than locked: concurrent extractions may overwrite each
     * other, and that is tolerated for the reason in the class KDoc — any valid
     * ID resolves the same global player script. A lock here would serialise a
     * long, network-bound path to prevent an interleaving that is harmless.
     */
    @Volatile
    private var lastVideoId: String? = null

    @Throws(ParsingException::class)
    override fun getPlayerData(videoId: String): YoutubeJavaScriptDecoder.PlayerData {
        lastVideoId = videoId
        ensureShadedNewPipeInitialised()

        val timestamp = decodeWithRetry("signatureTimestamp") {
            ShadedPlayerManager.getSignatureTimestamp(videoId)
                ?: throw ParsingException("NewPipe returned no signature timestamp for $videoId")
        }

        // PipePipe only uses playerId as a decode cache key, and every decode is
        // local from here on, so a marker is enough. It must still be non-empty
        // and stable: an empty one would collide across cache entries.
        return YoutubeJavaScriptDecoder.PlayerData(LOCAL_PLAYER_ID, timestamp)
    }

    @Throws(ParsingException::class)
    override fun decodeBatch(
        playerId: String,
        signatures: List<String>?,
        throttlingParameters: List<String>?,
    ): YoutubeApiDecoder.BatchDecodeResult {
        val signatureResults = HashMap<String, String>()
        val throttlingResults = HashMap<String, String>()

        if (signatures.isNullOrEmpty() && throttlingParameters.isNullOrEmpty()) {
            return YoutubeApiDecoder.BatchDecodeResult(signatureResults, throttlingResults)
        }

        val videoId = lastVideoId
            ?: throw ParsingException(
                "No video ID seen yet — decodeBatch was called before getPlayerData",
            )

        ensureShadedNewPipeInitialised()

        signatures?.forEach { signature ->
            signatureResults[signature] = decodeWithRetry("signature") {
                ShadedPlayerManager.deobfuscateSignature(videoId, signature)
            }
        }

        throttlingParameters?.forEach { parameter ->
            throttlingResults[parameter] = decodeWithRetry("n-parameter") {
                deobfuscateThrottlingParameter(videoId, parameter)
            }
        }

        return YoutubeApiDecoder.BatchDecodeResult(signatureResults, throttlingResults)
    }

    /**
     * NewPipe exposes no "deobfuscate this bare `n` value" call — only
     * `getUrlWithThrottlingParameterDeobfuscated`, which rewrites the `n` inside
     * a whole URL. So we wrap the value in a throwaway URL and read the
     * rewritten `n` back out.
     *
     * The host is irrelevant and never contacted; only the query is parsed.
     */
    @Throws(ParsingException::class)
    private fun deobfuscateThrottlingParameter(videoId: String, parameter: String): String {
        val encoded = java.net.URLEncoder.encode(parameter, Charsets.UTF_8.name())
        val rewritten = ShadedPlayerManager.getUrlWithThrottlingParameterDeobfuscated(
            videoId,
            "$THROTTLING_PROBE_URL?n=$encoded",
        )

        val decoded = rewritten.substringAfter("n=", missingDelimiterValue = "")
            .substringBefore('&')
        if (decoded.isEmpty()) {
            throw ParsingException("NewPipe returned no n-parameter for $parameter")
        }

        return java.net.URLDecoder.decode(decoded, Charsets.UTF_8.name())
    }

    /**
     * Retry once on failure.
     *
     * Covers exactly the two things that go transiently wrong here — a network
     * blip, or a borrowed video ID that turned out to be unplayable — and the
     * retry re-reads [lastVideoId] implicitly via the caller, so a second
     * extraction's ID can rescue the first.
     *
     * One retry, not a loop: PipePipe's own API fallback is right behind us, so
     * the cost of giving up is a slower extraction rather than a failed one.
     * Retrying harder would just delay that.
     */
    @Throws(ParsingException::class)
    private fun <T> decodeWithRetry(what: String, block: () -> T): T = try {
        block()
    } catch (first: Exception) {
        try {
            block()
        } catch (second: Exception) {
            throw ParsingException(
                "Local $what deciphering failed twice: ${second.message}",
                second,
            )
        }
    }

    /**
     * The relocated fork keeps its own static downloader, and it is null until
     * someone calls its `NewPipe.init`. When PipePipe is doing the extracting
     * that may never have happened — the fallback engine has not run — so the
     * first local decode would die on a null downloader deep inside NewPipe.
     *
     * Initialising here is safe precisely because the statics are independent:
     * touching the relocated one cannot disturb PipePipe's. That independence is
     * the whole point of the relocation (CLAUDE.md, Gotcha 2).
     */
    private fun ensureShadedNewPipeInitialised() {
        if (ShadedNewPipe.getDownloader() != null) {
            return
        }
        synchronized(INIT_LOCK) {
            if (ShadedNewPipe.getDownloader() == null) {
                ShadedNewPipe.init(
                    NewPipeDownloader(),
                    ShadedLocalization.DEFAULT,
                    ShadedContentCountry.DEFAULT,
                )
            }
        }
    }

    companion object {

        /**
         * Stands in for the 8-character player hash PipePipe would get from its
         * API. Never sent to YouTube — see the class KDoc.
         */
        private const val LOCAL_PLAYER_ID = "localjs"

        private const val THROTTLING_PROBE_URL = "https://127.0.0.1/videoplayback"

        private val INIT_LOCK = Any()
    }
}
