package ink.harsh.plugins.pipe

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.getcapacitor.JSObject
import ink.harsh.plugins.pipe.engine.ExtractionRequest
import org.json.JSONArray
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Finds a video that is actually SABR-only right now.
 *
 * Diagnostic, not a pass/fail test — it asserts nothing and always passes.
 * SABR enforcement moves per-video over time, so a hardcoded "SABR video" in a
 * test goes stale silently. Run this to pick a current one.
 *
 * Candidates were mined from the comments on
 * `InfinityLoop1308/PipePipe#2330` ("[YouTube] Content Not Yet Supported
 * (SABR) after 5.1.0").
 *
 * How the detection works, given we cannot mint PO tokens yet:
 *
 *  - Pass 1 uses the `visionos` client and extracts direct formats.
 *    If that yields streams, the video is *not* SABR-only.
 *  - If pass 1 yields nothing we retry on `mweb`, which routes through
 *    SABR and needs a PO token. Without one it throws
 *    [NullPointerException] inside `createMwebPlayerRequest`.
 *
 * So an NPE from the PipePipe engine is the signal: pass 1 found nothing, and
 * only SABR remains.
 *
 * ```
 * ./gradlew connectedAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=\
 * ink.harsh.plugins.pipe.SabrCandidateProbeTest
 * ```
 */
@RunWith(AndroidJUnit4::class)
class SabrCandidateProbeTest {

    @Test
    fun probeCandidates() {
        val extractor = PipeExtractor()
        val summary = StringBuilder("\n=== SABR probe ===\n")

        for (videoId in CANDIDATES) {
            val url = "https://www.youtube.com/watch?v=$videoId"
            var verdict: String
            var detail = ""

            try {
                val result = extractor.extractStreamInfo(
                    ExtractionRequest(url, false, "en-GB", "GB"),
                    listOf("pipepipe"),
                )

                if (result.getBool("success") == true) {
                    val streamInfo = result.getJSObject("streamInfo")
                    val requiresSabr = streamInfo != null &&
                        streamInfo.getBool("requiresSabr") == true
                    verdict = if (requiresSabr) "SABR-ONLY" else "DIRECT"
                    detail = if (streamInfo == null) "" else streamInfo.getString("title").toString()
                } else {
                    val error = firstAttemptError(result)
                    if (error != null && (
                            error.contains("NullPointerException") ||
                                error.contains("PoTokenResult") ||
                                error.contains("createMwebPlayerRequest")
                            )
                    ) {
                        // Pass 1 produced nothing and pass 2 needed a token.
                        verdict = "SABR-ONLY"
                        detail = "no direct formats; mweb needs a PO token"
                    } else {
                        verdict = "FAILED"
                        detail = result.getString("error").toString()
                    }
                }
            } catch (e: Throwable) {
                verdict = "ERROR"
                detail = e.toString()
            }

            val line = String.format("%-12s %-10s %s", videoId, verdict, truncate(detail))
            Log.i(TAG, line)
            summary.append(line).append('\n')
        }

        Log.i(TAG, summary.toString())
    }

    companion object {

        private const val TAG = "SabrProbe"

        private val CANDIDATES = arrayOf(
            "94ae6Lq4oZk", "sJtBOUR4L30", "l-JbaMXYfaM", "yQF0reViYDg",
            "6onc3rpArns", "dortSZ7JvNI", "KZU2dDG32p4", "FHA6CP3gYwY",
            "gZWvvowpfhQ", "AnKiIyiRdxo", "27F9bFGFzfI", "7QwiyyyQXjg",
            // Known-good control: verified non-SABR, should report DIRECT.
            "iUtnZpzkbG8",
        )

        private fun firstAttemptError(result: JSObject): String? {
            try {
                val attempts = result.opt("attempts")
                if (attempts !is JSONArray) {
                    return result.getString("error")
                }
                for (i in 0 until attempts.length()) {
                    val attempt = attempts.optJSONObject(i)
                    if (attempt != null && !attempt.optBoolean("ok", false)) {
                        return attempt.optString("errorType", "") + ": " +
                            attempt.optString("error", "")
                    }
                }
            } catch (ignored: Exception) {
                // Diagnostic only; fall through to the top-level error.
            }
            return result.getString("error")
        }

        private fun truncate(value: String?): String {
            if (value == null) {
                return ""
            }
            return if (value.length <= 90) value else value.substring(0, 90) + "..."
        }
    }
}
