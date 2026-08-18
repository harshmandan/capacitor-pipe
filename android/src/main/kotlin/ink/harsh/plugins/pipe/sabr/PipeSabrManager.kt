package ink.harsh.plugins.pipe.sabr

import android.content.Context
import android.util.Log
import ink.harsh.plugins.pipe.engine.ExtractionRequest
import ink.harsh.plugins.pipe.engine.PipePipeEngine
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrSession
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Opens, tracks and closes SABR sessions.
 *
 * Sessions are expensive and stateful — a protocol driver, a segment cache
 * spooled to disk, and a WebView-minted token — so they are explicitly opened
 * and must be explicitly closed. Nothing here is reference-counted or
 * garbage-collected.
 */
class PipeSabrManager(context: Context, private val engine: PipePipeEngine) {

    private val context: Context = context.applicationContext
    private val server: PipeSabrServer = PipeSabrServer(this)
    private val sessions: MutableMap<String, PipeSabrSession> = ConcurrentHashMap()

    fun get(sessionId: String): PipeSabrSession? = sessions[sessionId]

    fun getPort(): Int = server.getPort()

    /**
     * Open a session for a SABR-delivered video.
     *
     * Blocking and slow — it performs an extraction and then several SABR
     * round trips to learn the segment timelines before a manifest can exist.
     * Never call from the main thread.
     */
    @Throws(Exception::class)
    fun open(request: ExtractionRequest, startPositionMs: Long): PipeSabrSession {
        // Must precede extraction: the MWEB player request carries the token,
        // so installing it afterwards is too late for this session.
        PipeSabrPoTokens.enableMinting(context)

        val extraction = engine.extractSabrInfo(request)

        val sessionId = UUID.randomUUID().toString().replace("-", "")
        val spool = File(context.cacheDir, "sabr/$sessionId")
        if (!spool.mkdirs() && !spool.isDirectory) {
            throw IOException("Could not create SABR spool directory: $spool")
        }

        val session = YoutubeSabrSession(extraction.info, spool)
        val spec = PipeSabrSpec(extraction.info)
        val bridge = PipeSabrBridge(session, spec, extraction.info.videoId)

        try {
            // Must precede manifest generation: segment counts and durations
            // come from the index inside the init segments this fetches.
            bridge.prepareTimelines(Math.max(0L, startPositionMs))

            val manifest = PipeSabrManifest.build(spec, bridge, extraction.durationMs)

            server.start()

            val opened = PipeSabrSession(
                sessionId,
                extraction.info,
                session,
                spec,
                bridge,
                manifest,
                spool,
                extraction.durationMs,
            )
            sessions[sessionId] = opened
            ALL_SESSIONS[sessionId] = opened

            Log.i(
                TAG,
                "SABR session " + sessionId + " open for " + extraction.info.videoId +
                    " (" + spec.audioFormats.size + " audio, " +
                    spec.videoFormats.size + " video formats)",
            )
            return opened
        } catch (e: Throwable) {
            // Never leave a half-open session holding a spool directory.
            bridge.stop()
            // kotlin.io's, replacing a hand-written copy of it.
            spool.deleteRecursively()
            throw e
        }
    }

    fun manifestUrl(sessionId: String): String = server.manifestUrl(sessionId)

    fun close(sessionId: String): Boolean {
        val session = sessions.remove(sessionId)
        ALL_SESSIONS.remove(sessionId)
        if (session == null) {
            return false
        }
        session.close()
        Log.i(TAG, "SABR session " + sessionId + " closed")

        // The server exists only to serve sessions; idle it when the last one
        // goes so we are not holding a listening socket for nothing.
        if (sessions.isEmpty()) {
            server.stop()
        }
        return true
    }

    /** Close every session. Call from the plugin's destroy hook. */
    fun closeAll() {
        for (id in sessions.keys) {
            val session = sessions.remove(id)
            ALL_SESSIONS.remove(id)
            session?.close()
        }
        server.stop()
    }

    companion object {

        private const val TAG = "PipeSabrManager"

        /**
         * Every open session, across managers.
         *
         * Exists so an app's own player code can resolve a `sessionId` it
         * received over the Capacitor bridge without having to plumb a reference to
         * the plugin instance through its media layer.
         */
        private val ALL_SESSIONS: MutableMap<String, PipeSabrSession> = ConcurrentHashMap()

        /** Resolve a session opened by any manager. Used by the Media3 adapter. */
        @JvmStatic
        fun lookup(sessionId: String): PipeSabrSession? = ALL_SESSIONS[sessionId]

    }
}
