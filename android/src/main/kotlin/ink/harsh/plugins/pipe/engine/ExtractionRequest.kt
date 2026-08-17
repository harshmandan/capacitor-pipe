package ink.harsh.plugins.pipe.engine

/** Everything an engine needs for one extraction, independent of which engine runs it. */
class ExtractionRequest(
    val videoUrl: String,
    @get:JvmName("isSponsorBlock")
    val sponsorBlock: Boolean,
    /** BCP-47 code, e.g. `en-GB`. Null means the engine default. */
    val localization: String?,
    /** ISO-3166 code, e.g. `GB`. Null means the engine default. */
    val contentCountry: String?,
)
