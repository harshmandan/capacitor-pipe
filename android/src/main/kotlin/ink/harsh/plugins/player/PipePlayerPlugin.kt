package ink.harsh.plugins.player

import android.graphics.RectF
import androidx.media3.common.util.UnstableApi
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin

/**
 * Optional native player, registered separately from the extractor.
 *
 * <p>Two independent `@CapacitorPlugin` classes share this package. An app that
 * only extracts never registers this one and never ships its dependencies —
 * Media3 and Compose are both `compileOnly` here, so a consuming app that wants
 * playback puts them on its own classpath.
 *
 * <p>That optionality is silent until runtime: an app without Media3 that calls
 * these methods would get `NoClassDefFoundError`. Every entry point therefore
 * checks {@link #isAvailable} first and rejects with something readable, and
 * {@code getPlayerStatus} exists so callers can ask before trying.
 *
 * <p>Phase 1 scope: prove a Compose surface composites over the host WebView
 * with Media3 playing into a docked TextureView. No gestures, no extraction
 * wiring — those are phases 2 and 5.
 */
@CapacitorPlugin(name = "PipePlayer")
open class PipePlayerPlugin : Plugin() {

    /**
     * Resolved reflectively, never by a direct reference: touching the overlay
     * type at all would load Media3 and Compose classes and defeat the point.
     */
    private val overlay: PipePlayerOverlay by lazy { createOverlay() }

    @UnstableApi
    private fun createOverlay(): PipePlayerOverlay = PipePlayerOverlay(activity)

    override fun handleOnDestroy() {
        if (available) {
            runCatching { overlay.release() }
        }
        super.handleOnDestroy()
    }

    @PluginMethod
    fun getPlayerStatus(call: PluginCall) {
        val result = JSObject()
        result.put("available", available)
        result.put("media3Available", hasClass("androidx.media3.exoplayer.ExoPlayer"))
        result.put("composeAvailable", hasClass("androidx.compose.ui.platform.ComposeView"))
        result.put("attached", available && runCatching { overlay.isAttached }.getOrDefault(false))
        call.resolve(result)
    }

    /**
     * Claim a rect for the player, in CSS pixels.
     *
     * <p>Hosts declare where their reserved space is rather than commanding the
     * player into position; the player owns every transition between rects.
     */
    @PluginMethod
    fun dock(call: PluginCall) {
        if (rejectIfUnavailable(call)) return

        val x = call.getFloat("x")
        val y = call.getFloat("y")
        val width = call.getFloat("w")
        val height = call.getFloat("h")
        if (x == null || y == null || width == null || height == null) {
            call.reject("x, y, w and h are all required")
            return
        }

        // The web side measures in CSS pixels; views are in device pixels.
        val density = activity.resources.displayMetrics.density

        activity.runOnUiThread {
            overlay.attach()
            overlay.dock(
                RectF(
                    x * density,
                    y * density,
                    (x + width) * density,
                    (y + height) * density,
                ),
            )
            call.resolve()
        }
    }

    @PluginMethod
    fun undock(call: PluginCall) {
        if (rejectIfUnavailable(call)) return
        activity.runOnUiThread {
            overlay.undock()
            call.resolve()
        }
    }

    @PluginMethod
    fun load(call: PluginCall) {
        if (rejectIfUnavailable(call)) return
        val url = call.getString("url")
        if (url.isNullOrBlank()) {
            call.reject("url is required")
            return
        }
        activity.runOnUiThread {
            overlay.attach()
            overlay.load(url)
            call.resolve()
        }
    }

    @PluginMethod
    fun play(call: PluginCall) {
        if (rejectIfUnavailable(call)) return
        activity.runOnUiThread {
            overlay.play()
            call.resolve()
        }
    }

    @PluginMethod
    fun pause(call: PluginCall) {
        if (rejectIfUnavailable(call)) return
        activity.runOnUiThread {
            overlay.pause()
            call.resolve()
        }
    }

    @PluginMethod
    fun release(call: PluginCall) {
        if (rejectIfUnavailable(call)) return
        activity.runOnUiThread {
            overlay.release()
            call.resolve()
        }
    }

    private fun rejectIfUnavailable(call: PluginCall): Boolean {
        if (available) return false
        call.reject(
            "capacitor-pipe's player needs Media3 and Compose on the app's classpath. " +
                "Add androidx.media3:media3-exoplayer and androidx.compose.ui:ui, " +
                "or call getPlayerStatus() first.",
        )
        return true
    }

    private companion object {

        val available: Boolean by lazy {
            hasClass("androidx.media3.exoplayer.ExoPlayer") &&
                hasClass("androidx.compose.ui.platform.ComposeView")
        }

        fun hasClass(name: String): Boolean =
            try {
                Class.forName(name)
                true
            } catch (ignored: Throwable) {
                false
            }
    }
}
