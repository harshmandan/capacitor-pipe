package ink.harsh.plugins.pipe

import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import ink.harsh.plugins.pipe.engine.ExtractionRequest
import ink.harsh.plugins.pipe.engine.PipePipeEngine
import ink.harsh.plugins.pipe.sabr.PipeSabrManager
import ink.harsh.plugins.pipe.sabr.PipeSabrPoTokens
import ink.harsh.plugins.pipe.sabr.PipeSabrSession
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrInfo
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@CapacitorPlugin(name = "Pipe")
open class PipePlugin : Plugin() {

    private val extractor = PipeExtractor()

    /**
     * Extraction is blocking network work and must never touch the main thread.
     * Bounded because each in-flight extraction can fan several InnerTube
     * requests out in parallel underneath.
     */
    private val executor: ExecutorService = Executors.newFixedThreadPool(3)

    /** Created on first SABR use; most callers never open a session. */
    @Volatile
    private var sabrManager: PipeSabrManager? = null

    override fun handleOnDestroy() {
        // Sessions hold a listening socket and spooled segment files on disk;
        // both outlive the plugin unless closed explicitly.
        val manager = sabrManager
        manager?.closeAll()
        executor.shutdownNow()
        super.handleOnDestroy()
    }

    @PluginMethod
    fun extractStreamInfo(call: PluginCall) {
        val videoUrl = call.getString("videoUrl")
        if (videoUrl == null || videoUrl.trim().isEmpty()) {
            call.reject("videoUrl is required")
            return
        }

        val request = ExtractionRequest(
            videoUrl.trim(),
            call.getBoolean("sponsorBlock", false) == true,
            call.getString("localization"),
            call.getString("contentCountry"),
        )
        val requestedEngines = stringList(call.getArray("engines", null))

        executor.execute {
            try {
                call.resolve(extractor.extractStreamInfo(request, requestedEngines))
            } catch (e: Throwable) {
                // The extractor already converts engine failures into a result
                // object, so reaching here means something outside the chain
                // broke and the caller should see it as an error, not a
                // well-formed failure.
                val failure = JSObject()
                failure.put("success", false)
                failure.put("error", "Plugin error: $e")
                failure.put("attempts", JSArray())
                call.resolve(failure)
            }
        }
    }

    @PluginMethod
    fun getEngineStatus(call: PluginCall) {
        val result = JSObject()
        val available = JSArray()

        for (engine in extractor.availableEngines()) {
            available.put(engine.id())
            if ("pipepipe" == engine.id()) {
                result.put("pipePipeVersion", engine.version())
            } else if ("newpipe" == engine.id()) {
                result.put("newPipeVersion", engine.version())
            }
        }

        result.put("available", available)
        result.put("media3Available", isMedia3Available())
        call.resolve(result)
    }

    @PluginMethod
    fun openSabrSession(call: PluginCall) {
        val videoUrl = call.getString("videoUrl")
        if (videoUrl == null || videoUrl.trim().isEmpty()) {
            call.reject("videoUrl is required")
            return
        }

        val engine = extractor.getPipePipeEngine()
        if (engine == null) {
            call.reject("SABR requires the PipePipe engine, which is not on the classpath")
            return
        }

        val request = ExtractionRequest(
            videoUrl.trim(),
            false,
            call.getString("localization"),
            call.getString("contentCountry"),
        )
        val startPositionMs: Long = call.getInt("startPositionMs", 0)!!.toLong()

        executor.execute {
            try {
                val manager = sabrManager(engine)
                val session = manager.open(request, startPositionMs)

                val result = JSObject()
                result.put("success", true)
                result.put("sessionId", session.id)
                result.put("manifestUrl", manager.manifestUrl(session.id))
                result.put("nativePlaybackAvailable", isMedia3Available())
                result.put("isLive", session.isLive())
                result.put("durationMs", session.durationMs)
                result.put("formats", describeFormats(session))
                call.resolve(result)
            } catch (e: Throwable) {
                val failure = JSObject()
                failure.put("success", false)
                failure.put("error", if (e.message == null) e.toString() else e.message)
                call.resolve(failure)
            }
        }
    }

    /**
     * Supply a Proof-of-Origin token for a video.
     *
     * SABR-gated videos refuse media without one. The extractor never mints
     * tokens, so a host that can produce them — by running BotGuard in a
     * WebView — pushes them in here before opening a session.
     */
    @PluginMethod
    fun providePoToken(call: PluginCall) {
        val videoId = call.getString("videoId")
        val visitorData = call.getString("visitorData")
        val clientVersion = call.getString("clientVersion")
        val playerPoToken = call.getString("playerPoToken")

        if (videoId == null || visitorData == null ||
            clientVersion == null || playerPoToken == null
        ) {
            call.reject("videoId, visitorData, clientVersion and playerPoToken are all required")
            return
        }

        // Default to just under the ~12h integrity-token lifetime.
        val ttlMs: Long = call.getInt("ttlMs", 11 * 60 * 60 * 1000)!!.toLong()
        PipeSabrPoTokens.provide(videoId, visitorData, clientVersion, playerPoToken, ttlMs)
        call.resolve()
    }

    @PluginMethod
    fun closeSabrSession(call: PluginCall) {
        val sessionId = call.getString("sessionId")
        if (sessionId == null || sessionId.isEmpty()) {
            call.reject("sessionId is required")
            return
        }
        val manager = sabrManager
        if (manager == null || !manager.close(sessionId)) {
            // Idempotent: closing an already-closed session is not an error, or
            // callers would have to track state we already track.
            call.resolve()
            return
        }
        call.resolve()
    }

    @Synchronized
    private fun sabrManager(engine: PipePipeEngine): PipeSabrManager {
        var manager = sabrManager
        if (manager == null) {
            manager = PipeSabrManager(context, engine)
            sabrManager = manager
        }
        return manager
    }

    companion object {

        private fun describeFormats(session: PipeSabrSession): JSArray {
            val out = JSArray()
            val spec = session.spec
            for (format in spec.audioFormats) {
                out.put(describeFormat(format, "audio"))
            }
            for (format in spec.videoFormats) {
                out.put(describeFormat(format, "video"))
            }
            return out
        }

        private fun describeFormat(format: YoutubeSabrInfo.Format, kind: String): JSObject {
            val entry = JSObject()
            entry.put("itag", format.itag)
            entry.put("mimeType", format.mimeType)
            entry.put("kind", kind)
            entry.put("bitrate", format.bitrate)
            entry.put("approxDurationMs", format.approxDurationMs)
            if ("video" == kind) {
                entry.put("width", format.width)
                entry.put("height", format.height)
            } else {
                entry.put("audioTrackId", format.audioTrackId)
            }
            return entry
        }

        /**
         * Media3 is an optional `compileOnly` dependency, so its absence is
         * normal — web-player consumers never add it. Probed reflectively; a direct
         * reference would turn a missing optional dependency into a crash.
         */
        private fun isMedia3Available(): Boolean = try {
            Class.forName("androidx.media3.exoplayer.ExoPlayer")
            true
        } catch (ignored: Throwable) {
            false
        }

        private fun stringList(array: JSArray?): List<String> {
            val out = ArrayList<String>()
            if (array == null) {
                return out
            }
            try {
                for (item in array.toList<Any?>()) {
                    if (item != null) {
                        out.add(item.toString())
                    }
                }
            } catch (ignored: Exception) {
                // A malformed engines[] should not fail the call; the default chain
                // is a sensible fallback.
            }
            return out
        }
    }
}
