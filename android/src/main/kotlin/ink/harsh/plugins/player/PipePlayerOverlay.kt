package ink.harsh.plugins.player

import android.app.Activity
import android.graphics.Rect
import android.view.WindowManager
import android.graphics.RectF
import android.util.Log
import android.view.TextureView
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.Gravity
import android.view.OrientationEventListener
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import kotlin.math.max
import androidx.compose.runtime.State
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.window.layout.WindowMetricsCalculator
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import ink.harsh.plugins.pipe.media3.PipeSabrMedia3

/**
 * The player's native surface, composited over the host WebView.
 *
 * Added to the Activity's content root at runtime rather than declared in the
 * host's layout, so it survives web-side navigation for free: the WebView
 * repaints underneath and the player does not care. That is the whole reason a
 * native player is worth having over a web one, and it is what makes a
 * persistent mini-player tractable later.
 *
 * **Surface type is TextureView, deliberately.** A SurfaceView composites
 * in its own layer and its surface lags its view bounds by a frame under
 * continuous transform — visible tearing on exactly the drag-tracked fullscreen
 * animation this module exists for. A TextureView renders as an ordinary
 * texture and transforms cleanly. The price is an extra GPU copy and no
 * secure/DRM output; expensive to reverse later, so it is a conscious choice.
 *
 * The overlay always fills the window and draws the video inside a rect. Which
 * rect — the host's dock, the mini corner, or the whole window — is a discrete
 * state owned by [PipePlayerMotion]; the drag between docked and fullscreen
 * only translates the video, and the switch itself happens behind the shutter,
 * carried by a real orientation change (CLAUDE.md Gotcha 24).
 */
/**
 * Where playback has got to, as a host sees it.
 *
 * Everything a progress record needs and nothing a host would have to shadow:
 * the position to resume from, the duration to divide by, and enough state to
 * tell "paused at 40%" apart from "finished". Live streams report
 * [durationMs] as 0, because a live stream has none.
 */
data class PipePlaybackPosition(
    val positionMs: Long,
    val durationMs: Long,
    val bufferedMs: Long,
    val playing: Boolean,
    val ended: Boolean,
    val live: Boolean,
)

@UnstableApi
class PipePlayerOverlay(private val activity: Activity) {

    private companion object {
        const val TAG = "PipePlayerOverlay"

        /** How long nothing may move before the curtain is allowed down. */
        /*
         * 90ms was too patient. The system's own rotation animation keeps
         * producing layout changes while it runs, so the quiet period did not
         * even start until that animation had finished — the video then appeared
         * a beat after everything else had stopped, which reads as the app being
         * stuck rather than as a covered transition.
         *
         * 40ms is still two-plus frames of stillness, enough to know the
         * geometry has settled, and the timeout is tightened to match: if
         * something is genuinely moving at 400ms, showing it beats continuing to
         * hide the app.
         */
        const val STABLE_QUIET_MS = 40L
        const val STABLE_POLL_MS = 16L
        const val STABLE_TIMEOUT_MS = 400L

        /**
         * How far playback advances between two position events.
         *
         * A second is the granularity a stored resume point is worth: finer
         * costs bridge crossings nobody reads, coarser loses the last few
         * seconds of a video that was closed rather than paused.
         */
        const val POSITION_EVERY_MS = 1_000L

        /**
         * A position change this large did not come from playing.
         *
         * The sampler runs every 250ms, so ordinary playback moves the position
         * by about that much — anything beyond a second is a seek, a
         * double-tap skip or a scrub, and a host storing progress wants it at
         * once rather than a second later.
         */
        const val SEEK_JUMP_MS = 1_000L

        /**
         * core-pip is compileOnly, so its absence is a normal state.
         *
         * Checked once by name rather than by touching the type, which would
         * defeat the point: resolving the class is exactly what we are trying
         * to avoid on an app that did not take the dependency.
         */
        val corePipPresent: Boolean by lazy {
            hasPlayerClass("androidx.core.pip.VideoPlaybackPictureInPicture")
        }
    }

    /** The TextureView the video renders into; core-pip tracks its bounds. */
    private var videoSurface: android.view.View? = null

    private var composeView: ComposeView? = null
    /** Page-level layer for sheets, deliberately outside the rotated player. */
    private var sheetView: ComposeView? = null
    private var player: ExoPlayer? = null

    /** Docking rect in device pixels; null when the host has claimed none. */
    private val dockRect = mutableStateOf<RectF?>(null)

    /** The single value the docked <-> fullscreen transform runs on. */
    val motion = PipePlayerMotion().also { it.awaitStable = ::awaitStableGeometry }

    /**
     * Where the host and the display sit inside the square overlay, measured.
     *
     * This used to be two numbers derived from `displayMetrics`, and that was
     * wrong twice over. The square is centred in the Activity's **content view**,
     * which system bars inset, while `displayMetrics` describes the **display**,
     * which they do not — so every host rect was drawn half the bar heights too
     * high, and fullscreen was centred on the content view rather than the
     * screen. Measuring the actual views is correct whether or not the host is
     * edge-to-edge, which is not something a plugin gets to assume.
     */
    private val geometry = mutableStateOf(PipePlayerGeometry())

    /**
     * The host's WebView, used only to locate its coordinate space.
     *
     * Dock rects arrive from `getBoundingClientRect`, so they are relative to
     * the WebView's viewport, not the screen. Falling back to the content root is
     * usually the same view and always the same origin.
     */
    var hostView: android.view.View? = null

    // --- Chrome state, observed by Compose ---
    private val playing = mutableStateOf(false)
    private val positionMs = mutableStateOf(0L)
    private val durationMs = mutableStateOf(0L)
    private val title = mutableStateOf<String?>(null)
    private val subtitle = mutableStateOf<String?>(null)

    /**
     * The third window state: a small floating window that keeps playing.
     *
     * Not a progress value like fullscreen — mini is a different rect, not a
     * point along the docked/fullscreen line, so it cannot ride the same
     * interpolation.
     */
    private val mini = mutableStateOf(false)

    /**
     * Which options sheet is open, if any.
     *
     * Held here, not in the surface: the sheet is mounted in its own
     * page-level view outside the rotated player box, so the state has to live
     * where both can see it.
     */
    private val openSheet = mutableStateOf<SheetKind?>(null)

    /**
     * Decoded video aspect, 0 until the first frame reports one.
     *
     * Needed because ExoPlayer paints the entire TextureView surface and does
     * no aspect handling itself; without this the video is stretched to whatever
     * shape the box happens to be. It is also what makes zoom-to-fill meaningful
     * — "fill" is a statement about the letterbox.
     */
    private val videoAspect = mutableStateOf(0f)

    /** Host-declared geometry for the corner player. See PipePlayerMiniConfig. */
    private val miniConfig = mutableStateOf(PipePlayerMiniConfig())

    /** Rate to return to when a press-and-hold boost ends. */
    private var rateBeforeBoost: Float? = null
    private val bufferedMs = mutableStateOf(0L)
    private val live = mutableStateOf(false)

