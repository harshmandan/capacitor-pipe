package ink.harsh.plugins.player

import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The offline file path, exercised against a real `javax.crypto` cipher.
 *
 * The point of encrypting with a plain `Cipher` and decrypting through
 * [PipeOfflineCipherDataSource] is that the test cannot agree with the
 * implementation by sharing its arithmetic — if the counter maths drifts, the
 * round trip fails. The seek cases are the ones that matter: a wrong
 * `blockOffset` discard reads perfectly from offset 0 and garbage from
 * everywhere else, and nothing but a non-block-aligned read catches it.
 */
@RunWith(AndroidJUnit4::class)
class PipePlayerOfflineTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var plain: ByteArray
    private lateinit var key: ByteArray
    private lateinit var iv: ByteArray
    private lateinit var encrypted: File

    @Before
    fun setUp() {
        val random = SecureRandom()
        // Deliberately not a multiple of 16: a size that ends mid-block catches
        // an implementation that pads the tail.
        plain = ByteArray(8_000 + 7).also { random.nextBytes(it) }
        key = ByteArray(16).also { random.nextBytes(it) }
        iv = ByteArray(16).also { random.nextBytes(it) }

        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val ciphertext = cipher.doFinal(plain)
        assertEquals("CTR must not change the length", plain.size, ciphertext.size)

        encrypted = File(context.cacheDir, "offline-cipher-test.bin")
        encrypted.writeBytes(ciphertext)
    }

    @After
    fun tearDown() {
        encrypted.delete()
        PipePlayerOffline.setKeyProvider(null)
    }

    @Test
    fun decryptsWholeFile() {
        assertArrayEquals(plain, readThroughSource(position = 0))
    }

    @Test
    fun decryptsFromUnalignedOffsets() {
        for (offset in longArrayOf(1, 15, 16, 17, 4095, 4096, 4097)) {
            val expected = plain.copyOfRange(offset.toInt(), plain.size)
            assertArrayEquals(
                "wrong plaintext seeking to byte $offset",
                expected,
                readThroughSource(offset),
            )
        }
    }

    @Test
    fun decryptsBoundedRange() {
        val read = readThroughSource(position = 1_000, length = 500)
        assertArrayEquals(plain.copyOfRange(1_000, 1_500), read)
    }

    @Test
    fun buildsOneProgressiveSourceForAMuxedTrack() {
        val source = PipePlayerOffline.buildMediaSource(
            context,
            PipeOfflineSource(listOf(track(encrypted, cipher = cipher()))),
        )
        assertTrue(source is ProgressiveMediaSource)
    }

    /**
     * Two tracks merge.
     *
     * Stops at construction rather than `prepare()`: preparing needs a real
     * decodable container and a playback thread, and everything below the
     * MediaSource is Media3's code. What is ours is that two tracks produce a
     * merge and one does not.
     */
    @Test
    fun mergesVideoAndAudioTracks() {
        val second = File(context.cacheDir, "offline-cipher-test-2.bin")
        second.writeBytes(encrypted.readBytes())
        try {
            val source = PipePlayerOffline.buildMediaSource(
                context,
                PipeOfflineSource(
                    listOf(
                        track(encrypted, "video/mp4", cipher()),
                        track(second, "audio/mp4", cipher()),
                    ),
                ),
            )
            assertTrue(source is MergingMediaSource)
        } finally {
            second.delete()
        }
    }

    @Test
    fun plaintextTrackNeedsNoCipher() {
        val file = File(context.cacheDir, "offline-plain-test.bin")
        file.writeBytes(plain)
        try {
            PipePlayerOffline.buildMediaSource(context, PipeOfflineSource(listOf(track(file))))
        } finally {
            file.delete()
        }
    }

    @Test
    fun keyRefWithoutAProviderIsAConfigurationError() {
        val failure = expectFailure {
            PipePlayerOffline.buildMediaSource(
                context,
                PipeOfflineSource(
                    listOf(track(encrypted, cipher = PipeOfflineCipher(iv, PipeOfflineKey.Ref("k1")))),
                ),
            )
        }
        assertTrue(
            "message must name the missing provider: ${failure.message}",
            failure.message!!.contains("key provider"),
        )
    }

    @Test
    fun aRegisteredProviderResolvesAKeyRef() {
        PipePlayerOffline.setKeyProvider { ref -> if (ref == "k1") key else null }
        PipePlayerOffline.buildMediaSource(
            context,
            PipeOfflineSource(
                listOf(track(encrypted, cipher = PipeOfflineCipher(iv, PipeOfflineKey.Ref("k1")))),
            ),
        )
    }

    @Test
    fun shortKeyIsRejected() {
        val failure = expectFailure {
            PipePlayerOffline.buildMediaSource(
                context,
                PipeOfflineSource(
                    listOf(
                        track(
                            encrypted,
                            cipher = PipeOfflineCipher(iv, PipeOfflineKey.Raw(ByteArray(15))),
                        ),
                    ),
                ),
            )
        }
        assertTrue(failure.message!!.contains("16 bytes"))
    }

    @Test
    fun shortIvIsRejected() {
        val failure = expectFailure {
            PipePlayerOffline.buildMediaSource(
                context,
                PipeOfflineSource(
                    listOf(
                        track(
                            encrypted,
                            cipher = PipeOfflineCipher(ByteArray(15), PipeOfflineKey.Raw(key)),
                        ),
                    ),
                ),
            )
        }
        assertTrue(failure.message!!.contains("iv"))
    }

    @Test
    fun aMissingFileFailsAtBuildTimeNotPlaybackTime() {
        expectFailure {
            PipePlayerOffline.buildMediaSource(
                context,
                PipeOfflineSource(listOf(track(File(context.cacheDir, "nope.bin")))),
            )
        }
    }

    @Test
    fun threeTracksAreRejected() {
        expectFailure {
            PipePlayerOffline.buildMediaSource(
                context,
                PipeOfflineSource(List(3) { track(encrypted) }),
            )
        }
    }

    // --- helpers ---

    private fun cipher() = PipeOfflineCipher(iv, PipeOfflineKey.Raw(key))

    private fun track(file: File, mimeType: String? = null, cipher: PipeOfflineCipher? = null) =
        PipeOfflineTrack(file.absolutePath, mimeType, cipher)

    private fun readThroughSource(position: Long, length: Long = -1L): ByteArray {
        val source = PipeOfflineCipherDataSource(key, iv)
        val spec = DataSpec.Builder()
            .setUri(Uri.fromFile(encrypted))
            .setPosition(position)
            .apply { if (length >= 0) setLength(length) }
            .build()
        source.open(spec)
        try {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(997) // Not a block multiple, on purpose.
            while (true) {
                val read = source.read(buffer, 0, buffer.size)
                if (read == androidx.media3.common.C.RESULT_END_OF_INPUT) break
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        } finally {
            source.close()
        }
    }

    private fun expectFailure(block: () -> Unit): Exception {
        try {
            block()
        } catch (expected: Exception) {
            return expected
        }
        fail("expected the offline source to be rejected")
        error("unreachable")
    }
}
