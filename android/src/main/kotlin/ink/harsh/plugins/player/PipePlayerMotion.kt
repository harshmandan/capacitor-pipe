package ink.harsh.plugins.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
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

    val isFullscreen: Boolean get() = progress.targetValue > 0.5f

    /**
     * Drive the transform directly from the finger.
     *
     * @param delta fraction of the travel distance moved since the last
     * event, positive toward fullscreen.
     */
    suspend fun drag(delta: Float) {
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
    suspend fun release(velocity: Float) {
        val target = decideTarget(progress.value, velocity)
        android.util.Log.i(
            "PipeDrag",
            "release from=${progress.value} v=$velocity -> target=$target",
        )
        progress.animateTo(
            targetValue = target,
            animationSpec = SETTLE,
            initialVelocity = velocity,
        )
    }

    /** Jump without a gesture — a button, or a host command. */
    suspend fun animateTo(fullscreen: Boolean) {
        // Anything that moves progress WITHOUT a gesture shows up here, which is
        // how a fix that lands and is then overwritten becomes visible.
        android.util.Log.i("PipeDrag", "animateTo($fullscreen) from ${progress.value}")
        progress.animateTo(if (fullscreen) 1f else 0f, SETTLE)
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
