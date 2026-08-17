package ink.harsh.plugins.player

import android.app.Activity
import android.graphics.RectF
import android.util.Log
import android.view.TextureView
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.roundToInt

/**
 * The player's native surface, composited over the host WebView.
 *
 * <p>Added to the Activity's content root at runtime rather than declared in the
 * host's layout, so it survives web-side navigation for free: the WebView
 * repaints underneath and the player does not care. That is the whole reason a
 * native player is worth having over a web one, and it is what makes a
 * persistent mini-player tractable later.
 *
 * <p><b>Surface type is TextureView, deliberately.</b> A SurfaceView composites
 * in its own layer and its surface lags its view bounds by a frame under
 * continuous transform — visible tearing on exactly the drag-tracked fullscreen
 * animation this module exists for. A TextureView renders as an ordinary
 * texture and transforms cleanly. The price is an extra GPU copy and no
 * secure/DRM output; expensive to reverse later, so it is a conscious choice.
 *
 * <p>The overlay always fills the window and draws the video inside a rect. That
 * shape is what phase 2 needs: fullscreen becomes interpolation of this rect
 * toward the full screen on a single progress value, not a different layout.
 */
@UnstableApi
class PipePlayerOverlay(private val activity: Activity) {

    private companion object {
        const val TAG = "PipePlayerOverlay"
    }

    private var composeView: ComposeView? = null
    private var player: ExoPlayer? = null

    /** Docking rect in device pixels; null when the host has claimed none. */
    private val dockRect = mutableStateOf<RectF?>(null)

    private var attached = false

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
            PlayerSurface(
                rect = dockRect,
                bindSurface = { texture -> exo.setVideoTextureView(texture) },
            )
        }
        root.addView(
            view,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        composeView = view
        attached = true
        Log.i(TAG, "overlay attached to content root")
    }

    fun detach() {
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
     * <p>Hosts declare a rect rather than commanding the player into a position;
     * the player owns every transition between rects. No rect claimed is
     * precisely the signal to fall back to the mini-player later.
     */
    fun dock(rect: RectF) {
        dockRect.value = rect
        Log.i(TAG, "docked at $rect")
    }

    fun undock() {
        dockRect.value = null
    }

    fun load(url: String) {
        ensurePlayer().apply {
            setMediaItem(MediaItem.fromUri(url))
            prepare()
        }
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

    private fun ensurePlayer(): ExoPlayer =
        player ?: ExoPlayer.Builder(activity).build().also { player = it }
}

/**
 * Draws the video inside the claimed rect, or nothing at all.
 *
 * <p>Top-level and stateless on purpose: it takes the rect to render and a
 * callback to bind the surface, and touches no player state itself.
 */
@Composable
private fun PlayerSurface(
    rect: State<RectF?>,
    bindSurface: (TextureView) -> Unit,
) {
    // No rect claimed means nothing to draw. The overlay stays attached and
    // fully transparent so the WebView shows through untouched — that composite
    // is what phase 1 exists to prove.
    val bounds = rect.value ?: return
    val density = LocalDensity.current

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { context -> TextureView(context).also(bindSurface) },
            modifier = Modifier
                // offset{} and a pixel size resolve during layout without
                // recomposing. Phase 2 replaces this with a graphicsLayer driven
                // by a spring, once the value actually changes every frame.
                .offset { IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt()) }
                .size(
                    width = with(density) { bounds.width().toDp() },
                    height = with(density) { bounds.height().toDp() },
                )
                .background(Color.Black),
        )
    }
}
