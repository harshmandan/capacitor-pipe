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
 * checks availability first and rejects with something readable, and
 * `getPlayerStatus` exists so callers can ask before trying.
 */
private const val TAG = "PipePlayerPlugin"

@CapacitorPlugin(name = "PipePlayer")
open class PipePlayerPlugin : Plugin() {

    /**
     * Created on first use, tracked so teardown can tell "never used" apart
     * from "in use". This was `by lazy`, and `handleOnDestroy` reading it to
     * release it built the entire overlay object during Activity destruction
     * for apps that never docked a player.
     */
    private var overlayInstance: PipePlayerOverlay? = null

    private val overlay: PipePlayerOverlay
        get() = overlayInstance ?: createOverlay().also { overlayInstance = it }

    /**
     * The session epoch: bumped by [release] (and destroy) the moment the call
     * reaches the bridge, and captured by [dock] and [load] at the same point.
     *
     * Those two are the only methods that ATTACH the overlay, and both do their
     * work on a posted main-thread runnable — so there is a window between a
     * call arriving and its runnable running in which a `release()` can land.
     * Without the epoch, a load that was already in flight when the release
     * executed would re-attach the overlay it had just torn down: a surface
     * painted over the host's page with no owner left to take it down, PiP
     * re-armable behind it, and a prepared ExoPlayer nothing would release.
     *
     * The runnable therefore re-checks the epoch it captured and rejects as
     * stale instead of touching the overlay. A load issued AFTER the release
     * captures the new epoch and proceeds — release-then-load is the normal
     * way to start a fresh video.
     *
     * An AtomicInteger rather than an Int because the capture happens on the
     * bridge's thread and the bump-vs-check on whichever thread gets there
     * first; the epoch only ever grows, so a lost race between two releases
     * still invalidates every load older than either.
     */
    private val epoch = java.util.concurrent.atomic.AtomicInteger(0)

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