    /**
     * Playback has run to the end.
     *
     * Kept separate from `playing` because the two are not opposites: paused
     * mid-video and finished both report "not playing", but only one of them
     * has anything left to resume.
     */
    private val ended = mutableStateOf(false)

    /**
     * Media has reached STATE_READY at least once since the last load.
     *
     * This is the loading presentation's gate. Between the overlay attaching
     * (a `dock()` lands before `load()` does, and extraction can hold that gap
     * open for seconds) and the first frame being decodable, the surface used
     * to present the full controls chrome, centred over an empty black box —
     * which read as the player being broken rather than the video loading.
     * Until this latches, the surface shows a black box with a centred
     * indeterminate spinner and no chrome at all.
     *
     * Latched rather than mirrored from the playback state: a mid-play
     * rebuffer must not strip the controls away — [buffering] carries that,
     * and only re-shows the spinner.
     */
    private val mediaReady = mutableStateOf(false)

    /**
     * ExoPlayer is buffering: the initial prepare, a seek, or a stall.
     *
     * Drives the spinner while [mediaReady] drives the chrome, so a rebuffer
     * spins over a video that keeps its controls.
     */
    private val buffering = mutableStateOf(false)

    /**
     * The only thing a consumer may restyle.
     *
     * Everything else about the chrome is fixed on purpose — see
     * PipePlayerChrome. Accent defaults to YouTube-ish red.
     */
    private val accent = mutableStateOf(Color(0xFFFF0033))
    private val showPreviousNext = mutableStateOf(false)
    private val extraButton = mutableStateOf<PipePlayerExtraButton?>(null)

    // Displayed, not owned: the player has no opinion about a quality list or a
    // speed menu, so the host tells it what to show and handles the taps.
    private val speedLabel = mutableStateOf("1x")
    private val qualityLabel = mutableStateOf("Auto")
    private val speedOptions =
        mutableStateOf(listOf("0.5x", "1x", "1.5x", "2x").map { SheetOption(it, it) })
    private val qualityOptions = mutableStateOf(listOf(SheetOption("Auto", "Auto")))

    /**
     * The current media came from local files rather than a URL.
     *
     * Reported through `getPlayerStatus`, not rendered: the host owns every
     * piece of chrome text. It exists so a host does not have to shadow this
     * state and drift out of sync with it.
     */
    var playingOffline: Boolean = false
        private set

    /** Raised when a consumer-supplied button, or quality/speed, is tapped. */
    var onChromeEvent: ((String, String?) -> Unit)? = null

    /**
     * Raised as playback moves, so a host can record where the viewer got to.
     *
     * Sampled rather than pushed — ExoPlayer has no position callback — and
     * rate-limited to roughly once a second while playing, plus immediately
     * whenever something a host would want to react to changes: play, pause,
     * end, a seek, or the duration becoming known. A host that stores progress
     * writes on the event and needs no timer of its own.
     */
    var onPositionEvent: ((PipePlaybackPosition) -> Unit)? = null

    private var attached = false

    /**
     * Compose animations need a MonotonicFrameClock in their context, which
     * AndroidUiDispatcher.Main supplies and a plain main-thread scope does not.
     */
    private val uiScope = CoroutineScope(AndroidUiDispatcher.Main)

    /**
     * Both owned per-attach and torn down in [detach].
     *
     * Neither used to be: the poll loop ran on [uiScope] with nothing ever
     * cancelling it, and the listener was an expression passed straight to
     * `addListener`. A release-then-dock cycle — closing one video and opening
     * another — therefore left the old loop polling a *released* player at
     * 4 Hz forever, retaining it and the Activity, while a second loop and a
     * second listener from the new attach fought it over the same state.
     */
    private var playerListener: Player.Listener? = null
    private var positionPoll: kotlinx.coroutines.Job? = null

    /** The last snapshot handed to [onPositionEvent]; null until one is sent. */
    private var lastEmitted: PipePlaybackPosition? = null

    val isAttached: Boolean get() = attached

    /** Add the overlay to the Activity's content root. Idempotent. */
    fun attach() {
        if (attached) return

        val root = activity.findViewById<ViewGroup>(android.R.id.content)
            ?: error("Activity has no content view")

        // Created here, never inside composition. Doing it lazily from the
        // AndroidView factory mutated class state during a composition pass and
        // sent Compose into an infinite recomposition loop — a StackOverflowError
        // on the first dock. Composition must stay a pure function of state.
        val exo = ensurePlayer()

        /*
         * Duration is often the only reliable source a host has — metadata it
         * holds is frequently absent or wrong — so report it the moment the
         * player knows it, not when playback starts.
         */
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing.value = isPlaying
                // The platform facility, not a wake lock: the screen stays on
                // exactly while video is actually moving, and a pause or an
                // ended video lets it sleep again.
                videoSurface?.keepScreenOn = isPlaying
                // The PiP action is a static snapshot, so it has to be rebuilt
                // or the strip shows a pause glyph on a paused video.
                pip?.setPlaybackState(isPlaying, ended.value)
            }

            override fun onPlaybackStateChanged(state: Int) {
                val reported = exo.duration
                durationMs.value = if (reported == C.TIME_UNSET) 0L else reported
                live.value = exo.isCurrentMediaItemLive
                ended.value = state == Player.STATE_ENDED
                buffering.value = state == Player.STATE_BUFFERING
                // Latched, not mirrored: READY means the loading presentation
                // is over for this media; only the next load() takes it back.
                if (state == Player.STATE_READY) mediaReady.value = true
                // The PiP strip is a static snapshot: without this it keeps a
                // play glyph on a finished video.
                pip?.setPlaybackState(playing.value, ended.value)
            }

