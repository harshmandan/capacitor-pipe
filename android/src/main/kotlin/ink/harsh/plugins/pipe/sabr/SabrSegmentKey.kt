package ink.harsh.plugins.pipe.sabr

import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrInfo
import java.util.Objects

/**
 * Identifies one segment of one format.
 *
 * SABR media is addressed by `(format, sequenceNumber)`, never by URL —
 * there is no per-segment URL to key on. Sequence [INIT] denotes the
 * initialisation segment, which carries the container header and the index the
 * timeline is parsed from.
 */
class SabrSegmentKey private constructor(
    val format: YoutubeSabrInfo.Format,
    val sequenceNumber: Int,
) {

    fun isInitialization(): Boolean = sequenceNumber == INIT

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is SabrSegmentKey) {
            return false
        }
        // Compared by identity of the Format instance, matching how the session
        // hands formats back. Two Format objects for the same itag are not
        // interchangeable: multi-language audio reuses one itag per track.
        return sequenceNumber == other.sequenceNumber && format == other.format
    }

    override fun hashCode(): Int = Objects.hash(format, sequenceNumber)

    override fun toString(): String =
        "itag=" + format.itag + ",seq=" + (if (isInitialization()) "init" else sequenceNumber)

    companion object {
        /** Sentinel sequence number for a format's initialisation segment. */
        const val INIT = -1

        @JvmStatic
        fun initialization(format: YoutubeSabrInfo.Format): SabrSegmentKey =
            SabrSegmentKey(format, INIT)

        @JvmStatic
        fun media(format: YoutubeSabrInfo.Format, sequenceNumber: Int): SabrSegmentKey {
            // Media sequences are 1-based (the manifest's startNumber is 1).
            // Upstream throws here too; without the guard a -1 built a media
            // key indistinguishable from the INIT sentinel, which could never
            // be served and spun the await budget out instead of failing fast.
            require(sequenceNumber > 0) { "Media sequence must be positive: $sequenceNumber" }
            return SabrSegmentKey(format, sequenceNumber)
        }
    }
}
