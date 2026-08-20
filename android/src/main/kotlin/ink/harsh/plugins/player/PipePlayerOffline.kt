package ink.harsh.plugins.player

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.getcapacitor.JSObject
import java.io.File
import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Playback of local files, optionally encrypted.
 *
 * This package does not download and does not manage keys. The host hands over
 * a path, an IV and a way to reach a key; the player turns that into a
 * [MediaSource]. Everything offline lives here rather than in
 * `PipePlayerOverlay`, which is long enough already.
 *
 * Media3 is a `compileOnly` dependency, so nothing here loads unless the app
 * actually ships Media3. Never reference it without a runtime guard.
 */

/** How to reach the 16-byte key for a file. Exactly one of the two is set. */
sealed class PipeOfflineKey {
    /**
     * A host-defined reference, resolved natively by [PipePlayerOffline.setKeyProvider].
     *
     * The path to prefer: the key never crosses the bridge and never sits in a
     * JS string.
     */
    data class Ref(val ref: String) : PipeOfflineKey()

    /** Raw key bytes, for hosts with no native code of their own. Weaker. */
    data class Raw(val key: ByteArray) : PipeOfflineKey() {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Raw && key.contentEquals(other.key))

        override fun hashCode(): Int = key.contentHashCode()
    }
}

/**
 * AES-128-CTR, no padding — the format shared verbatim with the downloader.
 *
 * 16-byte key, 16-byte per-file IV, ciphertext length equal to plaintext
 * length. No header, no trailer. Chosen because it streams, seeks by
 * arithmetic, and adds no bytes.
 */
data class PipeOfflineCipher(
    val iv: ByteArray,
    val key: PipeOfflineKey,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is PipeOfflineCipher && iv.contentEquals(other.iv) && key == other.key)

    override fun hashCode(): Int = 31 * iv.contentHashCode() + key.hashCode()
}

/** One local file. `cipher` is null for a plaintext file. */
data class PipeOfflineTrack(
    val path: String,
    val mimeType: String? = null,
    val cipher: PipeOfflineCipher? = null,
)

/**
 * The media to play.
 *
 * One track when the file is muxed, two when video and audio were downloaded
 * separately — which is the common case, not an edge one: YouTube muxes only
 * up to 360p, and everything above it is video-only.
 */
data class PipeOfflineSource(val tracks: List<PipeOfflineTrack>)

@UnstableApi
object PipePlayerOffline {

    private const val BLOCK = 16

    @Volatile
    private var keyProvider: ((String) -> ByteArray?)? = null

    /**
     * Turns a host-defined key reference into 16 raw bytes.
     *
     * Registered from the host's own Kotlin, so a Keystore-wrapped key is
     * unwrapped in the host's process and never appears in a JS string. Absent
     * by default: a `keyRef` with no provider is a configuration error and
     * rejects saying so.
     *
     * Note for the host side: an `AndroidKeyStore` `SecretKey` is
     * non-exportable, so `secretKey.encoded` returns **null**. The provider
     * cannot hand back a Keystore key directly. The shape that works is a
     * random 16-byte data key, wrapped with a Keystore AES-GCM key and
     * unwrapped on demand.
     */
    fun setKeyProvider(provider: ((String) -> ByteArray?)?) {
        keyProvider = provider
    }

    /**
     * Build a source for [source], validating everything first.
     *
     * A bad offline source must fail the bridge call, not produce a black frame
     * ten seconds later — so missing files, unreadable files and wrong key or IV
     * lengths all throw here, before any player sees them.
     */
    fun buildMediaSource(context: Context, source: PipeOfflineSource): MediaSource {
        require(source.tracks.isNotEmpty()) { "offline.tracks must not be empty" }
        require(source.tracks.size <= 2) {
            "offline.tracks takes one muxed track or two (video + audio), not ${source.tracks.size}"
        }
        val sources = source.tracks.map { track -> progressive(track) }
        return if (sources.size == 1) sources[0] else MergingMediaSource(sources[0], sources[1])
    }

