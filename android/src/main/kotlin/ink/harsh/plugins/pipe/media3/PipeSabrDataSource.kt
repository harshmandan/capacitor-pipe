package ink.harsh.plugins.pipe.media3

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import ink.harsh.plugins.pipe.sabr.PipeSabrBridge
import ink.harsh.plugins.pipe.sabr.PipeSabrSession
import ink.harsh.plugins.pipe.sabr.PipeSabrSpec
import ink.harsh.plugins.pipe.sabr.SabrSegmentKey
import org.schabi.newpipe.extractor.services.youtube.sabr.media.SabrMediaSegment
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

/**
 * Serves Media3's segment demand straight from a SABR session.
 *
 * Optional: everything here also works over the loopback HTTP server, which
 * is what web players use. This exists so a native player skips that hop —
 * no socket, no HTTP framing, no cleartext exemption needed.
 *
 * Media3 is a `compileOnly` dependency, so this class only loads when
 * the app actually ships Media3. Never reference it without a runtime guard.
 *
 * Segment URIs look like `sabrseg://<sessionId>/<formatKey>/<n>`,
 * where `n` is a sequence number or the literal `init`. The
 * synthesized manifest emits these as relative paths, so Media3 resolves them
 * against the base URI the manifest was parsed with.
 */
@UnstableApi
/*
 * isNetwork = false, and that is not a detail.
 *
 * Extending BaseDataSource makes TransferListener callbacks fire, which is the
 * point — until now `addTransferListener` was a no-op, so DefaultBandwidthMeter,
 * EventLogger and any analytics saw zero bytes from this source. But these reads
 * come off a local spool file at RAM speed. Counted as network transfer they
 * would produce wildly optimistic estimates and push AdaptiveTrackSelection
 * toward a rendition the actual SABR session cannot sustain. Declaring the
 * source non-network keeps the listeners honest without poisoning ABR.
 */
