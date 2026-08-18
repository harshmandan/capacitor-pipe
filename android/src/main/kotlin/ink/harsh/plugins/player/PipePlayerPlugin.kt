package ink.harsh.plugins.player

import android.graphics.RectF
import android.util.Log
import androidx.media3.common.util.UnstableApi
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import androidx.compose.ui.platform.AndroidUiDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Optional native player, registered separately from the extractor.
 *
 * Two independent `@CapacitorPlugin` classes share this package. An app that
 * only extracts never registers this one and never ships its dependencies —
 * Media3 and Compose are both `compileOnly` here, so a consuming app that wants
 * playback puts them on its own classpath.
 *
 * That optionality is silent until runtime: an app without Media3 that calls
 * these methods would get `NoClassDefFoundError`. Every entry point therefore
 * checks [isAvailable] first and rejects with something readable, and
 * `getPlayerStatus` exists so callers can ask before trying.
 *
 * Phase 1 scope: prove a Compose surface composites over the host WebView
 * with Media3 playing into a docked TextureView. No gestures, no extraction
 * wiring — those are phases 2 and 5.
 */
private const val TAG = "PipePlayerPlugin"

@CapacitorPlugin(name = "PipePlayer")
open class PipePlayerPlugin : Plugin() {

    /**
     * Resolved reflectively, never by a direct reference: touching the overlay
     * type at all would load Media3 and Compose classes and defeat the point.
     */
    private val overlay: PipePlayerOverlay by lazy { createOverlay() }

    /**
     * Drives the motion animations.
     *
     * Must be AndroidUiDispatcher.Main, not MainScope(): Compose's Animatable
     * needs a MonotonicFrameClock in its context to schedule frames, and a plain
     * main-thread scope has none — animateTo throws IllegalStateException. The
     * gesture path gets one for free from rememberCoroutineScope(); this
     * non-composable entry point has to supply it.
     */
    private val scope = CoroutineScope(AndroidUiDispatcher.Main)

    @UnstableApi
    private fun createOverlay(): PipePlayerOverlay =
        PipePlayerOverlay(activity).also { created ->
            /*
             * Dock rects come from getBoundingClientRect, so they are relative to
             * the WebView's viewport rather than the screen. Handing the overlay
             * the actual WebView lets it measure that offset instead of assuming
             * the two origins coincide — they do not once a status bar, a
             * notch or an edge-to-edge host is involved.
             */
            created.hostView = bridge?.webView

            // Chrome taps the host cares about — quality, speed, minimise, and
            // any consumer button — surface as one event rather than a listener
            // per control.
            created.onChromeEvent = { action, buttonId ->
                notifyListeners(
                    "playerAction",
                    JSObject().put("action", action).put("buttonId", buttonId),
                )
            }
        }

    /**
     * Options for a sheet, as `[{ id, label }]` or plain strings.
     *
     * Plain strings are accepted because speeds and qualities are usually
     * their own label — making a caller write `{id:'1x',label:'1x'}` would be
     * ceremony for nothing.
     */
    private fun sheetOptions(call: PluginCall, key: String): List<SheetOption>? {
        val array = call.getArray(key, null) ?: return null
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { item ->
                val id = item.optString("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                SheetOption(id, item.optString("label").ifEmpty { id })
            } ?: array.optString(index).takeIf { it.isNotEmpty() }?.let { SheetOption(it, it) }
        }
    }

    /** Accepts #RGB, #RRGGBB and #AARRGGBB. */
    private fun parseColour(value: String): Int? =
        runCatching { android.graphics.Color.parseColor(value) }.getOrNull()

    override fun handleOnDestroy() {
        scope.cancel()
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
        // Format modules are loaded reflectively at playback time, so their
        // absence is otherwise invisible until a stream fails to open.
        result.put("hlsAvailable", hasClass("androidx.media3.exoplayer.hls.HlsMediaSource"))
        result.put("dashAvailable", hasClass("androidx.media3.exoplayer.dash.DashMediaSource"))
        result.put(
            "pipSupported",
            available && runCatching { overlay.pipSupported }.getOrDefault(false),
        )
        // Reported separately from pipSupported so a host can tell "this device
        // cannot do PiP" apart from "you did not add the dependency".
        result.put("corePipAvailable", hasClass("androidx.core.pip.VideoPlaybackPictureInPicture"))
        result.put(
            "media3UiComposeAvailable",
            hasClass("androidx.media3.ui.compose.modifiers.ExtensionsKt"),
        )
        call.resolve(result)
    }

    /**
     * Claim a rect for the player, in CSS pixels.
     *
     * Hosts declare where their reserved space is rather than commanding the
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

        /*
         * The web side measures in CSS pixels; views are in device pixels. Trust
         * the WebView's own devicePixelRatio when it sends one — assuming it
         * matches resources.density leaves the video short of the reserved rect
         * wherever the two disagree.
         */
        val density = call.getFloat("dpr") ?: activity.resources.displayMetrics.density
        Log.i(TAG, "dock css=($x,$y,${width}x$height) dpr=$density")

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

