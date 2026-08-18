package ink.harsh.plugins.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.animation.core.tween
import kotlin.math.abs

/**
 * The single value the whole window transform runs on.
 *
 * `progress` is 0 when docked and 1 when fullscreen, and **every value
 * between is a renderable frame**. That is the property the feature exists for:
 * a drag can be tracked, reversed mid-flight and released at any point, because
 * there is no discrete state to be caught between.
 *
 * It is also why fullscreen is a view transform rather than an OS
 * orientation change. A configuration change is a discrete event — it cannot be
 * driven continuously by a finger, and it would rotate the host's WebView
 * underneath.
 */
class PipePlayerMotion {

    /** 0 = docked, 1 = fullscreen. Never leaves [0, 1]. */
    val progress = Animatable(0f)

    /**
     * The state the player is actually IN, changed only on release.
     *
     * Deliberately not derived from [progress]. The drag moves progress, so a
     * derived flag flipped the moment the finger crossed halfway — the player
     * committed to fullscreen mid-gesture, rotated the Activity under the
     * finger, and left no way to change your mind by dragging back. Latching it
     * here means a drag is always cancellable: nothing commits until you let go.
     */
    var committed by mutableStateOf(false)
        private set

    /**
     * A shutter drawn OVER everything, including the player. 0..1.
     *
     * It began as a backdrop behind the video, and that was the wrong layer:
     * the ugliest part of the switch is not the page behind, it is the PLAYER.
     * Frame-stepping a recording showed why — the player resizes to fullscreen
     * inside the still-portrait window and the chrome swaps to its landscape
     * layout, both a beat before the system rotates. A curtain behind the video
     * hid the page and left all of that on display.
     *
     * So this covers the lot. Everything between raising and lowering it — the
     * resize, the chrome relayout, the orientation change, the reflow — happens
     * out of sight, and the screen simply goes black and comes back settled.
     *
     * Always returns to 0: it is a shutter, not a backdrop. Hiding the page
     * behind a fullscreen video is a separate, permanent job — see the backdrop
     * in PipePlayerSurface.
     */
    val curtain = Animatable(0f)

    /**
     * Waits until the window has actually finished moving, if anyone can tell.
     *
     * A fixed delay is a guess about someone else's work: the orientation
     * change, the window resize and the host page's reflow each land whenever
     * they land, and the host re-reports its rect after that. Guessing short
     * meant the curtain lifted onto a player that was still being told where to
     * be, so it visibly hopped a few times before settling.
     *
     * Supplied by the overlay, which is the only thing that can see the
     * geometry. Null falls back to the fixed hold, so the motion object stays
     * usable on its own.
     */
    var awaitStable: (suspend () -> Unit)? = null

    /**
     * True from the moment a commit starts until the curtain is down again.
     *
     * The chrome hides on this. Without it the controls of the *destination*
     * state flashed up over the transition — a portrait player's title,
     * subtitle and buttons appearing for a frame or two on the way back from
     * landscape, which is exactly the kind of detail that reads as broken.
     */
    var transitioning by mutableStateOf(false)
        private set

    val isFullscreen: Boolean get() = committed

    /**
     * Drive the transform directly from the finger.
     *
     * @param delta fraction of the travel distance moved since the last
     * event, positive toward fullscreen.
     */
    suspend fun drag(delta: Float) {
        // The shutter stays down during a drag: dimming the video the user is
        // dragging would be feedback pointed at the wrong thing. The backdrop
        // behind the player does the rising instead.
        progress.snapTo((progress.value + delta).coerceIn(0f, 1f))
    }

    /**
     * Release, seeded with the finger's exit velocity.
     *
     * This is the highest-leverage detail in the whole module. A release that
     * restarts from zero velocity on a fixed-duration curve reads as wrong
     * immediately, and no amount of curve-tweaking rescues it — the hand-off has
     * to preserve the momentum the finger already had. Hence a spring seeded
     * with `initialVelocity` rather than an animator with a duration.
     *
     * @param velocity progress-units per second, positive toward fullscreen
     */
    /**
     * Decide, then snap the drag offset away.
     *
     * The gesture no longer renders intermediate states — the video translates
     * during the drag and the actual change of state is discrete, carried by the
     * system's rotation animation. So this settles the *offset* quickly back to
     * a resting value rather than easing a shape through a quarter turn.
     *
     * `initialVelocity` still matters: the snap picks up the momentum the finger
     * had, which is what keeps a flick feeling like a flick.
     */
    suspend fun release(velocity: Float) {
        commit(decideTarget(progress.value, velocity), velocity)
    }

