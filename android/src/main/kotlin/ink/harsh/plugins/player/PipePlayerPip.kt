package ink.harsh.plugins.player

import android.app.Activity
import android.app.PendingIntent
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.util.Log
import android.util.Rational
import android.view.View
import androidx.core.app.PictureInPictureParamsCompat
import androidx.core.app.PictureInPictureProvider
import androidx.core.content.ContextCompat
import androidx.core.pip.PictureInPictureDelegate
import ink.harsh.plugins.pipe.R
import androidx.core.pip.VideoPlaybackPictureInPicture

/**
 * Picture-in-Picture, delegated to `androidx.core:core-pip`.
 *
 * Every line of this file used to be hand-written against the platform API, and
 * it did not work. The rewrite is not a tidy-up: the hand-rolled version entered
 * PiP and then showed the host's web page instead of the video, because the
 * mode-change callback it waited on never arrived on Android 17 — which changed
 * the Activity-recreation defaults out from under it.
 *
 * What the library replaces, concretely:
 *
 * - the `setAutoEnterEnabled` (API 31+) / `onUserLeaveHint` (below) fork, with
 *   one `setEnabled`. The pre-12 half was previously impossible for us anyway:
 *   Capacitor's `Plugin` exposes no `onUserLeaveHint`, so those devices needed
 *   the host to call us. `ComponentActivity` is an `OnUserLeaveHintProvider`,
 *   so the library reaches the hook we could not.
 * - `addOnPictureInPictureModeChangedListener` plus a manual
 *   `isInPictureInPictureMode` poll, with `onPictureInPictureEvent`.
 * - a hand-computed `sourceRectHint` from `getGlobalVisibleRect`, with
 *   `setPlayerView` and the library's own `ViewBoundsTracker` — the piece with
 *   no layout callback to hang off, and where ours broke.
 *
 * Isolated in its own class ON PURPOSE. `core-pip` is `compileOnly` like the
 * rest of the player's dependencies, so an app that never wants PiP ships none
 * of it. Keeping every reference behind this one type means
 * [PipePlayerOverlay] can be loaded and used without the library present; touch
 * `androidx.core.pip` from a field type there and the class fails to verify on
 * a device that skipped the dependency.
 */
