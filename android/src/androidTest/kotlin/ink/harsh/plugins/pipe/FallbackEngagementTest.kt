package ink.harsh.plugins.pipe

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.getcapacitor.JSObject
import ink.harsh.plugins.pipe.engine.ExtractionEngine
import ink.harsh.plugins.pipe.engine.ExtractionRequest
import ink.harsh.plugins.pipe.engine.NewPipeEngine
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import java.io.IOException

/**
 * Does the chain actually fall through when the primary fails?
 *
 * `newPipeFallbackWorksIndependently` in [SabrEndToEndTest] proves NewPipe
 * answers when asked directly. That says nothing about the chain: a primary
 * failure has to be caught, recorded in `attempts`, and passed on — or, for a
 * failure that describes the content, deliberately not passed on. The primary
 * here is a stub that fails on demand, because a real PipePipe outage cannot be
 * produced when you need one.
 *
 * The fall-through case makes one live NewPipe extraction; the other makes none.
 */
@RunWith(AndroidJUnit4::class)
class FallbackEngagementTest {

    @Test
    fun primaryOutageFallsThroughToNewPipe() {
        val extractor = PipeExtractor(listOf(FailingEngine(IOException("simulated outage")), NewPipeEngine()))
        val result = extractor.extractStreamInfo(REQUEST, emptyList())
        Log.i(TAG, "fall-through result: engine=${result.getString("engine")} attempts=${result.opt("attempts")}")

        assertTrue("chain should succeed via newpipe: ${result.getString("error")}", result.getBool("success") == true)
        assertEquals("newpipe", result.getString("engine"))

        val attempts = attempts(result)
        assertEquals(2, attempts.length())
        assertEquals("pipepipe", attempts.getJSONObject(0).getString("engine"))
        assertFalse(attempts.getJSONObject(0).getBoolean("ok"))
        assertEquals("IOException", attempts.getJSONObject(0).getString("errorType"))
        assertTrue(attempts.getJSONObject(1).getBoolean("ok"))
    }

    /**
     * Uses PipePipe's real exception class, so this also proves the
     * simple-name match in NOT_WORTH_RETRYING (DIVERGENCES §10) works on an
     * actual thrown type rather than on a string.
     */
    @Test
    fun contentFailureDoesNotFallThrough() {
        val second = RecordingEngine()
        val extractor = PipeExtractor(
            listOf(FailingEngine(AgeRestrictedContentException("simulated age gate")), second),
        )
        val result = extractor.extractStreamInfo(REQUEST, emptyList())

        assertFalse(result.getBool("success") == true)
        assertFalse("an age gate must not be retried on the next engine", second.called)
        assertEquals(1, attempts(result).length())
        assertEquals("AgeRestrictedContentException", attempts(result).getJSONObject(0).getString("errorType"))
    }

    private class FailingEngine(private val failure: Exception) : ExtractionEngine {
        override fun id() = "pipepipe"
        override fun isAvailable() = true
        override fun extractStreamInfo(request: ExtractionRequest): JSObject = throw failure
        override fun version() = "stub"
    }

    private class RecordingEngine : ExtractionEngine {
        var called = false
        override fun id() = "newpipe"
        override fun isAvailable() = true
        override fun extractStreamInfo(request: ExtractionRequest): JSObject {
            called = true
            return JSObject()
        }
        override fun version() = "stub"
    }

    companion object {
        private const val TAG = "FallbackTest"

        // SabrEndToEndTest's video, so this adds no new id to the request history.
        private val REQUEST = ExtractionRequest("https://www.youtube.com/watch?v=iUtnZpzkbG8", false, "en-GB", "GB")

        private fun attempts(result: JSObject): JSONArray = result.opt("attempts") as JSONArray
    }
}
