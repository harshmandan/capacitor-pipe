/*
 * Derived from PipePipeClient's SabrDashMediaSource manifest synthesis.
 *   https://codeberg.org/NullPointerException/PipePipeClient
 *   app/src/main/java/org/schabi/newpipe/player/datasource/SabrDashMediaSource.java
 * Copyright (C) the PipePipe authors. Licensed under GPL-3.0-or-later.
 * Converted from Java to Kotlin; behaviour unchanged.
 *
 * Modifications: emits a standalone MPD string with HTTP-relative segment URLs
 * rather than sabrseg:// URIs parsed straight into a DashManifest, so the same
 * document serves both a native player and a web player over loopback.
 */
package ink.harsh.plugins.pipe.sabr

import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrFormatTimeline
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrInfo
import java.util.LinkedHashMap
import java.util.Locale
import java.util.Objects

/**
 * Synthesises a DASH manifest describing a SABR session.
 *
 * SABR serves no manifest of its own — the timing comes from index boxes
 * inside the init segments, which [PipeSabrBridge] parses. We turn that
 * into an MPD so ordinary players can drive the session without knowing SABR
 * exists.
 *
 * Segment URLs are emitted **relative** to the manifest, so the same document
 * works for the loopback server and for a native player resolving against a
 * synthetic base. With the manifest at
 * `http://127.0.0.1:P/<session>/manifest.mpd`, a segment resolves to
 * `http://127.0.0.1:P/<session>/<formatKey>/<number>`.
 *
 * Structure mirrors PipePipe's generator; keep them aligned when porting
 * fixes.
 */
object PipeSabrManifest {

    /**
     * Guard against a corrupt index producing a multi-million-entry timeline.
     * At typical ~5s segments this is still ~14 hours of media.
     */
    private const val MAX_SEGMENTS = 10_000

    @JvmStatic
    fun build(spec: PipeSabrSpec, bridge: PipeSabrBridge, durationMs: Long): String {
        val mpd = StringBuilder(4096)
        mpd.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            .append("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"static\" ")
            .append("profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\" ")
            .append("minBufferTime=\"PT1.5S\" mediaPresentationDuration=\"")
            .append(formatDuration(durationMs))
            .append("\"><Period id=\"0\" start=\"PT0S\">")

        videoAdaptationSet(mpd, spec, bridge)
        audioAdaptationSets(mpd, spec, bridge)

        return mpd.append("</Period></MPD>").toString()
    }

    private fun videoAdaptationSet(
        out: StringBuilder,
        spec: PipeSabrSpec,
        bridge: PipeSabrBridge,
    ) {
        val formats = usable(spec, spec.videoFormats, bridge)
        if (formats.isNotEmpty()) {
            adaptationSet(out, spec, bridge, formats, false, "0")
        }
    }

    /**
     * One AdaptationSet per audio track, so players expose a language selector.
     * Multi-language audio reuses a single itag across tracks, so grouping by
     * itag would collapse the languages into one.
     */
    private fun audioAdaptationSets(
        out: StringBuilder,
        spec: PipeSabrSpec,
        bridge: PipeSabrBridge,
    ) {
        val tracks: MutableMap<String, MutableList<YoutubeSabrInfo.Format>> = LinkedHashMap()
        for (format in usable(spec, spec.audioFormats, bridge)) {
            val trackId = Objects.toString(format.audioTrackId, "default")
            var group = tracks[trackId]
            if (group == null) {
                group = ArrayList()
                tracks[trackId] = group
            }
            group.add(format)
        }
        var index = 0
        for (track in tracks.entries) {
            adaptationSet(out, spec, bridge, track.value, true, (++index).toString())
        }
    }

    /**
     * Only formats this session can actually serve.
     *
     * **Two conditions, and both are load-bearing.** A timeline is needed to
     * place segments; an *init segment* is needed to decode them, and only the
     * bootstrapped audio and video formats have one — SABR selects a format up
     * front and the session streams that one.
     *
     * The init check used to be missing, and the timeline alone let this
     * advertise every format the extraction knew about: on a real device, 6
     * video and 8 audio Representations of which 2 were servable. Media3 then
     * did what an adaptive player does — picked a different Representation —
     * and every consumer failed the same way, because both of them go through
     * the same manifest: the loopback server answered `503 Initialisation not
     * ready` and `PipeSabrDataSource` threw `SABR initialisation missing`. A
     * manifest that offers what cannot be served is not a smaller bug than one
     * that offers nothing.
     */
    private fun usable(
        spec: PipeSabrSpec,
        formats: List<YoutubeSabrInfo.Format>,
        bridge: PipeSabrBridge,
    ): List<YoutubeSabrInfo.Format> {
        val out = ArrayList<YoutubeSabrInfo.Format>()
        for (format in formats) {
            if (spec.getInitializationData(format) == null) continue
            try {
                val timeline = bridge.getTimeline(format)
                val end = timeline.endSequence
                if (end > 0 && end <= MAX_SEGMENTS) {
                    out.add(format)
                }
            } catch (ignored: IllegalStateException) {
                // No timeline yet for this format.
            }
        }
        return out
    }

