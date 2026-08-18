package ink.harsh.plugins.pipe.sabr

import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrInfo
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrSession
import java.io.File

/** One open SABR session: protocol driver, bridge, format registry and manifest. */
class PipeSabrSession internal constructor(
    val id: String,
    val info: YoutubeSabrInfo,
    private val session: YoutubeSabrSession,
    val spec: PipeSabrSpec,
    val bridge: PipeSabrBridge,
    /** The synthesized DASH manifest, built once after timelines were parsed. */
    val manifest: String,
    private val spoolDirectory: File,
    val durationMs: Long,
) {

    fun isLive(): Boolean = session.isLive

    /**
     * Release the bridge cache and the spool directory.
     *
     * Segments are spooled to disk, so skipping this leaks files that survive
     * the process.
     */
    internal fun close() {
        bridge.stop()
        // kotlin.io's, replacing a hand-written copy of it. Best effort either
        // way: a segment still being streamed may briefly hold a handle.
        spoolDirectory.deleteRecursively()
    }
}
