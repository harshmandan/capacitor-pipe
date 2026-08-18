package ink.harsh.plugins.player

import android.graphics.RectF
import android.util.Log
import android.view.TextureView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.util.lerp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.compose.modifiers.resizeWithContentScale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draws the video somewhere between its docked rect and fullscreen, and lets a
 * finger move it between the two.
 *
 * The whole transform is one interpolation on [PipePlayerMotion]'s
 * progress:
 *
 * ```
 *              p = 0 (docked)        p = 1 (fullscreen)
 *   bounds     host's dock rect      screenH x screenW, centred
 *   rotation   0°                    90°
 * ```
 *
 * The size swaps and the layer rotates, so at p = 1 a landscape-shaped box
 * rotated a quarter turn exactly fills a portrait screen. The Activity never
 * changes orientation — no config change, no recreation, no black frame, and
 * the host WebView underneath is never reflowed mid-drag. YouTube fakes it the
 * same way.
 */
@Composable
internal fun PipePlayerSurface(
    dockRect: State<RectF?>,
    motion: PipePlayerMotion,
    mini: Boolean,
    /**
     * The Activity is in Picture-in-Picture.
     *
     * Handled here rather than by the host hiding things, because in PiP the
     * system shrinks the **whole Activity window** — the WebView included. The
     * player has to claim the entire window or the user gets a thumbnail of a
     * web page with a video somewhere inside it.
     */
    pip: Boolean,
    onSpeedBoost: (Boolean) -> Unit,
    onSeekBy: (Long) -> Unit,
    geometry: State<PipePlayerGeometry>,
    /** Width / height of the decoded video, 0 until the first frame is sized. */
    videoAspect: State<Float>,
    /** Host-declared size, corner and margins for the corner player. */
    miniConfig: PipePlayerMiniConfig,
    chromeState: PipePlayerChromeState,
    chromeCallbacks: PipePlayerChromeCallbacks,
    bindSurface: (TextureView) -> Unit,
    onImmersiveChanged: (Boolean) -> Unit,
) {
    // No rect claimed means nothing to draw. The overlay stays attached and
    // fully transparent so the host WebView shows through untouched.
    val hostRect = dockRect.value

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current

        /*
         * Read the CURRENT window size rather than the values captured when the
         * overlay attached: those go stale the moment the device rotates.
         */
        val configuration = LocalConfiguration.current

        /*
         * Measured geometry, never displayMetrics.
         *
         * Two different origins are in play and conflating them caused two
         * separate bugs. Host rects arrive in WebView coordinates; the
         * fullscreen target is in display coordinates; and this composable draws
         * in the square overlay's own coordinates, which match neither. The
         * overlay measures all three from the live views — see
         * PipePlayerOverlay.measureGeometry.
         */
        val geo = geometry.value
        val screenW = geo.screenW
        val screenH = geo.screenH

        // Nothing has been laid out yet: drawing against a zero-sized screen
        // would flash the video at the wrong place for a frame.
        if (screenW <= 0f || screenH <= 0f) return@BoxWithConstraints

        /*
         * If the Activity is ALREADY landscape, do not fake a rotation on top of
         * the real one — the video would end up a quarter turn out. This happens
         * when the host is not portrait-locked and the user turns the phone:
         * Android rotates the Activity, and our transform rotated again.
         *
         * The host is documented as needing screenOrientation="portrait", but
         * being defensive here is cheap and the failure is otherwise baffling.
         */
        val activityLandscape =
            configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        /*
         * The corner window. A host that has navigated away claims no rect at
         * all, which is exactly when mini has to keep working, so mini
         * synthesises its own rather than deriving one from the host's.
         */
        val miniWidth = with(density) { miniConfig.width.dp.toPx() }
        val miniHeight = miniWidth * 9f / 16f
        val padLeft = with(density) { miniConfig.paddingLeft.dp.toPx() }
        val padTop = with(density) { miniConfig.paddingTop.dp.toPx() }
        val padRight = with(density) { miniConfig.paddingRight.dp.toPx() }
        val padBottom = with(density) { miniConfig.paddingBottom.dp.toPx() }

        /*
         * Bottom spacing is the HOST's to declare, not something to derive.
         *
         * The obvious move is WindowInsets.navigationBars, and it is wrong: the
         * mini player lives inside the app's own window, so what it actually
         * collides with is the host's bottom nav or drawer — which Android
         * knows nothing about. Only the host knows its tab bar is 56dp tall.
         * The system inset is a different measurement to a different edge.
         */


        /*
         * EVERYTHING below is in overlay coordinates before anything is mixed.
         *
         * Three rects, three different spaces: a host rect is relative to the
         * WebView, the corner and fullscreen rects are relative to the display,
         * and this composable draws in the square overlay's own space. Converting
         * first and interpolating second is what keeps the maths honest —
         * interpolating first and converting once was the bug that drew the
         * docked video half a status bar too high.
         */
        val docked = hostRect?.let { rect ->
            RectF(
                geo.hostX + rect.left,
                geo.hostY + rect.top,
                geo.hostX + rect.right,
                geo.hostY + rect.bottom,
            )
        }
        /*
         * Which corner, and where the user has dragged it to.
         *
         * `miniCorner` starts from the host's config but the user may override
         * it by dragging; `miniDrag` is the in-flight offset, zero except while
         * a finger is down. Keeping them separate means a release animates from
         * wherever the finger left off to the corner it snapped to, rather than
         * jumping.
         */
        var miniCorner by remember(miniConfig.corner) { mutableStateOf(miniConfig.corner) }
        var miniDrag by remember { mutableStateOf(Offset.Zero) }

        val cornerLeft = geo.screenX + if (miniCorner.isLeft) {
            padLeft
        } else {
            screenW - miniWidth - padRight
        }
        val cornerTop = geo.screenY + if (miniCorner.isTop) {
            padTop
        } else {
            screenH - miniHeight - padBottom
        }
        val corner = RectF(
            cornerLeft + miniDrag.x,
            cornerTop + miniDrag.y,
            cornerLeft + miniWidth + miniDrag.x,
            cornerTop + miniHeight + miniDrag.y,
        )

        /*
         * No claimed rect means the corner is both ends of the journey.
         *
         * This used to `return` when un-minimising without a rect, which made
         * the player VANISH: navigate away from the page that docked it, hit
         * expand, and playback continued with nothing on screen. Falling back to
         * the corner keeps the player somewhere real; refusing the expand
         * outright is handled in PipePlayerOverlay.setMini, which can tell the
         * host about it.
         */
        val base = docked ?: corner

        /*
         * PiP pins both axes rather than animating to them. The system is
         * already animating the window itself, and running our transform at the
         * same time would be two animations fighting over the same pixels.
         */
        val p = if (pip) 1f else motion.progress.value
        val m = if (pip) 0f else motion.miniProgress.value

        /*
         * Two axes, applied in order: first travel from the docked rect toward
         * the corner, then from wherever that is toward fullscreen. They are
         * mutually exclusive in practice — going mini drives fullscreen to 0
         * first — but composing them means a mini request mid-expand still
         * produces a continuous path instead of a jump.
         */
        val start = lerpRect(base, corner, m)

        val upright = activityLandscape || pip
        val fullWidth = if (upright) screenW else screenH
        val fullHeight = if (upright) screenH else screenW

        val width = lerp(start.width(), fullWidth, p)
        val height = lerp(start.height(), fullHeight, p)
        val centreX = lerp(start.centerX(), geo.screenX + screenW / 2f, p)
        val centreY = lerp(start.centerY(), geo.screenY + screenH / 2f, p)

        /*
         * Bars follow the transform, but only once it has committed past the
         * midpoint — toggling them continuously during a drag would make the
         * host WebView reflow under the finger, which is the exact thing this
         * design avoids.
         */
        val immersive = p > 0.5f
        LaunchedEffect(immersive) { onImmersiveChanged(immersive) }

        val scope = rememberCoroutineScope()

        /*
         * Travel is the drag distance spanning docked to fullscreen.
         *
         * Back to the shorter screen edge at 0.45, by request — this is the
         * value that was in place before the slow-down, and the one that
         * actually let a normal flick dismiss fullscreen. Lengthening it to
         * `screenH * 0.55` did slow the tracking as asked, but release velocity
         * is divided by this number, so it also made flicks ~3x harder to
         * commit and swipe-to-exit stopped landing.
         *
         * If it ever needs slowing again, change this multiplier AND
         * COMMIT_VELOCITY in PipePlayerMotion together. They are one setting.
         */
        val travel = min(screenW, screenH) * 0.45f

        /*
         * Which node owns the in-flight drag.
         *
         * `enabled` is re-evaluated continuously, so a flag that flips mid-drag
         * cancels the gesture. These latch for the duration of one drag, which
         * lets the two draggables below hand over cleanly at the boundary
         * instead of killing each other's gesture halfway.
         */
        var boxDragging by remember { mutableStateOf(false) }
        var screenDragging by remember { mutableStateOf(false) }

        val dragState = rememberDraggableState { delta ->
            Log.i(
                "PipeDrag",
                "delta=$delta p=${motion.progress.value} box=$boxDragging screen=$screenDragging",
            )
            // Up is negative in screen coordinates and positive in progress.
            scope.launch { motion.drag(-delta / travel) }
        }

        /*
         * A screen-sized owner for the COLLAPSE drag.
         *
         * The drag used to live entirely on the video box, and swipe-down
         * therefore did not follow the finger: collapsing shrinks that box
         * upward while the finger travels downward, the finger leaves its
         * bounds within a few events, and Compose stops delivering. Only a very
         * fast flick landed enough events to move anything — which is exactly
         * how it behaved. Expanding never showed the bug because the box grows
         * toward the finger.
         *
         * This node never resizes, so a collapse tracks all the way down.
         *
         * `enabled` matters as much as the node: while docked it is off, so the
         * overlay does not swallow vertical scrolls meant for the host's page.
         */
        /*
         * The collapse drag is ATTACHED CONDITIONALLY, not merely disabled.
         *
         * This node fills the whole overlay, and the overlay covers the whole
         * window. A pointer-input modifier here makes the ComposeView claim
         * every touch in the window — so with a plain `enabled = false`
         * draggable, the host's web page stopped responding entirely: no
         * button, no scroll, nothing. The plugin silently broke the app around
         * it.
         *
         * Adding the modifier only while it is actually needed means that at
         * rest there is no pointer-input node here at all, and touches reach the
         * WebView untouched — which is the whole premise of an overlay.
         */
        val collapseDraggable = !mini && !pip && (screenDragging || p > 0.01f) && !boxDragging
        val boxArmed = !mini && !pip && (boxDragging || p <= 0.01f)
        Log.i(
            "PipeDrag",
            "armed: box=$boxArmed screen=$collapseDraggable p=$p mini=$mini pip=$pip",
        )

        Box(
            Modifier
                .fillMaxSize()
                /*
                 * Rotated to match the video, so "down" means what the USER
                 * means by it.
                 *
                 * In fullscreen the video is fake-rotated 90 degrees, so the
                 * viewer's down is not the window's down. Reading raw screen
                 * space here gave NEGATIVE deltas for a downward swipe — the
                 * gesture registered, tracked, and moved the player toward
                 * fullscreen it was already at, which is why it looked like
                 * nothing happened.
                 *
                 * Rotating this node by the same amount puts the gesture back in
                 * the video's frame: local +y is always the direction the viewer
                 * perceives as down, in both the fake-rotated and the genuinely
                 * landscape case. The overlay is a square larger than the
                 * screen, so a rotated full-size child still covers it.
                 */
                .graphicsLayer {
                    rotationZ = if (activityLandscape || pip) 0f else 90f * p
                }
                .then(
                    if (!collapseDraggable) {
                        Modifier
                    } else {
                        Modifier.draggable(
                            state = dragState,
                            orientation = Orientation.Vertical,
                            onDragStarted = {
                                Log.i("PipeDrag", "SCREEN drag started at p=$p")
                                screenDragging = true
                            },
                            onDragStopped = { velocity ->
                                Log.i("PipeDrag", "SCREEN stop v=$velocity u/s=${-velocity / travel}")
                                screenDragging = false
                                motion.release(-velocity / travel)
                            },
                        )
                    },
                ),
        ) {
        Box(
            Modifier
                .offset {
                    IntOffset(
                        (centreX - width / 2f).roundToInt(),
                        (centreY - height / 2f).roundToInt(),
                    )
                }
                /*
                 * requiredSize, not size: at p = 1 the box is deliberately WIDER
                 * than the screen (it is landscape, about to be rotated a quarter
                 * turn). `size` still honours the parent's constraints, so the box
                 * was clamped to screen width and the rotation happened about the
                 * wrong centre — the video ended up jammed against the left edge.
                 * requiredSize ignores incoming constraints, which is what a
                 * child larger than its parent needs.
                 */
                .requiredSize(
                    width = with(density) { width.toDp() },
                    height = with(density) { height.toDp() },
                )
                // Rotation as a render-thread property, not a layout change: this
                // moves every frame during a drag, and re-laying-out the tree
                // per frame is the wrong cost model. Origin is the centre by
                // default, which the interpolation above assumes.
                /*
                 * BEFORE graphicsLayer, and that placement is the whole fix.
                 *
                 * Pointer coordinates are transformed by every layer between the
                 * gesture and the root. Placed after the rotation, this gesture
                 * ran in the box's LOCAL space — where, at 90 degrees, a
                 * screen-vertical swipe is horizontal. Orientation.Vertical saw
                 * almost no movement, never passed slop, and swipe-to-dismiss
                 * simply never fired in fullscreen. Dragging *into* fullscreen
                 * always worked because at p = 0 there is no rotation to undo.
                 *
                 * Sitting outside the layer, it reads unrotated screen-space
                 * movement, which is what "swipe down" means to a user.
                 */
                /*
                 * Modifier.draggable, not a raw pointerInput.
                 *
                 * The hand-rolled version ran detectVerticalDragGestures and
                 * kept its own VelocityTracker, feeding it every change purely
                 * to recover an exit velocity the detector does not provide.
                 * draggable's onDragStopped hands that over already computed,
                 * and fires on cancel too — so the tracker, the reset call and
                 * the separate cancel branch all go away.
                 *
                 * `enabled` replaces the early returns: mini is a target rather
                 * than a stage for gestures, and in PiP the system owns input.
                 *
                 * Deliberately NOT disabled when the Activity is really
                 * landscape. It used to be, on the reasoning that a rotated
                 * window IS the screen so there is nothing to expand into. True
                 * while fullscreen was always faked, but the orientation
                 * listener now rotates the Activity for real, so the guard
                 * matched almost every fullscreen session and swipe-to-dismiss
                 * silently stopped existing. Expanding is indeed a no-op there;
                 * collapsing is not, and collapsing is the whole point.
                 */
                .draggable(
                    state = dragState,
                    orientation = Orientation.Vertical,
                    /*
                     * Only from the docked end. Expanding grows the box TOWARD
                     * the finger, so it stays under it and this node keeps
                     * receiving events; collapsing shrinks the box away and is
                     * handled by the screen-sized node instead.
                     */
                    enabled = !mini && !pip && (boxDragging || p <= 0.01f),
                    onDragStarted = {
                        Log.i("PipeDrag", "BOX drag started at p=$p")
                        boxDragging = true
                    },
                    onDragStopped = { velocity ->
                        Log.i("PipeDrag", "BOX stop v=$velocity u/s=${-velocity / travel}")
                        boxDragging = false
                        // px/s, positive toward fullscreen. Negated because
                        // dragging *up* (dy < 0) is what expands.
                        motion.release(-velocity / travel)
                    },
                )
                // No fake rotation in PiP: the system sized the window to the
                // aspect ratio we asked for, so it is already the right shape.
                .graphicsLayer {
                    rotationZ = if (activityLandscape || pip) 0f else 90f * p
                }
                /*
                 * Rounded and lifted, but only as it becomes the corner window.
                 *
                 * Android's PiP gets its rounded corners from the SYSTEM, which
                 * clips the task window itself. The mini player is not a system
                 * window — it is our own view inside the host Activity — so
                 * nothing rounds it for us, and it read as a hard-edged
                 * rectangle sitting where people expect a PiP-shaped one.
                 *
                 * Driven by the same `m`, so the corners round as the player
                 * travels to the corner and are square again by the time it is
                 * docked, where a radius would look like a mistake against the
                 * host's own rect.
                 *
                 * Deliberately NOT rounded in PiP: there the system is already
                 * clipping our window to its own radius, and rounding the
                 * content as well leaves black wedges in the corners wherever
                 * the two radii disagree.
                 *
                 * clip = true also does the job the old clipToBounds did, which
                 * is keeping a pinch-zoomed video inside the player's bounds.
                 */
                .shadow(
                    elevation = if (pip) 0.dp else 14.dp * m,
                    shape = RoundedCornerShape(if (pip) 0.dp else 12.dp * m),
                    clip = true,
                )
                .background(Color.Black)
                /*
                 * Drag the corner player to another corner.
                 *
                 * Two-dimensional, so it needs detectDragGestures rather than
                 * the vertical `draggable` the fullscreen transform uses. Only
                 * while mini: at any other size this would fight the
                 * docked/fullscreen drag for the same finger.
                 *
                 * Snapping rather than free placement, because a window parked
                 * at an arbitrary offset has no relationship to the host's
                 * layout — the margins exist to clear the host's own chrome, and
                 * only the corners honour them.
                 */
                .pointerInput(mini, miniConfig.draggable, screenW, screenH) {
                    if (!mini || !miniConfig.draggable) return@pointerInput
                    detectDragGestures(
                        onDragEnd = {
                            val settled = PipePlayerCorner.nearest(
                                centreX = corner.centerX() - geo.screenX,
                                centreY = corner.centerY() - geo.screenY,
                                width = screenW,
                                height = screenH,
                            )
                            // Corner first, offset second: the rect recomputes
                            // around the new corner, and zeroing the drag then
                            // animates it home rather than teleporting.
                            miniCorner = settled
                            miniDrag = Offset.Zero
                        },
                        onDragCancel = { miniDrag = Offset.Zero },
                    ) { change, dragAmount ->
                        miniDrag += dragAmount
                        change.consume()
                    }
                }
        ) {
            /*
             * Letterbox the video rather than stretching it to the box.
             *
             * ExoPlayer draws into the whole of a TextureView's surface and does
             * no aspect handling of its own — that normally lives in
             * AspectRatioFrameLayout, which we do not use. So a fillMaxSize
             * TextureView distorted every video whose aspect did not match the
             * box, which is most of them once fullscreen makes the box the
             * shape of the phone.
             *
             * It is also the precondition for zoom-to-fill: "fill" only means
             * something if there are bars to crop.
             */
            val aspect = videoAspect.value
            val boxAspect = if (height > 0f) width / height else 1f

            /*
             * Source size for resizeWithContentScale. Only the RATIO matters —
             * ContentScale.Fit scales uniformly into whatever constraints the
             * box supplies — so any pair with the right proportions will do.
             *
             * NEVER Size.Unspecified. It is Size(NaN, NaN), and the modifier
             * rounds its computed dimensions: passing it crashes the frame with
             * "Cannot round NaN value". Media3's own code avoids this by
             * withholding the surface until a size is known; we cannot, because
             * the TextureView has to exist for ExoPlayer to attach to and for
             * core-pip to track.
             *
             * Before the first frame reports a size, fall back to the box's own
             * aspect, which makes Fit a no-op and fills the box — exactly what
             * the hand-written version did in that case.
             */
            val sourceAspect = when {
                aspect > 0f -> aspect
                boxAspect > 0f -> boxAspect
                else -> 16f / 9f
            }
            val videoSize = Size(sourceAspect * 1000f, 1000f)

            /*
             * The scale at which the letterboxed video covers the box — where
             * the shorter edge fits exactly and the longer one is cropped. This
             * is the "fit one of the smaller edges" stop that pinch snaps to.
             *
             * Falls out of the two aspects directly, now that the fit sizing is
             * the modifier's job rather than ours.
             */
            val coverScale =
                if (aspect <= 0f) 1f else max(boxAspect / aspect, aspect / boxAspect)

            var zoom by remember { mutableStateOf(1f) }

            val transformState = rememberTransformableState { zoomChange, _, _ ->
                val next = (zoom * zoomChange).coerceIn(1f, max(2f, coverScale))
                /*
                 * Magnetic at the fill point. Landing exactly on cover by pinch
                 * is impossible, and being a pixel off leaves a hairline of
                 * letterbox that reads as a rendering bug rather than a choice.
                 */
                zoom = if (abs(next - coverScale) < 0.06f) coverScale else next
            }

            // Zoom is a fullscreen affordance; carrying a 2x crop back down into
            // a small docked rect would leave the host with a mystery.
            LaunchedEffect(p < 0.01f) { if (p < 0.01f) zoom = 1f }

            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                /*
                 * Our own TextureView, deliberately — NOT media3-ui-compose's
                 * PlayerSurface.
                 *
                 * PlayerSurface owns the view internally, and core-pip needs a
                 * handle to it: `setPlayerView` is what tracks the bounds that
                 * become PiP's sourceRectHint. Taking the modifier without the
                 * surface keeps that working and still deletes the arithmetic.
                 */
                AndroidView(
                    factory = { context -> TextureView(context).also(bindSurface) },
                    modifier = Modifier
                        .resizeWithContentScale(ContentScale.Fit, videoSize)
                        .graphicsLayer {
                            scaleX = zoom
                            scaleY = zoom
                        },
                )
            }

            /*
             * Chrome lives INSIDE the rotated box, so it turns with the video
             * rather than staying in portrait over a landscape frame. Tap
             * toggles it; it hides itself while playing so the video is not
             * permanently framed by controls.
             */
            var controlsVisible by remember { mutableStateOf(true) }

            LaunchedEffect(controlsVisible, chromeState.playing) {
                if (controlsVisible && chromeState.playing) {
                    delay(3_500)
                    controlsVisible = false
                }
            }

            var boosting by remember { mutableStateOf(false) }
            var seekBurst by remember { mutableStateOf<SeekBurst?>(null) }

            /*
             * Last line of defence for the boost.
             *
             * onPress's tryAwaitRelease is the normal way out, but it only
             * returns if the gesture coroutine survives to see the finger lift.
             * Anything that tears the detector down mid-press — a pointerInput
             * key changing, the surface leaving composition — cancels it
             * silently, and playback would be stranded at 2x with the pill
             * stuck on screen and no finger to lift.
             */
            DisposableEffect(Unit) {
                onDispose {
                    if (boosting) {
                        boosting = false
                        onSpeedBoost(false)
                    }
                }
            }

            // The burst label lingers after the last tap, which is what makes
            // chaining legible: it must still be on screen when the next tap
            // lands, or three taps read as three separate jumps.
            LaunchedEffect(seekBurst) {
                if (seekBurst != null) {
                    delay(800)
                    seekBurst = null
                }
            }

            Box(
                Modifier
                    .fillMaxSize()
                    /*
                     * Modifier.transformable with canPan = { false }.
                     *
                     * This was hand-rolled — awaitEachGesture, a two-pointer
                     * guard, calculateZoom — specifically because
                     * detectTransformGestures ALSO handles single-finger pan and
                     * consumes it, which once silently ate the vertical drag
                     * that moves the player between docked and fullscreen.
                     *
                     * canPan is the supported answer to exactly that. Compose's
                     * slop test passes on pan only when canPan returns true, and
                     * if slop is never passed it branches past every
                     * PointerInputChange.consume() — so with canPan false, a
                     * one-finger drag is never consumed here. Zoom and rotation
                     * are inherently two-pointer, so the guard is redundant.
                     *
                     * lockRotationOnZoomPan because a video does not rotate; we
                     * want the zoom out of this gesture and nothing else.
                     */
                    .transformable(
                        state = transformState,
                        canPan = { false },
                        lockRotationOnZoomPan = true,
                        enabled = !mini && !pip,
                    )
                    /*
                     * Keyed on `live` as well as `mini`. pointerInput does not
                     * restart when only the lambdas change, so the running
                     * detector keeps the closures it was created with — a
                     * stream that goes live after the detector started would
                     * still be judged against the liveness captured back then.
                     */
                    .pointerInput(mini, pip, chromeState.live, chromeState.playing) {
                        detectTapGestures(
                            onTap = {
                                if (pip) return@detectTapGestures
                                /*
                                 * A tap REVEALS controls; it never expands.
                                 *
                                 * Expanding is the expand button's job, and
                                 * doing both meant the button was decorative —
                                 * anywhere you pressed did the same thing. It
                                 * also matches system PiP, where a tap raises
                                 * the controls and a separate affordance
                                 * restores the app.
                                 */
                                controlsVisible = !controlsVisible
                            },
                            onDoubleTap = { offset ->
                                if (mini || pip) return@detectTapGestures
                                val forward = offset.x > size.width / 2f
                                // Accumulate against the burst still on screen;
                                // reversing direction restarts the count.
                                val previous = seekBurst
                                val seconds =
                                    if (previous != null && previous.forward == forward) {
                                        previous.seconds + 10
                                    } else {
                                        10
                                    }
                                // `tick` always increments, even when the label
                                // does not change, so that every tap restarts the
                                // ripple. Keying it on the seconds instead would
                                // silently swallow the animation on a repeat tap.
                                seekBurst = SeekBurst(
                                    forward = forward,
                                    seconds = seconds,
                                    tick = (previous?.tick ?: 0) + 1,
                                )
                                onSeekBy(if (forward) 10_000L else -10_000L)
                            },
                            onLongPress = {
                                if (mini || pip) return@detectTapGestures
                                // Live has no buffer ahead of the play head, so
                                // there is nothing to play faster THROUGH: the
                                // rate would climb, hit the live edge and stall.
                                if (chromeState.live) return@detectTapGestures
                                // Nor does a paused video. 2x on a still frame
                                // shows a pill over something that is not
                                // moving, which reads as a broken control.
                                if (!chromeState.playing) return@detectTapGestures
                                boosting = true
                                onSpeedBoost(true)
                                // The pill IS the feedback, so the chrome gets
                                // out of its way. Leaving both up buries a small
                                // pill under a full set of controls at exactly
                                // the moment the viewer is watching the video.
                                controlsVisible = false
                            },
                            onPress = {
                                // Also returns on cancel, which is what we want:
                                // a boost must never outlive the finger.
                                tryAwaitRelease()
                                if (boosting) {
                                    boosting = false
                                    onSpeedBoost(false)
                                }
                            },
                        )
                    },
            ) {
                if (boosting) {
                    Box(
                        Modifier.fillMaxSize().padding(top = 32.dp),
                        contentAlignment = Alignment.TopCenter,
                    ) { SpeedBoostPill() }
                }

                // Behind the label, so the number stays legible over it.
                seekBurst?.let { burst ->
                    SeekRipple(burst.forward, burst.tick)
                }

                seekBurst?.let { burst ->
                    Box(
                        Modifier.fillMaxSize().padding(horizontal = 48.dp),
                        contentAlignment = if (burst.forward) {
                            Alignment.CenterEnd
                        } else {
                            Alignment.CenterStart
                        },
                    ) { SeekBurstIndicator(burst.seconds, burst.forward) }
                }

                /*
                 * Compact takes over once the player is more than half way to
                 * the corner, rather than on arrival: the full chrome is already
                 * illegible at that size, so waiting until the animation lands
                 * would show a squashed set of controls for the whole journey.
                 */
                val compact = m > 0.5f || pip

                AnimatedVisibility(
                    visible = compact && (pip || controlsVisible),
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    PipePlayerCompactChrome(
                        state = chromeState,
                        callbacks = chromeCallbacks,
                        showButtons = !pip,
                    )
                }

                AnimatedVisibility(
                    visible = controlsVisible && !compact,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    PipePlayerChrome(
                        state = chromeState.copy(fullscreen = p > 0.5f),
                        callbacks = chromeCallbacks,
                    )
                }

            }
        }
        }
    }
}


