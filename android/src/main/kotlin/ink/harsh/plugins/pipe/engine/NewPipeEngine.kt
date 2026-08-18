package ink.harsh.plugins.pipe.engine

import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import ink.harsh.pipe.shaded.org.schabi.newpipe.extractor.Image
import ink.harsh.pipe.shaded.org.schabi.newpipe.extractor.NewPipe
import ink.harsh.pipe.shaded.org.schabi.newpipe.extractor.ServiceList
import ink.harsh.pipe.shaded.org.schabi.newpipe.extractor.localization.ContentCountry
import ink.harsh.pipe.shaded.org.schabi.newpipe.extractor.localization.Localization
import ink.harsh.pipe.shaded.org.schabi.newpipe.extractor.stream.AudioStream
import ink.harsh.pipe.shaded.org.schabi.newpipe.extractor.stream.Stream
import ink.harsh.pipe.shaded.org.schabi.newpipe.extractor.stream.StreamInfo
import ink.harsh.pipe.shaded.org.schabi.newpipe.extractor.stream.StreamType
import ink.harsh.pipe.shaded.org.schabi.newpipe.extractor.stream.SubtitlesStream
import ink.harsh.pipe.shaded.org.schabi.newpipe.extractor.stream.VideoStream
import ink.harsh.plugins.pipe.net.NewPipeDownloader
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * Fallback engine: NewPipeExtractor, relocated to
 * `ink.harsh.pipe.shaded.org.schabi.newpipe.extractor`.
 *
 * The imports above are what makes two engines possible at once — see
 * CLAUDE.md, Gotcha 2. They must track the relocation prefix in
 * `tools/shade/build.gradle`.
 *
 * Deliberately narrower than [PipePipeEngine]: no SABR (this fork has
 * no `DeliveryMethod.SABR`), no SponsorBlock, and no restored dislikes.
 * Its value is that it deciphers signatures locally in Rhino instead of calling
 * a remote service, so it survives outages that take the primary down.
 */
class NewPipeEngine : ExtractionEngine {

    @Volatile
    private var initialized = false

    /**
     * Guards this fork's global localization state; see the identical
     * arrangement in PipePipeEngine. The two engines have independent statics
     * (that is the point of the relocation), so each carries its own lock.
     */
    private val localizationLock = ReentrantReadWriteLock()

    @Volatile
    private var appliedLocale: String? = null

    override fun id(): String = ID

    override fun isAvailable(): Boolean = try {
        Class.forName("ink.harsh.pipe.shaded.org.schabi.newpipe.extractor.NewPipe")
        true
    } catch (ignored: Throwable) {
        false
    }

    override fun version(): String = "NewPipeExtractor"

    private fun localeKey(request: ExtractionRequest): String =
        (request.localization ?: "") + "|" + (request.contentCountry ?: "")

    /**
     * Run `block` with the request's locale applied and held stable — the
     * matching-locale fast path stays concurrent on the read lock; a locale
     * change holds the write lock for its whole extraction.
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
        // This fork returns Optional here; PipePipe's returns the Localization
        // directly. Same method name, different signature — one of several
        // reasons the two engines cannot share mapping code.
        val localization = if (request.localization == null) {
            Localization.DEFAULT
        } else {
            Localization.fromLocalizationCode(request.localization)
                .orElse(Localization.DEFAULT)
        }
        val country = if (request.contentCountry == null) {
            ContentCountry.DEFAULT
        } else {
            ContentCountry(request.contentCountry)
        }

        if (!initialized) {
            // A different NewPipe class from the one PipePipeEngine initialises,
            // with its own static downloader. Initialising one does not disturb
            // the other — that independence is the point of the relocation.
            NewPipe.init(NewPipeDownloader(), localization, country)
            initialized = true
            return
        }

        NewPipe.setPreferredLocalization(localization)
        NewPipe.setPreferredContentCountry(country)
    }

    @Throws(Exception::class)
    override fun extractStreamInfo(request: ExtractionRequest): JSObject =
        withLocalization(request) {
            map(StreamInfo.getInfo(ServiceList.YouTube, request.videoUrl))
        }

    companion object {

        const val ID = "newpipe"

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
            out.put("uploaderAvatarUrl", firstImageUrl(info.uploaderAvatars))
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

            // This fork has no getThumbnailUrl() shortcut, so derive it.
            out.put("thumbnailUrl", firstImageUrl(info.thumbnails))
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

            // This fork cannot produce SABR streams at all, so anything it returns
            // is fetchable by URL.
            out.put("requiresSabr", false)
            return out
        }

        private fun isLive(info: StreamInfo): Boolean = when (info.streamType) {
            StreamType.LIVE_STREAM, StreamType.AUDIO_LIVE_STREAM -> true
            else -> false
        }

        private fun firstImageUrl(images: List<Image>?): String? {
            if (images == null || images.isEmpty()) {
                return null
            }
            var best = images[0]
            for (image in images) {
                if (image.height > best.height) {
                    best = image
                }
            }
            return best.url
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