    private fun progressive(track: PipeOfflineTrack): MediaSource {
        val file = File(track.path)
        if (!file.isFile) throw IOException("offline file not found: ${track.path}")
        if (!file.canRead()) throw IOException("offline file not readable: ${track.path}")

        val factory = if (track.cipher == null) {
            FileDataSource.Factory()
        } else {
            val key = resolveKey(track.cipher.key)
            val iv = track.cipher.iv
            require(key.size == BLOCK) { "offline key must be 16 bytes, got ${key.size}" }
            require(iv.size == BLOCK) { "offline iv must be 16 bytes, got ${iv.size}" }
            DataSource.Factory { PipeOfflineCipherDataSource(key, iv) }
        }

        val item = MediaItem.Builder()
            .setUri(Uri.fromFile(file))
            .apply { track.mimeType?.let { setMimeType(it) } }
            .build()
        return ProgressiveMediaSource.Factory(factory).createMediaSource(item)
    }

    private fun resolveKey(key: PipeOfflineKey): ByteArray = when (key) {
        is PipeOfflineKey.Raw -> key.key
        is PipeOfflineKey.Ref -> {
            val provider = keyProvider
                ?: throw IllegalStateException(
                    "offline keyRef '${key.ref}' was given but no key provider is " +
                        "registered — call PipePlayerOffline.setKeyProvider from your app",
                )
            provider(key.ref)
                ?: throw IllegalStateException("key provider returned no key for ref '${key.ref}'")
        }
    }

    /**
     * Counter block for a byte offset: the IV plus `offset / 16`, big-endian.
     *
     * This is what makes the format seekable without reading from the start —
     * `AES/CTR/NoPadding` increments the same way natively, so initialising with
     * this block and discarding `offset % 16` keystream bytes lands exactly on
     * the plaintext byte at `offset`.
     */
    internal fun counterBlock(iv: ByteArray, offset: Long): ByteArray {
        val block = iv.copyOf()
        var carry = offset / BLOCK
        var index = block.size - 1
        while (index >= 0 && carry != 0L) {
            val sum = (block[index].toInt() and 0xFF) + (carry and 0xFF).toInt()
            block[index] = (sum and 0xFF).toByte()
            carry = (carry ushr 8) + (sum ushr 8)
            index--
        }
        return block
    }

    internal fun cipherAt(key: ByteArray, iv: ByteArray, offset: Long): Cipher {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            IvParameterSpec(counterBlock(iv, offset)),
        )
        // Land mid-block: burn the keystream bytes before the requested offset.
        val skew = (offset % BLOCK).toInt()
        if (skew > 0) cipher.update(ByteArray(skew))
        return cipher
    }
}

/**
 * A [FileDataSource] with the keystream applied on the way out.
 *
 * `BaseDataSource`, not a bare `DataSource`: the `TransferListener` callbacks
 * are what feed ExoPlayer's bandwidth meter, and a plain implementation makes
 * `addTransferListener` a silent no-op. `PipeSabrDataSource` records the same
 * reasoning. `isNetwork = false` because these bytes come off local storage —
 * counted as network they would poison every bitrate estimate.
 *
 * Deliberately not Media3's `AesCipherDataSource`: that derives its nonce from
 * `DataSpec.key` through an internal FNV-64 hash, which would couple our
 * on-disk format to a Media3 implementation detail across two codebases and
 * every version bump. The IV is explicit in our contract instead.
 */
@UnstableApi
class PipeOfflineCipherDataSource(
    private val key: ByteArray,
    private val iv: ByteArray,
) : BaseDataSource(/* isNetwork = */ false) {

    /*
     * Listeners are attached to this source only, never forwarded to `inner` —
     * both report the same bytes, so forwarding would double-count every read.
     * BaseDataSource makes addTransferListener final, which enforces that.
     */
    private val inner = FileDataSource()
    private var cipher: Cipher? = null
    private var opened = false

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        // FileDataSource already honours position and length; all this adds is
        // starting the keystream at the same place.
        val length = inner.open(dataSpec)
        opened = true
        cipher = PipePlayerOffline.cipherAt(key, iv, maxOf(0L, dataSpec.position))
        transferStarted(dataSpec)
        return length
    }

    @Throws(IOException::class)
    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        val read = inner.read(target, offset, length)
        if (read == C.RESULT_END_OF_INPUT || read == 0) return read
        // CTR is a stream cipher: update consumes and produces the same count,
        // so this is an in-place transform and never buffers or pads.
        val plain = cipher!!.update(target, offset, read)
        System.arraycopy(plain, 0, target, offset, read)
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = inner.uri

    @Throws(IOException::class)
    override fun close() {
        cipher = null
        try {
            inner.close()
        } finally {
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }
}