/** Corner-wise, so a rect changing size and position stays one continuous move. */
private fun lerpRect(from: RectF, to: RectF, fraction: Float): RectF =
    if (fraction <= 0f) {
        from
    } else {
        RectF(
            lerp(from.left, to.left, fraction),
            lerp(from.top, to.top, fraction),
            lerp(from.right, to.right, fraction),
            lerp(from.bottom, to.bottom, fraction),
        )
    }

/**
 * Where the host's rect and the display sit inside the square overlay.
 *
 * The square is centred in the Activity's content view, so its coordinates
 * are nobody else's. Host rects arrive in WebView space and the fullscreen
 * target is in display space; both need shifting, and by different amounts,
 * because system bars inset one and not the other.
 */
internal data class PipePlayerGeometry(
    /** WebView origin, in overlay coordinates. */
    val hostX: Float = 0f,
    val hostY: Float = 0f,
    /** Display origin, in overlay coordinates. */
    val screenX: Float = 0f,
    val screenY: Float = 0f,
    /** Full display size, including whatever the system bars are covering. */
    val screenW: Float = 0f,
    val screenH: Float = 0f,
)

/**
 * One chained double-tap burst.
 *
 * Only the direction is kept, not where the finger landed: the ripple is
 * anchored to the player's edge, so the tap position stops mattering the moment
 * the half is decided.
 */
