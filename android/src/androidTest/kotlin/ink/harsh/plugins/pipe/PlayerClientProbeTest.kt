package ink.harsh.plugins.pipe

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import ink.harsh.plugins.pipe.net.PipePipeDownloader
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Compares PipePipe's player clients on the same videos.
 *
 * Diagnostic, not a pass/fail test — it asserts nothing and always passes.
 * It calls the extractor directly, one client per pass, bypassing
 * `PipePipeEngine`'s two-pass fallback, so each line is what that one client
 * returned. Clients run in a fixed order per video; run it with the order
 * swapped too before blaming a client for what may be rate limiting.
 * A URL is only counted as working if a 1 KB range request to it
 * answers 2xx: an extracted URL that 403s is not a stream.
 *
 * Record what it prints in docs/EXTRACTION.md with the date; the answer moves
 * whenever YouTube does.
 *
 * ```
 * ./gradlew connectedAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=\
 * ink.harsh.plugins.pipe.PlayerClientProbeTest
 * ```
 */
@RunWith(AndroidJUnit4::class)
class PlayerClientProbeTest {

    @Test
    fun compareClients() {
        NewPipe.init(PipePipeDownloader(), Localization.fromLocalizationCode("en-GB"), ContentCountry("GB"))
        val summary = StringBuilder("\n=== player client probe ===\n")

        try {
            for (videoId in VIDEOS) {
                for (client in CLIENTS) {
                    val line = String.format("%-12s %-11s %s", videoId, client, probe(videoId, client))
                    Log.i(TAG, line)
                    summary.append(line).append('\n')
                }
            }
        } finally {
            NewPipe.setYoutubePlayerClient("visionos")
        }

        Log.i(TAG, summary.toString())
    }

    private fun probe(videoId: String, client: String): String {
        NewPipe.setYoutubePlayerClient(client)
        val started = System.currentTimeMillis()
        val info = try {
            StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
        } catch (e: Throwable) {
            return "FAILED ${e.javaClass.simpleName}: ${truncate(e.message)} | ${elapsed(started)}"
        }
        val took = elapsed(started)

        val all: List<Stream> = info.videoStreams + info.videoOnlyStreams + info.audioStreams
        val sabr = all.count { it.deliveryMethod == DeliveryMethod.SABR }
        val hls = all.count { it.deliveryMethod == DeliveryMethod.HLS }
        val muxed = info.videoStreams.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        val videoOnly = info.videoOnlyStreams.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }

        return "muxed ${describe(muxed)} | video-only ${describe(videoOnly)} | " +
            "audio ${info.audioStreams.size} | sabr $sabr | hls $hls | " +
            "url ${fetchStatus(muxed.firstOrNull() ?: videoOnly.firstOrNull())} | $took"
    }

    companion object {

        private const val TAG = "ClientProbe"

        private val CLIENTS = arrayOf("tv_samsung", "visionos")

        private val VIDEOS = arrayOf(
            // The reference pair from docs/EXTRACTION.md: floor and ceiling.
            "jNQXAC9IVRw",
            "aqz-KE-bpKQ",
            // DIVERGENCES §6a's video; long verified non-SABR.
            "iUtnZpzkbG8",
            // Two of SabrCandidateProbeTest's PipePipe#2330 candidates.
            "94ae6Lq4oZk",
            "sJtBOUR4L30",
        )

        private fun describe(streams: List<VideoStream>): String {
            if (streams.isEmpty()) {
                return "0"
            }
            // getResolution(), not .resolution — the field shadows it (CLAUDE.md, Kotlin hazards).
            val best = streams.maxOf { it.getResolution().substringBefore('p').toIntOrNull() ?: 0 }
            return "${streams.size} (≤${best}p)"
        }

        private fun fetchStatus(stream: Stream?): String {
            val url = stream?.content ?: return "-"
            return try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.setRequestProperty("Range", "bytes=0-1023")
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                try {
                    connection.responseCode.toString()
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                e.javaClass.simpleName
            }
        }

        private fun elapsed(started: Long): String = "${(System.currentTimeMillis() - started) / 1000}s"

        private fun truncate(value: String?): String {
            if (value == null) {
                return ""
            }
            return if (value.length <= 80) value else value.substring(0, 80) + "..."
        }
    }
}