    private fun adaptationSet(
        out: StringBuilder,
        spec: PipeSabrSpec,
        bridge: PipeSabrBridge,
        formats: List<YoutubeSabrInfo.Format>,
        audio: Boolean,
        adaptationId: String,
    ) {
        val first = formats[0]

        out.append("<AdaptationSet id=\"").append(xml(adaptationId))
            .append("\" contentType=\"").append(if (audio) "audio" else "video")
            .append("\" mimeType=\"").append(xml(containerMimeType(first)))
            .append("\" segmentAlignment=\"true\" startWithSAP=\"1\"")

        if (audio) {
            val language = audioLanguage(first)
            if (language != null) {
                out.append(" lang=\"").append(xml(language)).append('"')
            }
            out.append('>')
            val label = first.audioTrackDisplayName
            if (label != null && label.isNotEmpty()) {
                out.append("<Label>").append(xml(label)).append("</Label>")
            }
            if (first.isOriginalAudio) {
                out.append("<Role schemeIdUri=\"urn:mpeg:dash:role:2011\" value=\"main\"/>")
            }
        } else {
            out.append('>')
        }

        for (format in formats) {
            val key = spec.getFormatKey(format)
            out.append("<Representation id=\"").append(xml(key))
                .append("\" bandwidth=\"").append(Math.max(1, format.bitrate))
                .append('"')

            val codecs = codecs(format)
            if (codecs != null && codecs.isNotEmpty()) {
                out.append(" codecs=\"").append(xml(codecs)).append('"')
            }
            if (audio) {
                out.append(" audioSamplingRate=\"48000\"")
            } else {
                out.append(" width=\"").append(Math.max(1, format.width))
                    .append("\" height=\"").append(Math.max(1, format.height))
                    .append('"')
            }

            // Relative BaseURL — resolved against wherever the manifest is served.
            out.append("><BaseURL>").append(xml(key)).append("/</BaseURL>")
            segmentTemplate(out, bridge.getTimeline(format))
            out.append("</Representation>")
        }

        out.append("</AdaptationSet>")
    }

    /**
     * An explicit SegmentTimeline, not a fixed duration.
     *
     * YouTube's segments are not uniform, and a player that assumes they are
     * will request the wrong sequence number after a seek — which SABR punishes,
     * since the request must state honestly what is buffered.
     */
    private fun segmentTemplate(out: StringBuilder, timeline: YoutubeSabrFormatTimeline) {
        out.append("<SegmentTemplate timescale=\"1000\" startNumber=\"1\" ")
            .append("initialization=\"init\" media=\"\$Number\$\">")
            .append("<SegmentTimeline>")

        val end = timeline.endSequence
        for (sequence in 1..end) {
            val startMs = timeline.getStartMs(sequence)
            val durationMs = Math.max(1L, timeline.getEndMs(sequence) - startMs)
            out.append("<S t=\"").append(Math.max(0L, startMs))
                .append("\" d=\"").append(durationMs).append("\"/>")
        }

        out.append("</SegmentTimeline></SegmentTemplate>")
    }

    private fun audioLanguage(format: YoutubeSabrInfo.Format): String? {
        val trackId = format.audioTrackId
        if (trackId == null || trackId.isEmpty()) {
            return null
        }
        return trackId.split("[._-]".toRegex(), 2)[0]
    }

    private fun formatDuration(durationMs: Long): String {
        val safe = Math.max(1L, durationMs)
        return "PT" + (safe / 1000) + "." + String.format(Locale.US, "%03d", safe % 1000) + "S"
    }

    private fun containerMimeType(format: YoutubeSabrInfo.Format): String {
        val mime = format.mimeType
        if (mime == null || mime.isEmpty()) {
            return if (format.isAudio) "audio/mp4" else "video/mp4"
        }
        val semicolon = mime.indexOf(';')
        return if (semicolon >= 0) mime.substring(0, semicolon).trim() else mime.trim()
    }

    private fun codecs(format: YoutubeSabrInfo.Format): String? {
        val mime = format.mimeType ?: return null
        val start = mime.indexOf("codecs=")
        if (start < 0) {
            return null
        }
        return mime.substring(start + "codecs=".length).replace("\"", "").trim()
    }

    private fun xml(value: String): String = value.replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
