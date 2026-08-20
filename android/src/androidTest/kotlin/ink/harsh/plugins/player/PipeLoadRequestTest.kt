package ink.harsh.plugins.player

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.getcapacitor.JSObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The `load()` contract, checked at the boundary the host actually meets.
 *
 * Instrumented rather than a plain unit test for one reason: `JSObject` and
 * `android.util.Base64` are Android classes, and a JVM test would exercise
 * stubs rather than the decoder that runs on device.
 *
 * Every case here asserts the *message*, not just that something threw. These
 * strings are the only feedback a host gets for a malformed call, so a
 * rejection that does not name the field is a bug of its own.
 */
@RunWith(AndroidJUnit4::class)
class PipeLoadRequestTest {

    private val iv = Base64.encodeToString(ByteArray(16), Base64.NO_WRAP)
    private val key = Base64.encodeToString(ByteArray(16), Base64.NO_WRAP)

    @Test
    fun aUrlParses() {
        val request = PipeLoadRequest.parse("https://example.com/v.mp4", null, 1_234L)
        assertTrue(request is PipeLoadRequest.Url)
        assertEquals("https://example.com/v.mp4", (request as PipeLoadRequest.Url).url)
        assertEquals(1_234L, request.startPositionMs)
    }

    @Test
    fun bothUrlAndOfflineIsRejected() {
        assertRejects("exactly one") {
            PipeLoadRequest.parse("https://example.com/v.mp4", offline(track()), 0L)
        }
    }

    @Test
    fun neitherUrlNorOfflineIsRejected() {
        assertRejects("neither was given") { PipeLoadRequest.parse(null, null, 0L) }
    }

    /** A blank url is "no url", not "a url that happens to be empty". */
    @Test
    fun aBlankUrlCountsAsAbsent() {
        assertRejects("neither was given") { PipeLoadRequest.parse("   ", null, 0L) }
        val request = PipeLoadRequest.parse("  ", offline(track()), 0L)
        assertTrue(request is PipeLoadRequest.Offline)
    }

    @Test
    fun aPlaintextTrackParses() {
        val request = PipeLoadRequest.parse(null, offline(track()), 0L)
        val source = (request as PipeLoadRequest.Offline).source
        assertEquals(1, source.tracks.size)
        assertEquals("/tmp/v.mp4", source.tracks[0].path)
        assertEquals("video/mp4", source.tracks[0].mimeType)
        assertNull(source.tracks[0].cipher)
    }

    @Test
    fun twoTracksParse() {
        val request = PipeLoadRequest.parse(
            null,
            offline(track(path = "/tmp/v.mp4"), track(path = "/tmp/a.m4a", mime = "audio/mp4")),
            0L,
        )
        assertEquals(2, (request as PipeLoadRequest.Offline).source.tracks.size)
    }

    @Test
    fun aRawKeyParses() {
        val request = PipeLoadRequest.parse(null, offline(track(cipher = cipher())), 0L)
        val parsed = (request as PipeLoadRequest.Offline).source.tracks[0].cipher!!
        assertTrue(parsed.key is PipeOfflineKey.Raw)
        assertEquals(16, parsed.iv.size)
    }

    @Test
    fun aKeyRefParses() {
        val cipher = """{"kind":"aes-ctr","ivBase64":"$iv","keyRef":"data-key-1"}"""
        val request = PipeLoadRequest.parse(null, offline(track(cipher = cipher)), 0L)
        val parsed = (request as PipeLoadRequest.Offline).source.tracks[0].cipher!!
        assertEquals("data-key-1", (parsed.key as PipeOfflineKey.Ref).ref)
    }

    @Test
    fun missingTracksIsRejected() {
        assertRejects("offline.tracks is required") {
            PipeLoadRequest.parse(null, JSObject(), 0L)
        }
    }

    @Test
    fun emptyTracksIsRejected() {
        assertRejects("must not be empty") { PipeLoadRequest.parse(null, offline(), 0L) }
    }

    @Test
    fun threeTracksIsRejected() {
        assertRejects("not 3") {
            PipeLoadRequest.parse(null, offline(track(), track(), track()), 0L)
        }
    }

    @Test
    fun aMissingPathIsRejected() {
        assertRejects("offline.tracks[0].path is required") {
            PipeLoadRequest.parse(null, offline("""{"mimeType":"video/mp4"}"""), 0L)
        }
    }

    @Test
    fun theWrongCipherKindIsRejected() {
        val cipher = """{"kind":"aes-gcm","ivBase64":"$iv","keyBase64":"$key"}"""
        assertRejects("must be 'aes-ctr'") {
            PipeLoadRequest.parse(null, offline(track(cipher = cipher)), 0L)
        }
    }

    @Test
    fun aMissingIvIsRejected() {
        assertRejects("ivBase64 is required") {
            PipeLoadRequest.parse(null, offline(track(cipher = """{"keyBase64":"$key"}""")), 0L)
        }
    }

    @Test
    fun aShortIvIsRejected() {
        val short = Base64.encodeToString(ByteArray(15), Base64.NO_WRAP)
        val cipher = """{"ivBase64":"$short","keyBase64":"$key"}"""
        assertRejects("must decode to 16 bytes, got 15") {
            PipeLoadRequest.parse(null, offline(track(cipher = cipher)), 0L)
        }
    }

    @Test
    fun aShortKeyIsRejected() {
        val short = Base64.encodeToString(ByteArray(15), Base64.NO_WRAP)
        val cipher = """{"ivBase64":"$iv","keyBase64":"$short"}"""
        assertRejects("keyBase64 must decode to 16 bytes") {
            PipeLoadRequest.parse(null, offline(track(cipher = cipher)), 0L)
        }
    }

    @Test
    fun bothKeyFormsAtOnceIsRejected() {
        val cipher = """{"ivBase64":"$iv","keyBase64":"$key","keyRef":"k1"}"""
        assertRejects("exactly one of keyRef and keyBase64") {
            PipeLoadRequest.parse(null, offline(track(cipher = cipher)), 0L)
        }
    }

    @Test
    fun neitherKeyFormIsRejected() {
        assertRejects("exactly one of keyRef and keyBase64") {
            PipeLoadRequest.parse(null, offline(track(cipher = """{"ivBase64":"$iv"}""")), 0L)
        }
    }

    /**
     * The field name has to survive into the message.
     *
     * A cipher error on the audio track that says `[0]` sends the host looking
     * at the wrong file.
     */
    @Test
    fun theRejectionNamesTheOffendingTrack() {
        val bad = """{"ivBase64":"$iv"}"""
        assertRejects("offline.tracks[1].cipher") {
            PipeLoadRequest.parse(null, offline(track(), track(cipher = bad)), 0L)
        }
    }

    // --- helpers ---

    private fun cipher() = """{"kind":"aes-ctr","ivBase64":"$iv","keyBase64":"$key"}"""

    private fun track(
        path: String = "/tmp/v.mp4",
        mime: String = "video/mp4",
        cipher: String? = null,
    ): String {
        val tail = if (cipher == null) "" else ""","cipher":$cipher"""
        return """{"path":"$path","mimeType":"$mime"$tail}"""
    }

    private fun offline(vararg tracks: String): JSObject =
        JSObject("""{"tracks":[${tracks.joinToString(",")}]}""")

    private fun assertRejects(fragment: String, block: () -> Unit) {
        try {
            block()
        } catch (rejected: IllegalArgumentException) {
            assertTrue(
                "message should mention \"$fragment\", was \"${rejected.message}\"",
                rejected.message!!.contains(fragment),
            )
            return
        }
        fail("expected a rejection mentioning \"$fragment\"")
    }
}