class PipeSabrDataSource internal constructor(
    private val session: PipeSabrSession,
) : BaseDataSource(/* isNetwork = */ false) {

    private var uri: Uri? = null
    private var initializationData: ByteArray? = null
    private var stream: InputStream? = null
    private var openedKey: SabrSegmentKey? = null
    private var bytesRemaining: Long = 0
    private var position = 0


    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        closeStream()
        initializationData = null
        position = Math.max(0L, dataSpec.position).toInt()

        val key = keyFrom(session.spec, dataSpec.uri)
        openedKey = key

        val available: Long
        val total: Int

        if (key.isInitialization()) {
            val data = session.spec.getInitializationData(key.format)
                ?: throw IOException("SABR initialisation missing for itag=" + key.format.itag)
            initializationData = data
            total = data.size
            available = Math.max(0L, (total - position).toLong())
        } else {
            val bridge = session.bridge
            // Upstream (SabrSegmentDataSource) fails a beyond-timeline request
            // instantly. Without this, a seek past the last segment blocked in
            // awaitSegment for the whole no-progress budget while holding the
            // transaction lock, starving every concurrent segment request.
            val endSequence = try {
                bridge.getTimeline(key.format).endSequence
            } catch (e: IllegalStateException) {
                Int.MAX_VALUE // Timeline not parsed yet; let the session drive it.
            }
            if (key.sequenceNumber > endSequence) {
                throw IOException(
                    "SABR sequence ${key.sequenceNumber} beyond timeline end " +
                        "$endSequence for itag=${key.format.itag}",
                )
            }
            var segment = awaitSegment(bridge, key)
            try {
                stream = segment.openStream()
            } catch (expired: FileNotFoundException) {
                // The spool file was evicted between delivery and open; drop the
                // cached entry and let the session produce it again.
                bridge.discard(key)
                segment = awaitSegment(bridge, key)
                stream = segment.openStream()
            }
            val skipped = skipFully(stream!!, dataSpec.position)
            position = Math.min(Integer.MAX_VALUE.toLong(), skipped).toInt()
            total = segment.length
            available = Math.max(0L, total - skipped)
        }

        bytesRemaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            available
        } else {
            Math.min(dataSpec.length, available)
        }
        transferStarted(dataSpec)
        return bytesRemaining
    }

    @Throws(IOException::class)
    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) {
            return 0
        }
        if (bytesRemaining <= 0) {
            return C.RESULT_END_OF_INPUT
        }

        val data = initializationData
        if (data != null) {
            if (position >= data.size) {
                return C.RESULT_END_OF_INPUT
            }
            val count = Math.min(
                Math.min(length, data.size - position).toLong(),
                bytesRemaining,
            ).toInt()
            System.arraycopy(data, position, target, offset, count)
            position += count
            bytesRemaining -= count.toLong()
            bytesTransferred(count)
            return count
        }

        val input = stream ?: return C.RESULT_END_OF_INPUT
        val count = input.read(target, offset, Math.min(length.toLong(), bytesRemaining).toInt())
        if (count < 0) {
            bytesRemaining = 0
            return C.RESULT_END_OF_INPUT
        }
        position += count
        bytesRemaining -= count.toLong()
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = uri

    @Throws(IOException::class)
    override fun close() {
        initializationData = null
        try {
            closeStream()
            transferEnded()
        } finally {
            val key = openedKey
            openedKey = null
            // Init data lives in the spec and is needed for the whole session;
            // only media segments are released.
            if (key != null && !key.isInitialization()) {
                session.bridge.discard(key)
            }
        }
    }

    @Throws(IOException::class)
    private fun closeStream() {
        stream?.let {
            it.close()
            stream = null
        }
    }

    companion object {

        /** URI scheme for synthesized SABR segment addresses. */
        const val SCHEME = "sabrseg"

        @Throws(IOException::class)
        private fun awaitSegment(bridge: PipeSabrBridge, key: SabrSegmentKey): SabrMediaSegment {
            try {
                return bridge.awaitSegment(key)
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                throw IOException("SABR segment extraction failed: " + e.message, e)
            }
        }

        /** Parse `sabrseg://<session>/<formatKey>/<sequence|init>`. */
        @Throws(IOException::class)
        internal fun keyFrom(spec: PipeSabrSpec, uri: Uri): SabrSegmentKey {
            val segments: List<String> = uri.pathSegments
            if (segments.size < 2) {
                throw IOException("Bad SABR segment URI: $uri")
            }
            val formatKey = segments[segments.size - 2]
            val sequence = segments[segments.size - 1]

            val format = spec.getFormat(formatKey)
                ?: throw IOException("Unknown SABR format '" + formatKey + "' in " + uri)
            if ("init" == sequence) {
                return SabrSegmentKey.initialization(format)
            }
            try {
                val sequenceNumber = sequence.toInt()
                // Fail as IOException rather than letting media()'s
                // IllegalArgumentException escape open() unchecked.
                if (sequenceNumber <= 0) {
                    throw IOException("Bad SABR sequence in $uri")
                }
                return SabrSegmentKey.media(format, sequenceNumber)
            } catch (e: NumberFormatException) {
                throw IOException("Bad SABR sequence in $uri", e)
            }
        }

        @Throws(IOException::class)
        private fun skipFully(input: InputStream, requested: Long): Long {
            var remaining = Math.max(0L, requested)
            val scratch = ByteArray(8192)
            while (remaining > 0) {
                val skipped = input.skip(remaining)
                if (skipped > 0) {
                    remaining -= skipped
                    continue
                }
                val read = input.read(scratch, 0, Math.min(scratch.size.toLong(), remaining).toInt())
                if (read < 0) {
                    break
                }
                remaining -= read.toLong()
            }
            return requested - remaining
        }
    }

    /** [DataSource.Factory] bound to one session. */
    @UnstableApi
    class Factory(private val session: PipeSabrSession) : DataSource.Factory {

        override fun createDataSource(): DataSource = PipeSabrDataSource(session)
    }
}
