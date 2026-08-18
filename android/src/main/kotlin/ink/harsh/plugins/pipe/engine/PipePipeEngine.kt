package ink.harsh.plugins.pipe.engine

import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import ink.harsh.plugins.pipe.PipeExtractor
import ink.harsh.plugins.pipe.net.PipePipeDownloader
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import ink.harsh.plugins.pipe.youtube.PipeLocalPlayerDecoder
import org.schabi.newpipe.extractor.services.youtube.YoutubeApiDecoder
import org.schabi.newpipe.extractor.services.youtube.YoutubePoTokenResult
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrInfo
import org.schabi.newpipe.extractor.sponsorblock.SponsorBlockApiSettings
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.SubtitlesStream
import org.schabi.newpipe.extractor.stream.VideoStream
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.function.Function

/**
 * Primary engine: PipePipeExtractor, on the unrelocated
 * `org.schabi.newpipe.extractor` namespace.
 *
 * Supports SABR, SponsorBlock and restored dislike counts, none of which the
 * NewPipe fallback provides. Its YouTube path defers signature and
 * `n`-parameter deciphering to a PipePipe-hosted service, so it fails in
 * different circumstances than NewPipe — which is what makes the fallback
 * meaningful rather than redundant.
 */
class PipePipeEngine : ExtractionEngine {

    @Volatile
    private var initialized = false

    /**
     * Guards the extractor's global localization state.
     *
     * `setPreferredLocalization`/`setPreferredContentCountry` are engine-wide,
     * so two concurrent extractions with different locales would silently run
     * one under the other's hl/gl — wrong-language metadata, no error. The
     * common case (every call on one locale) takes the read lock and stays
     * fully concurrent; only an actual locale *change* serialises, holding the
     * write lock for the duration of its extraction.
     */
    private val localizationLock = ReentrantReadWriteLock()

    /** The locale key currently applied to the global state; null before init. */
    @Volatile
    private var appliedLocale: String? = null

    override fun id(): String = ID

    override fun isAvailable(): Boolean = try {
        Class.forName("org.schabi.newpipe.extractor.NewPipe")
        true
    } catch (ignored: Throwable) {
        false
    }

    override fun version(): String = "PipePipeExtractor"

    private fun localeKey(request: ExtractionRequest): String =
        (request.localization ?: "") + "|" + (request.contentCountry ?: "")

    /**
     * Run `block` with the request's locale applied to the extractor's global
     * state, holding it stable for the duration.
     *
     * Compared by the request's raw locale strings rather than the extractor's
     * Localization objects, so nothing here depends on either fork's equals().
     */
    private fun <T> withLocalization(request: ExtractionRequest, block: () -> T): T {
        val key = localeKey(request)
        val read = localizationLock.readLock()
        read.lock()
        if (initialized && key == appliedLocale) {
            try {
                return block()
            } finally {
                read.unlock()
            }
        }
        read.unlock()

        val write = localizationLock.writeLock()
        write.lock()
        try {
            if (!initialized || key != appliedLocale) {
                applyLocalization(request)
                appliedLocale = key
            }
            return block()
        } finally {
            write.unlock()
        }
    }

    private fun applyLocalization(request: ExtractionRequest) {
        val localization = if (request.localization == null) {
            Localization.DEFAULT
        } else {
            Localization.fromLocalizationCode(request.localization)
        }
        val country = if (request.contentCountry == null) {
            ContentCountry.DEFAULT
        } else {
            ContentCountry(request.contentCountry)
        }

        if (!initialized) {
            NewPipe.init(PipePipeDownloader(), localization, country)

            /*
             * Decipher on device instead of at api.pipepipe.dev.
             *
             * Registered here rather than lazily because the very first thing a
             * cold extraction needs is the signature timestamp, and without a
             * local decoder that call reaches PipePipe's infrastructure BEFORE
             * YouTube (CLAUDE.md, Gotcha 5). Registering after the first
             * extraction would leak exactly the request we are trying to avoid.
             *
             * Not a hard switch: PipePipe still falls back to its API if the
             * local decoder throws. The fallback is one-way and permanent
             * though, which is why PipeLocalPlayerDecoder swallows transient
             * failures rather than letting them escape.
             */
            YoutubeApiDecoder.setLocalDecoder(PipeLocalPlayerDecoder())

            initialized = true
            return
        }

        // Localisation feeds InnerTube's hl/gl, so it changes what YouTube
        // returns, not just how it is formatted. Honour a per-call override.
        NewPipe.setPreferredLocalization(localization)
        NewPipe.setPreferredContentCountry(country)
    }

