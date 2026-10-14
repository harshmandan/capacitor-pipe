package ink.harsh.plugins.pipe

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ink.harsh.plugins.pipe.engine.ExtractionRequest
import ink.harsh.plugins.pipe.sabr.PipeSabrExtractionCache
import ink.harsh.plugins.pipe.sabr.PipeSabrManager
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * A quality switch reopens the same video at another height. The reopen must
 * reuse the first session's extraction, and the session it builds from that
 * reused extraction must still serve real media at the new height.
 *
 * Live network: two extractions, three session opens.
 */
@RunWith(AndroidJUnit4::class)
class SabrQualitySwitchTest {

    @Test
    fun reopenReusesExtractionAndServesTheNewHeight() {
        PipeSabrExtractionCache.clear()
        val engine = PipeExtractor().getPipePipeEngine()
        assertNotNull("PipePipe engine missing", engine)
        val manager = PipeSabrManager(InstrumentationRegistry.getInstrumentation().targetContext, engine!!)
        val client = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build()

        try {
            var started = System.nanoTime()
            val first = manager.open(REQUEST, 0, 720)
            val freshMs = (System.nanoTime() - started) / 1_000_000

            started = System.nanoTime()
            val second = manager.open(REQUEST, 30_000, 360)
            val reusedMs = (System.nanoTime() - started) / 1_000_000

            // The first open also warms the BotGuard minter, so it flatters the
            // reuse. A fresh open with the minter already warm is the fair baseline.
            PipeSabrExtractionCache.clear()
            started = System.nanoTime()
            val third = manager.open(REQUEST, 30_000, 360)
            val warmFreshMs = (System.nanoTime() - started) / 1_000_000
            manager.close(third.id)

            Log.i(TAG, "cold fresh open ${freshMs}ms, warm fresh open ${warmFreshMs}ms, reused open ${reusedMs}ms")

            assertSame("the reopen should reuse the first extraction", first.info, second.info)
            assertEquals(720, videoHeight(first.manifest))
            assertTrue("expected ≤360p, got ${videoHeight(second.manifest)}", videoHeight(second.manifest) <= 360)

            val rep = VIDEO_REP.find(second.manifest)!!.groupValues[1]
            val url = manager.manifestUrl(second.id).substringBeforeLast('/') + "/$rep/5"
            val bytes = client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                assertTrue("GET $url -> ${response.code}", response.isSuccessful)
                response.body.bytes().size
            }
            Log.i(TAG, "reused session served $rep segment 5: $bytes bytes")
            assertTrue(bytes > 0)

            manager.close(first.id)
            manager.close(second.id)
        } finally {
            manager.closeAll()
        }
    }

    companion object {
        private const val TAG = "SabrQualitySwitch"

        // SabrPlaybackBehaviourTest's video, so this adds no new id to the request history.
        private val REQUEST = ExtractionRequest("https://www.youtube.com/watch?v=iUtnZpzkbG8", false, "en-GB", "GB")

        private val VIDEO_REP = Regex("<Representation id=\"(v[^\"]+)\"[^>]*height=\"(\\d+)\"")

        private fun videoHeight(manifest: String): Int = VIDEO_REP.find(manifest)!!.groupValues[2].toInt()
    }
}
