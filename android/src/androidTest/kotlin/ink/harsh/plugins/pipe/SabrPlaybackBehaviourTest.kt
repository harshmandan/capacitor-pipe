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
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Behaviour a player will actually exercise: seeking, track selection and
 * sustained fetching.
 *
 * These matter because SABR is a conversation, not a fetch. The server sends
 * only what the client claims to be missing, so a dishonest buffered-range
 * report does not fail loudly — it just silently starves the reader. A seek is
 * where that goes wrong first, which is why upstream's own SABR PR called out
 * backward seek as the hard case.
 *
 * Live network. Run with:
 * ```
 * ./gradlew connectedAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=\
 * ink.harsh.plugins.pipe.SabrPlaybackBehaviourTest
 * ```
 */
@RunWith(AndroidJUnit4::class)
class SabrPlaybackBehaviourTest {

    private var manager: PipeSabrManager? = null
    private var session: PipeSabrSession? = null
    private lateinit var manifest: String
    private lateinit var base: String
    private lateinit var client: OkHttpClient

    @Before
    fun openSession() {
        val extractor = PipeExtractor()
        val engine = extractor.getPipePipeEngine()
        assertNotNull("PipePipe engine missing", engine)

        val manager = PipeSabrManager(context(), engine!!)
        this.manager = manager
        val session = manager.open(ExtractionRequest(VIDEO_URL, false, "en-GB", "GB"), 0)
        this.session = session

        client = OkHttpClient.Builder().callTimeout(90, TimeUnit.SECONDS).build()
        val manifestUrl = manager.manifestUrl(session.id)
        base = manifestUrl.substring(0, manifestUrl.lastIndexOf('/') + 1)
        manifest = String(bytes(manifestUrl), Charsets.UTF_8)
    }

    @After
    fun closeSession() {
        val manager = this.manager
        if (manager != null) {
            val session = this.session
            if (session != null) {
                manager.close(session.id)
            }
            manager.closeAll()
        }
    }

    /**
     * Jumping far ahead, as a player does when the user drags the scrubber.
     * The session must not insist on walking every intermediate segment.
     */
    @Test
    fun seeksForward() {
        val format = firstRepresentation()
        val total = segmentCount()
        val target = Math.max(2, total - 2)

        val first = fetchSegment(format, 1)
        Log.i(TAG, "forward seek: segment 1 = " + first + " bytes")

        val ahead = fetchSegment(format, target)
        Log.i(TAG, "forward seek: segment " + target + " of " + total + " = " + ahead + " bytes")
        assertTrue("far-ahead segment came back empty", ahead > 0)
    }

    /**
     * The hard case. After reading ahead, ask for an earlier segment.
     *
     * The buffered head only ever advances unless the request reports
     * honestly that we no longer hold the earlier range; if it does not, the
     * server considers the segment already delivered and sends nothing back.
     */
    @Test
    fun seeksBackward() {
        val format = firstRepresentation()
        val total = segmentCount()
        val ahead = Math.max(3, total / 2)

        val forward = fetchSegment(format, ahead)
        Log.i(TAG, "backward seek: primed at segment " + ahead + " = " + forward + " bytes")
        assertTrue(forward > 0)

        val rewound = fetchSegment(format, 1)
        Log.i(TAG, "backward seek: back to segment 1 = " + rewound + " bytes")
        assertTrue("rewind returned nothing — the server thinks we still hold it", rewound > 0)
    }

    /** Multi-language audio must survive as separate selectable tracks. */
    @Test
    fun exposesAudioTracks() {
        val session = this.session!!
        val audioSets = count(AUDIO_ADAPTATION, manifest)
        val audioFormats = session.spec.audioFormats.size
        Log.i(
            TAG,
            "audio: " + audioFormats + " format(s), " +
                audioSets + " AdaptationSet(s) in the manifest",
        )

        assertTrue("no audio AdaptationSet in the manifest", audioSets >= 1)

        // Distinct keys per format, or the manifest would collapse two formats
        // into one Representation and a language would silently disappear.
        val keys = ArrayList<String>()
        for (format in session.spec.audioFormats) {
            val key = session.spec.getFormatKey(format)
            assertTrue("duplicate format key $key", !keys.contains(key))
            keys.add(key)
        }
        Log.i(TAG, "audio format keys: $keys")
    }

    /**
     * Sustained sequential reading, which is what playback actually looks like.
     * Also exercises the ahead-cache eviction: every segment must still arrive
     * after the cache has turned over.
     */
    @Test
    fun sustainsSequentialFetching() {
        val format = firstRepresentation()
        val total = Math.min(segmentCount(), 12)
        var bytes: Long = 0

        for (sequence in 1..total) {
            val size = fetchSegment(format, sequence)
            assertTrue("segment " + sequence + " came back empty", size > 0)
            bytes += size.toLong()
        }
        Log.i(TAG, "sequential: " + total + " segments, " + bytes + " bytes total")
        assertTrue(bytes > 0)
    }

    // --- helpers ---

    private fun firstRepresentation(): String {
        val matcher = REPRESENTATION.matcher(manifest)
        assertTrue("no Representation in manifest", matcher.find())
        return matcher.group(1)!!
    }

    /**
     * Segments belonging to the FIRST Representation only.
     *
     * Counting every &lt;S&gt; in the manifest sums all representations, which
     * produced a target sequence far beyond the chosen format's timeline.
     */
    private fun segmentCount(): Int {
        val start = manifest.indexOf("<Representation id=\"" + firstRepresentation())
        val timeline = manifest.indexOf("<SegmentTimeline", start)
        val end = manifest.indexOf("</SegmentTimeline>", timeline)
        return count(SEGMENT, manifest.substring(timeline, end))
    }

    private fun fetchSegment(formatKey: String, sequence: Int): Int =
        bytes(base + formatKey + "/" + sequence).size

    private fun bytes(url: String): ByteArray {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            val body = response.body
            if (!response.isSuccessful) {
                throw AssertionError("GET " + url + " -> " + response.code + " " + body.string())
            }
            return body.bytes()
        }
    }

    companion object {

        private const val TAG = "SabrBehaviour"
        private const val VIDEO_URL = "https://www.youtube.com/watch?v=iUtnZpzkbG8"

        private val REPRESENTATION: Pattern = Pattern.compile("<Representation id=\"([^\"]+)\"")
        private val SEGMENT: Pattern = Pattern.compile("<S t=\"")
        private val AUDIO_ADAPTATION: Pattern =
            Pattern.compile("<AdaptationSet[^>]*contentType=\"audio\"")

        private fun context(): Context =
            InstrumentationRegistry.getInstrumentation().targetContext

        private fun count(pattern: Pattern, haystack: String): Int {
            val matcher = pattern.matcher(haystack)
            var found = 0
            while (matcher.find()) {
                found++
            }
            return found
        }
    }
}