    @Throws(Exception::class)
    override fun extractStreamInfo(request: ExtractionRequest): JSObject =
        withLocalization(request) { extractLocked(request) }

    private fun extractLocked(request: ExtractionRequest): JSObject {
        synchronized(CLIENT_LOCK) {
            try {
                /*
                 * SponsorBlock rides on the same global-service-state
                 * discipline as the player client: StreamInfo.getInfo only
                 * fetches segments while the service carries settings, so they
                 * are installed per-call under CLIENT_LOCK and always cleared,
                 * or one caller's opt-in would leak into everyone else's
                 * extractions.
                 */
                if (request.sponsorBlock) {
                    ServiceList.YouTube.setSponsorBlockApiSettings(SPONSOR_BLOCK_SETTINGS)
                }

                // Pass 1: the default client, which returns progressive/DASH
                // URLs. Preferred whenever it works — a plain URL plays
                // anywhere, with no session to drive, no PO token to mint and
                // no WebView.
                var directFailure: Exception? = null
                try {
                    NewPipe.setYoutubePlayerClient(CLIENT_DEFAULT)
                    val info = StreamInfo.getInfo(ServiceList.YouTube, request.videoUrl)
                    if (hasAnyStream(info)) {
                        return map(info)
                    }
                } catch (e: Exception) {
                    // Typically ContentNotSupportedException for SABR-only videos.
                    directFailure = e
                }

                // Age gates, geo blocks and private videos fail identically on
                // mweb — the same set the engine chain consults, for the same
                // reason: the retry is a guaranteed-failure round trip.
                if (directFailure != null &&
                    PipeExtractor.NOT_WORTH_RETRYING.contains(directFailure.javaClass.simpleName)
                ) {
                    throw directFailure
                }

                // Pass 2: retry on mweb, which routes through SABR. Only
                // reached when pass 1 produced nothing playable.
                try {
                    NewPipe.setYoutubePlayerClient(CLIENT_SABR)
                    return map(StreamInfo.getInfo(ServiceList.YouTube, request.videoUrl))
                } catch (sabrFailure: Exception) {
                    if (directFailure != null) {
                        // The direct failure is usually the more descriptive one
                        // (age gate, geo block, private), so lead with it.
                        directFailure.addSuppressed(sabrFailure)
                        throw directFailure
                    }
                    throw sabrFailure
                } finally {
                    NewPipe.setYoutubePlayerClient(CLIENT_DEFAULT)
                }
            } finally {
                if (request.sponsorBlock) {
                    ServiceList.YouTube.setSponsorBlockApiSettings(null)
                }
            }
        }
    }

    /**
     * Extract the SABR session descriptor for a video.
     *
     * Forces the `mweb` path, because that is what makes the extractor
     * build SABR streams at all. The descriptor rides along on each SABR stream
     * as its `deliveryMethodInfo`, so this costs one extraction and no
     * separate probe request.
     *
     * @return the descriptor and the video duration in ms
     * @throws ExtractionException when the video is not served over SABR
     */
    @Throws(Exception::class)
    fun extractSabrInfo(request: ExtractionRequest): SabrExtraction = withLocalization(request) {
        synchronized(CLIENT_LOCK) {
            try {
                NewPipe.setYoutubePlayerClient(CLIENT_SABR)
                val info = StreamInfo.getInfo(ServiceList.YouTube, request.videoUrl)

                val sabrInfo = findSabrInfo(info)
                    ?: throw ExtractionException(
                        "This video is not served over SABR; play its stream URLs directly",
                    )
                SabrExtraction(sabrInfo, info.duration * 1000L, isLive(info))
            } finally {
                NewPipe.setYoutubePlayerClient(CLIENT_DEFAULT)
            }
        }
    }

    /** Result of [extractSabrInfo]. */
    class SabrExtraction internal constructor(
        @JvmField val info: YoutubeSabrInfo,
        @JvmField val durationMs: Long,
        @JvmField val live: Boolean,
    )

