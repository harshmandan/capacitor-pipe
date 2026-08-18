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
 * The overlay always fills the window and draws the video inside a rect. That
 * shape is what phase 2 needs: fullscreen becomes interpolation of this rect
 * toward the full screen on a single progress value, not a different layout.
 */
@UnstableApi
class PipePlayerOverlay(private val activity: Activity) {

    private companion object {
        const val TAG = "PipePlayerOverlay"

        /** How long nothing may move before the curtain is allowed down. */
        const val STABLE_QUIET_MS = 90L
        const val STABLE_POLL_MS = 16L
        const val STABLE_TIMEOUT_MS = 700L

        /**
         * core-pip is compileOnly, so its absence is a normal state.
         *
         * Checked once by name rather than by touching the type, which would
         * defeat the point: resolving the class is exactly what we are trying
         * to avoid on an app that did not take the dependency.
         */
        val corePipPresent: Boolean by lazy {
            try {
                Class.forName("androidx.core.pip.VideoPlaybackPictureInPicture")
                true
            } catch (ignored: Throwable) {
                false
            }
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

    /** Raised when a consumer-supplied button, or quality/speed, is tapped. */
    var onChromeEvent: ((String, String?) -> Unit)? = null

    private var attached = false

    /**
     * Compose animations need a MonotonicFrameClock in their context, which
     * AndroidUiDispatcher.Main supplies and a plain main-thread scope does not.
     */
    private val uiScope = CoroutineScope(AndroidUiDispatcher.Main)

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
        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing.value = isPlaying
                // The PiP action is a static snapshot, so it has to be rebuilt
                // or the strip shows a pause glyph on a paused video.
                pip?.setPlaybackState(isPlaying, ended.value)
            }

            override fun onPlaybackStateChanged(state: Int) {
                val reported = exo.duration
                durationMs.value = if (reported == C.TIME_UNSET) 0L else reported
                live.value = exo.isCurrentMediaItemLive
                ended.value = state == Player.STATE_ENDED
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
        })

        // Position has no callback; it has to be sampled. 250ms is fine for a
        // scrubber and cheap enough to leave running.
        uiScope.launch {
            while (true) {
                positionMs.value = exo.currentPosition
                bufferedMs.value = exo.bufferedPosition
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
                    positionMs = positionMs.value,
                    bufferedMs = bufferedMs.value,
                    durationMs = durationMs.value,
                    fullscreen = motion.isFullscreen,
                    ended = ended.value,
                    canMinimise = pipSupported,
                    live = live.value,
                    title = title.value,
                    subtitle = subtitle.value,
                    accent = accent.value,
                    showPreviousNext = showPreviousNext.value,
                    speedOptions = speedOptions.value,
                    qualityOptions = qualityOptions.value,
                    speedLabel = speedLabel.value,
                    qualityLabel = qualityLabel.value,
                    extraButton = extraButton.value,
                ),
                chromeCallbacks = PipePlayerChromeCallbacks(
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
                    onSpeedSelected = { id ->
                        speedLabel.value = id
                        player?.setPlaybackSpeed(
                            id.removeSuffix("x").toFloatOrNull() ?: 1f,
                        )
                        onChromeEvent?.invoke("speedSelected", id)
                    },
                    onQualitySelected = { id ->
                        // The player cannot switch quality itself — that is an
                        // extractor/track decision — so it reports and lets the
                        // host reload at the chosen level.
                        qualityLabel.value = id
                        onChromeEvent?.invoke("qualitySelected", id)
                    },
                    onSeek = { ms -> player?.seekTo(ms) },
                ),
                bindSurface = { texture ->
                    exo.setVideoTextureView(texture)
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
         * At full progress the video box is laid out LANDSCAPE, wider than a
         * portrait screen, and only then rotated a quarter turn to fit. Clipping
         * is applied to those pre-rotation bounds, so a screen-sized overlay
         * sliced the box down to screen width first and the rotation then spun a
         * narrow strip — full height, about a third of the width. Turning off
         * clipChildren does not help, because the clip happens inside Compose's
         * own draw rather than at the ViewGroup boundary.
         *
         * Sizing the overlay to the diagonal-safe square means the box always
         * fits before rotation, whatever the progress. The cost is that Compose
         * coordinates are no longer screen coordinates, hence screenOrigin below.
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
        sheetView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            view.disposeComposition()
        }
        sheetView = null
        pip?.close()
        pip = null
        (activity.findViewById<ViewGroup>(android.R.id.content))
            ?.viewTreeObserver
            ?.removeOnGlobalLayoutListener(geometryListener)
        orientationListener.disable()
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
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
    }

