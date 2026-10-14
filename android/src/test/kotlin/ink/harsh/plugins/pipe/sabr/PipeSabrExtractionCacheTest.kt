package ink.harsh.plugins.pipe.sabr

import ink.harsh.plugins.pipe.engine.ExtractionRequest
import ink.harsh.plugins.pipe.engine.PipePipeEngine.SabrExtraction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrInfo

class PipeSabrExtractionCacheTest {

    private var now = 1_000_000_000_000L

    @Before
    fun setUp() {
        PipeSabrExtractionCache.clear()
        PipeSabrExtractionCache.clock = { now }
    }

    @After
    fun tearDown() {
        PipeSabrExtractionCache.clear()
        PipeSabrExtractionCache.clock = System::currentTimeMillis
    }

    @Test
    fun reusesWithinMaxAge() {
        val extraction = extraction(url = null)
        PipeSabrExtractionCache.put(REQUEST, extraction)

        now += PipeSabrExtractionCache.MAX_AGE_MS - 1
        assertSame(extraction, PipeSabrExtractionCache.get(REQUEST))

        now += 1
        assertNull(PipeSabrExtractionCache.get(REQUEST))
    }

    @Test
    fun urlExpiryWinsWhenSooner() {
        // The URL expires in 10 minutes, so with the 5-minute margin the entry lasts 5.
        val expireSec = (now + 10 * 60_000L) / 1000
        PipeSabrExtractionCache.put(REQUEST, extraction(url = "https://x.googlevideo.com/sabr?a=1&expire=$expireSec&b=2"))

        now += 5 * 60_000L - 1
        assertEquals(true, PipeSabrExtractionCache.get(REQUEST) != null)
        now += 1
        assertNull(PipeSabrExtractionCache.get(REQUEST))
    }

    @Test
    fun keyIncludesLocale() {
        PipeSabrExtractionCache.put(REQUEST, extraction(url = null))
        assertNull(PipeSabrExtractionCache.get(ExtractionRequest(REQUEST.videoUrl, false, "de-DE", "DE")))
    }

    @Test
    fun liveIsNeverCached() {
        PipeSabrExtractionCache.put(REQUEST, extraction(url = null, live = true))
        assertNull(PipeSabrExtractionCache.get(REQUEST))
    }

    @Test
    fun evictRemoves() {
        PipeSabrExtractionCache.put(REQUEST, extraction(url = null))
        PipeSabrExtractionCache.evict(REQUEST)
        assertNull(PipeSabrExtractionCache.get(REQUEST))
    }

    @Test
    fun sizeIsBoundedOldestFirst() {
        val requests = (0..PipeSabrExtractionCache.MAX_ENTRIES).map {
            ExtractionRequest("https://www.youtube.com/watch?v=video$it", false, null, null)
        }
        requests.forEach { PipeSabrExtractionCache.put(it, extraction(url = null)) }

        assertNull(PipeSabrExtractionCache.get(requests.first()))
        assertEquals(true, PipeSabrExtractionCache.get(requests.last()) != null)
    }

    @Test
    fun parsesExpire() {
        assertEquals(1_700_000_000_000L, PipeSabrExtractionCache.expireMs("https://h/p?expire=1700000000"))
        assertNull(PipeSabrExtractionCache.expireMs("https://h/p?expires=1700000000"))
        assertNull(PipeSabrExtractionCache.expireMs("https://h/p"))
        assertNull(PipeSabrExtractionCache.expireMs(null))
    }

    private fun extraction(url: String?, live: Boolean = false) = SabrExtraction(
        YoutubeSabrInfo("iUtnZpzkbG8", "cpn", "2.0", null, url, null, emptyList(), null),
        185_000L,
        live,
    )

    companion object {
        private val REQUEST = ExtractionRequest("https://www.youtube.com/watch?v=iUtnZpzkbG8", false, "en-GB", "GB")
    }
}