    companion object {

        const val ID = "pipepipe"

        /**
         * The extractor picks its path from a global player-client setting:
         *
         * ```
         * if ("mweb".equals(client) && !live && hasSabrStreamingUrl())
         *     buildSabrStreams(...);   // SABR wins whenever a SABR URL exists
         * else
         *     extractDirectFormats(...);
         * ```
         *
         * So `mweb` is not "enable SABR support", it is "prefer SABR over
         * progressive/DASH". Pinning it globally would force a session driver onto
         * videos that have a perfectly good URL. We therefore extract with the
         * default client first and only retry on `mweb` when that yields
         * nothing playable.
         */
        private const val CLIENT_DEFAULT = "visionos"
        private const val CLIENT_SABR = "mweb"

        /**
         * Installed on the YouTube service only while an opted-in extraction
         * is running — see extractLocked. The URL is the public SponsorBlock
         * API, matching PipePipeClient's default; every category is requested
         * because filtering is the caller's decision, not ours. Never mutated
         * after construction, so one shared instance is safe.
         */
        private val SPONSOR_BLOCK_SETTINGS = SponsorBlockApiSettings().apply {
            apiUrl = "https://sponsor.ajay.app/api/"
            includeSponsorCategory = true
            includeIntroCategory = true
            includeOutroCategory = true
            includeInteractionCategory = true
            includeHighlightCategory = true
            includeSelfPromoCategory = true
            includeMusicCategory = true
            includePreviewCategory = true
            includeFillerCategory = true
        }

        /**
         * `youtubePlayerClient` is global mutable state inside the extractor,
         * so the two-pass extraction below must not interleave with another call.
         */
        private val CLIENT_LOCK = Any()

        /**
         * Install the PO token resolver used for the MWEB player request.
         *
         * Must be called before extracting SABR content. The extractor never
         * mints tokens itself — the host runs BotGuard in a WebView and supplies the
         * result here.
         */
        @JvmStatic
        fun setPoTokenResolver(resolver: Function<String, YoutubePoTokenResult?>?) {
            NewPipe.setYoutubePoTokenResolver(resolver)
        }

        private fun findSabrInfo(info: StreamInfo): YoutubeSabrInfo? {
            val groups: List<List<Stream>> =
                listOf(info.videoOnlyStreams, info.audioStreams, info.videoStreams)
            for (group in groups) {
                for (stream in group) {
                    if (stream.deliveryMethod != DeliveryMethod.SABR) {
                        continue
                    }
                    val attached = stream.deliveryMethodInfo
                    if (attached is YoutubeSabrInfo) {
                        return attached
                    }
                }
            }
            return null
        }

        private fun hasAnyStream(info: StreamInfo): Boolean =
            info.videoStreams.isNotEmpty() ||
                info.videoOnlyStreams.isNotEmpty() ||
                info.audioStreams.isNotEmpty()

        private fun map(info: StreamInfo): JSObject {
            val out = JSObject()

            out.put("id", info.id)
            out.put("url", info.url)
            out.put("title", info.name)
            out.put("duration", info.duration)
            out.put("streamType", info.streamType.name)
            out.put("isLive", isLive(info))

            out.put("uploader", info.uploaderName)
            out.put("uploaderUrl", info.uploaderUrl)
            out.put("uploaderAvatarUrl", info.uploaderAvatarUrl)
            out.put("uploaderVerified", info.isUploaderVerified)
            out.put("uploaderSubscriberCount", info.uploaderSubscriberCount)

            out.put("viewCount", info.viewCount)
            out.put("likeCount", info.likeCount)
            out.put("dislikeCount", info.dislikeCount)

            val description = info.description
            if (description != null) {
                out.put("description", description.content)
            }
            out.put("textualUploadDate", info.textualUploadDate)
            val uploadDate = info.uploadDate
            if (uploadDate != null) {
                out.put("uploadDate", uploadDate.offsetDateTime().toString())
            }
            out.put("category", info.category)
            out.put("tags", JSArray(info.tags))

            out.put("thumbnailUrl", info.thumbnailUrl)
            out.put("thumbnails", mapThumbnails(info.thumbnails))

            val videoStreams = JSArray()
            for (stream in info.videoStreams) {
                videoStreams.put(mapVideo(stream))
            }
            out.put("videoStreams", videoStreams)

            val videoOnly = JSArray()
            for (stream in info.videoOnlyStreams) {
                videoOnly.put(mapVideo(stream))
            }
            out.put("videoOnlyStreams", videoOnly)

            val audioStreams = JSArray()
            for (stream in info.audioStreams) {
                audioStreams.put(mapAudio(stream))
            }
            out.put("audioStreams", audioStreams)

            val subtitles = JSArray()
            for (subtitle in info.subtitles) {
                subtitles.put(mapSubtitle(subtitle))
            }
            out.put("subtitles", subtitles)

            val segments = info.sponsorBlockSegments
            if (segments != null && segments.isNotEmpty()) {
                val mapped = JSArray()
                for (segment in segments) {
                    val entry = JSObject()
                    entry.put("uuid", segment.uuid)
                    entry.put("category", segment.category?.name)
                    entry.put("action", segment.action?.name)
                    // The extractor stores these as fractional milliseconds.
                    entry.put("startTimeMs", segment.startTime.toLong())
                    entry.put("endTimeMs", segment.endTime.toLong())
                    mapped.put(entry)
                }
                out.put("sponsorBlockSegments", mapped)
            }

            out.put("requiresSabr", requiresSabr(info))
            return out
        }

        /**
         * True when nothing in this result can be fetched by URL.
         *
         * SABR streams carry the serverAbrStreamingUrl as their content purely
         * for reference — `isUrl` is false and there is no per-format URL — so
         * a caller that ignores this flag sees plausible-looking formats that cannot
         * be played.
         */
        private fun requiresSabr(info: StreamInfo): Boolean {
            val anySabr = hasSabr(info.videoStreams) ||
                hasSabr(info.videoOnlyStreams) ||
                hasSabr(info.audioStreams)
            if (!anySabr) {
                return false
            }
            val anyPlayableUrl = hasPlayableUrl(info.videoStreams) ||
                hasPlayableUrl(info.videoOnlyStreams) ||
                hasPlayableUrl(info.audioStreams)
            return !anyPlayableUrl
        }

        private fun hasSabr(streams: List<Stream>): Boolean {
            for (stream in streams) {
                if (stream.deliveryMethod == DeliveryMethod.SABR) {
                    return true
                }
            }
            return false
        }

        private fun hasPlayableUrl(streams: List<Stream>): Boolean {
            for (stream in streams) {
                if (stream.deliveryMethod != DeliveryMethod.SABR && stream.isUrl) {
                    return true
                }
            }
            return false
        }

        private fun isLive(info: StreamInfo): Boolean = when (info.streamType) {
            StreamType.LIVE_STREAM, StreamType.AUDIO_LIVE_STREAM -> true
            else -> false
        }

        private fun mapThumbnails(images: List<Image>?): JSArray {
            val out = JSArray()
            if (images == null) {
                return out
            }
            for (image in images) {
                val entry = JSObject()
                entry.put("url", image.url)
                entry.put("width", image.width)
                entry.put("height", image.height)
                entry.put("estimatedResolutionLevel", image.estimatedResolutionLevel.name)
                out.put(entry)
            }
            return out
        }

        private fun putBase(out: JSObject, stream: Stream) {
            out.put("content", stream.content)
            out.put("isUrl", stream.isUrl)
            out.put("deliveryMethod", stream.deliveryMethod.name)
            val format = stream.format
            out.put("format", format?.name)
            if (format != null) {
                out.put("mimeType", format.mimeType)
            }
        }

        private fun mapVideo(stream: VideoStream): JSObject {
            val out = JSObject()
            putBase(out, stream)
            out.put("resolution", stream.getResolution())
            out.put("width", stream.width)
            out.put("height", stream.height)
            out.put("fps", stream.fps)
            out.put("isVideoOnly", stream.isVideoOnly())
            out.put("itag", stream.itag)
            out.put("codec", stream.codec)
            out.put("initStart", stream.initStart)
            out.put("initEnd", stream.initEnd)
            out.put("indexStart", stream.indexStart)
            out.put("indexEnd", stream.indexEnd)
            return out
        }

        private fun mapAudio(stream: AudioStream): JSObject {
            val out = JSObject()
            putBase(out, stream)
            out.put("bitrate", stream.averageBitrate)
            out.put("itag", stream.itag)
            out.put("codec", stream.codec)
            out.put("audioTrackId", stream.audioTrackId)
            out.put("audioTrackName", stream.audioTrackName)
            out.put("initStart", stream.initStart)
            out.put("initEnd", stream.initEnd)
            out.put("indexStart", stream.indexStart)
            out.put("indexEnd", stream.indexEnd)
            return out
        }

        private fun mapSubtitle(stream: SubtitlesStream): JSObject {
            val out = JSObject()
            putBase(out, stream)
            out.put("languageCode", stream.languageTag)
            out.put("autoGenerated", stream.isAutoGenerated)
            return out
        }
    }
}
