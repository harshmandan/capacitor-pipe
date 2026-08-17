package ink.harsh.plugins.pipe

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ink.harsh.plugins.pipe.engine.ExtractionRequest
import ink.harsh.plugins.pipe.media3.PipeSabrDataSource
import ink.harsh.plugins.pipe.media3.PipeSabrMedia3
import ink.harsh.plugins.pipe.sabr.PipeSabrManager
import ink.harsh.plugins.pipe.sabr.PipeSabrSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/**
 * Exercises the native playback path.
 *
 * Media3 is `compileOnly` in the library, so nothing in the main build
 * ever loads these classes. Without this test the adapter would compile and
 * still be broken — the test classpath supplies Media3 so it actually runs.
 *
 * Deliberately stops short of a real player: an ExoPlayer instance needs a
 * surface and a main looper, and everything player-specific below the
 * MediaSource is Media3's code, not ours. What is ours is that the manifest
 * parses and the DataSource resolves synthesized URIs to real bytes.
 */
@RunWith(AndroidJUnit4::class)
class SabrMedia3AdapterTest {

    private var manager: PipeSabrManager? = null
    private var session: PipeSabrSession? = null

    @Before
    fun openSession() {
        val extractor = PipeExtractor()
        val engine = extractor.getPipePipeEngine()
        assertNotNull("PipePipe engine missing", engine)

        val manager = PipeSabrManager(context(), engine!!)
        this.manager = manager
        session = manager.open(ExtractionRequest(VIDEO_URL, false, "en-GB", "GB"), 0)
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

    /** Our synthesized manifest must satisfy Media3's own DASH parser. */
    @Test
    fun buildsMediaSource() {
        val source = PipeSabrMedia3.mediaSource(session!!.id)
        assertNotNull("no MediaSource produced", source)
        Log.i(TAG, "MediaSource: " + source.javaClass.name)
    }

    /** The session must be resolvable by id alone, as an app's player code would. */
    @Test
    fun resolvesSessionById() {
        val session = this.session!!
        val looked = PipeSabrManager.lookup(session.id)
        assertNotNull("session not resolvable by id", looked)
        assertEquals(session.id, looked!!.id)
    }

    /** The real work: synthesized URIs must yield actual media bytes. */
    @Test
    fun dataSourceServesSegments() {
        val formatKey = firstRepresentation()
        val dataSource: DataSource = PipeSabrDataSource.Factory(session!!).createDataSource()

        val initUri = segmentUri(formatKey, "init")
        val initLength = dataSource.open(DataSpec(initUri))
        Log.i(TAG, "init  " + initUri + " -> " + initLength + " bytes")
        assertTrue("init segment empty", initLength > 0)
        assertTrue("init bytes unreadable", readSome(dataSource) > 0)
        dataSource.close()

        val mediaUri = segmentUri(formatKey, "1")
        val mediaLength = dataSource.open(DataSpec(mediaUri))
        Log.i(TAG, "media " + mediaUri + " -> " + mediaLength + " bytes")
        assertTrue("media segment empty", mediaLength > 0)
        assertTrue("media bytes unreadable", readSome(dataSource) > 0)
        dataSource.close()
    }

    private fun segmentUri(formatKey: String, sequence: String): Uri = Uri.Builder()
        .scheme(PipeSabrDataSource.SCHEME)
        .authority(session!!.id)
        .appendPath(formatKey)
        .appendPath(sequence)
        .build()

    private fun firstRepresentation(): String {
        val matcher = REPRESENTATION.matcher(session!!.manifest)
        assertTrue("no Representation in manifest", matcher.find())
        return matcher.group(1)!!
    }

    companion object {

        private const val TAG = "SabrMedia3"
        private const val VIDEO_URL = "https://www.youtube.com/watch?v=iUtnZpzkbG8"
        private val REPRESENTATION: Pattern = Pattern.compile("<Representation id=\"([^\"]+)\"")

        private fun context(): Context =
            InstrumentationRegistry.getInstrumentation().targetContext

        private fun readSome(dataSource: DataSource): Int {
            val buffer = ByteArray(8192)
            val read = dataSource.read(buffer, 0, buffer.size)
            return Math.max(read, 0)
        }
    }
}