    /** Jump without a gesture — a button, or a host command. */
    /** Jump without a gesture — a button, or a host command. */
    suspend fun animateTo(fullscreen: Boolean) {
        commit(if (fullscreen) 1f else 0f, 0f)
    }

    /**
     * Raise the curtain, switch, wait for the reflow, lower it.
     *
     * The order is the whole point. Everything that looks bad — the orientation
     * change, the window resize, the host page relaying out, the chrome
     * swapping between portrait and landscape layouts — happens between the
     * snapTo(1f) and the animateTo below, with the screen covered.
     */
    private suspend fun commit(target: Float, velocity: Float) {
        /*
         * A cancelled drag is not a transition.
         *
         * Dragging up and then back down again ends where it started, but this
         * still ran the full choreography — snapping the curtain to opaque
         * before settling. So cancelling made the screen flash black, which is
         * worse than the transition it was meant to cover. Nothing is changing
         * state here; the only work is putting the drag offset and the curtain
         * back where they were.
         */
        if ((target > 0.5f) == committed) {
            progress.animateTo(target, SNAP_BACK, velocity)
            curtain.animateTo(0f, tween(FADE_MS / 2))
            return
        }

        /*
         * try/finally, because a stuck curtain is unrecoverable.
         *
         * This function outlives the gesture that starts it, and the gesture's
         * coroutine scope does NOT: the drag modifier is detached the moment the
         * state commits, which cancels whatever it was running. That killed this
         * at the delay below, so on the way back to portrait the curtain never
         * lowered and the screen stayed black over a working player.
         *
         * The caller now launches this on a scope that survives, and the finally
         * guarantees the curtain comes down even if something else cancels it.
         * A cancelled animation is a cosmetic glitch; a cancelled cleanup is a
         * black screen.
         */
        try {
            transitioning = true
            curtain.snapTo(1f)
            committed = target > 0.5f

            progress.animateTo(
                targetValue = target,
                animationSpec = SNAP_BACK,
                initialVelocity = velocity,
            )

            /*
             * Held deliberately past the animation.
             *
             * requestedOrientation is asynchronous: the Activity has not resized
             * when this line runs, and the host's WebView reflow lands a frame
             * or two after that. Lifting the curtain on the animation's end
             * would uncover exactly the part worth hiding.
             */
            /*
             * Asymmetric on purpose: leaving is faster than entering.
             *
             * Entering fullscreen, the curtain lifts onto the video itself, so
             * holding it costs nothing — the user is waiting to arrive
             * somewhere. Leaving, it lifts onto the host's page, and the user
             * has already decided to go; every extra frame reads as the app
             * hesitating rather than as the transition being covered.
             *
             * The reflow is also cheaper in this direction, because the page is
             * returning to the portrait layout it was built for.
             */
            val entering = committed
            val settle = awaitStable
            if (settle != null) {
                settle()
            } else {
                delay(if (entering) REFLOW_HOLD_MS else REFLOW_HOLD_MS / 2)
            }
            curtain.animateTo(
                targetValue = 0f,
                animationSpec = tween(if (entering) FADE_MS else FADE_MS / 2),
            )
        } finally {
            transitioning = false
            // snapTo needs a scope; withContext(NonCancellable) keeps this
            // running even when the reason we are here is cancellation.
            withContext(NonCancellable) {
                curtain.snapTo(0f)
            }
        }
    }

