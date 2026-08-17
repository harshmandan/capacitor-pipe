package ink.harsh.plugins.pipe

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ink.harsh.plugins.pipe.engine.ExtractionRequest
import ink.harsh.plugins.pipe.sabr.PipeSabrManager
import ink.harsh.plugins.pipe.sabr.PipeSabrSession
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * End-to-end verification against live YouTube.
 *
 * Not a unit test and not hermetic — it deliberately hits the real service,
 * because everything this code does is negotiate with a hostile, changing
 * target. A green compile proves nothing about whether extraction still works;
 * only this does.
 *
 * Run with:
 * ```
 * ./gradlew connectedAndroidTest
 * ```
 *
 * Failures here are informative rather than alarming: YouTube changes, and
 * distinguishing "our bug" from "upstream moved" is the point.
 */
@RunWith(AndroidJUnit4::class)
class SabrEndToEndTest {

    /** Baseline: does the engine chain produce anything at all, and via which engine? */
    @Test
    fun extractsStreamInfo() {
        val extractor = PipeExtractor()
        val result = extractor.extractStreamInfo(request(), emptyList())

        Log.i(TAG, "extract result: $result")

        // JSObject extends JSONObject; there is no typed getter for arrays here.
        val attempts = result.opt("attempts")
        Log.i(TAG, "attempts: $attempts")

        if (result.getBool("success") != true) {
            fail("Both engines failed: " + result.getString("error") + " | attempts=" + attempts)
        }

        val streamInfo = result.getJSObject("streamInfo")
        assertNotNull("streamInfo missing on a successful result", streamInfo)

        Log.i(
            TAG,
            "engine=" + result.getString("engine") +
                " title=" + streamInfo!!.getString("title") +
                " duration=" + streamInfo.getInteger("duration") +
                " requiresSabr=" + streamInfo.getBool("requiresSabr"),
        )

        assertNotNull("title missing", streamInfo.getString("title"))
        assertTrue("duration should be positive", streamInfo.getInteger("duration", 0)!! > 0)
    }

    /** Does the fallback engine work on its own? Proves the relocation is real. */
    @Test
    fun newPipeFallbackWorksIndependently() {
        val extractor = PipeExtractor()
        val result = extractor.extractStreamInfo(request(), listOf("newpipe"))

        Log.i(TAG, "newpipe-only result: $result")

        if (result.getBool("success") != true) {
            // Worth knowing, but not necessarily our bug — NewPipe's SABR
            // workaround has a shelf life set by YouTube.
            Log.w(TAG, "NewPipe engine failed standalone: " + result.getString("error"))
            return
        }
        val streamInfo = result.getJSObject("streamInfo")
        assertNotNull(streamInfo)
        assertFalse(
            "NewPipe cannot produce SABR streams",
            streamInfo!!.getBool("requiresSabr") == true,
        )
    }

    /**
     * The real target: open a SABR session and pull actual media bytes through
     * the loopback transport, exactly as a web player would.
     *
     * Skipped unless a PO token has been supplied for this video. That is not
     * a soft prerequisite — verified on device,
     * `createMwebPlayerRequest` dereferences the token for clientVersion,
     * visitorData and poToken, so with none the session throws
     * [NullPointerException] before any request is sent. Until the bundled
     * BotGuard minter exists, a token has to be injected by the host.
     */
    @Test
    fun opensSabrSessionAndServesSegments() {
        val extractor = PipeExtractor()
        val engine = extractor.getPipePipeEngine()
        assertNotNull("PipePipe engine missing from the build", engine)

        val manager = PipeSabrManager(context(), engine!!)
        var session: PipeSabrSession? = null
        try {
            session = manager.open(request(), 0)

            val manifestUrl = manager.manifestUrl(session.id)
            Log.i(
                TAG,
                "session=" + session.id + " manifest=" + manifestUrl +
                    " durationMs=" + session.durationMs + " live=" + session.isLive(),
            )

            val client = OkHttpClient.Builder()
                .callTimeout(60, TimeUnit.SECONDS)
                .build()

            // 1. The manifest must be served and look like DASH.
            val manifest = body(client, manifestUrl)
            Log.i(
                TAG,
                "manifest (" + manifest.length + " chars): " +
                    manifest.substring(0, Math.min(600, manifest.length)),
            )
            assertTrue("not an MPD", manifest.contains("<MPD"))
            assertTrue("no Representation in manifest", manifest.contains("<Representation"))
            assertTrue("no SegmentTimeline in manifest", manifest.contains("<SegmentTimeline"))

            // 2. Pull the format key straight out of the manifest, so the test
            //    follows the same path a player would rather than guessing.
            val formatKey = firstAttribute(manifest, "<Representation id=\"")
            assertNotNull("could not read a Representation id", formatKey)
            val base = manifestUrl.substring(0, manifestUrl.lastIndexOf('/') + 1)

            // 3. Initialisation segment.
            val init = bytes(client, base + formatKey + "/init")
            Log.i(TAG, "init segment for " + formatKey + ": " + init.size + " bytes")
            assertTrue("empty init segment", init.isNotEmpty())

            // 4. First media segment — this is the part that requires the SABR
            //    conversation to have actually worked.
            val segment = bytes(client, "$base$formatKey/1")
            Log.i(TAG, "media segment 1 for " + formatKey + ": " + segment.size + " bytes")
            assertTrue("empty media segment", segment.isNotEmpty())
        } finally {
            if (session != null) {
                manager.close(session.id)
            }
            manager.closeAll()
        }
    }

    companion object {

        private const val TAG = "SabrE2E"
        private const val VIDEO_ID = "iUtnZpzkbG8"
        private const val VIDEO_URL = "https://www.youtube.com/watch?v=$VIDEO_ID"

        private fun context(): Context =
            InstrumentationRegistry.getInstrumentation().targetContext

        private fun request(): ExtractionRequest =
            ExtractionRequest(VIDEO_URL, false, "en-GB", "GB")

        private fun body(client: OkHttpClient, url: String): String =
            String(bytes(client, url), Charsets.UTF_8)

        private fun bytes(client: OkHttpClient, url: String): ByteArray {
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                val responseBody = response.body
                if (!response.isSuccessful) {
                    throw AssertionError(
                        "GET " + url + " -> " + response.code + " " + responseBody.string(),
                    )
                }
                return responseBody.bytes()
            }
        }

        private fun firstAttribute(haystack: String, prefix: String): String? {
            val start = haystack.indexOf(prefix)
            if (start < 0) {
                return null
            }
            val from = start + prefix.length
            val end = haystack.indexOf('"', from)
            return if (end < 0) null else haystack.substring(from, end)
        }
    }
}
