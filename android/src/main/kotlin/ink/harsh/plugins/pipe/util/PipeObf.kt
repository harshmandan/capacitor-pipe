package ink.harsh.plugins.pipe.util

/**
 * Runtime decoder for identifying string literals in the plugin's own Kotlin.
 *
 * The extractor jars are handled by `tools/strenc` at build time, but the
 * plugin's own sources ship as source and are compiled by the consuming app, so
 * that build-time transform never sees them. A grep over a release APK would
 * otherwise still turn up our attestation diagnostics and the youtube.com hosts
 * we reference — see ENCRYPTED-STRINGS.md.
 *
 * So the few identifying literals we control are stored ciphered (as `\u`
 * escapes at the call site) and reconstructed here. Because [d] runs at runtime,
 * the value is fully normal once decoded — an exception message logged through
 * [d] reads correctly in logcat; only the dex and the source hold ciphertext.
 *
 * ## This is not a secret
 *
 * [KEY] is committed and matches `StringEncryptor.KEY`. This is obfuscation, not
 * cryptography — it defeats `strings`/grep, not a debugger. The point is only
 * that a casual scan of the APK does not announce a YouTube extractor. The
 * ciphertext↔plaintext table is committed at
 * [`tools/strenc/kotlin-strings.md`](../../../../../../../../../tools/strenc/kotlin-strings.md)
 * so a garbled value seen in a log or the source can always be looked up, and
 * `PipeObfTest` asserts every entry round-trips.
 */
internal object PipeObf {

    /** XOR key, position-dependent. Committed on purpose — see the class KDoc. */
    private const val KEY = 0x5B

    /** Reverse of `StringEncryptor.cipher`: `c[i] xor (KEY + i)`, symmetric. */
    fun d(ciphered: String): String {
        val chars = ciphered.toCharArray()
        for (i in chars.indices) {
            chars[i] = (chars[i].code xor (KEY + i)).toChar()
        }
        return String(chars)
    }
}
