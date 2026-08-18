package ink.harsh.plugins.pipe

import androidx.test.ext.junit.runners.AndroidJUnit4
import ink.harsh.plugins.pipe.engine.ExtractionRequest
import ink.harsh.plugins.pipe.engine.PipePipeEngine
import ink.harsh.plugins.pipe.net.PipePipeDownloader
import ink.harsh.plugins.pipe.youtube.PipeLocalPlayerDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.CancellableCall
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.YoutubeApiDecoder
import java.util.Collections

/**
 * Proves the primary engine deciphers on device instead of calling
 * `api.pipepipe.dev`.
 *
 * The load-bearing assertion is [extractionNeverContactsPipePipesApi]. A test
 * that merely checks the decoder returns plausible values would pass just as
 * happily with the remote path doing the work — the whole point of
 * [PipeLocalPlayerDecoder] is *which host gets the request*, so that is what
 * gets asserted, by recording every URL the extractor asks for.
 *
 * Instrumented and network-dependent: it deciphers a real YouTube player. A
 * failure here is as likely to mean "YouTube changed" as "we broke it" — check
 * whether the NewPipe fallback still extracts before assuming a regression.
 *
 * ```
 * ./gradlew connectedAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=\
 * ink.harsh.plugins.pipe.LocalPlayerDecoderTest
 * ```
 */
@RunWith(AndroidJUnit4::class)
class LocalPlayerDecoderTest {

    /**
     * Records every URL the extractor reaches, then delegates to the real
     * downloader.
     *
     * `executeAsync` has to be overridden as well — it is abstract in PipePipe's
     * fork and returns an okhttp-backed `CancellableCall`, which is exactly the
     * leak documented in CLAUDE.md, Gotcha 3. Delegating keeps that coupling
     * inside the real downloader where it already lives.
     */
    private class RecordingDownloader : Downloader() {
        private val delegate = PipePipeDownloader()
        val urls: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override fun execute(request: Request): Response {
            urls.add(request.url())
            return delegate.execute(request)
        }

        override fun executeAsync(request: Request, callback: AsyncCallback): CancellableCall {
            urls.add(request.url())
            return delegate.executeAsync(request, callback)
        }
    }

    @Before
    fun resetDecoderState() {
        // The decode cache is global and static, so a value cached by an earlier
        // test would let a later one pass without deciphering anything at all.
        YoutubeApiDecoder.setLocalDecoder(null)
    }

    @Test
    fun decodeBatchRefusesBeforeAnyVideoIdIsKnown() {
        val decoder = PipeLocalPlayerDecoder()

        // decodeBatch borrows the video ID that getPlayerData stashed. Called
        // first, it has nothing to borrow — and must say so rather than guess at
        // an ID, which would decipher against the wrong player.
        val threw = try {
            decoder.decodeBatch("someplayer", listOf("abc"), null)
            false
        } catch (expected: Exception) {
            true
        }

        assertTrue("decodeBatch silently accepted an unknown video ID", threw)
    }

    @Test
    fun emptyBatchIsNotAnError() {
        val decoder = PipeLocalPlayerDecoder()
        val result = decoder.decodeBatch("someplayer", emptyList(), emptyList())

        // Nothing to decode is a legitimate call, not a failure. Throwing here
        // would trip disableLocalDecoder and permanently revert to the API.
        assertEquals(0, result.signatures.size)
        assertEquals(0, result.nParameters.size)
    }

    @Test
    fun getPlayerDataReturnsARealSignatureTimestamp() {
        NewPipe.init(PipePipeDownloader(), Localization.DEFAULT, ContentCountry.DEFAULT)

        val data = PipeLocalPlayerDecoder().getPlayerData(VIDEO_ID)

        // The timestamp goes into the InnerTube payload; a zero would be
        // accepted by the compiler and rejected by YouTube.
        assertTrue("implausible signature timestamp: ${data.signatureTimestamp}", data.signatureTimestamp > 10_000)
        assertTrue("empty player id", data.playerId.isNotEmpty())
    }

    @Test
    fun extractionNeverContactsPipePipesApi() {
        val recorder = RecordingDownloader()
        NewPipe.init(recorder, Localization.DEFAULT, ContentCountry.DEFAULT)
        YoutubeApiDecoder.setLocalDecoder(PipeLocalPlayerDecoder())

        val engine = PipePipeEngine()
        val info = engine.extractStreamInfo(
            ExtractionRequest(
                videoUrl = "https://www.youtube.com/watch?v=$VIDEO_ID",
                sponsorBlock = false,
                localization = null,
                contentCountry = null,
            ),
        )

        assertTrue("extraction returned no title", info.getString("title").orEmpty().isNotEmpty())

        val leaked = recorder.urls.filter { it.contains("api.pipepipe.dev") }
        assertTrue(
            "extraction called PipePipe's decoder API despite a local decoder: $leaked",
            leaked.isEmpty(),
        )

        // Sanity check on the recorder itself: an extraction that reached no
        // YouTube host at all would make the assertion above vacuously true.
        assertTrue(
            "recorder saw no YouTube traffic — the assertion above proves nothing",
            recorder.urls.any { it.contains("youtube.com") || it.contains("googlevideo.com") },
        )
    }

    private companion object {
        /** "Me at the zoo" — the oldest video on YouTube, and the least likely to be taken down. */
        const val VIDEO_ID = "jNQXAC9IVRw"
    }
}
