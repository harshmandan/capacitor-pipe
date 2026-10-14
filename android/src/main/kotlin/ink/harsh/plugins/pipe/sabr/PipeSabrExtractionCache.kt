package ink.harsh.plugins.pipe.sabr

import ink.harsh.plugins.pipe.engine.ExtractionRequest
import ink.harsh.plugins.pipe.engine.PipePipeEngine.SabrExtraction

/**
 * Recent SABR extractions, reused when a session for the same video is reopened.
 *
 * A session serves one quality, so a quality switch closes one session and opens
 * another for the same video — and without this the reopen repeats the whole
 * extraction before it can fetch a byte. PipePipeClient avoids the same cost by
 * reading its StreamInfo from cache on a quality change.
 *
 * Sharing an extraction between sessions is safe: `YoutubeSabrInfo` is
 * immutable, and `YoutubeSabrSession.setPoToken` replaces the session's own copy
 * of the token rather than the one inside the info.
 *
 * An entry lives until the earlier of [MAX_AGE_MS] after extraction and
 * [EXPIRY_MARGIN_MS] before the streaming URL's own `expire`. A reused entry
 * can still go stale sooner than that (a rejected token, a moved node), which is
 * why [PipeSabrManager.open] retries once with a fresh extraction on failure.
 */
internal object PipeSabrExtractionCache {

    const val MAX_AGE_MS = 20 * 60_000L
    const val EXPIRY_MARGIN_MS = 5 * 60_000L
    const val MAX_ENTRIES = 8

    /** Replaced by tests. */
    @Volatile
    var clock: () -> Long = System::currentTimeMillis

    private class Entry(val extraction: SabrExtraction, val validUntilMs: Long)

    // Insertion order is age order, which is what the size bound evicts by.
    private val entries = LinkedHashMap<String, Entry>()

    @Synchronized
    fun get(request: ExtractionRequest): SabrExtraction? {
        val key = keyOf(request)
        val entry = entries[key] ?: return null
        if (clock() >= entry.validUntilMs) {
            entries.remove(key)
            return null
        }
        return entry.extraction
    }

    /** Live sessions are not cached: the extractor gives live no SABR path today. */
    @Synchronized
    fun put(request: ExtractionRequest, extraction: SabrExtraction) {
        if (extraction.live) return
        val now = clock()
        entries.values.removeAll { now >= it.validUntilMs }
        entries.remove(keyOf(request))
        entries[keyOf(request)] = Entry(extraction, validUntil(extraction, now))
        while (entries.size > MAX_ENTRIES) {
            entries.remove(entries.keys.first())
        }
    }

    @Synchronized
    fun evict(request: ExtractionRequest) {
        entries.remove(keyOf(request))
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    fun validUntil(extraction: SabrExtraction, now: Long): Long {
        val byAge = now + MAX_AGE_MS
        val expireMs = expireMs(extraction.info.serverAbrStreamingUrl) ?: return byAge
        return minOf(byAge, expireMs - EXPIRY_MARGIN_MS)
    }

    /**
     * Keyed by the URL as the caller wrote it, not the video id: the id is only
     * known after extracting, and a caller switching quality passes the same URL.
     * Locale is part of the key because it feeds the player request's hl/gl.
     */
    fun keyOf(request: ExtractionRequest): String =
        request.videoUrl + "|" + (request.localization ?: "") + "|" + (request.contentCountry ?: "")

    /** The `expire` query parameter (Unix seconds) as epoch ms, or null if absent. */
    fun expireMs(url: String?): Long? {
        if (url == null) return null
        val query = url.substringAfter('?', "")
        for (pair in query.split('&')) {
            if (pair.startsWith("expire=")) {
                return pair.substringAfter('=').toLongOrNull()?.let { it * 1000L }
            }
        }
        return null
    }
}