            /*
             * Liveness is a property of the TIMELINE, not of the playback state.
             *
             * Detecting it only in onPlaybackStateChanged meant a stream whose
             * manifest arrived without a state transition kept reporting
             * not-live — no badge, a seekable-looking bar, and a duration of
             * zero. onTimelineChanged is where the answer actually becomes
             * known; the state callback stays because it is the one that fires
             * on a media item swap.
             */
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                live.value = exo.isCurrentMediaItemLive
                val reported = exo.duration
                durationMs.value = if (reported == C.TIME_UNSET) 0L else reported
            }

            override fun onVideoSizeChanged(size: VideoSize) {
                // pixelWidthHeightRatio, not just width/height: anamorphic
                // content reports square-pixel dimensions and corrects with this,
                // and ignoring it stretches exactly the videos most likely to be
                // noticed.
                videoAspect.value =
                    if (size.width <= 0 || size.height <= 0) {
                        0f
                    } else {
                        size.width * size.pixelWidthHeightRatio / size.height
                    }
            }
        }
        exo.addListener(listener)
        playerListener = listener

        /*
         * Seeded from the live player, not blindly reset. Attach normally runs
         * before load() — nothing ready, show the loading presentation — but a
         * dock() can also re-attach around a player that is already mid-video,
         * and starting that one back at "loading" would strip its chrome and
         * spin over a playing frame.
         */
        mediaReady.value = exo.playbackState == Player.STATE_READY ||
            exo.playbackState == Player.STATE_ENDED
        buffering.value = exo.playbackState == Player.STATE_BUFFERING

        /*
         * Position has no callback; it has to be sampled. 250ms is fine for a
         * scrubber. While paused the sampled values do not change, and writing
         * an equal value to a MutableState invalidates nothing, so the idle
         * cost is four cheap reads a second — but the job itself is owned and
         * cancelled in detach(), because "cheap forever on a released player"
         * is how the leak read the first time.
         */
        positionPoll?.cancel()
        lastEmitted = null
        positionPoll = uiScope.launch {
            while (true) {
                positionMs.value = exo.currentPosition
                bufferedMs.value = exo.bufferedPosition
                emitPositionIfWorthIt()
                delay(250)
            }
        }

        /*
         * Deliberately NOT `ComposeView(activity).apply { setContent { ... } }`.
         *
         * Inside `apply` the receiver is the ComposeView, which has its own
         * protected `@Composable fun Content()`. A composable of ours named
         * `Content()` therefore resolved to *ComposeView's* method, which
         * invokes the content lambda, which calls it again — infinite
         * recomposition and a StackOverflowError on the first frame. It
         * compiles perfectly and the stack trace blames Compose internals.
         *
         * Keeping the ComposeView out of implicit-receiver scope makes that
         * class of shadowing impossible rather than merely avoided.
         */
        /*
         * Built ONCE per attach, outside composition.
         *
         * These thirteen lambdas capture only the overlay itself, so rebuilding
         * them inside setContent bought nothing — and cost a fresh callbacks
         * object on every recomposition, four times a second while the position
         * ticked. Every lambda reads live fields at invoke time, so hoisting
         * changes no behaviour.
         */
        val callbacks = PipePlayerChromeCallbacks(
            onPlayPause = { if (playing.value) pause() else play() },
            onReplay = ::replay,
            onPrevious = { onChromeEvent?.invoke("previous", null) },
            onNext = { onChromeEvent?.invoke("next", null) },
            onMinimise = {
                // Shrink first, then tell the host — it may want to
                // navigate away, and the player keeps playing regardless.
                setMini(true)
                onChromeEvent?.invoke("minimise", null)
            },
            onFullscreen = { uiScope.launch { motion.animateTo(!motion.isFullscreen) } },
            onExpand = {
                // One button, two ways of being small. From PiP the
                // system owns the window and has to be asked to give it
                // back; from the corner it is just our own animation.
                if (inPip.value) {
                    activity.startActivity(
                        android.content.Intent(activity, activity.javaClass).addFlags(
                            android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                        ),
                    )
                } else {
                    setMini(false)
                }
                onChromeEvent?.invoke("expand", null)
            },
            onQuality = {
                alignActivityWithVideo()
                openSheet.value = SheetKind.QUALITY
                onChromeEvent?.invoke("quality", null)
            },
            onSpeed = {
                alignActivityWithVideo()
                openSheet.value = SheetKind.SPEED
                onChromeEvent?.invoke("speed", null)
            },
            onExtraButton = { id -> onChromeEvent?.invoke("button", id) },
            onSeek = { ms -> player?.seekTo(ms) },
        )

        val view = ComposeView(activity)
        view.setContent {
            PipePlayerSurface(
                dockRect = dockRect,
                motion = motion,
                mini = mini.value,
                pip = inPip.value,
                onSpeedBoost = ::setSpeedBoost,
                onSeekBy = ::seekBy,
                geometry = geometry,
                videoAspect = videoAspect,
                miniConfig = miniConfig.value,
                chromeState = PipePlayerChromeState(
                    playing = playing.value,
                    durationMs = durationMs.value,
                    fullscreen = motion.isFullscreen,
                    ended = ended.value,
                    canMinimise = pipSupported,
                    live = live.value,
                    title = title.value,
                    subtitle = subtitle.value,
                    accent = accent.value,
                    showPreviousNext = showPreviousNext.value,
                    speedLabel = speedLabel.value,
                    qualityLabel = qualityLabel.value,
                    extraButton = extraButton.value,
                ),
                // As State, not fields: the 4Hz tick then invalidates only the
                // scrubber's draw and the timestamp, not this whole tree.
                position = positionMs,
                buffered = bufferedMs,
                mediaReady = mediaReady,
                buffering = buffering,
                chromeCallbacks = callbacks,
                bindSurface = { texture ->
                    exo.setVideoTextureView(texture)
                    texture.keepScreenOn = playing.value
                    videoSurface = texture
                    pip?.setPlayerView(texture)
                },
                onImmersiveChanged = ::setImmersive,
                onFullscreenSettled = ::setFullscreenSettled,
            )
        }
        /*
         * A SQUARE overlay, side = max(screen), centred — not MATCH_PARENT.
         *
         * A relic of the fake-rotation model, where the video box was laid out
         * landscape inside a portrait window and clipped to its pre-rotation
         * bounds — a screen-sized overlay sliced it to a strip. The box no
         * longer rotates, but the square stays: it is orientation-agnostic, so
         * the real rotation that fullscreen now performs never resizes the
         * overlay itself, and every rect the geometry publishes keeps one
         * stable frame across the switch. The cost is that Compose coordinates
         * are not screen coordinates, hence the measured origins.
         */
        val bounds = displayBounds()
        val side = max(bounds.width(), bounds.height())

        root.addView(
            view,
            FrameLayout.LayoutParams(side, side, Gravity.CENTER),
        )

        /*
         * Re-measure on every layout pass rather than once at attach.
         *
         * The numbers change under us constantly — system bars hiding for
         * immersive mode, the device rotating, the host resizing its own rect —
         * and a value captured at attach is stale by the first transition.
         * Writing an identical PipePlayerGeometry is free: it is a data class,
         * so Compose's structural equality check makes an unchanged layout pass
         * cost nothing.
         */
        root.viewTreeObserver.addOnGlobalLayoutListener(geometryListener)
        view.post { measureGeometry() }

        val sheets = ComposeView(activity)
        sheets.setContent { SheetLayer() }
        root.addView(
            sheets,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        sheetView = sheets

        composeView = view
        attached = true
        if (orientationListener.canDetectOrientation()) {
            orientationListener.enable()
        }

        Log.i(TAG, "overlay attached to content root")
    }

    fun detach() {
        if (!attached) return
        positionPoll?.cancel()
        positionPoll = null
        lastEmitted = null
        playerListener?.let { listener -> player?.removeListener(listener) }
        playerListener = null
        sheetView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            view.disposeComposition()
        }
        sheetView = null
        pip?.close()
        pip = null
        /*
         * PiP auto-enter is armed only while a video surface is live. The
         * delegate's close() above disarms the Activity's sticky params; this
         * clears our own latch so a detached player cannot answer `enterPip`,
         * and the next session starts unarmed until its host opts in again.
         */
        pipEnabled = false
        (activity.findViewById<ViewGroup>(android.R.id.content))
            ?.viewTreeObserver
            ?.removeOnGlobalLayoutListener(geometryListener)
        orientationListener.disable()

        /*
         * Give the window back the way it was found.
         *
         * Detaching while fullscreen used to keep the bars hidden and the decor
         * un-fitted — the host's page left running edge-to-edge under a hidden
         * status bar with no player to bring it back. And UNSPECIFIED, not
         * PORTRAIT: the manifest's own screenOrientation resumes, instead of
         * this plugin permanently portrait-locking a host that never was.
         */
        setImmersive(false)
        orientationListener.noteRequested(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        suppressAutoFullscreen = false
        holdLandscape = false
        // The motion object outlives the view tree; without this a later dock
        // resurrected a player that still believed it was fullscreen. The mode
        // flags outlive it the same way — detach resets the miniProgress axis,
        // so a stale `mini` would disagree with it on the next attach — and a
        // stale `mediaReady` would skip the loading presentation entirely.
        mini.value = false
        mediaReady.value = false
        buffering.value = false
        uiScope.launch { motion.reset() }

        composeView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            view.disposeComposition()
        }
        composeView = null
        attached = false
    }

    /**
     * Declare where the host has reserved space, in device pixels.
     *
     * Hosts declare a rect rather than commanding the player into a position;
     * the player owns every transition between rects. No rect claimed is
     * precisely the signal to fall back to the mini-player later.
     */
    fun dock(rect: RectF) {
        // A re-dock after a reflow is the host telling us it has moved, so it
        // counts toward the same quiet period the curtain waits on.
        if (dockRect.value != rect) geometryChangedAt = android.os.SystemClock.uptimeMillis()
        dockRect.value = rect
        Log.i(TAG, "docked at $rect")
    }

    fun undock() {
        dockRect.value = null

        /*
         * Claiming no rect IS the mini player — make the mode agree.
         *
         * The surface already fell back to drawing at the corner rect when no
         * rect was claimed, but nothing flipped `mini`, so a host that
         * undocked a full-size player left it in a half state: corner-sized,
         * yet wearing the full docked chrome, immovable (the corner drag only
         * arms while mini), and answering the swipe-up fullscreen gesture.
         * That is exactly the "mini-sized player with a full overlay" a host
         * navigation produces when the video was docked at the moment of
         * leaving — minimise-then-navigate never showed it because the
         * minimise button had already set the flag.
         *
         * Guarded to the case it is for: an attached player with media that is
         * not already mini and not in PiP (there the system owns the window).
         * The collapse runs first for the same reason setMini's does — a
         * fullscreen player travelling diagonally to the corner while
         * shrinking reads as a glitch. Device-unverified.
         */
        if (attached && !mini.value && !inPip.value && (player?.mediaItemCount ?: 0) > 0) {
            mini.value = true
            uiScope.launch {
                motion.animateTo(false)
                motion.animateMini(true)
            }
        }
    }

    /**
     * Hide or show the system bars.
     *
     * Immersive mode in fullscreen is required, not cosmetic: a fullscreen
     * video framed by a status bar and a navigation bar is not fullscreen, and
     * the bars flip in with the committed state — before the orientation change
     * lands — so the switch behind the shutter uncovers a bar-less window.
     */
    fun setImmersive(immersive: Boolean) {
        val window = activity.window ?: return
        val controller = WindowCompat.getInsetsController(window, window.decorView)


        /*
         * Hiding the bars is not enough on its own: while the decor still fits
         * system windows, the content view keeps its inset padding, and the
         * overlay is a child of that content view. It gets clipped to it, so
         * fullscreen stopped short of the screen edge no matter how large we
         * sized the video — the pixels were never ours to draw.
         *
         * setDecorFitsSystemWindows(false) hands the whole window to the content
         * view, which is what makes the last strip reachable. It is restored on
         * the way out because the host page is laid out against the fitted
         * version and would otherwise slide under the status bar.
         */
        WindowCompat.setDecorFitsSystemWindows(window, !immersive)

        if (immersive) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())

        }

        // The content view is about to resize; the overlay's coordinate space
        // moves with it, so re-measure once that has actually happened.
        composeView?.post { measureGeometry() }
    }

    /**
     * Press-and-hold 2x.
     *
     * Restores the *previous* rate on release, not 1x: someone already
     * watching at 1.5x who holds and lets go must land back at 1.5x, or the
     * gesture quietly destroys their setting.
     */
    fun setSpeedBoost(active: Boolean) {
        val exo = player ?: return
        if (active) {
            if (rateBeforeBoost == null) {
                rateBeforeBoost = exo.playbackParameters.speed
            }
            exo.setPlaybackSpeed(2f)
        } else {
            rateBeforeBoost?.let { exo.setPlaybackSpeed(it) }
            rateBeforeBoost = null
        }
    }

    /** Relative seek, clamped to the media. Used by double-tap. */
    fun seekBy(deltaMs: Long) {
        val exo = player ?: return
        val target = (exo.currentPosition + deltaMs)
            .coerceIn(0L, if (exo.duration > 0) exo.duration else Long.MAX_VALUE)
        exo.seekTo(target)
    }

    /** Shrink to the floating mini window, or restore to the host's rect. */
    fun setMini(value: Boolean) {
        /*
         * Expanding needs somewhere to expand INTO.
         *
         * The player outlives web navigation, so the rect it was given on one
         * page is meaningless on the next — the host either claimed a new one
         * or claimed none. Expanding against a stale rect dropped the video
         * onto arbitrary content, and expanding against no rect made it vanish.
         *
         * Neither is ours to guess at. Only the host knows whether the right
         * answer is "route back to the page that owns this video" or "there is
         * nowhere for it here" — so we stay in the corner and say so.
         */
        if (!value && dockRect.value == null) {
            Log.i(TAG, "expand refused: no host rect claimed")
            onChromeEvent?.invoke("expandUnavailable", null)
            return
        }
        mini.value = value
        uiScope.launch {
            // Collapse out of fullscreen FIRST, then travel to the corner.
            // Running both at once sends the player diagonally across the screen
            // while shrinking, which reads as a glitch rather than a move.
            if (value) motion.animateTo(false)
            motion.animateMini(value)
        }
    }

    /**
     * Whether the Activity is currently in Picture-in-Picture.
     *
     * Observed rather than assumed: the user can leave PiP by dragging the
     * window off screen, which closes it without any call of ours.
     */
    private val inPip = mutableStateOf(false)

    /** Opt-in. PiP shrinks the host's whole window, so it is never a default. */
    private var pipEnabled = false

    /**
     * The user left fullscreen, so the sensor must stop dragging them back.
     *
     * Without this, exiting fullscreen while holding the phone sideways was
     * impossible: the collapse ran, the orientation listener saw landscape on
     * its very next reading and immediately re-expanded. The gesture worked
     * perfectly and looked completely broken — the transform reached p = 0 and
     * was overwritten within a frame.
     *
     * Cleared when the device is genuinely turned upright again, so turning
     * back to landscape re-enters fullscreen as before. This is how YouTube
     * behaves: exit while sideways and it stays exited until you rotate.
     */
    private var suppressAutoFullscreen = false

    /**
     * The mirror image of [suppressAutoFullscreen], for the way IN.
     *
     * Tap the fullscreen button while holding the phone upright and the very
     * next sensor reading is portrait — which used to be taken as "the user
     * turned back" and undid the button within a frame, leaving a
     * portrait-fullscreen video. YouTube holds the landscape it was asked for
     * until the device actually reaches landscape (or fullscreen is exited);
     * this flag is that hold. Cleared the moment a landscape reading arrives,
     * so from then on the sensor governs as usual.
     */
    private var holdLandscape = false

    /**
     * The core-pip delegate, created only if the library is actually present.
     *
     * Held as our own type rather than the library's so that this class still
     * loads and runs on a device whose app skipped the dependency — the same
     * discipline the plugin uses to keep Media3 optional.
     */
    private var pip: PipePlayerPip? = null

    /**
     * Is PiP usable at all?
     *
     * Four things have to be true and only one of them is ours. The OS must be
     * new enough — PiP is API 26 on handhelds whatever core-pip's own minSdk of
     * 23 suggests. The device must have the feature, which Android TV and some
     * low-memory devices do not. The app must have put `core-pip` on the
     * classpath, since it is `compileOnly` here. And the **host** must have
     * declared `android:supportsPictureInPicture` on its Activity — a library
     * manifest cannot add that, because merging needs the activity's real name,
     * so a host that skips it gets a silent no-op.
     *
     * `lazy`, because none of the four inputs can change within a session and
     * the live getter re-ran `hasSystemFeature` plus an instanceof on every
     * chrome recomposition. The clause ORDER is load-bearing: `corePipPresent`
     * must short-circuit before the `PictureInPictureProvider` instanceof —
     * that type resolves only with the androidx.core that core-pip brings, and
     * evaluating it on an app without the dependency throws.
     */
    val pipSupported: Boolean by lazy {
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
            activity.packageManager.hasSystemFeature(
                android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE,
            ) &&
            corePipPresent &&
            activity is androidx.core.app.PictureInPictureProvider
    }

    /**
     * Turn PiP on or off for this session.
     *
     * One call, no version fork. The library decides between auto-enter on
     * Android 12+ and `onUserLeaveHint` below it, and reaches the latter
     * through `ComponentActivity` — a hook Capacitor's `Plugin` never exposed
     * to us, so pre-12 auto-entry was previously not possible at all.
     */
    fun setPipEnabled(enabled: Boolean) {
        pipEnabled = enabled
        if (!pipSupported) {
            Log.i(TAG, "pip requested but unavailable (core-pip present=$corePipPresent)")
            return
        }
        // Disabling something that was never enabled needs no delegate; building
        // one here just to tell it "off" registered callbacks for nothing.
        if (!enabled && pip == null) return
        val delegate = pip ?: runCatching {
            PipePlayerPip(
                activity = activity,
                onPipChanged = ::onPipChanged,
                onTogglePlay = {
                    // Ended needs a seek before play(), or the tap is a no-op.
                    if (ended.value) replay() else if (playing.value) pause() else play()
                },
            )
        }
            .onFailure { Log.w(TAG, "could not create pip delegate: ${it.message}") }
            .getOrNull()
            ?.also { created ->
                pip = created
                // sourceRectHint tracking: the library watches this view's bounds
                // so the window animates out of the video, not out of nowhere.
                videoSurface?.let(created::setPlayerView)
            }
        delegate?.setEnabled(enabled, videoAspect.value)
        delegate?.setPlaybackState(playing.value, ended.value)
    }

    /** Enter PiP now. Rarely needed — with `pipEnabled` the system does it. */
    fun enterPip(): Boolean {
        if (!pipSupported || !pipEnabled) return false
        return pip?.enter(videoAspect.value) ?: false
    }

    /**
     * The library tells us; we no longer guess.
     *
     * The previous version polled `isInPictureInPictureMode` on every layout
     * pass because its mode-change listener never fired. That was treating the
     * symptom: on Android 17 the callback it waited on does not arrive, so the
     * player stayed laid out against the docked rect and PiP showed the host's
     * web page instead of the video.
     */
    private fun onPipChanged(active: Boolean) {
        inPip.value = active
        // Entering and leaving both resize the window, and the overlay's
        // coordinate space moves with it. `post` because the event arrives
        // before the new layout has happened.
        measureGeometry()
        composeView?.post { measureGeometry() }
    }

    /**
     * Block screenshots and screen recording for the whole Activity.
     *
     * `FLAG_SECURE` is a window flag, not a view one, so this necessarily covers
     * the host's page as well as the video — there is no way to secure only the
     * player's pixels. Opt-in for that reason, and reversible.
     *
     * Worth knowing: this also makes the **PiP window** and the recents
     * thumbnail blank, and on some devices it disables casting entirely. That is
     * the point of the flag, but it surprises people who enabled it only to stop
     * screenshots.
     */
    fun setSecure(secure: Boolean) {
        val window = activity.window ?: return
        if (secure) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        Log.i(TAG, "secure mode $secure")
    }

    /**
     * Follow the physical device orientation.
     *
     * The host is portrait-locked so the fullscreen transform can be a view
     * rotation rather than a config change. That leaves real rotation doing
     * nothing, which is wrong: turning the phone is how most people expect to go
     * fullscreen. So we watch the sensor and drive the Activity ourselves —
     * landscape hands control to the OS and drops the fake rotation, portrait
     * takes it back.
     */
    private val orientationListener by lazy {
        object : OrientationEventListener(activity) {
            /*
             * Seeded from the CURRENT configuration, not null.
             *
             * Starting from null made the first sensor callback look like a
             * transition — on a device sitting in portrait it immediately drove
             * motion back to docked, silently undoing an explicit
             * setFullscreen(true). The listener must only react to genuine
             * changes, never assert the status quo.
             */
            private var lastLandscape: Boolean =
                activity.resources.configuration.orientation ==
                    Configuration.ORIENTATION_LANDSCAPE

            /**
             * The last orientation we asked for, so a re-request is a no-op.
             *
             * Starts UNSPECIFIED rather than guessing a side: we know from the
             * Configuration whether we are landscape, but not *which* landscape,
             * and asserting the wrong one would cause the very 180 degree flip
             * this listener exists to avoid.
             *
             * Code outside this listener also requests orientations — the
             * settled-fullscreen swap, detach — and reports them through
             * [noteRequested], or the dedup here compares against a stale value
             * and swallows or repeats requests unpredictably.
             */
            private var lastRequested: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

            /** Last physical reading; null until the sensor has spoken. */
            var physicalLandscape: Boolean? = null
                private set

            fun noteRequested(target: Int) {
                lastRequested = target
            }

            override fun onOrientationChanged(degrees: Int) {
                if (degrees == ORIENTATION_UNKNOWN || !attached) return

                /*
                 * Name the exact landscape, never SCREEN_ORIENTATION_SENSOR_LANDSCAPE.
                 *
                 * SENSOR_LANDSCAPE lets the system pick either side, and it picks
                 * the one it last used — so turning the phone LEFT rotated the
                 * player right and then swung it 180 degrees once the sensor
                 * caught up. Two rotations, one of them wrong, on every turn.
                 *
                 * The two scales are mirrored, which is the easy thing to get
                 * backwards: OrientationEventListener reports how far the DEVICE
                 * has turned clockwise from its natural position, while the
                 * ActivityInfo constants name where the CONTENT ends up. A device
                 * at 90 degrees therefore wants REVERSE_LANDSCAPE, not LANDSCAPE.
                 *
                 * Dead zones around the diagonals stop a phone held at an angle
                 * from flipping back and forth.
                 */
                val target = when {
                    degrees in 65..115 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                    degrees in 245..295 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    // Upside-down portrait deliberately resolves to ordinary
                    // portrait: someone lying down has not asked to be flipped.
                    degrees <= 25 || degrees >= 335 || degrees in 155..205 ->
                        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    else -> return
                }
                val landscapeReading = target != ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                physicalLandscape = landscapeReading

                // The device has caught up with a button-requested landscape;
                // from here the sensor governs normally.
                if (landscapeReading) holdLandscape = false

                /*
                 * The sensor only drives a player the user can actually see
                 * changing state. In the corner or in PiP a rotation must not
                 * blow the window up to fullscreen, and with no rect claimed
                 * there is nothing to expand — but an EXIT is always allowed,
                 * or a player whose host undocked mid-fullscreen would be stuck
                 * there.
                 */
                if (!motion.isFullscreen &&
                    (mini.value || inPip.value || dockRect.value == null)
                ) {
                    return
                }

                /*
                 * Honour a deliberate exit from fullscreen — and a deliberate
                 * entry.
                 *
                 * The phone is often still sideways at the moment the user
                 * swipes down, so without the suppression the next sensor
                 * reading undid the gesture instantly. Portrait clears it: the
                 * user is done with landscape. The hold is the same idea
                 * mirrored — a portrait reading right after the fullscreen
                 * button is not the user changing their mind, it is the phone
                 * not having moved.
                 */
                if (target == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
                    if (holdLandscape) return
                    suppressAutoFullscreen = false
                } else if (suppressAutoFullscreen) {
                    return
                }

                if (target == lastRequested) return
                lastRequested = target
                activity.requestedOrientation = target

                /*
                 * Motion is tracked separately from the requested orientation,
                 * because turning from one landscape to the other changes the
                 * latter but not the former — animating fullscreen again there
                 * would replay the whole transition for a rotation the user
                 * experienced as continuous.
                 */
                if (landscapeReading == lastLandscape) return
                lastLandscape = landscapeReading
                uiScope.launch { motion.animateTo(landscapeReading) }
            }
        }
    }

    private val geometryListener = ViewTreeObserver.OnGlobalLayoutListener { measureGeometry() }

    /** When the player's geometry last actually moved. */
    private var geometryChangedAt = 0L

    /**
     * Block until nothing has moved for a short while.
     *
     * Both inputs matter and they arrive separately: our own measured geometry
     * changes when the window resizes, and the host's dock rect changes when its
     * page finishes reflowing and calls `dock()` again. Waiting on a quiet
     * period covers both without needing to know which is slower on a given
     * device.
     *
     * Bounded, because a host that re-docks continuously would otherwise hold
     * the curtain up forever. Hitting the cap shows a little reflow; never
     * lowering the curtain hides the app.
     */
    private suspend fun awaitStableGeometry() {
        val deadline = android.os.SystemClock.uptimeMillis() + STABLE_TIMEOUT_MS

        /*
         * Phase one: wait for the switch to BEGIN.
         *
         * The quiet period alone had a hole at the front: `requestedOrientation`
         * is asynchronous, and if the last geometry change (typically the
         * bar-hide relayout) landed more than a quiet-period ago, the wait
         * returned before the rotation had even started — lifting the shutter
         * onto exactly the switch it exists to cover. So first wait until the
         * configuration agrees with the committed state; a commit that changes
         * nothing (already-landscape entry, PiP) agrees immediately.
         */
        val wantLandscape = motion.isFullscreen && !inPip.value
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            val landscape = activity.resources.configuration.orientation ==
                Configuration.ORIENTATION_LANDSCAPE
            if (landscape == wantLandscape || inPip.value) break
            delay(STABLE_POLL_MS)
        }

        // Phase two: the quiet period — nothing may have moved for a beat.
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            val quietFor = android.os.SystemClock.uptimeMillis() - geometryChangedAt
            if (quietFor >= STABLE_QUIET_MS) return
            delay(STABLE_POLL_MS)
        }
    }

    /**
     * The current window's bounds, system bars included, in screen coordinates.
     *
     * `displayMetrics` is not a substitute: for an Activity-scoped context it
     * reports the app window, which shrinks whenever bars are showing, so
     * fullscreen sized from it stops short of the screen edge.
     *
     * This replaced a hand-written `SDK_INT >= R` fork whose pre-R branch used
     * exactly that — and therefore under-reported height on API 24-29, the only
     * versions the fork existed for. `WindowMetricsCalculator` backfills through
     * `Display.getRealSize` instead, so the fallback is finally correct.
     *
     * Returns the *window*, not the display: in PiP those differ, and it is the
     * window we need. See Gotcha 20 for why its origin matters too.
     */
    private fun displayBounds(): Rect =
        Rect(
            WindowMetricsCalculator.getOrCreate()
                .computeCurrentWindowMetrics(activity)
                .bounds,
        )

    /**
     * Locate the host's coordinate space and the display inside the overlay's own.
     *
     * Everything is measured through `getLocationOnScreen` rather than
     * computed from insets, because the arithmetic version has to know whether
     * the host is edge-to-edge, whether it opted out of Android 15's
     * enforcement, and which bars are currently hidden. The views already know.
     */
    private fun measureGeometry() {
        val square = composeView ?: return
        val host = hostView ?: activity.findViewById(android.R.id.content) ?: return

        /*
         * Read PiP state here as well as from core-pip's events.
         *
         * Not a workaround for a missing callback this time — the events do
         * arrive. It closes a GAP: the window resizes and lays out before
         * ENTERED reaches us, so for a frame or two the player was still drawing
         * full-size chrome inside a thumbnail. The title and controls flashed on
         * entering PiP and again on every resize. This runs on every layout
         * pass, including the resize's own, so the flag can never lag the
         * geometry that depends on it.
         */
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            inPip.value = activity.isInPictureInPictureMode
        }

        val squareLocation = IntArray(2)
        square.getLocationOnScreen(squareLocation)
        val hostLocation = IntArray(2)
        host.getLocationOnScreen(hostLocation)
        val bounds = displayBounds()

        val measured = PipePlayerGeometry(
            hostX = (hostLocation[0] - squareLocation[0]).toFloat(),
            hostY = (hostLocation[1] - squareLocation[1]).toFloat(),
            /*
             * bounds.left/top, NOT zero.
             *
             * `getLocationOnScreen` is absolute, so the window's own origin has
             * to come off it — and in PiP the window does not start at the top
             * left of the screen. Assuming it did put the video's centre at a
             * NEGATIVE y in PiP: the whole frame was drawn above the window and
             * the host's web page showed through underneath, which looked like
             * the player ignoring PiP entirely.
             *
             * Fullscreen and docked both report a window at 0,0, so this is a
             * no-op everywhere except the case it fixes.
             */
            screenX = (bounds.left - squareLocation[0]).toFloat(),
            screenY = (bounds.top - squareLocation[1]).toFloat(),
            screenW = bounds.width().toFloat(),
            screenH = bounds.height().toFloat(),
        )
        // Only a real change counts as movement; an identical re-measure would
        // otherwise keep resetting the quiet period and hold the curtain up.
        // The log lives inside the same gate — this runs on EVERY layout pass
        // of the host, and an unconditional line was permanent log spam.
        if (measured != geometry.value) {
            geometry.value = measured
            geometryChangedAt = android.os.SystemClock.uptimeMillis()
            Log.i(TAG, "geom pip=${inPip.value} $measured")
        }
    }

    /**
     * Make fullscreen a genuinely landscape Activity, on the committed state.
     *
     * A configuration change is discrete — there is no 37% of an orientation
     * change — so it cannot ride the drag; it rides the commit, behind the
     * shutter. Keeping steady-state fullscreen an ordinary landscape window is
     * what dissolved the two-coordinate-frame bugs: the exit gesture reading
     * the video's "down" instead of the window's, and sheets opening upright in
     * their own window over a sideways video.
     *
     * Stated plainly, the cost: the host page reflows once on entering
     * fullscreen. That is the price of fullscreen being a real landscape
     * window, and the shutter is what keeps the reflow off screen.
     */
    fun setFullscreenSettled(fullscreen: Boolean) {
        /*
         * Never from inside PiP. Entering PiP flips the surface's settled flag
         * to false as a side effect of the window mode, not of any user intent
         * about orientation — acting on it requested PORTRAIT on the pipped
         * Activity and armed the sensor suppression for an exit that never
         * happened. When PiP ends the flag recomputes and this runs then.
         */
        if (inPip.value) return
        val landscape = activity.resources.configuration.orientation ==
            Configuration.ORIENTATION_LANDSCAPE
        if (fullscreen) {
            /*
             * A button press with the phone still upright must HOLD: the next
             * sensor reading is portrait, and without the hold it undid the
             * button within a frame. Sensor-driven entries arrive with the
             * device already physically landscape, so the hold stays off and
             * the sensor keeps governing.
             */
            if (orientationListener.physicalLandscape != true) holdLandscape = true
            if (!landscape) {
                orientationListener.noteRequested(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
        } else {
            holdLandscape = false
            /*
             * Suppress the sensor ONLY if the phone is actually sideways.
             *
             * The flag exists for one case: exiting fullscreen while still
             * holding the phone in landscape, where the next sensor reading
             * would otherwise re-expand immediately. Setting it unconditionally
             * made it stick — exit while already upright and nothing clears it,
             * because the sensor emits no event when the device has not moved.
             * Rotating the phone then did nothing at all, in whichever direction
             * happened to be tried first.
             *
             * Deciding from the CURRENT configuration keeps it to the case it
             * was written for. `lastLandscape` is still left alone: clearing it
             * would make that same next reading look like a fresh turn.
             */
            suppressAutoFullscreen = landscape
            if (landscape) {
                orientationListener.noteRequested(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        }
    }

    /**
     * Belt-and-braces: a fullscreen player must live in a landscape Activity
     * before a sheet opens, because a ModalBottomSheet renders in its own
     * window and arrives in the Activity's orientation.
     *
     * Under the current model this is nearly always a no-op — the settled
     * commit already made the Activity genuinely landscape — but the
     * orientation request is asynchronous, so a sheet opened in the sliver
     * between commit and configuration change still deserves the nudge.
     */
    private fun alignActivityWithVideo() {
        if (!motion.isFullscreen) return
        val portrait = activity.resources.configuration.orientation !=
            Configuration.ORIENTATION_LANDSCAPE
        if (portrait) {
            orientationListener.noteRequested(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    /** Animate to fullscreen or back, without a gesture. */
    suspend fun setFullscreen(fullscreen: Boolean) {
        motion.animateTo(fullscreen)
    }

    /**
     * Swap the media, and leave nothing of the last one behind.
     *
     * The player is deliberately long-lived — it outlives web navigation — which
     * means every piece of per-video state it holds outlives the video too
     * unless it is cleared here. Without this, loading a second video inherited
     * the first one's aspect ratio (wrong letterbox until the first frame),
     * duration and position (a scrubber showing the previous video's timeline),
     * and `ended` (a replay icon on a video that has not started).
     *
     * Kept deliberately: playback speed. That is a user preference rather than a
     * property of the media, and resetting it every video would quietly undo a
     * setting the user chose. An in-flight press-and-hold boost is NOT kept —
     * the finger that started it is long gone.
     *
     * Shared by [load] and [loadOffline], so a change here must be checked
     * against both.
     */
    /**
     * The current snapshot, for a host that would rather ask than listen.
     *
     * Reads the sampled state rather than the player, so it is safe on any
     * thread and cannot disagree with the last event a listener saw.
     */
    fun position(): PipePlaybackPosition =
        PipePlaybackPosition(
            positionMs = positionMs.value,
            durationMs = durationMs.value,
            bufferedMs = bufferedMs.value,
            playing = playing.value,
            ended = ended.value,
            live = live.value,
        )

    /**
     * Emit a position event, but only when it says something new.
     *
     * The sampler runs at 4 Hz because a scrubber needs that; a host storing
     * progress does not, and four bridge crossings a second for the length of a
     * lecture is a cost with no reader. So this emits about once a second while
     * playing, and immediately when a host would want to react anyway: playback
     * starting or stopping, the video ending, the duration becoming known, or a
     * seek — which is the only way the position moves by more than a sample.
     *
     * A paused player emits nothing at all: nothing about it is changing, and
     * the host already has the snapshot from the pause.
     */
    private fun emitPositionIfWorthIt() {
        val listener = onPositionEvent ?: return
        val now = position()
        val previous = lastEmitted

        val notable = previous == null ||
            now.playing != previous.playing ||
            now.ended != previous.ended ||
            now.live != previous.live ||
            now.durationMs != previous.durationMs ||
            kotlin.math.abs(now.positionMs - previous.positionMs) >= SEEK_JUMP_MS ||
            (now.playing && now.positionMs - previous.positionMs >= POSITION_EVERY_MS)

        if (!notable) return
        lastEmitted = now
        listener(now)
    }

    private fun resetForNewMedia() {
        ended.value = false
        // Back to the loading presentation: black box, spinner, no chrome,
        // until this media reports READY. `buffering` is set eagerly rather
        // than waiting for ExoPlayer's callback so there is no chrome-less,
        // spinner-less gap between the load call and the first state event.
        mediaReady.value = false
        buffering.value = true
        videoAspect.value = 0f
        positionMs.value = 0L
        bufferedMs.value = 0L
        durationMs.value = 0L
        live.value = false
        // Restores the pre-boost rate rather than pinning 2x forever.
        if (rateBeforeBoost != null) setSpeedBoost(false)

        /*
         * New media claims the host's rect if one is claimed.
         *
         * Loading a video is the host saying "this is the thing to watch now",
         * so leaving it parked in the corner would be wrong. setMini self-guards
         * when no rect exists, so a host that loads while genuinely rect-less
         * keeps its corner player instead of losing the video entirely.
         */
        setMini(false)
    }

    /**
     * Play a URL from [startPositionMs].
     *
     * The position goes into `setMediaItem` rather than a `seekTo` after
     * `prepare()`: a post-prepare seek buffers position 0 first and shows a
     * visible flash of the wrong frame.
     */
    fun load(url: String, startPositionMs: Long = 0L) {
        resetForNewMedia()
        playingOffline = false
        ensurePlayer().apply {
            setMediaItem(MediaItem.fromUri(url), startPositionMs)
            prepare()
        }
    }

    /**
     * Play local files, optionally encrypted, from [startPositionMs].
     *
     * Builds its own [MediaSource], so [ensurePlayer] needs no factory override
     * and the online path is untouched.
     */
    /**
     * Play an open SABR session through its own Media3 source.
     *
     * The loopback manifest is the portable route and this is the direct one:
     * no socket, no cleartext exemption, no HTTP copy of every segment. Both
     * read the same synthesised manifest, so what plays is identical.
     *
     * `playingOffline` stays false — this is as online as media gets.
     */
    fun loadSabrSession(sessionId: String, startPositionMs: Long = 0L) {
        // Built before any state is cleared, exactly as loadOffline does: a
        // session that cannot be found must leave the current video playing.
        val media = PipeSabrMedia3.mediaSource(sessionId)
        resetForNewMedia()
        playingOffline = false
        ensurePlayer().apply {
            setMediaSource(media, startPositionMs)
            prepare()
        }
    }

    fun loadOffline(source: PipeOfflineSource, startPositionMs: Long = 0L) {
        // Built before any state is cleared: a source that cannot be built must
        // leave the currently playing video exactly as it was.
        val media = PipePlayerOffline.buildMediaSource(activity, source)
        resetForNewMedia()
        playingOffline = true
        ensurePlayer().apply {
            setMediaSource(media, startPositionMs)
            prepare()
        }
    }

    /**
     * Restart a finished video.
     *
     * Shared by the chrome button and the PiP action, because "play" is not a
     * substitute: ExoPlayer's `play()` on an ended item is a no-op without a
     * seek first, so the PiP strip's play button did visibly nothing.
     *
     * Clears `ended` here rather than waiting for the state callback — the seek
     * and the play arrive first, so the icon would flicker back through replay
     * on the way.
     */
    fun replay() {
        ended.value = false
        player?.seekTo(0L)
        player?.play()
        pip?.setPlaybackState(playing = true, ended = false)
    }

    fun play() {
        player?.play()
    }

    fun pause() {
        player?.pause()
    }

    fun release() {
        player?.release()
        player = null
        detach()
    }

    /** The live mini geometry, so a partial reconfigure can merge onto it. */
    internal val currentMiniConfig: PipePlayerMiniConfig get() = miniConfig.value

    /** Apply the one supported piece of styling, plus the chrome's options. */
    fun configure(
        accentColor: Int?,
        mini: PipePlayerMiniConfig?,
        showPreviousNext: Boolean?,
        title: String?,
        subtitle: String?,
        speedLabel: String?,
        qualityLabel: String?,
        speeds: List<SheetOption>?,
        qualities: List<SheetOption>?,
        /**
         * Split into value + provided, unlike every sibling: null means REMOVE
         * for a button — a host must be able to take its extra button down —
         * while for every other field null means "leave as is". The flag is
         * what tells those apart, so a configure call that never mentions
         * `button` cannot silently delete one.
         */
        extraButton: PipePlayerExtraButton?,
        extraButtonProvided: Boolean,
    ) {
        accentColor?.let { accent.value = Color(it) }
        mini?.let { this.miniConfig.value = it }
        showPreviousNext?.let { this.showPreviousNext.value = it }
        title?.let { this.title.value = it }
        subtitle?.let { this.subtitle.value = it }
        speedLabel?.let { this.speedLabel.value = it }
        speeds?.let { this.speedOptions.value = it }
        qualities?.let { this.qualityOptions.value = it }
        qualityLabel?.let { this.qualityLabel.value = it }
        if (extraButtonProvided) this.extraButton.value = extraButton
    }

    // One body for both sheets — they differ only in data and event name, and
    // two hand-maintained copies had already drifted once.
    @androidx.compose.runtime.Composable
    private fun SheetLayer() {
        val kind = openSheet.value ?: return
        val speed = kind == SheetKind.SPEED
        PipePlayerSheet(
            title = if (speed) "Playback speed" else "Quality",
            options = if (speed) speedOptions.value else qualityOptions.value,
            selectedId = if (speed) speedLabel.value else qualityLabel.value,
            accent = accent.value,
            onSelect = { id ->
                openSheet.value = null
                if (speed) {
                    speedLabel.value = id
                    player?.setPlaybackSpeed(id.removeSuffix("x").toFloatOrNull() ?: 1f)
                    onChromeEvent?.invoke("speedSelected", id)
                } else {
                    // The player cannot switch quality itself — that is an
                    // extractor/track decision — so it reports and lets the
                    // host reload at the chosen level.
                    qualityLabel.value = id
                    onChromeEvent?.invoke("qualitySelected", id)
                }
            },
            onDismiss = { openSheet.value = null },
            immersive = motion.isFullscreen,
        )
    }

    private fun ensurePlayer(): ExoPlayer =
        player ?: ExoPlayer.Builder(activity)
            /*
             * The system facilities a video player is expected to plug into,
             * previously missing entirely: without audio focus this player
             * talked over whatever music was already playing; without
             * becoming-noisy handling an unplugged headphone jack blasted the
             * speaker; without a wake mode a long buffer let the CPU doze
             * mid-stream. All three are ExoPlayer one-liners — the hand-rolled
             * alternative is an AudioFocusRequest, a BroadcastReceiver and a
             * WifiLock nobody needs to write.
             */
            .setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus= */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .also { player = it }
}


/** The two option menus the player knows how to present. */
internal enum class SheetKind { SPEED, QUALITY }