    /**
     * Hide or show the system bars.
     *
     * Immersive mode in fullscreen is required, not cosmetic: the Activity is
     * still portrait, so leaving the bars visible would show portrait status and
     * navigation bars framing a landscape video and give the illusion away.
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
     */
    val pipSupported: Boolean
        get() = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
            activity.packageManager.hasSystemFeature(
                android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE,
            ) &&
            corePipPresent &&
            activity is androidx.core.app.PictureInPictureProvider

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
             */
            private var lastRequested: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

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
                /*
                 * Honour a deliberate exit from fullscreen.
                 *
                 * The phone is often still sideways at the moment the user
                 * swipes down, so without this the next sensor reading undid
                 * the gesture instantly. Portrait clears the suppression, which
                 * is the user telling us they are done with landscape.
                 */
                if (target == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
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
                val landscape = target != ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                if (landscape == lastLandscape) return
                lastLandscape = landscape
                uiScope.launch { motion.animateTo(landscape) }
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

        Log.i(
            TAG,
            "geom pip=${inPip.value} square=${squareLocation[0]},${squareLocation[1]} " +
                "host=${hostLocation[0]},${hostLocation[1]} bounds=${bounds.width()}x${bounds.height()}",
        )
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
        if (measured != geometry.value) {
            geometry.value = measured
            geometryChangedAt = android.os.SystemClock.uptimeMillis()
        }
    }

    /**
     * Make fullscreen a genuinely landscape Activity, once it has settled.
     *
     * The transform is faked *during* the drag because a configuration change is
     * discrete — there is no 37% of an orientation change, and every
     * intermediate position of a drag has to be renderable. But leaving the fake
     * rotation in place afterwards meant one visual state had two coordinate
     * frames, and that duality caused its own run of bugs: the viewer's "down"
     * was the video's down rather than the window's, so the exit gesture
     * tracked and then moved the player the wrong way; sheets opened upright in
     * their own window over a sideways video.
     *
     * Swapping only when settled is what keeps it seamless — and why this is NOT
     * driven from the same midpoint that hides the system bars. A config change
     * partway through a drag would reflow the host page under the finger, which
     * is the exact thing this design exists to avoid.
     *
     * The video does not move at the swap: the surface applies its own rotation
     * only while the Activity is portrait, so the fake rotation switches off in
     * the same frame the real one arrives.
     *
     * Stated plainly, the cost: the host page reflows once on entering
     * fullscreen, where before it never did. That is the price of steady-state
     * fullscreen being an ordinary landscape window.
     */
    fun setFullscreenSettled(fullscreen: Boolean) {
        val landscape = activity.resources.configuration.orientation ==
            Configuration.ORIENTATION_LANDSCAPE
        if (fullscreen) {
            if (!landscape) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
        } else {
            // Leaving fullscreen is a decision the sensor must now respect, or
            // it re-expands on its next reading while the phone is still on its
            // side. `lastLandscape` is deliberately left alone: clearing it
            // would make that same reading look like a fresh turn.
            suppressAutoFullscreen = true
            if (landscape) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        }
    }

    /**
     * Turn the Activity to match a video that is only *pretending* to be
     * landscape, so page-level UI arrives the right way up.
     *
     * A ModalBottomSheet renders in its own window, which means no amount of
     * rotating our own composables can turn it — it is not in our hierarchy.
     * The way to get a landscape sheet is therefore to make the Activity really
     * landscape.
     *
     * Nothing jumps when this happens. The surface only applies its own 90
     * degree rotation while the Activity is portrait, so as the configuration
     * flips the fake rotation switches off in the same frame and the video stays
     * exactly where it was. That symmetry is the reason this is a two-line fix
     * rather than a second layout.
     */
    private fun alignActivityWithVideo() {
        if (!motion.isFullscreen) return
        val portrait = activity.resources.configuration.orientation !=
            Configuration.ORIENTATION_LANDSCAPE
        if (portrait) {
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
     */
    fun load(url: String) {
        ended.value = false
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

        ensurePlayer().apply {
            setMediaItem(MediaItem.fromUri(url))
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
        extraButton: PipePlayerExtraButton?,
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
        this.extraButton.value = extraButton
    }

    @androidx.compose.runtime.Composable
    private fun SheetLayer() {
        when (openSheet.value) {
            SheetKind.SPEED -> PipePlayerSheet(
                title = "Playback speed",
                options = speedOptions.value,
                selectedId = speedLabel.value,
                accent = accent.value,
                onSelect = { id ->
                    openSheet.value = null
                    speedLabel.value = id
                    player?.setPlaybackSpeed(id.removeSuffix("x").toFloatOrNull() ?: 1f)
                    onChromeEvent?.invoke("speedSelected", id)
                },
                onDismiss = { openSheet.value = null },
                immersive = motion.isFullscreen,
            )

            SheetKind.QUALITY -> PipePlayerSheet(
                title = "Quality",
                options = qualityOptions.value,
                selectedId = qualityLabel.value,
                accent = accent.value,
                onSelect = { id ->
                    openSheet.value = null
                    // The player cannot switch quality itself — that is an
                    // extractor/track decision — so it reports and lets the host
                    // reload at the chosen level.
                    qualityLabel.value = id
                    onChromeEvent?.invoke("qualitySelected", id)
                },
                onDismiss = { openSheet.value = null },
                immersive = motion.isFullscreen,
            )

            null -> Unit
        }
    }

    private fun ensurePlayer(): ExoPlayer =
        player ?: ExoPlayer.Builder(activity).build().also { player = it }
}


/** The two option menus the player knows how to present. */
internal enum class SheetKind { SPEED, QUALITY }