    /**
     * 0 = wherever the player already is, 1 = parked in the corner.
     *
     * A second axis rather than a point on [progress], because mini is a
     * different rect and not a stage on the docked-to-fullscreen line. Sharing
     * one value would have meant the player passing *through* fullscreen on its
     * way to the corner.
     *
     * Interpolated rather than swapped: the mini player is the same player, so
     * it has to be seen travelling to the corner. Snapping it there reads as a
     * second player appearing and the first one vanishing, which is exactly the
     * illusion a persistent player exists to avoid.
     */
    val miniProgress = Animatable(0f)

    val isMini: Boolean get() = miniProgress.targetValue > 0.5f

    suspend fun animateMini(mini: Boolean) {
        miniProgress.animateTo(if (mini) 1f else 0f, DOCK)
    }

    companion object {

        /**
         * Above this speed the flick decides, regardless of how far it got.
         *
         * In progress-units per second, and it has to be read together with
         * `travel` in PipePlayerSurface, because release velocity is DIVIDED by
         * travel before it lands here. Lengthening travel therefore raises the
         * real-world speed this represents, and that is exactly how it broke:
         * travel went from ~486px to ~1333px to slow the drag down, which
         * silently made even a fast 1400px/s flick score 1.05 against a
         * threshold of 1.2. Velocity could never commit, so a flick fell back to
         * "did you drag past halfway" — and swipe-down-to-exit stopped working
         * while the slow tracking it was meant to fix looked fine.
         *
         * 1.2 pairs with `travel = min(screenW, screenH) * 0.45f`, where a
         * ~540px/s flick commits. If travel changes again, recompute this with
         * it; the two are one setting wearing two names.
         */
        private const val COMMIT_VELOCITY = 1.2f

        /**
         * How long the curtain stays up after the transform has landed.
         *
         * Covers the asynchronous part: the orientation change, the window
         * resize and the host page's reflow, none of which are finished when
         * the animation is. Measured by eye — too short and the reflow shows,
         * too long and the player feels sluggish to arrive.
         */
        private const val REFLOW_HOLD_MS = 260L
        private const val FADE_MS = 200

        /**
         * Spring, not a duration.
         *
         * These are placeholders pending measurement. The plan's method is to
         * screen-record YouTube at 60fps, step the frames, and read the two
         * constants off the displacement curve — overshoot ratio gives the
         * damping ratio, time to first peak gives the stiffness. Expand,
         * collapse and the mini-player dock are separate curves; do not assume
         * one fits all three.
         */
        private val SETTLE: AnimationSpec<Float> = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            /*
             * Softer than StiffnessMediumLow (400f), which settled fast enough
             * that the release read as a snap rather than as the finger's
             * momentum running out. This is the whole-screen transform, and a
             * big move that arrives instantly reads as a state change — which
             * is exactly the impression a continuously-tracked gesture exists
             * to avoid.
             */
            stiffness = 260f,
            visibilityThreshold = 0.001f,
        )

        /**
         * The corner dock is its own curve, per the note above.
         *
         * Slightly stiffer than [SETTLE]: the travel is short and a soft spring
         * over a short distance reads as sluggish rather than as gentle. Also a
         * placeholder pending the same frame-stepping measurement.
         */
        /**
         * Returning the drag offset to rest.
         *
         * Stiffer than [SETTLE] on purpose: this travels a short distance and is
         * competing with the system's rotation animation, so a soft spring reads
         * as lag rather than as weight.
         */
        private val SNAP_BACK: AnimationSpec<Float> = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium,
            visibilityThreshold = 0.001f,
        )

        private val DOCK: AnimationSpec<Float> = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium,
            visibilityThreshold = 0.001f,
        )

        /**
         * Where a released drag lands.
         *
         * Velocity wins when the flick is decisive; displacement only decides
         * a slow release. Deciding on displacement alone is the classic mistake
         * — it makes a fast flick that barely moved snap back, which feels like
         * the gesture was ignored.
         */
        fun decideTarget(progress: Float, velocity: Float): Float = when {
            velocity > COMMIT_VELOCITY -> 1f
            velocity < -COMMIT_VELOCITY -> 0f
            abs(velocity) < COMMIT_VELOCITY && progress > 0.5f -> 1f
            else -> 0f
        }
    }
}
