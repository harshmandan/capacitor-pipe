package ink.harsh.plugins.player

import android.graphics.RectF
import android.util.Log
import android.view.TextureView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.size
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
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
import kotlin.math.sign

/**
 * Draws the video at its settled rect — docked, corner or fullscreen — and lets
 * a finger move it between docked and fullscreen.
 *
 * The model is discrete, not interpolated. A drag only TRANSLATES the settled
 * video with rubber-band resistance; the actual change of state happens on
 * release, behind [PipePlayerMotion]'s shutter, and is carried by a real
 * orientation change — steady-state fullscreen is a genuinely landscape
 * Activity, so there is one coordinate frame rather than two. `progress` is a
 * drag offset in travel units (0 at the docked rest, 1 at the fullscreen
 * rest), not a shape parameter; nothing between the two states is ever
 * rendered. See CLAUDE.md Gotcha 24 for why the earlier continuous rotation
 * was the wrong model.
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
    /**
     * Playhead and buffered-ahead, deliberately NOT fields of [chromeState]:
     * they tick four times a second forever, and as fields every tick
     * recomposed everything the state touches. As `State` they are read only
     * where they are drawn.
     */
    position: State<Long>,
    buffered: State<Long>,
    /**
     * The current media has reported STATE_READY at least once.
     *
     * Until it does, the surface is a black box with a centred spinner and NO
     * chrome — the loading presentation. Showing the full controls over an
     * empty surface, which is what happened before this flag existed, reads
     * as the player being broken rather than the video arriving.
     */
    mediaReady: State<Boolean>,
    /** ExoPlayer is buffering: initial prepare, a seek, or a mid-play stall. */
    buffering: State<Boolean>,
    chromeCallbacks: PipePlayerChromeCallbacks,
    bindSurface: (TextureView) -> Unit,
    onImmersiveChanged: (Boolean) -> Unit,
    /** Fired when the transform has come to rest, not at the midpoint. */
    onFullscreenSettled: (Boolean) -> Unit,
) {
    // No rect claimed means nothing to draw. The overlay stays attached and
    // fully transparent so the host WebView shows through untouched.
    val hostRect = dockRect.value

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current

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

        /*
         * Back to the configured corner every time the player is minimised.
         *
         * A dragged corner used to persist for the life of the app, so
         * minimising again put the window wherever it was last dropped. System
         * PiP does remember, but this is the host's layout rather than the
         * system's: the corner is chosen to avoid the host's own bottom nav or
         * app bar, and a window that quietly stops honouring that is worse than
         * one that forgets a drag. The drag still holds for as long as the mini
         * player is on screen.
         */
        LaunchedEffect(mini) { if (mini) miniCorner = miniConfig.corner }

        /*
         * Animatable, so releasing SLIDES to the corner instead of jumping.
         *
         * It was a plain state zeroed on release, which teleported the window:
         * the anchor moves to the new corner and the offset vanishes in the same
         * frame. Animating the offset home — after compensating for the anchor
         * having moved — turns those two changes into one continuous move.
         */
        val miniDrag = remember { Animatable(Offset.Zero, Offset.VectorConverter) }

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
            cornerLeft + miniDrag.value.x,
            cornerTop + miniDrag.value.y,
            cornerLeft + miniWidth + miniDrag.value.x,
            cornerTop + miniHeight + miniDrag.value.y,
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
         * PiP pins the mini axis rather than animating it. The system is
         * already animating the window itself, and running our transform at the
         * same time would be two animations fighting over the same pixels.
         * (The drag axis is pinned inside the deferred readers below, for the
         * same reason.)
         */
        val m = if (pip) 0f else motion.miniProgress.value

        /*
         * The box IS the corner window whenever no rect is claimed — whatever
         * the mini axis says. `base = docked ?: corner` already draws it there,
         * but everything presentational used to key off `m` alone, so a mini
         * axis left at 0 while undocked (a cancelled transition, a skipped
         * driveMini) produced a corner-sized box wearing the DOCKED rules:
         * square corners, the full chrome squeezed into it, the swipe-up
         * fullscreen gesture armed, the corner drag not. Observed live as an
         * undismissable floating box. The rect is the one source of truth for
         * where the box is, so the presentation and the gestures follow the
         * same fact rather than trusting a second flag to agree with it.
         */
        val cornered = mini || (hostRect == null && !pip)
        val cornerness = if (hostRect == null && !pip) 1f else m

        /*
         * Two axes, applied in order: first travel from the docked rect toward
         * the corner, then from wherever that is toward fullscreen. They are
         * mutually exclusive in practice — going mini drives fullscreen to 0
         * first — but composing them means a mini request mid-expand still
         * produces a continuous path instead of a jump.
         */
        val start = lerpRect(base, corner, m)

        /*
         * The drag TRANSLATES; it does not morph.
         *
         * This used to interpolate the whole rect from docked to fullscreen
         * while rotating 90 degrees, so every intermediate frame was a video at
         * some awkward angle, mid-reshape. That is not a tuning problem — there
         * is no good-looking way to render halfway between two geometries a
         * quarter turn apart, which is exactly why YouTube never shows you one.
         *
         * So the state change is DISCRETE and happens on release, carried by
         * the system's own rotation animation. The drag's only job is feedback:
         * the video keeps its size and orientation and follows the finger, with
         * a slight scale so it feels picked up rather than merely slid.
         *
         * `fullscreen` here is the settled state, not a fraction of one.
         */
        /*
         * PiP counts as fullscreen, and forgetting that was a regression.
         *
         * The old geometry forced `p = 1f` in PiP, which filled the window. The
         * rewrite replaced that with the committed flag and dropped the PiP
         * case — so entering PiP from the DOCKED state drew the player at the
         * host's dock rect inside a thumbnail-sized window: a shrunken web page
         * with a sliver of video where the rect happened to fall.
         *
         * In PiP the system has already sized the window to the video's aspect,
         * so filling it is the only correct answer whatever the player was doing
         * a moment ago.
         */
        /*
         * GEOMETRY follows the window, not the intent.
         *
         * `committed` flips the instant the user lets go, which resized the
         * player to fullscreen while the window was still portrait — the odd
         * letterboxed frame that had to be hidden. Keyed to the MEASURED window
         * instead, the player is only ever the shape its window actually is, so
         * there is no wrong frame to hide and the video can stay visible while
         * the system rotates it. The measurement updates exactly when the config
         * change lands, which is the moment the new shape becomes correct.
         *
         * Entering, it stays docked-size until the config lands and is then
         * already fullscreen; leaving, the reverse. The resize coincides with
         * the rotation completing instead of preceding it.
         */
        val windowIsLandscape = screenW > screenH
        val fullscreen = pip || windowIsLandscape

        val restWidth = if (fullscreen) screenW else start.width()
        val restHeight = if (fullscreen) screenH else start.height()
        val restCentreX =
            if (fullscreen) geo.screenX + screenW / 2f else start.centerX()
        val restCentreY =
            if (fullscreen) geo.screenY + screenH / 2f else start.centerY()

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
         * COMMIT_VELOCITY in PipePlayerMotion together. They are one setting —
         * and ONE value here: the rubber-band shift and the velocity divisor
         * both read this val, after briefly being two copies of the same
         * expression that could drift apart.
         */
        val travel = min(screenW, screenH) * 0.45f

        /*
         * Deferred readers, NOT composition values.
         *
         * `progress` moves every frame of a drag and every frame of the release
         * spring. Read here in composition, each of those frames re-executed
         * this whole function — rect maths, modifier chains, gesture nodes,
         * chrome plumbing. These helpers read it instead from whichever scope
         * calls them: the offset lambda (placement), the graphicsLayer block
         * (pick-up scale) and the backdrop's draw pass. A drag frame then costs
         * a placement + a layer update + a redraw, and recomposes nothing.
         *
         * The drag offset is 0 at rest, growing as the finger pulls away from
         * the settled state; expanding pulls up (negative y), collapsing pulls
         * down. Pinned to 0 in PiP — the system is animating the window itself,
         * and our transform fighting it would be two animations on one pixel.
         *
         * The rubber band: movement tapers as it grows, and the shift
         * approaches an asymptote around 40% of travel however far the finger
         * goes. The resistance itself is the signal that you have gone far
         * enough to commit.
         */
        fun currentDragOffset(): Float =
            if (pip) 0f else motion.progress.value - (if (fullscreen) 1f else 0f)

        fun currentResisted(): Float {
            val magnitude = abs(currentDragOffset()).coerceIn(0f, 1f)
            return magnitude / (1f + magnitude * 1.5f)
        }

        fun currentShift(): Float = -sign(currentDragOffset()) * currentResisted() * travel

        /*
         * The one structural question the drag answers — "is the player at its
         * docked rest?" — as derived state, so composition is invalidated only
         * when the answer CHANGES, not on every frame the finger moves.
         */
        val atDockedRest by remember { derivedStateOf { motion.progress.value <= 0.01f } }

        /*
         * Bars follow the COMMITTED state, not a threshold on progress.
         *
         * This was `p > 0.5f`, which is the drag itself — so halfway through a
         * gesture the navigation bar vanished and the status bar changed colour,
         * announcing a commit that had not happened and could still be
         * cancelled. Every visible consequence of fullscreen has to hang off the
         * same latch, or the drag keeps leaking decisions.
         */
        /*
         * The first emission of each effect below is SKIPPED unless it has
         * something to change. On attach, `immersive` is false and "settled
         * fullscreen" is false — but calling the host with those defaults was
         * not a no-op: setImmersive(false) un-hid bars an edge-to-edge host had
         * deliberately hidden and re-fitted its decor, and the settled callback
         * armed the sensor suppression before anything had happened. Window
         * state belongs to the host until the player actually changes it.
         */
        val immersive = motion.committed
        var immersiveSeen by remember { mutableStateOf<Boolean?>(null) }
        LaunchedEffect(immersive) {
            val first = immersiveSeen == null
            immersiveSeen = immersive
            if (!first || immersive) onImmersiveChanged(immersive)
        }

        /*
         * Orientation waits for the COMMITTED state, decided on release — never
         * a threshold on the moving drag. Bars can toggle the moment the latch
         * flips because hiding them costs nothing; the orientation change rides
         * the same latch because it reflows the host page, which must never
         * happen under a finger.
         */
        val settledFullscreen = motion.committed && !mini && !pip
        var settledSeen by remember { mutableStateOf<Boolean?>(null) }
        LaunchedEffect(settledFullscreen) {
            val first = settledSeen == null
            settledSeen = settledFullscreen
            if (!first || settledFullscreen) onFullscreenSettled(settledFullscreen)
        }

        val scope = rememberCoroutineScope()

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
        /*
         * `!transitioning` is what stops an impatient finger from wrecking the
         * switch. The shutter deliberately does not intercept touches, so
         * without this a touch during the black-out drove `drag`, whose snapTo
         * cancelled the in-flight commit — dropping the curtain onto a
         * half-rotated window, with `committed` already latched. While the
         * choreography runs there is simply nothing to drag.
         */
        val collapseDraggable = !cornered && !pip && !motion.transitioning &&
            (screenDragging || !atDockedRest) && !boxDragging

        /*
         * An opaque backdrop between the host's page and the video: permanent
         * in fullscreen, progressive during a drag-up. Dragging a fullscreen
         * video down reveals whatever is behind it — a web page still laid out
         * for portrait, or actively reflowing — so YouTube puts black there,
         * and so do we. It has nothing to do with covering the switch; that is
         * the shutter's job, drawn over everything at the end of this
         * composable.
         *
         * A permanent node whose alpha is read in DRAW scope: the fade tracks
         * the finger frame by frame, and reading progress here in composition
         * would rebuild the whole surface per frame for one rectangle.
         */
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    /*
                     * Also carries the transition cover now.
                     *
                     * The shutter used to be a second layer drawn over
                     * everything, video included, so the rotation happened
                     * entirely out of sight — correct, but it meant a few
                     * hundred milliseconds of blank screen with the video
                     * reappearing only after it finished. Folded in here,
                     * BEHIND the video: the page and chrome stay hidden while
                     * the video itself remains visible and rotates with the
                     * system.
                     */
                    val alpha = maxOf(
                        if (fullscreen) 1f else (currentDragOffset() * 0.5f).coerceIn(0f, 0.5f),
                        motion.curtain.value,
                    )
                    if (alpha > 0.01f) drawRect(Color.Black.copy(alpha = alpha))
                },
        )

        Box(
            Modifier
                .fillMaxSize()
                .then(
                    if (!collapseDraggable) {
                        Modifier
                    } else {
                        Modifier.draggable(
                            state = dragState,
                            orientation = Orientation.Vertical,
                            onDragStarted = {
                                screenDragging = true
                            },
                            onDragStopped = { velocity ->
                                screenDragging = false
                                // Surface scope: see the note on the box drag.
                                scope.launch { motion.release(-velocity / travel) }
                            },
                        )
                    },
                ),
        ) {
        Box(
            Modifier
                /*
                 * The drag's translation lives INSIDE this lambda, so a moving
                 * finger invalidates placement only. The rest centre is a
                 * composition value because it changes only with state; the
                 * shift changes per frame and is read where per-frame is cheap.
                 */
                .offset {
                    IntOffset(
                        (restCentreX - restWidth / 2f).roundToInt(),
                        (restCentreY + currentShift() - restHeight / 2f).roundToInt(),
                    )
                }
                /*
                 * requiredSize, not size: fullscreen sizes the box to the whole
                 * window, and the square overlay's own constraints must not
                 * clamp it. requiredSize ignores incoming constraints, which is
                 * what a child larger than its parent needs.
                 *
                 * Always the REST size. The pick-up shrink is a graphicsLayer
                 * scale below, not a size change — resizing per drag frame
                 * forced a full measure/layout pass per frame for a 6% effect.
                 */
                .requiredSize(
                    width = with(density) { restWidth.toDp() },
                    height = with(density) { restHeight.toDp() },
                )
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
                 * BEFORE the graphicsLayer below, deliberately. Pointer
                 * coordinates are transformed by every layer between the
                 * gesture and the root — a rotation layer here once turned a
                 * vertical swipe horizontal and killed the exit gesture
                 * entirely. The pick-up scale is far milder, but the rule
                 * stands: gestures read the space above any transform.
                 *
                 * `enabled` replaces the early returns: mini is a target rather
                 * than a stage for gestures, in PiP the system owns input, and
                 * during the commit choreography there is nothing to drag —
                 * touching the shutter must not cancel the switch it covers.
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
                    enabled = !cornered && !pip && !motion.transitioning &&
                        (boxDragging || atDockedRest),
                    onDragStarted = {
                        boxDragging = true
                    },
                    onDragStopped = { velocity ->
                        boxDragging = false
                        /*
                         * Launched on the SURFACE's scope, not this one.
                         *
                         * Committing detaches this very modifier, which cancels
                         * the scope it runs in — so calling release() directly
                         * killed it partway and left the curtain up. The surface
                         * outlives the gesture; the gesture does not outlive its
                         * own consequences.
                         */
                        scope.launch { motion.release(-velocity / travel) }
                    },
                )
                /*
                 * A touch smaller as it is dragged, so the gesture feels
                 * physical. Tiny on purpose: this is feedback, not a
                 * transformation. A layer property so each frame updates the
                 * render node and nothing else; the default centre pivot keeps
                 * the box centred exactly as the old size-based shrink did.
                 */
                .graphicsLayer {
                    val pickUp = 1f - 0.06f * currentResisted()
                    scaleX = pickUp
                    scaleY = pickUp
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
                    elevation = if (pip) 0.dp else 14.dp * cornerness,
                    shape = RoundedCornerShape(if (pip) 0.dp else 12.dp * cornerness),
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
                .pointerInput(cornered, miniConfig, screenW, screenH) {
                    if (!cornered || !miniConfig.draggable) return@pointerInput
                    detectDragGestures(
                        onDragEnd = {
                            /*
                             * Recomputed from live state, NOT from the captured
                             * `corner` rect.
                             *
                             * pointerInput keeps the lambda it was created with,
                             * and it is not keyed on the corner or the drag
                             * offset — so `corner` here was frozen at the value
                             * it had when the gesture detector was installed.
                             * Every release therefore measured the ORIGINAL
                             * position and snapped back to the same corner, no
                             * matter where the window had been dragged. It
                             * looked like snapping was not implemented at all.
                             *
                             * `miniCorner` and `miniDrag` are state delegates,
                             * so reading them here reads them now. The paddings
                             * and sizes are captured, which is safe only because
                             * everything they derive from is a key: the screen,
                             * and `miniConfig` itself — the host can reconfigure
                             * mini geometry at any moment, so the config being a
                             * key is what keeps these captures current.
                             */
                            val left = if (miniCorner.isLeft) {
                                padLeft
                            } else {
                                screenW - miniWidth - padRight
                            }
                            val top = if (miniCorner.isTop) {
                                padTop
                            } else {
                                screenH - miniHeight - padBottom
                            }
                            val settled = PipePlayerCorner.nearest(
                                centreX = left + miniDrag.value.x + miniWidth / 2f,
                                centreY = top + miniDrag.value.y + miniHeight / 2f,
                                width = screenW,
                                height = screenH,
                            )
                            val newLeft = if (settled.isLeft) {
                                padLeft
                            } else {
                                screenW - miniWidth - padRight
                            }
                            val newTop = if (settled.isTop) {
                                padTop
                            } else {
                                screenH - miniHeight - padBottom
                            }
                            /*
                             * Compensate, then animate home.
                             *
                             * Changing the corner moves the anchor instantly, so
                             * the offset has to absorb that jump in the same
                             * frame to keep the window exactly where the finger
                             * left it. Animating to zero from there is what makes
                             * it travel to the new corner rather than appear in
                             * it.
                             */
                            val carried = Offset(
                                left - newLeft + miniDrag.value.x,
                                top - newTop + miniDrag.value.y,
                            )
                            miniCorner = settled
                            scope.launch {
                                miniDrag.snapTo(carried)
                                miniDrag.animateTo(
                                    targetValue = Offset.Zero,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                        stiffness = Spring.StiffnessMedium,
                                    ),
                                )
                            }
                        },
                        onDragCancel = {
                            scope.launch { miniDrag.animateTo(Offset.Zero) }
                        },
                    ) { change, dragAmount ->
                        scope.launch { miniDrag.snapTo(miniDrag.value + dragAmount) }
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
            // Rest dimensions: the pick-up scale is uniform, so it cancels out
            // of the ratio anyway.
            val boxAspect = if (restHeight > 0f) restWidth / restHeight else 1f

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
            LaunchedEffect(atDockedRest) { if (atDockedRest) zoom = 1f }

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

            /*
             * The loading presentation's gate, read once per recomposition.
             *
             * `controlsVisible` stays whatever it is — the chrome's visibility
             * is simply ANDed with readiness below, so a video that becomes
             * ready arrives with the chrome up (the initial true, or the
             * arrival effect) exactly as before.
             */
            val ready = mediaReady.value

            /*
             * A fresh load starts its chrome timer over.
             *
             * Readiness flipping true is an arrival: show the controls and let
             * the ordinary auto-hide take them down. Without this, a video
             * whose chrome had auto-hidden before a reload (a seek is a
             * reload) came back ready with no controls at all.
             */
            LaunchedEffect(ready) { if (ready) controlsVisible = true }

            /*
             * Arrive with the chrome UP, then let it auto-hide.
             *
             * This briefly did the opposite, to stop controls flashing during a
             * transition — but that was the wrong lever, and it took the arrival
             * with it: reaching fullscreen showed a bare video with no controls
             * at all, when the whole point of arriving is to see where you have
             * landed. Suppressing the *flash* is `transitioning`'s job, which
             * covers only the frames behind the curtain; this covers what the
             * user sees once it lifts.
             */
            LaunchedEffect(motion.committed) { controlsVisible = true }

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
                        enabled = !cornered && !pip,
                    )
                    /*
                     * Keyed on `live` as well as `mini`. pointerInput does not
                     * restart when only the lambdas change, so the running
                     * detector keeps the closures it was created with — a
                     * stream that goes live after the detector started would
                     * still be judged against the liveness captured back then.
                     */
                    .pointerInput(cornered, pip, chromeState.live, chromeState.playing) {
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
                                if (cornered || pip) return@detectTapGestures
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
                                if (cornered || pip) return@detectTapGestures
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
                val compact = cornerness > 0.5f || pip

                /*
                 * NO chrome at all while the box travels the mini axis.
                 *
                 * The box is mid-way between the docked rect and the corner
                 * there — roughly half size — and either chrome laid out in it
                 * reads as the player broken at half height rather than as a
                 * window in motion: the full set squeezed into a shrinking box
                 * on the way out, the compact set stretched across a growing
                 * one on the way back. The video travels bare, as a moved
                 * window should, and whichever chrome fits the destination
                 * fades in on arrival. Same idea as `transitioning` on the
                 * fullscreen axis. Derived, so composition is invalidated when
                 * the answer changes, not on every frame of the travel. PiP is
                 * exempt: the system is animating the window itself, and the
                 * axis is pinned there anyway.
                 */
                val miniTravelling by remember {
                    derivedStateOf {
                        motion.miniProgress.value > 0.01f && motion.miniProgress.value < 0.99f
                    }
                }
                val travellingChromeHidden = miniTravelling && !pip

                /*
                 * The spinner, over the video and under the chrome.
                 *
                 * Two reasons to spin: the media has never been ready (the
                 * loading presentation — no chrome is up, so this is the only
                 * thing on the black box), or a ready video is rebuffering (a
                 * seek, a stall), where it spins over the frame with the
                 * chrome still available on tap.
                 */
                if (!ready || buffering.value) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        PipePlayerSpinner(
                            // Sized to its window: full-size boxes get the
                            // 40dp YouTube-ish wheel, the corner and PiP a
                            // smaller one that does not dominate the tile.
                            diameter = if (compact) 24.dp else 40.dp,
                        )
                    }
                }

                AnimatedVisibility(
                    /*
                     * NOT gated on `ready`, unlike the full chrome. The loading
                     * presentation exists because a full control set centred
                     * over an empty box reads as broken — but the corner window
                     * is different: its close button is the only way OUT, and a
                     * load that never resolves (extraction stalled, network
                     * gone) left a spinner the user could not dismiss except by
                     * killing the app. Observed live. The compact set is three
                     * corner buttons and a hairline, which read fine over a
                     * spinner.
                     */
                    visible = compact && !travellingChromeHidden &&
                        (pip || controlsVisible),
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    PipePlayerCompactChrome(
                        state = chromeState,
                        position = position,
                        callbacks = chromeCallbacks,
                        showButtons = !pip,
                    )
                }

                AnimatedVisibility(
                    /*
                     * Nothing while a commit is in flight.
                     *
                     * The chrome is laid out for the state it is IN, so during
                     * the switch it briefly renders the destination's title,
                     * subtitle and buttons at the source's size — a flash of the
                     * portrait player's controls on the way back from landscape.
                     * Hidden instantly rather than faded, because a fade is
                     * itself visible at this length.
                     */
                    visible = ready && controlsVisible && !compact &&
                        !motion.transitioning && !travellingChromeHidden,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    PipePlayerChrome(
                        /*
                         * `committed`, not a threshold on progress — the last
                         * place that was still leaking a decision mid-drag.
                         *
                         * The chrome switches its whole layout on this flag:
                         * fullscreen shows the title and subtitle and sizes the
                         * buttons larger. Driven by `p`, that happened as the
                         * finger passed halfway, so dragging up in portrait made
                         * a title appear and every control jump size mid-gesture
                         * — over a player that had not gone anywhere yet and
                         * might still be dragged back.
                         */
                        state = chromeState.copy(fullscreen = motion.committed),
                        position = position,
                        buffered = buffered,
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
 * The indeterminate loading wheel — a three-quarter white arc, rotating.
 *
 * Hand-drawn rather than material's CircularProgressIndicator, for the same
 * reason as the rest of the chrome: material3 is on the classpath for
 * ModalBottomSheet ONLY, and the chrome's look does not come from a theme.
 * White with no accent, because it sits on the pre-video black where any
 * colour reads as branding rather than as progress.
 *
 * The rotation is a graphicsLayer property, so each frame updates the render
 * node and recomposes nothing — the same discipline as the drag transform.
 */
@Composable
private fun PipePlayerSpinner(diameter: Dp) {
    val transition = rememberInfiniteTransition(label = "playerSpinner")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 900, easing = LinearEasing)),
        label = "playerSpinnerAngle",
    )
    Canvas(
        Modifier
            .size(diameter)
            .graphicsLayer { rotationZ = angle },
    ) {
        drawArc(
            color = Color.White,
            startAngle = 0f,
            sweepAngle = 270f,
            useCenter = false,
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

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