    /**
     * Animate to fullscreen or back.
     *
     * The gesture is the primary way in; this exists for hosts that want a
     * button, and for tests that cannot swipe.
     */
    @PluginMethod
    fun setFullscreen(call: PluginCall) {
        if (rejectIfUnavailable(call)) return
        val fullscreen = call.getBoolean("fullscreen", true) ?: true
        activity.runOnUiThread {
            scope.launch {
                overlay.setFullscreen(fullscreen)
                call.resolve()
            }
        }
    }

    /**
     * The player's only styling hook, plus its two extension points.
     *
     * Accent colour is the whole of the theming surface — layout, sizing and
     * timing are fixed, because the interaction is the product. Previous/next
     * are off unless a host has a queue, and extra buttons get our chrome with
     * the consumer's glyph so additions cannot look bolted on.
     */
    @PluginMethod
    fun configure(call: PluginCall) {
        if (rejectIfUnavailable(call)) return

        val accent = call.getString("accentColor")?.let(::parseColour)
        val showPreviousNext = call.getBoolean("showPreviousNext")
        val title = call.getString("title")

        // Exactly one custom slot. The top row is speed, quality, then yours —
        // a variable number of consumer buttons would push the fixed ones around
        // and cost them their stable position.
        val button = call.getObject("button", null)?.let { item ->
            val id = item.optString("id").takeIf { it.isNotEmpty() }
            val icon = item.optString("icon").takeIf { it.isNotEmpty() }
            if (id != null && icon != null) PipePlayerExtraButton(id, icon) else null
        }

        // Both default to OFF and stay off unless asked for. PiP hands the
        // host's whole window to the system and FLAG_SECURE blacks out the
        // host's own page, so neither is ours to switch on by inference.
        val pip = call.getBoolean("pip")
        val secure = call.getBoolean("secure")

        /*
         * Mini geometry. Absent keys keep their current value rather than
         * resetting to the default, so a host can adjust one padding without
         * restating the whole block.
         */
        val miniOptions = call.getObject("mini", null)?.let { item ->
            val current = PipePlayerMiniConfig()
            PipePlayerMiniConfig(
                width = item.optDouble("width", current.width.toDouble()).toFloat(),
                corner = PipePlayerCorner.parse(item.optString("corner")),
                paddingLeft = item.optDouble("paddingLeft", current.paddingLeft.toDouble()).toFloat(),
                paddingTop = item.optDouble("paddingTop", current.paddingTop.toDouble()).toFloat(),
                paddingRight = item.optDouble("paddingRight", current.paddingRight.toDouble()).toFloat(),
                paddingBottom = item.optDouble("paddingBottom", current.paddingBottom.toDouble()).toFloat(),
                draggable = item.optBoolean("draggable", current.draggable),
            )
        }

        activity.runOnUiThread {
            pip?.let { overlay.setPipEnabled(it) }
            secure?.let { overlay.setSecure(it) }
            overlay.configure(
                accentColor = accent,
                mini = miniOptions,
                showPreviousNext = showPreviousNext,
                title = title,
                subtitle = call.getString("subtitle"),
                speedLabel = call.getString("speedLabel"),
                qualityLabel = call.getString("qualityLabel"),
                speeds = sheetOptions(call, "speeds"),
                qualities = sheetOptions(call, "qualities"),
                extraButton = button,
            )
            call.resolve()
        }
    }

    /**
     * Shrink to the corner window, or come back from it.
     *
     * The same player animates there; it is not a second, smaller player. That
     * matters because playback is never interrupted — which is the entire reason
     * the overlay lives on the Activity rather than in the page.
     */
    @PluginMethod
    fun setMini(call: PluginCall) {
        if (rejectIfUnavailable(call)) return
        val mini = call.getBoolean("mini", true) ?: true
        activity.runOnUiThread {
            overlay.setMini(mini)
            call.resolve()
        }
    }

    /**
     * Enter Picture-in-Picture now.
     *
     * Requires `pip: true` from [configure] first, and requires the **host** to
     * have declared the activity as PiP-capable in its own manifest — see the
     * README. On API 31+ the system enters PiP by itself when the user swipes
     * home, so this is for an explicit button.
     */
    @PluginMethod
    fun enterPip(call: PluginCall) {
        if (rejectIfUnavailable(call)) return
        activity.runOnUiThread {
            val entered = overlay.enterPip()
            call.resolve(JSObject().put("entered", entered))
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

        /*
         * media3-ui-compose belongs in this check, not just in the docs.
         *
         * PipePlayerSurface references `resizeWithContentScale` directly, so the
         * class fails to load without it — an app that shipped Media3 and
         * Compose but not this would get NoClassDefFoundError on first play
         * instead of the readable rejection every other missing dependency
         * produces. Optionality here is player-vs-extractor, not per-artifact
         * within the player.
         */
        val available: Boolean by lazy {
            hasClass("androidx.media3.exoplayer.ExoPlayer") &&
                hasClass("androidx.compose.ui.platform.ComposeView") &&
                hasClass("androidx.media3.ui.compose.modifiers.ExtensionsKt")
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