/**
 * What the bridge was asked to play.
 *
 * Parsing lives here rather than in the plugin so it can be tested without a
 * Capacitor bridge — every rejection below is a contract rule a host will hit,
 * and "it rejects" is only useful if the message says why.
 */
sealed class PipeLoadRequest {

    abstract val startPositionMs: Long

    data class Url(val url: String, override val startPositionMs: Long) : PipeLoadRequest()

    data class Offline(
        val source: PipeOfflineSource,
        override val startPositionMs: Long,
    ) : PipeLoadRequest()

    /**
     * An open SABR session, played from its segment bridge directly.
     *
     * The same session is playable from its `manifestUrl` as an ordinary
     * `Url` — this skips the loopback socket, the cleartext exemption and a
     * copy of every byte through HTTP. Nothing else differs: both routes go
     * through the one synthesised manifest.
     */
    data class Sabr(
        val sessionId: String,
        override val startPositionMs: Long,
    ) : PipeLoadRequest()

    companion object {

        private const val BLOCK = 16

        /**
         * Validate one `load()` call.
         *
         * Throws [IllegalArgumentException] with a message meant for the host
         * developer. Every failure names the field, because "invalid offline
         * source" on its own sends a caller reading Kotlin.
         */
        @JvmOverloads
        fun parse(
            url: String?,
            offline: JSObject?,
            startPositionMs: Long,
            sessionId: String? = null,
        ): PipeLoadRequest {
            val trimmed = url?.takeIf { it.isNotBlank() }
            val session = sessionId?.takeIf { it.isNotBlank() }
            val given = listOfNotNull(trimmed, offline, session).size
            require(given <= 1) { "pass exactly one of url, offline and sessionId, not several" }
            require(given == 1) {
                "pass exactly one of url, offline and sessionId; none was given"
            }
            return when {
                trimmed != null -> Url(trimmed, startPositionMs)
                session != null -> Sabr(session, startPositionMs)
                else -> Offline(parseSource(offline!!), startPositionMs)
            }
        }

        private fun parseSource(offline: JSObject): PipeOfflineSource {
            val array = offline.optJSONArray("tracks")
                ?: throw IllegalArgumentException("offline.tracks is required")
            require(array.length() > 0) { "offline.tracks must not be empty" }
            require(array.length() <= 2) {
                "offline.tracks takes one muxed track or two (video + audio), " +
                    "not ${array.length()}"
            }

            val tracks = (0 until array.length()).map { index ->
                val track = JSObject.fromJSONObject(array.getJSONObject(index))
                val path = track.getString("path")
                    ?: throw IllegalArgumentException("offline.tracks[$index].path is required")
                PipeOfflineTrack(
                    path = path,
                    mimeType = track.getString("mimeType"),
                    cipher = track.getJSObject("cipher")?.let { parseCipher(it, index) },
                )
            }
            return PipeOfflineSource(tracks)
        }

        private fun parseCipher(cipher: JSObject, index: Int): PipeOfflineCipher {
            val field = "offline.tracks[$index].cipher"
            val kind = cipher.getString("kind") ?: "aes-ctr"
            require(kind == "aes-ctr") { "$field.kind must be 'aes-ctr', got '$kind'" }

            val ivBase64 = cipher.getString("ivBase64")
                ?: throw IllegalArgumentException("$field.ivBase64 is required")
            val iv = decodeBase64(ivBase64, "$field.ivBase64")
            require(iv.size == BLOCK) {
                "$field.ivBase64 must decode to $BLOCK bytes, got ${iv.size}"
            }

            val keyRef = cipher.getString("keyRef")
            val keyBase64 = cipher.getString("keyBase64")
            require((keyRef == null) != (keyBase64 == null)) {
                "$field needs exactly one of keyRef and keyBase64"
            }

            val key = if (keyRef != null) {
                PipeOfflineKey.Ref(keyRef)
            } else {
                val raw = decodeBase64(keyBase64!!, "$field.keyBase64")
                require(raw.size == BLOCK) {
                    "$field.keyBase64 must decode to $BLOCK bytes, got ${raw.size}"
                }
                PipeOfflineKey.Raw(raw)
            }
            return PipeOfflineCipher(iv = iv, key = key)
        }

        private fun decodeBase64(value: String, what: String): ByteArray =
            try {
                Base64.decode(value, Base64.DEFAULT)
            } catch (bad: IllegalArgumentException) {
                throw IllegalArgumentException("$what is not valid base64")
            }
    }
}