private data class SeekBurst(
    val forward: Boolean,
    val seconds: Int,
    val tick: Int,
)

/**
 * The expanding wash behind a double-tap seek, as YouTube draws it.
 *
 * The circle is centred on the player's own left or right EDGE, not on the
 * tap. Half of it therefore falls outside the view and what remains reads as a
 * semicircle bulging inward from the edge — which is the shape people recognise.
 * Centring it on the finger instead produced a full disc floating wherever the
 * tap happened to land, so identical gestures drew different shapes and it read
 * as a button press rather than as the side of the player responding.
 *
 * Clipped to the tapped half as well, so a forward seek never washes over the
 * rewind side.
 */
@Composable
private fun SeekRipple(forward: Boolean, tick: Int) {
    // Keyed on tick, so a repeat tap in the same direction restarts the wash
    // rather than letting the first one finish alone.
    val progress = remember(tick) { Animatable(0f) }
    LaunchedEffect(tick) {
        progress.animateTo(1f, animationSpec = tween(durationMillis = 520))
    }

    Canvas(Modifier.fillMaxSize()) {
        val t = progress.value
        if (t >= 1f) return@Canvas

        val half = size.width / 2f
        val left = if (forward) half else 0f
        val right = if (forward) size.width else half

        clipRect(left = left, top = 0f, right = right, bottom = size.height) {
            drawCircle(
                // Fades out as it grows; a constant alpha reads as a flash.
                color = Color.White.copy(alpha = 0.20f * (1f - t)),
                // Sized against the half it lives in, so it sweeps most of that
                // half without ever reaching across the midline.
                radius = half * (0.78f + 0.5f * t),
                /*
                 * Centred OUTSIDE the player, not on the edge itself. Sitting
                 * exactly on the edge shows the widest part of the circle
                 * flush against it, which still reads as a disc cut in half;
                 * pushing the centre further out shows only the shallower arc,
                 * so the shape leans in from off-screen the way YouTube's does.
                 */
                center = Offset(
                    x = if (forward) size.width + half * 0.22f else -half * 0.22f,
                    y = size.height / 2f,
                ),
            )
        }
    }
}

