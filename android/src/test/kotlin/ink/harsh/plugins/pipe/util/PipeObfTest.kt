package ink.harsh.plugins.pipe.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Guards [PipeObf] and the committed map in tools/strenc/kotlin-strings.md.
 *
 * Every identifying literal obfuscated in the plugin's own Kotlin is listed
 * here as (ciphertext to plaintext). The test asserts each decodes to its
 * original — so a change to [PipeObf.KEY] or the cipher, or a bad hand-edited
 * escape, fails the build instead of shipping a garbled exception message — and
 * that no ciphertext still contains the needle it was meant to hide.
 *
 * This is a plain JVM unit test (no device): it runs in `gradlew test`, i.e.
 * `verify:android`.
 */
class PipeObfTest {

    // Kept in lockstep with tools/strenc/kotlin-strings.md and the call sites.
    private val map: List<Pair<String, String>> = listOf(
        "\u001a\u0029\u0029\u0036\u003a\u000e\u0015\u000b\u0000\u0005\u0011\u0003\u0003\u0048\u0030\u0005\u001e\u0038\u0018\u000c\u000a\u0050\u0019\u001d\u001e\u0011\u0055\u001e\u0016\u000b\u0059\u0014\u0014\u005c\u0039\u001f\u000b\u00e1\u00a1\u00d1\u00fa\u00ea\u00e6\u00a6\u00ce\u00cc" to "Authenticated YouTube home has no Data Sync ID",
        "\u000e\u0032\u002e\u002b\u002f\u0010\u000e\u0010\u0017\u0001\u0001\u0046\u003e\u0007\u001c\u003e\u001e\u000e\u0008\u004e\u0007\u001f\u001c\u0017\u0053\u0017\u0019\u001f\u0012\u0016\u000d\u0040\u005b" to "Unsupported YouTube home client: ",
        "\u0002\u0033\u0028\u000a\u002a\u0002\u0004\u0042\u000b\u000b\u0008\u0003\u0047\u000c\u0006\u000f\u0018\u004c\u0003\u0001\u001b\u0050\u0014\u001c\u0012\u0016\u0019\u0013\u0057\u0019\u0059\u0009\u000e\u000c\u000d\u0011\u000d\u00f4\u00e4\u00e6\u00a3\u00d4\u00ca\u00a6\u00f3\u00e7\u00e2\u00ef\u00e5\u00ac\u00ef\u00e7\u00e1\u00f4\u00f8\u00fc\u00f4" to "YouTube home does not enable a supported PO token binding",
        "\u0002\u0033\u0028\u000a\u002a\u0002\u0004\u0042\u000b\u000b\u0008\u0003\u0047\u0000\u0008\u0019\u004b\u0002\u0002\u004e\u002a\u0026\u0034\u003c\u0027\u002b\u003c\u0032" to "YouTube home has no EVENT_ID",
        "\u0002\u0033\u0028\u000a\u002a\u0002\u0004\u0042\u000b\u000b\u0008\u0003\u0047\u0000\u0008\u0019\u004b\u0002\u0002\u004e\u0026\u001e\u001f\u0017\u0001\u0000\u0000\u0014\u0012\u0058\u001a\u0016\u0012\u0019\u0013\u000a\u005f\u00e3\u00ee\u00ec\u00f7\u00e1\u00fd\u00f2" to "YouTube home has no Innertube client context",
        "\u0002\u0033\u0028\u000a\u002a\u0002\u0004\u0042\u000b\u000b\u0008\u0003\u0047\u0000\u0008\u0019\u004b\u0002\u0002\u004e\u000e\u001e\u001e\u001c\u000a\u0019\u001a\u0003\u0004\u0058\u000f\u0013\u0008\u0015\u0009\u0011\u000d\u00a0\u00e5\u00e3\u00f7\u00e5" to "YouTube home has no anonymous visitor data",
        "\u0002\u0033\u0028\u000a\u002a\u0002\u0004\u0042\u000b\u000b\u0008\u0003\u0047\u0000\u0008\u0019\u004b\u0002\u0002\u004e\u000c\u001c\u0018\u0017\u001d\u0000\u0055\u0015\u0018\u0016\u000d\u001f\u0003\u0008" to "YouTube home has no client context",
        "\u0002\u0033\u0028\u000a\u002a\u0002\u0004\u0042\u000b\u000b\u0008\u0003\u0047\u0000\u0008\u0019\u004b\u0002\u0002\u004e\u000c\u001c\u0018\u0017\u001d\u0000\u0055\u0018\u0016\u0015\u001c" to "YouTube home has no client name",
        "\u0002\u0033\u0028\u000a\u002a\u0002\u0004\u0042\u000b\u000b\u0008\u0003\u0047\u0000\u0008\u0019\u004b\u0002\u0002\u004e\u000c\u001c\u0018\u0017\u001d\u0000\u0055\u0000\u0012\u000a\u000a\u0013\u0014\u0012" to "YouTube home has no client version",
        "\u0002\u0033\u0028\u000a\u002a\u0002\u0004\u0042\u000b\u000b\u0008\u0003\u0047\u0000\u0008\u0019\u004b\u0002\u0002\u004e\u0006\u001e\u0018\u0006\u001a\u0015\u0019\u0056\u0016\u000c\u000d\u001f\u0008\u0008\u001c\u000a\u0016\u00ef\u00ef\u00a2\u00e0\u00ec\u00e4\u00ea\u00eb\u00ed\u00e7\u00ed\u00ee" to "YouTube home has no initial attestation challenge",
        "\u0002\u0033\u0028\u002a\u002a\u0002\u0004\u002f\u0016\u0017\u000c\u0005\u0037\u001a\u000c\u0007\u0002\u0019\u0000\u002d\u0000\u001e\u0005\u0017\u001d\u0000\u0030\u000e\u0014\u001d\u0009\u000e\u0012\u0013\u0013" to "YoutubeMusicPremiumContentException",
        "\u0033\u0028\u0029\u002e\u002c\u005a\u004e\u004d\u0014\u0013\u0012\u0048\u001e\u0007\u001c\u001e\u001e\u000e\u0008\u0040\u000c\u001f\u001c" to "https://www.youtube.com",
        "\u0033\u0028\u0029\u002e\u002c\u005a\u004e\u004d\u0014\u0013\u0012\u0048\u001e\u0007\u001c\u001e\u001e\u000e\u0008\u0040\u000c\u001f\u001c\u005d" to "https://www.youtube.com/",
        "\u0022\u0033\u0028\u002a\u002a\u0002\u0004\u004c\u0000\u000b\u0008" to "youtube.com",
    )

    @Test
    fun everyObfuscatedLiteralDecodesToItsOriginal() {
        for ((cipher, plain) in map) {
            assertEquals("decode mismatch for [$plain]", plain, PipeObf.d(cipher))
        }
    }

    @Test
    fun noCiphertextStillLeaksItsNeedle() {
        // The whole point: the stored form must not contain what it hides.
        for ((cipher, _) in map) {
            assertFalse(
                "ciphertext still contains 'youtube'",
                cipher.lowercase().contains("youtube"),
            )
        }
    }
}