internal class PipePlayerPip(
    private val activity: Activity,
    private val onPipChanged: (Boolean) -> Unit,
    private val onTogglePlay: () -> Unit,
) : PictureInPictureDelegate.OnPictureInPictureEventListener, AutoCloseable {

    private companion object {
        const val TAG = "PipePlayerPip"

        /**
         * The system rejects anything outside this range by throwing, and it
         * throws at the moment the user leaves the app — so an ultra-wide
         * trailer would crash on the way to the background.
         */
        const val MIN_ASPECT = 1f / 2.39f
        const val MAX_ASPECT = 2.39f

        /** Namespaced: a bare action name would collide with the host's own. */
        const val ACTION_TOGGLE = "ink.harsh.plugins.player.PIP_TOGGLE_PLAY"
    }

    /*
     * Play/pause is a RemoteAction, NOT a button we draw.
     *
     * In PiP the system owns input: any tap raises its own control strip, so
     * chrome painted into the window is decorative and unreachable — which is
     * exactly how it behaved, our controls visible underneath the system overlay
     * and dead to touch. setActions puts our control INTO that strip, where the
     * taps actually go.
     *
     * Only play/pause. The system already supplies expand and close, and
     * duplicating them would crowd a strip that holds very few actions.
     */
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_TOGGLE) onTogglePlay()
        }
    }

    private val executor = ContextCompat.getMainExecutor(activity)

    /*
     * One-arg constructor: alpha02 takes just the provider. alpha03 added an
     * Executor parameter, which is why the version below is pinned rather than
     * tracked — see the note in android/build.gradle.
     */
    private val impl = VideoPlaybackPictureInPicture(
        activity as PictureInPictureProvider,
    ).also { pip ->
        pip.addOnPictureInPictureEventListener(executor, this)
    }

    init {
        // NOT_EXPORTED: the only legitimate sender is the PendingIntent we hand
        // to the system, so nothing else has business broadcasting this.
        ContextCompat.registerReceiver(
            activity,
            receiver,
            IntentFilter(ACTION_TOGGLE),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    /**
     * Refresh the strip so the glyph matches reality.
     *
     * Called on every play/pause change: a RemoteAction is a static snapshot, so
     * a stale one leaves a pause icon sitting on a paused video.
     */
    fun setPlaybackState(playing: Boolean, ended: Boolean) {
        val intent = Intent(ACTION_TOGGLE).setPackage(activity.packageName)
        val pending = PendingIntent.getBroadcast(
            activity,
            0,
            intent,
            // IMMUTABLE is required from API 31; UPDATE_CURRENT keeps a single
            // instance rather than leaking one per state change.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        /*
         * Three states, not two — the same rule the full chrome follows.
         *
         * At the end of a video a play glyph is a lie: there is nothing left to
         * resume, and tapping it did nothing at all, because ExoPlayer's play()
         * on an ended item is a no-op without a seek first. Replay says what the
         * tap will actually do, and the toggle below now does it.
         */
        val label = when {
            ended -> "Replay"
            playing -> "Pause"
            else -> "Play"
        }
        val icon = Icon.createWithResource(
            activity,
            when {
                ended -> R.drawable.pipe_ic_replay
                playing -> R.drawable.pipe_ic_pause
                else -> R.drawable.pipe_ic_play
            },
        )
        runCatching { impl.setActions(listOf(RemoteAction(icon, label, label, pending))) }
            .onFailure { Log.w(TAG, "could not set pip actions: ${it.message}") }
    }

    /**
     * The view whose bounds become the `sourceRectHint`.
     *
     * Give it the video surface, not the overlay: the overlay is a screen-sized
     * square and hinting at that would animate the PiP window out of the wrong
     * rectangle.
     */
    fun setPlayerView(view: View) {
        impl.setPlayerView(view)
    }

    /**
     * Fluent setters that apply as they are called.
     *
     * Note for whoever bumps the version: alpha03 introduced an explicit
     * `commit()`, and without it the setters only stage the change. Moving the
     * pin forward means adding that call, not just changing a number.
     */
    fun setEnabled(enabled: Boolean, aspect: Float) {
        impl.setEnabled(enabled)
        impl.setAspectRatio(aspectRatio(aspect))
    }

    fun setAspectRatio(aspect: Float) {
        impl.setAspectRatio(aspectRatio(aspect))
    }

    /**
     * Enter now, for a button or for a host that wants an explicit call.
     *
     * With `setEnabled(true)` the system does this by itself when the user
     * leaves, on every version the library supports, so this is the exception
     * rather than the route.
     */
    fun enter(aspect: Float): Boolean = runCatching {
        val params = PictureInPictureParamsCompat.Builder()
            .setAspectRatio(aspectRatio(aspect))
            .build()
        (activity as PictureInPictureProvider).enterPictureInPictureMode(params)
        true
    }.onFailure { Log.w(TAG, "enterPictureInPictureMode failed: ${it.message}") }
        .getOrDefault(false)

    override fun onPictureInPictureEvent(event: PictureInPictureDelegate.Event, config: Configuration?) {
        Log.i(TAG, "pip event $event")
        when (event) {
            PictureInPictureDelegate.Event.ENTERED -> onPipChanged(true)
            PictureInPictureDelegate.Event.EXITED -> onPipChanged(false)
            /*
             * ENTER_ANIMATION_START deliberately does NOT claim the window.
             *
             * It used to, so that the video filled the frame before the host's
             * page could show. But the window has not shrunk yet at that point,
             * so from the corner player it read as: expand to full size, then
             * shrink away with the app. Waiting for ENTERED keeps the player the
             * size it already was while the system animates the window down.
             *
             * The page-showing problem it was guarding against is handled
             * properly now, by reading PiP state on every layout pass.
             */
            PictureInPictureDelegate.Event.ENTER_ANIMATION_START -> Unit
            // STASHED / UNSTASHED are the user tucking the window against an
            // edge. Playback continues and our layout does not change, so there
            // is deliberately nothing to do.
            else -> Unit
        }
    }

    override fun close() {
        runCatching { activity.unregisterReceiver(receiver) }
        impl.removeOnPictureInPictureEventListener(this)
        impl.close()
    }

    private fun aspectRatio(aspect: Float): Rational {
        val clamped = aspect.takeIf { it > 0f }?.coerceIn(MIN_ASPECT, MAX_ASPECT) ?: (16f / 9f)
        // Rational of two ints; scaling by 1000 keeps a usable precision without
        // risking the overflow a larger multiplier invites.
        return Rational((clamped * 1000f).toInt(), 1000)
    }
}
