package ink.harsh.plugins.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The offline path driven by a real ExoPlayer, on real media.
 *
 * [PipePlayerOfflineTest] proves the bytes come back correctly; this proves
 * Media3 can actually make sense of them. The distinction matters — a
 * DataSource can return perfect plaintext and still be unusable if it reports
 * the wrong length, mishandles a bounded `DataSpec` or fails to reopen for the
 * extractor's second pass, and none of that shows up in a byte comparison.
 *
 * Fixtures are generated, one second each, and committed: `offline-video.mp4`
 * (video-only), `offline-audio.m4a` (audio-only) and `offline-muxed.mp4`.
 * Small enough that "commit a binary" costs nothing, real enough that the mp4
 * extractor has to parse them.
 */
@RunWith(AndroidJUnit4::class)
class PipePlayerOfflinePlaybackTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    private lateinit var key: ByteArray
    private lateinit var iv: ByteArray
    private val scratch = mutableListOf<File>()

    @Before
    fun setUp() {
        val random = SecureRandom()
        key = ByteArray(16).also { random.nextBytes(it) }
        iv = ByteArray(16).also { random.nextBytes(it) }
    }

    @After
    fun tearDown() {
        scratch.forEach { it.delete() }
        scratch.clear()
        PipePlayerOffline.setKeyProvider(null)
    }

    @Test
    fun playsAMuxedPlaintextFile() {
        val duration = prepare(PipeOfflineSource(listOf(track("offline-muxed.mp4"))))
        assertPlausibleDuration(duration)
    }

    @Test
    fun playsAMuxedEncryptedFile() {
        val duration = prepare(
            PipeOfflineSource(listOf(track("offline-muxed.mp4", encrypt = true))),
        )
        assertPlausibleDuration(duration)
    }

    /**
     * The two-track case, end to end.
     *
     * This is the one the whole `MergingMediaSource` branch exists for: above
     * 360p YouTube ships video-only, so a real download is two files that have
     * to be merged at playback. Both are encrypted here, with the same key and
     * IV — the sources are independent, so a counter leaking between them would
     * show up as a failure to prepare.
     */
    @Test
    fun playsAnEncryptedVideoAndAudioPair() {
        val duration = prepare(
            PipeOfflineSource(
                listOf(
                    track("offline-video.mp4", "video/mp4", encrypt = true),
                    track("offline-audio.m4a", "audio/mp4", encrypt = true),
                ),
            ),
        )
        assertPlausibleDuration(duration)
    }

    /** A `keyRef` resolved by a registered provider must play like any other. */
    @Test
    fun playsThroughAKeyProvider() {
        val file = copyOut("offline-muxed.mp4", encrypt = true)
        PipePlayerOffline.setKeyProvider { ref -> if (ref == "data-key-1") key else null }
        val duration = prepare(
            PipeOfflineSource(
                listOf(
                    PipeOfflineTrack(
                        file.absolutePath,
                        "video/mp4",
                        PipeOfflineCipher(iv, PipeOfflineKey.Ref("data-key-1")),
                    ),
                ),
            ),
        )
        assertPlausibleDuration(duration)
    }

    /**
     * The wrong key must fail, not play noise.
     *
     * Worth asserting explicitly: CTR never fails to decrypt — it happily
     * produces garbage — so the only thing that rejects a wrong key is the
     * container parser downstream. If this ever starts passing, the cipher is
     * not being applied at all.
     */
    @Test
    fun theWrongKeyFailsToPrepare() {
        val file = copyOut("offline-muxed.mp4", encrypt = true)
        val wrong = ByteArray(16) { 0x11 }
        val failure = prepareExpectingFailure(
            PipeOfflineSource(
                listOf(
                    PipeOfflineTrack(
                        file.absolutePath,
                        "video/mp4",
                        PipeOfflineCipher(iv, PipeOfflineKey.Raw(wrong)),
                    ),
                ),
            ),
        )
        assertTrue("expected a source error, got $failure", failure is PlaybackException)
    }

    /** Seeking into an encrypted file is the case the counter maths exists for. */
    @Test
    fun seeksInsideAnEncryptedFile() {
        val source = PipeOfflineSource(listOf(track("offline-muxed.mp4", encrypt = true)))
        val duration = prepare(source, seekToMs = 500L)
        assertPlausibleDuration(duration)
    }

    // --- helpers ---

    private fun assertPlausibleDuration(durationMs: Long) {
        assertTrue(
            "duration should be about a second, was ${durationMs}ms",
            durationMs in 500..2_000,
        )
    }

    private fun track(asset: String, mimeType: String? = null, encrypt: Boolean = false) =
        PipeOfflineTrack(
            copyOut(asset, encrypt).absolutePath,
            mimeType,
            if (encrypt) PipeOfflineCipher(iv, PipeOfflineKey.Raw(key)) else null,
        )

    /** Assets are not files on disk; the player needs a path, so copy one out. */
    private fun copyOut(asset: String, encrypt: Boolean): File {
        val bytes = instrumentation.context.assets.open(asset).use { it.readBytes() }
        val payload = if (!encrypt) bytes else {
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            cipher.doFinal(bytes)
        }
        val suffix = if (encrypt) "enc" else "raw"
        return File(context.cacheDir, "$suffix-$asset").also {
            it.writeBytes(payload)
            scratch.add(it)
        }
    }

    private fun prepare(source: PipeOfflineSource, seekToMs: Long = 0L): Long {
        val outcome = drive(source, seekToMs)
        assertNull("prepare failed: ${outcome.second}", outcome.second)
        return outcome.first
    }

    private fun prepareExpectingFailure(source: PipeOfflineSource): PlaybackException? =
        drive(source, 0L).second

    /**
     * Prepare on the main looper and wait for READY or an error.
     *
     * ExoPlayer must be built and driven from the thread that owns its looper,
     * which under instrumentation is the main one — hence `runOnMainSync` for
     * every touch of the player, including the release.
     */
    private fun drive(source: PipeOfflineSource, seekToMs: Long): Pair<Long, PlaybackException?> {
        val ready = CountDownLatch(1)
        val error = AtomicReference<PlaybackException?>()
        val player = AtomicReference<ExoPlayer>()

        instrumentation.runOnMainSync {
            val exo = ExoPlayer.Builder(context).build()
            player.set(exo)
            exo.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) ready.countDown()
                }

                override fun onPlayerError(e: PlaybackException) {
                    error.set(e)
                    ready.countDown()
                }
            })
            exo.setMediaSource(
                PipePlayerOffline.buildMediaSource(context, source),
                seekToMs,
            )
            exo.prepare()
        }

        val settled = ready.await(30, TimeUnit.SECONDS)
        var duration = C.TIME_UNSET
        var position = 0L
        instrumentation.runOnMainSync {
            duration = player.get().duration
            position = player.get().currentPosition
            player.get().release()
        }
        assertTrue("player never reached READY or failed", settled)
        if (error.get() == null && seekToMs > 0) {
            assertEquals("start position was not honoured", seekToMs, position, 250.0)
        }
        return duration to error.get()
    }

    private fun assertEquals(message: String, expected: Long, actual: Long, delta: Double) {
        assertTrue(
            "$message (expected ~$expected, was $actual)",
            Math.abs(expected - actual) <= delta,
        )
    }
}
