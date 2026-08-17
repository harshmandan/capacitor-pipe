package ink.harsh.plugins.pipe.engine

import com.getcapacitor.JSObject

/**
 * One extraction backend.
 *
 * There are two implementations and they share no code, because they cannot:
 * PipePipeExtractor and NewPipeExtractor both occupy
 * `org.schabi.newpipe.extractor`, so the fallback only exists because
 * NewPipe is relocated at build time. To the compiler the two sets of types are
 * entirely unrelated, which is why each engine carries its own mapping code.
 * See CLAUDE.md, Gotcha 2.
 */
interface ExtractionEngine {

    /** Stable identifier matching the `ExtractionEngine` union in definitions.ts. */
    fun id(): String

    /**
     * Whether this engine's classes are actually on the classpath.
     *
     * Both jars normally ship, but aggressive shrinking in a consuming app
     * can remove one. Callers skip unavailable engines rather than failing.
     */
    fun isAvailable(): Boolean

    /**
     * Extract stream info, returning the `streamInfo` object described by
     * `StreamInfo` in definitions.ts.
     *
     * Throws on any failure; the caller records the attempt and moves to the
     * next engine.
     */
    @Throws(Exception::class)
    fun extractStreamInfo(request: ExtractionRequest): JSObject

    /** Engine version, for diagnostics. Null when it cannot be determined. */
    fun version(): String?
}
