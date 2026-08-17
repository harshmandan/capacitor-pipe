package ink.harsh.plugins.pipe.sabr

import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrInfo
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * The formats a SABR session offers, plus a stable key for each.
 *
 * The key is what appears in the synthesized DASH manifest and in segment
 * URLs, so it must be stable for the session's lifetime and unique per format.
 * itag alone is not enough: multi-language audio serves the same itag once per
 * track, so the key folds in the audio track id.
 */
class PipeSabrSpec(info: YoutubeSabrInfo) {

    val videoId: String = info.videoId
    val audioFormats: List<YoutubeSabrInfo.Format>
    val videoFormats: List<YoutubeSabrInfo.Format>

    private val byKey: MutableMap<String, YoutubeSabrInfo.Format> = LinkedHashMap()
    private val keyOf: MutableMap<YoutubeSabrInfo.Format, String> = LinkedHashMap()

    /** Initialisation bytes per format, populated as init segments arrive. */
    private val initializationData: MutableMap<String, ByteArray> = ConcurrentHashMap()

    init {
        val audio = ArrayList<YoutubeSabrInfo.Format>()
        val video = ArrayList<YoutubeSabrInfo.Format>()
        for (format in info.formats) {
            if (format.isAudio) {
                audio.add(format)
            } else if (format.isVideo) {
                video.add(format)
            }
        }
        audioFormats = Collections.unmodifiableList(audio)
        videoFormats = Collections.unmodifiableList(video)

        for (format in audioFormats) {
            register(format, "a")
        }
        for (format in videoFormats) {
            register(format, "v")
        }
    }

    private fun register(format: YoutubeSabrInfo.Format, kind: String) {
        val key = StringBuilder(kind).append(format.itag)
        val trackId = format.audioTrackId
        if (trackId != null && trackId.isNotEmpty()) {
            key.append('-').append(trackId.replace("[^A-Za-z0-9]".toRegex(), ""))
        }
        // Defensive: an unexpected duplicate would silently collapse two formats
        // into one manifest Representation.
        var candidate = key.toString()
        var suffix = 1
        while (byKey.containsKey(candidate)) {
            candidate = key.toString() + "_" + suffix++
        }
        byKey[candidate] = format
        keyOf[format] = candidate
    }

    fun getFormat(key: String): YoutubeSabrInfo.Format? = byKey[key]

    fun getFormatKey(format: YoutubeSabrInfo.Format): String =
        keyOf[format] ?: throw IllegalStateException("Format not registered: itag=" + format.itag)

    /**
     * The format each track starts on.
     *
     * SABR requires a concrete selection up front to open the session; the
     * player can switch later. Highest bitrate audio and highest resolution
     * video, matching the order the extractor already sorts them in.
     */
    fun bootstrapAudio(): YoutubeSabrInfo.Format? {
        var best: YoutubeSabrInfo.Format? = null
        for (format in audioFormats) {
            if (best == null || format.bitrate > best.bitrate) {
                best = format
            }
        }
        return best
    }

    fun bootstrapVideo(): YoutubeSabrInfo.Format? {
        var best: YoutubeSabrInfo.Format? = null
        for (format in videoFormats) {
            if (best == null || format.height > best.height) {
                best = format
            }
        }
        return best
    }

    fun putInitializationData(format: YoutubeSabrInfo.Format, data: ByteArray) {
        initializationData[getFormatKey(format)] = data
    }

    fun getInitializationData(format: YoutubeSabrInfo.Format): ByteArray? =
        initializationData[getFormatKey(format)]
}
