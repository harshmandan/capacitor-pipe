package ink.harsh.plugins.pipe.media3

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.DefaultDashChunkSource
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.source.MediaSource
import ink.harsh.plugins.pipe.sabr.PipeSabrManager
import ink.harsh.plugins.pipe.sabr.PipeSabrSession
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * Builds a Media3 [MediaSource] for a SABR session.
 *
 * Entry point for apps that play with ExoPlayer or the Media3 UI rather than
 * a web player:
 *
 * ```
 * // sessionId comes back from Pipe.openSabrSession() over the bridge
 * MediaSource source = PipeSabrMedia3.mediaSource(sessionId);
 * player.setMediaSource(source);
 * player.prepare();
 * ```
 *
 * This is an optimisation, not a requirement: the same session is playable
 * from `manifestUrl` over loopback HTTP, which is what apps without
 * Media3 use. Going direct skips a socket and the cleartext exemption.
 *
 * Media3 is `compileOnly`. Calling anything here in an app that does
 * not ship Media3 throws [NoClassDefFoundError] — check
 * `getEngineStatus().media3Available` first.
 */
@UnstableApi
object PipeSabrMedia3 {

    /**
     * A DASH media source backed directly by the session's segment bridge.
     *
     * @param sessionId as returned by `openSabrSession`
     * @throws IOException if the session is unknown or its manifest will not parse
     */
    @JvmStatic
    @Throws(IOException::class)
    fun mediaSource(sessionId: String): MediaSource {
        val session = PipeSabrManager.lookup(sessionId)
            ?: throw IOException(
                "No open SABR session with id " + sessionId +
                    " — it may already have been closed",
            )
        return mediaSource(session)
    }

    @JvmStatic
    @Throws(IOException::class)
    fun mediaSource(session: PipeSabrSession): MediaSource {
        /*
         * The manifest's segment URLs are relative, so the base URI decides
         * where they resolve. Parsing against sabrseg://<session>/ turns them
         * into addresses PipeSabrDataSource understands, without the manifest
         * itself needing to know which transport is in use.
         */
        val base = Uri.Builder()
            .scheme(PipeSabrDataSource.SCHEME)
            .authority(session.id)
            .appendPath("")
            .build()

        val manifest = DashManifestParser().parse(
            base,
            ByteArrayInputStream(session.manifest.toByteArray(StandardCharsets.UTF_8)),
        )

        return DashMediaSource.Factory(
            DefaultDashChunkSource.Factory(PipeSabrDataSource.Factory(session)),
            // No manifest DataSource: the manifest is already in memory and is
            // static for the session's lifetime, so there is nothing to refetch.
            null,
        )
            .createMediaSource(manifest, MediaItem.fromUri(base))
    }
}