            /*
             * Where playback has got to. The overlay decides when this is worth
             * saying — roughly once a second while playing, and at once on a
             * play, pause, seek or end — so the bridge carries progress rather
             * than a 4 Hz sampler.
             */
            created.onPositionEvent = { position ->
                notifyListeners("playerPosition", positionPayload(position))
            }
        }

    private fun positionPayload(position: PipePlaybackPosition): JSObject =
        JSObject()
            .put("positionMs", position.positionMs)
            .put("durationMs", position.durationMs)
            .put("bufferedMs", position.bufferedMs)
            .put("playing", position.playing)
            .put("ended", position.ended)
            .put("live", position.live)

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

    /**
     * Accepts #RGB, #ARGB, #RRGGBB and #AARRGGBB.
     *
     * The short forms are expanded by hand because `Color.parseColor` throws
     * on them — the doc used to promise #RGB while silently dropping it.
     */
    private fun parseColour(value: String): Int? {
        val expanded = if (value.startsWith("#") && value.length in 4..5) {
            "#" + value.drop(1).map { digit -> "$digit$digit" }.joinToString("")
        } else {
            value
        }
        return runCatching { android.graphics.Color.parseColor(expanded) }.getOrNull()
    }

    override fun handleOnDestroy() {
        // Invalidate any dock/load still in flight: attaching to an Activity
        // that is being destroyed helps nobody.
        epoch.incrementAndGet()
        scope.cancel()
        // Only an overlay that exists needs releasing; `overlay` here would
        // have CREATED one just to tear it down.
        overlayInstance?.let { runCatching { it.release() } }
        super.handleOnDestroy()
    }

    @PluginMethod
    fun getPlayerStatus(call: PluginCall) {
        val result = JSObject()
        result.put("available", available)
        result.put("media3Available", hasPlayerClass("androidx.media3.exoplayer.ExoPlayer"))
        result.put("composeAvailable", hasPlayerClass("androidx.compose.ui.platform.ComposeView"))
        // overlayInstance, not the creating getter: asking "is it attached?"
        // must not build an overlay to hear "no".
        result.put("attached", overlayInstance?.isAttached == true)
        // Format modules are loaded reflectively at playback time, so their
        // absence is otherwise invisible until a stream fails to open.
        result.put("hlsAvailable", hasPlayerClass("androidx.media3.exoplayer.hls.HlsMediaSource"))
        result.put("dashAvailable", hasPlayerClass("androidx.media3.exoplayer.dash.DashMediaSource"))
        result.put(
            "pipSupported",
            available && runCatching { overlay.pipSupported }.getOrDefault(false),
        )
        // Reported separately from pipSupported so a host can tell "this device
        // cannot do PiP" apart from "you did not add the dependency".
        result.put("corePipAvailable", hasPlayerClass("androidx.core.pip.VideoPlaybackPictureInPicture"))
        result.put(
            "media3UiComposeAvailable",
            hasPlayerClass("androidx.media3.ui.compose.modifiers.ExtensionsKt"),
        )
        // overlayInstance again: a status question must not build a player to
        // answer "no, nothing offline is playing".
        result.put("playingOffline", overlayInstance?.playingOffline == true)
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

        // Captured before the post: a release() that lands between here and the
        // runnable running must win, because dock attaches — see [epoch].
        val generation = epoch.get()
        activity.runOnUiThread {
            if (generation != epoch.get()) {
                call.reject("the player was released while this dock was pending")
                return@runOnUiThread
            }
            // attach() throws on a host with no content view; a crash inside
            // runOnUiThread takes the app down, a rejection tells the caller.
            runCatching {
                overlay.attach()
                overlay.dock(
                    RectF(
                        x * density,
                        y * density,
                        (x + width) * density,
                        (y + height) * density,
                    ),
                )
            }.onSuccess { call.resolve() }
                .onFailure { call.reject("could not attach player: ${it.message}") }
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
        // AndroidUiDispatcher.Main already lands on the main thread — the old
        // runOnUiThread wrapper around this launch was a second hop for
        // nothing. The finally keeps the bridge call from dangling forever if
        // the scope dies mid-animation.
        scope.launch {
            try {
                overlay.setFullscreen(fullscreen)
            } finally {
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

        /*
         * Exactly one custom slot. The top row is speed, quality, then yours —
         * a variable number of consumer buttons would push the fixed ones
         * around and cost them their stable position.
         *
         * Presence and value travel separately: a `button` key that is present
         * but unparsable (or null) REMOVES the button, while a configure call
         * that never mentions `button` leaves an existing one alone — the same
         * keep-current contract every other field has.
         */
        val buttonProvided = call.data.has("button")
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

        val miniObject = call.getObject("mini", null)

        activity.runOnUiThread {
            /*
             * Mini geometry merges onto the LIVE config, not a fresh default.
             * Absent keys keep their current value, so a host can adjust one
             * padding without restating the whole block — seeded from a
             * default, `{mini:{paddingBottom:80}}` silently reset the width
             * and corner it never mentioned.
             */
            val miniOptions = miniObject?.let { item ->
                val current = overlay.currentMiniConfig
                PipePlayerMiniConfig(
                    width = item.optDouble("width", current.width.toDouble()).toFloat(),
                    corner = if (item.has("corner")) {
                        PipePlayerCorner.parse(item.optString("corner"))
                    } else {
                        current.corner
                    },
                    paddingLeft = item.optDouble("paddingLeft", current.paddingLeft.toDouble()).toFloat(),
                    paddingTop = item.optDouble("paddingTop", current.paddingTop.toDouble()).toFloat(),
                    paddingRight = item.optDouble("paddingRight", current.paddingRight.toDouble()).toFloat(),
                    paddingBottom = item.optDouble("paddingBottom", current.paddingBottom.toDouble()).toFloat(),
                    draggable = item.optBoolean("draggable", current.draggable),
                )
            }

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
                extraButtonProvided = buttonProvided,
                handleClose = call.getBoolean("handleClose"),
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

    /**
     * Load a URL or a set of local files, and prepare.
     *
     * Exactly one of `url` and `offline`. Both or neither rejects: a silent
     * fall back to the network would hide a broken download behind a data
     * charge.
     *
     * The request is parsed **before** `runOnUiThread`, so a malformed contract
     * rejects synchronously rather than on some later frame. The parsing itself
     * lives in [PipeLoadRequest] rather than here, so it can be tested without
     * standing up a bridge.
     */
    @PluginMethod
    fun load(call: PluginCall) {
        if (rejectIfUnavailable(call)) return

        val request = try {
            PipeLoadRequest.parse(
                url = call.getString("url"),
                offline = call.getObject("offline"),
                /*
                 * NOT call.getLong(). Capacitor's getLong returns the stored
                 * object only `if (value instanceof Long)` — and a JavaScript
                 * number lands in the JSONObject as an Integer or a Double, so
                 * getLong answered null for EVERY value a web caller could
                 * possibly send. The `?: 0L` then swallowed it: every
                 * startPositionMs ever passed — resume points, highlight
                 * seeks, quality-switch positions — silently became 0, on all
                 * three load paths. Found on device: a 33s seek that reloaded
                 * and landed at 0:00. Number-and-convert accepts whatever
                 * numeric type the bridge chose.
                 */
                startPositionMs = (call.data.opt("startPositionMs") as? Number)?.toLong() ?: 0L,
                sessionId = call.getString("sessionId"),
            )
        } catch (invalid: Exception) {
            call.reject(invalid.message)
            return
        }

        // Captured before the post, checked inside it: load is the other method
        // that attaches, and a load left pending across a release() would
        // otherwise resurrect the overlay the release just took down, prepare
        // an ExoPlayer nothing owns, and leave the surface painted over the
        // host's page with no way back — see [epoch].
        val generation = epoch.get()
        activity.runOnUiThread {
            if (generation != epoch.get()) {
                call.reject("the player was released while this load was pending")
                return@runOnUiThread
            }
            runCatching {
                overlay.attach()
                when (request) {
                    is PipeLoadRequest.Url ->
                        overlay.load(request.url, request.startPositionMs)
                    is PipeLoadRequest.Offline ->
                        overlay.loadOffline(request.source, request.startPositionMs)
                    is PipeLoadRequest.Sabr ->
                        overlay.loadSabrSession(request.sessionId, request.startPositionMs)
                }
            }.onSuccess { call.resolve() }
                .onFailure { call.reject("could not load: ${it.message}") }
        }
    }

    /**
     * Where playback is now, without waiting for the next event.
     *
     * The event stream is the normal way to follow a video; this is for the
     * one-shot questions — the position to pass as `startPositionMs` when
     * reloading at a different quality, or what to store as a page unmounts.
     *
     * Resolves zeroes when nothing is loaded rather than rejecting: "no video"
     * is a state a host reads, not an error it handles.
     */
    @PluginMethod
    fun getPosition(call: PluginCall) {
        if (rejectIfUnavailable(call)) return
        activity.runOnUiThread {
            val snapshot = overlayInstance?.position()
                ?: PipePlaybackPosition(0L, 0L, 0L, playing = false, ended = false, live = false)
            call.resolve(positionPayload(snapshot))
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

    /**
     * Tear down the player and remove the overlay.
     *
     * The epoch bump happens HERE, at bridge entry, not inside the runnable:
     * a dock or load that is already past its own capture but not yet run must
     * see the release the moment it was issued, and a fresh load issued after
     * this call returns to JS is guaranteed to capture the new epoch. The
     * teardown itself is synchronous once the runnable runs — the overlay
     * detaches, PiP is disarmed (the overlay's detach clears both the sticky
     * Activity params and its own latch), and the player is released.
     */
    @PluginMethod
    fun release(call: PluginCall) {
        if (rejectIfUnavailable(call)) return
        epoch.incrementAndGet()
        activity.runOnUiThread {
            // overlayInstance, not the creating getter: releasing a player that
            // was never built must not build one to tear it down.
            overlayInstance?.release()
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
            hasPlayerClass("androidx.media3.exoplayer.ExoPlayer") &&
                hasPlayerClass("androidx.compose.ui.platform.ComposeView") &&
                hasPlayerClass("androidx.media3.ui.compose.modifiers.ExtensionsKt")
        }
    }
}
