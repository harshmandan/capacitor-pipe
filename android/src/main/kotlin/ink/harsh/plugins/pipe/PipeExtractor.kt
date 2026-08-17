package ink.harsh.plugins.pipe

import android.util.Log
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import ink.harsh.plugins.pipe.engine.ExtractionEngine
import ink.harsh.plugins.pipe.engine.ExtractionRequest
import ink.harsh.plugins.pipe.engine.NewPipeEngine
import ink.harsh.plugins.pipe.engine.PipePipeEngine
import java.util.LinkedHashMap

/** Runs the engine chain and reports what each engine did. */
class PipeExtractor {

    private val engines: MutableMap<String, ExtractionEngine> = LinkedHashMap()

    init {
        // Insertion order is the fallback order.
        register(PipePipeEngine())
        register(NewPipeEngine())
    }

    private fun register(engine: ExtractionEngine) {
        if (engine.isAvailable()) {
            engines[engine.id()] = engine
        } else {
            Log.w(TAG, "Engine unavailable, skipping: " + engine.id())
        }
    }

    fun availableEngines(): List<ExtractionEngine> = ArrayList(engines.values)

    /**
     * The PipePipe engine, or null if it was stripped from the build.
     *
     * SABR needs the concrete type rather than the interface: it is the only
     * engine that implements the protocol, so there is nothing to abstract over.
     */
    fun getPipePipeEngine(): PipePipeEngine? {
        val engine = engines[PipePipeEngine.ID]
        return if (engine is PipePipeEngine) engine else null
    }

    /**
     * Try each engine in order until one succeeds.
     *
     * Always resolves rather than throwing: the result carries `attempts`,
     * so a caller can see the primary failed even when the call ultimately
     * succeeded. Swallowing that would hide a degraded primary until it broke
     * completely.
     */
    fun extractStreamInfo(
        request: ExtractionRequest,
        requestedEngines: List<String>,
    ): JSObject {
        val result = JSObject()
        val attempts = JSArray()

        val chain = ArrayList<ExtractionEngine>()
        if (requestedEngines.isEmpty()) {
            chain.addAll(engines.values)
        } else {
            for (id in requestedEngines) {
                val engine = engines[id]
                if (engine != null) {
                    chain.add(engine)
                }
            }
        }

        if (chain.isEmpty()) {
            result.put("success", false)
            result.put("error", "No extraction engine is available")
            result.put("attempts", attempts)
            return result
        }

        var lastError: String? = null

        for (engine in chain) {
            val startedAt = System.nanoTime()
            try {
                val streamInfo = engine.extractStreamInfo(request)

                attempts.put(attempt(engine.id(), true, null, null, startedAt))
                result.put("success", true)
                result.put("engine", engine.id())
                result.put("attempts", attempts)
                result.put("streamInfo", streamInfo)
                return result
            } catch (e: Throwable) {
                val type = e.javaClass.simpleName
                val message = if (e.message == null) type else e.message
                lastError = message

                attempts.put(attempt(engine.id(), false, message, type, startedAt))
                Log.w(TAG, "Engine " + engine.id() + " failed: " + type + ": " + message)

                if (NOT_WORTH_RETRYING.contains(type)) {
                    Log.i(
                        TAG,
                        "Not retrying other engines; " + type + " is a property of the content",
                    )
                    break
                }
            }
        }

        result.put("success", false)
        result.put("error", if (lastError == null) "Extraction failed" else lastError)
        result.put("attempts", attempts)
        return result
    }

    companion object {

        private const val TAG = "PipeExtractor"

        /**
         * Failures that describe the *content* rather than the extractor.
         *
         * Both engines see the same age gate, the same geo block and the same
         * private video, so falling through on these buys nothing and doubles the
         * latency of a guaranteed failure. Matched by simple name because the two
         * engines' exception types live in different namespaces after relocation.
         */
        private val NOT_WORTH_RETRYING: Set<String> = setOf(
            "AgeRestrictedContentException",
            "GeographicRestrictionException",
            "PrivateContentException",
            "PaidContentException",
            "AccountTerminatedException",
            "YoutubeMusicPremiumContentException",
            "SoundCloudGoPlusContentException",
            "LiveNotStartException",
            "VideoNotReleaseException",
        )

        private fun attempt(
            engineId: String,
            ok: Boolean,
            error: String?,
            errorType: String?,
            startedAtNanos: Long,
        ): JSObject {
            val entry = JSObject()
            entry.put("engine", engineId)
            entry.put("ok", ok)
            if (error != null) {
                entry.put("error", error)
            }
            if (errorType != null) {
                entry.put("errorType", errorType)
            }
            entry.put("durationMs", (System.nanoTime() - startedAtNanos) / 1_000_000L)
            return entry
        }
    }
}
