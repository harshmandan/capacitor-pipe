package ink.harsh.plugins.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.Util
import java.util.Formatter

/**
 * A consumer-supplied button: our chrome, their glyph and callback.
 *
 * Public because it crosses the plugin boundary; the rest of the chrome stays
 * internal so it cannot be restyled from outside.
 */
data class PipePlayerExtraButton(
    val id: String,
    val svgPath: String,
)

internal data class PipePlayerChromeState(
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    /** How far ahead media is buffered, for the seekbar's middle layer. */
    val bufferedMs: Long,
    val fullscreen: Boolean,
    /** Playback has reached the end, so the centre button offers a restart. */
    val ended: Boolean,
    /**
     * Whether shrinking the player is possible at all.
     *
     * Tracks PiP availability, which depends on the OS version, the device, the
     * host's manifest and whether the host shipped core-pip — none of which the
     * player can arrange for itself.
     */
    val canMinimise: Boolean,
    val live: Boolean,
    val title: String?,
    val subtitle: String?,
    val accent: Color,
    val showPreviousNext: Boolean,
    /** Current playback rate, e.g. "1x". The player displays it; the host owns it. */
    val speedLabel: String,
    /** Current quality, e.g. "720p". */
    val qualityLabel: String,
    /** Options the host offers. The player presents them; it does not invent them. */
    val speedOptions: List<SheetOption>,
    val qualityOptions: List<SheetOption>,
    /** At most one, occupying the third slot in the top row. */
    val extraButton: PipePlayerExtraButton?,
)

internal data class PipePlayerChromeCallbacks(
    val onPlayPause: () -> Unit,
    /** Restart from the beginning after the video has ended. */
    val onReplay: () -> Unit,
    val onPrevious: () -> Unit,
    val onNext: () -> Unit,
    val onMinimise: () -> Unit,
    val onFullscreen: () -> Unit,
    /** Leave the corner window or PiP and go back to the host's rect. */
    val onExpand: () -> Unit,
    val onQuality: () -> Unit,
    val onSpeed: () -> Unit,
    val onExtraButton: (String) -> Unit,
    val onSpeedSelected: (String) -> Unit,
    val onQualitySelected: (String) -> Unit,
    val onSeek: (Long) -> Unit,
)

/**
 * The player's controls.
 *
 * **Opinionated by design.** The only thing a consumer may restyle is the
 * accent colour; layout, sizing, spacing, timing and behaviour are fixed. That
 * is the point of shipping a player rather than a toolkit — the interaction is
 * the product, and a half-restyled version of it is worse than either.
 *
 * Glyphs are the stock Material set, so the controls read as Android rather
 * than as hand-drawn approximations. The one exception is a consumer's extra
 * button, which supplies SVG path data and gets our chrome around it.
 */
@Composable
internal fun PipePlayerChrome(
    state: PipePlayerChromeState,
    callbacks: PipePlayerChromeCallbacks,
    modifier: Modifier = Modifier,
) {
    val metrics = ChromeMetrics.of(state.fullscreen)

    Box(
        modifier
            .fillMaxSize()
            /*
             * Keep the chrome out of the display cutout.
             *
             * In landscape the title sat flush against the screen edge and lost
             * its first letter. This is the official inset for that, and it is
             * zero on devices without a cutout, so it costs nothing elsewhere.
             *
             * Note what it does NOT cover: rounded display corners have no inset
             * to ask for below API 31, so `edge` still carries a margin of its
             * own. Between the two, the title clears both.
             */
            .windowInsetsPadding(WindowInsets.displayCutout)
            .background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.45f),
                    0.32f to Color.Transparent,
                    0.68f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.55f),
                ),
            ),
    ) {
        TopRow(state, callbacks, metrics, Modifier.align(Alignment.TopCenter))
        CentreRow(state, callbacks, metrics, Modifier.align(Alignment.Center))
        BottomBar(state, callbacks, metrics, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun TopRow(
    state: PipePlayerChromeState,
    callbacks: PipePlayerChromeCallbacks,
    metrics: ChromeMetrics,
    modifier: Modifier,
) {
    /*
     * A Box with three independently-aligned children, NOT a Row.
     *
     * In a Row the title needed a weight to truncate, and that weight competed
     * with the spacer meant to push the buttons right — so a long title dragged
     * the buttons into the middle of the screen. Independent alignment means
     * the buttons are pinned to the end no matter what the title does, and the
     * title appearing or disappearing cannot shift anything else.
     */
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = metrics.edge, vertical = metrics.edge),
    ) {
        /*
         * Minimise: docked only, and only where shrinking leads somewhere.
         * Positioned absolutely so its absence never moves the title.
         *
         * Hidden when PiP is unavailable. A control that visibly does nothing
         * is worse than an absent one — and the host cannot reason about it
         * either, since PiP needs a manifest entry and a dependency that a
         * plugin cannot supply on the host's behalf.
         */
        /*
         * Shown or not shown — no slide.
         *
         * The animation existed to soften a change that happened in view. It no
         * longer does: the fullscreen switch now happens behind an opaque
         * curtain, so anything animating here is either invisible or, worse,
         * still mid-slide when the curtain lifts. Cheaper and steadier to have
         * the chrome simply be correct for the state it is in.
         */
        if (!state.fullscreen && state.canMinimise) {
            Box(Modifier.align(Alignment.TopStart)) {
                ChromeButton(Icons.Filled.KeyboardArrowDown, callbacks.onMinimise, metrics)
            }
        }

        // Fullscreen only, and without a transition — see the note above.
        if (state.fullscreen && (state.title != null || state.subtitle != null)) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    // Reserve room for the button row so a long title never runs
                    // underneath it.
                    .padding(end = metrics.control * 4),
            ) {
            Column {
                state.title?.let { title ->
                    BasicText(
                        text = title,
                        style = TextStyle(
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        maxLines = 1,
                    )
                }
                state.subtitle?.let { subtitle ->
                    BasicText(
                        text = subtitle,
                        style = TextStyle(
                            color = Color.White.copy(alpha = 0.75f),
                            fontSize = 12.sp,
                        ),
                        maxLines = 1,
                    )
                }
            }
            }
        }

        // Fixed order, pinned to the corner: speed, quality, then the consumer's.
        Row(
            Modifier.align(Alignment.TopEnd),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ValueButton(Icons.Filled.Speed, state.speedLabel, callbacks.onSpeed, metrics)
            Spacer(Modifier.width(6.dp))
            ValueButton(Icons.Filled.HighQuality, state.qualityLabel, callbacks.onQuality, metrics)
            state.extraButton?.let { button ->
                Spacer(Modifier.width(6.dp))
                ChromeButton(
                    onClick = { callbacks.onExtraButton(button.id) },
                    metrics = metrics,
                    svgPath = button.svgPath,
                )
            }
        }
    }
}

@Composable
private fun CentreRow(
    state: PipePlayerChromeState,
    callbacks: PipePlayerChromeCallbacks,
    metrics: ChromeMetrics,
    modifier: Modifier,
) {
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.showPreviousNext) {
            ChromeButton(
                Icons.Filled.SkipPrevious,
                callbacks.onPrevious,
                metrics,
                size = metrics.skipButton,
                glyph = metrics.skipGlyph,
            )
        }
        /*
         * Three states, not two. At the end of a video a play glyph is a lie:
         * there is nothing left to resume, and tapping it restarts from the
         * beginning whether or not the icon admitted that. Replay says what the
         * tap will actually do.
         */
        ChromeButton(
            when {
                state.ended -> Icons.Filled.Replay
                state.playing -> Icons.Filled.Pause
                else -> Icons.Filled.PlayArrow
            },
            if (state.ended) callbacks.onReplay else callbacks.onPlayPause,
            metrics,
            size = metrics.playButton,
            glyph = metrics.playGlyph,
        )
        if (state.showPreviousNext) {
            ChromeButton(
                Icons.Filled.SkipNext,
                callbacks.onNext,
                metrics,
                size = metrics.skipButton,
                glyph = metrics.skipGlyph,
            )
        }
    }
}

@Composable
private fun BottomBar(
    state: PipePlayerChromeState,
    callbacks: PipePlayerChromeCallbacks,
    metrics: ChromeMetrics,
    modifier: Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            /*
             * The seekbar is inset, not flush to the bottom edge. Flush reads as
             * a page-loading indicator rather than a scrubber, and on a
             * gesture-navigation device it sits underneath the home affordance.
             */
            .padding(start = metrics.edge, end = metrics.edge, bottom = metrics.bottomEdge),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // The timestamp needs the same backing as the buttons — plain white
            // text over bright video is unreadable, which is the whole reason
            // the controls have scrims at all.
            Row(
                Modifier
                    .height(metrics.control)
                    .clip(RoundedCornerShape(50))
                    .background(SCRIM)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    /*
                     * Live shows elapsed time only. A live stream has no
                     * duration — ExoPlayer reports TIME_UNSET, which arrives
                     * here as 0 — so the usual form rendered as "12:04 / 0:00",
                     * which looks like a stall rather than like a live edge.
                     */
                    text = if (state.live) {
                        formatTime(state.positionMs)
                    } else {
                        "${formatTime(state.positionMs)} / ${formatTime(state.durationMs)}"
                    },
                    style = TextStyle(color = Color.White, fontSize = 12.sp),
                )
                if (state.live) {
                    Spacer(Modifier.width(8.dp))
                    LiveBadge(state.accent)
                }
            }
            Spacer(Modifier.weight(1f))
            ChromeButton(
                if (state.fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                callbacks.onFullscreen,
                metrics,
            )
        }

        Spacer(Modifier.height(8.dp))
        Scrubber(state, callbacks.onSeek)
    }
}

/**
 * The progress bar, and the only place a seek can start.
 *
 * The visible bar is 4dp but the touch target is 24dp: a 4dp strip is
 * effectively untappable, and a scrubber you cannot grab is worse than no
 * scrubber. Dragging previews the position and commits on release, so a scrub
 * across a long video does not fire a seek per frame.
 */
@Composable
private fun Scrubber(state: PipePlayerChromeState, onSeek: (Long) -> Unit) {
    val played = when {
        state.live -> 1f
        state.durationMs <= 0L -> 0f
        else -> (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
    }
    val buffered = when {
        state.live -> 1f
        state.durationMs <= 0L -> 0f
        else -> (state.bufferedMs.toFloat() / state.durationMs).coerceIn(played, 1f)
    }

    // While dragging, the bar follows the finger rather than playback.
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val shown = dragFraction ?: played

    // Live has no seekable timeline: the bar is a state indicator, not a control.
    val seekable = !state.live && state.durationMs > 0L

    Box(
        Modifier
            .fillMaxWidth()
            .height(24.dp)
            .then(
                if (!seekable) {
                    Modifier
                } else {
                    Modifier
                        .pointerInput(state.durationMs) {
                            detectTapGestures { offset ->
                                val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                                onSeek((fraction * state.durationMs).toLong())
                            }
                        }
                        .pointerInput(state.durationMs) {
                            detectHorizontalDragGestures(
                                onDragStart = { offset ->
                                    dragFraction = (offset.x / size.width).coerceIn(0f, 1f)
                                },
                                onDragEnd = {
                                    dragFraction?.let { fraction ->
                                        onSeek((fraction * state.durationMs).toLong())
                                    }
                                    dragFraction = null
                                },
                                onDragCancel = { dragFraction = null },
                            ) { change, amount ->
                                val width = size.width.toFloat()
                                dragFraction = ((dragFraction ?: played) + amount / width)
                                    .coerceIn(0f, 1f)
                                change.consume()
                            }
                        }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(4.dp),
        ) {
            val radius = CornerRadius(size.height / 2f)
            // Three layers: track, buffered, played. Buffered sits between the
            // two so a viewer can tell "not downloaded yet" from "not watched
            // yet" — without it a stalled stream looks like a paused one.
            drawRoundRect(color = Color.White.copy(alpha = 0.25f), cornerRadius = radius)
            drawRoundRect(
                color = Color.White.copy(alpha = 0.45f),
                size = size.copy(width = size.width * buffered),
                cornerRadius = radius,
            )
            drawRoundRect(
                color = state.accent,
                size = size.copy(width = size.width * shown),
                cornerRadius = radius,
            )
            /*
             * The thumb is part of the chrome, so it lives and dies with it.
             *
             * This whole composable already sits inside the controls'
             * AnimatedVisibility, so simply drawing it unconditionally gives
             * exactly the requested behaviour: visible whenever the overlay is,
             * gone when the overlay fades. It grows while dragging so the grab
             * is acknowledged — a thumb that stays the same size under the
             * finger gives no feedback that the drag was picked up at all.
             *
             * Live is the exception: with no seekable timeline there is no
             * position to point at, and a thumb pinned to the right-hand end
             * would invite a drag that cannot do anything.
             */
            if (seekable) {
                val thumb = if (dragFraction != null) 9.dp.toPx() else 6.dp.toPx()
                drawCircle(
                    color = state.accent,
                    radius = thumb,
                    center = Offset(size.width * shown, size.height / 2f),
                )
            }
        }
    }
}

/** "2x speed" pill, shown while a press-and-hold boost is active. */
@Composable
internal fun SpeedBoostPill() {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.7f))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = "2x speed",
            style = TextStyle(
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
        Spacer(Modifier.width(6.dp))
        Glyph(Icons.Filled.FastForward, 16.dp)
    }
}

/**
 * The accumulated double-tap seek, e.g. "30 seconds".
 *
 * Accumulating matters: three taps must read as one 30s jump, not three
 * separate 10s ones, or chaining feels like taps were dropped.
 */
@Composable
internal fun SeekBurstIndicator(seconds: Int, forward: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Glyph(if (forward) Icons.Filled.FastForward else Icons.Filled.FastRewind, 30.dp)
        Spacer(Modifier.height(4.dp))
        BasicText(
            text = "$seconds seconds",
            style = TextStyle(color = Color.White, fontSize = 13.sp),
        )
    }
}

/** Dot plus label, beside the timestamp — where a viewer looks for elapsed time. */
@Composable
private fun LiveBadge(accent: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(7.dp)) { drawCircle(accent) }
        Spacer(Modifier.width(5.dp))
        BasicText(
            text = "LIVE",
            style = TextStyle(
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
    }
}

/**
 * Every control in the top and bottom rows is this tall.
 *
 * Circles and value pills previously differed by ~11dp, which left the row
 * visibly ragged. One constant is the only way that stays true as buttons are
 * added.
 */
/**
 * Control sizing, which scales with the window rather than being fixed.
 *
 * A docked player is a fraction of the screen; buttons sized for fullscreen
 * swamp it, and buttons sized for docked look lost in fullscreen. One constant
 * cannot serve both.
 */
internal data class ChromeMetrics(
    val control: Dp,
    val glyph: Dp,
    val playButton: Dp,
    val playGlyph: Dp,
    val skipButton: Dp,
    val skipGlyph: Dp,
    val edge: Dp,
    val bottomEdge: Dp,
) {
    companion object {
        fun of(fullscreen: Boolean): ChromeMetrics = if (fullscreen) {
            ChromeMetrics(
                control = 40.dp,
                glyph = 23.dp,
                playButton = 76.dp,
                playGlyph = 48.dp,
                skipButton = 56.dp,
                skipGlyph = 30.dp,
                // 28, not 20: rounded display corners expose no inset to ask
                // for, and 20 left the title's first letter inside the curve.
                edge = 28.dp,
                // Landscape has vertical room to spare and a gesture bar to
                // clear, so only it gets the doubled bottom inset. Docked
                // portrait is short enough that the same doubling crowds the
                // video.
                bottomEdge = 40.dp,
            )
        } else {
            ChromeMetrics(
                control = 30.dp,
                glyph = 17.dp,
                playButton = 52.dp,
                playGlyph = 32.dp,
                skipButton = 38.dp,
                skipGlyph = 21.dp,
                edge = 12.dp,
                // Portrait is short: the doubled landscape inset would crowd
                // the video, so the bottom stays tight here.
                bottomEdge = 6.dp,
            )
        }
    }
}

/** Icon plus current value, for the two state-carrying top buttons. */
@Composable
private fun ValueButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    metrics: ChromeMetrics,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, label = "press")

    Row(
        Modifier
            .scale(scale)
            .height(metrics.control)
            .clip(RoundedCornerShape(50))
            .background(SCRIM)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(icon, metrics.glyph)
        Spacer(Modifier.width(5.dp))
        BasicText(
            text = label,
            style = TextStyle(
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
    }
}

/**
 * Every plain button in the player, built-in or consumer-supplied.
 *
 * One implementation on purpose: an extra button gets the same hit area, the
 * same translucent backing and the same press response as the built-ins, so a
 * consumer cannot produce something that looks bolted on. The backing is not
 * decoration — white glyphs over arbitrary video are illegible without it.
 */
@Composable
private fun ChromeButton(
    icon: ImageVector? = null,
    onClick: () -> Unit,
    metrics: ChromeMetrics,
    svgPath: String? = null,
    size: Dp = metrics.control,
    glyph: Dp = metrics.glyph,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.88f else 1f, label = "press-scale")
    val alpha by animateFloatAsState(if (pressed) 0.7f else 1f, label = "press-alpha")

    Box(
        Modifier
            .scale(scale)
            .alpha(alpha)
            .size(size)
            .clip(CircleShape)
            .background(SCRIM)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when {
            icon != null -> Glyph(icon, glyph)
            svgPath != null -> SvgGlyph(svgPath, glyph)
        }
    }
}

@Composable
internal fun TintedGlyph(icon: ImageVector, size: Dp, tint: Color) {
    val painter = rememberVectorPainter(icon)
    Canvas(Modifier.size(size)) {
        with(painter) { draw(this@Canvas.size, colorFilter = ColorFilter.tint(tint)) }
    }
}

@Composable
internal fun Glyph(icon: ImageVector, size: Dp) {
    val painter = rememberVectorPainter(icon)
    Canvas(Modifier.size(size)) {
        // Material vectors default to black. Over video that is invisible, so
        // every glyph is tinted white explicitly.
        with(painter) {
            draw(this@Canvas.size, colorFilter = ColorFilter.tint(Color.White))
        }
    }
}

/**
 * Renders 24x24 SVG path data, for consumer-supplied glyphs only.
 *
 * A path string rather than a drawable so an icon crosses the Capacitor
 * bridge as plain data and still renders in our style at any density — no
 * resource ids, no asset packaging, no bitmap scaling.
 */
@Composable
private fun SvgGlyph(pathData: String, size: Dp) {
    val path = remember(pathData) {
        runCatching { PathParser().parsePathString(pathData).toPath() }.getOrNull()
    } ?: return

    Canvas(Modifier.size(size)) {
        val factor = this.size.minDimension / 24f
        scale(factor, factor, pivot = Offset.Zero) {
            drawPath(path, Color.White)
        }
    }
}

/** Translucent backing that keeps white glyphs legible over arbitrary video. */
private val SCRIM = Color.Black.copy(alpha = 0.4f)

/**
 * Media3's formatter, not ours.
 *
 * It already renders h:mm:ss / m:ss with the same elision rules, and it is the
 * same function the rest of the Media3 ecosystem uses, so a timestamp here reads
 * identically to one from any other player in the app.
 *
 * The zero floor stays deliberately. `getStringForTime` renders TIME_UNSET and
 * negatives its own way, and this player reaches here with 0 for a live stream
 * (which has no duration) — "0:00" is the answer we want in that case.
 */
private fun formatTime(millis: Long): String =
    if (millis <= 0L) "0:00" else Util.getStringForTime(StringBuilder(), Formatter(), millis)

/**
 * The controls for a player too small to carry the full set.
 *
 * Shared deliberately by the corner mini player and by Picture-in-Picture. The
 * two are the same idea — a shrunken player that keeps playing while the user is
 * doing something else — and giving them different chrome would make the same
 * video look like two different features depending on how it got small.
 *
 * Three controls, and no more: play/pause, expand, and a progress line. Anything
 * else is unhittable at this size, and a control the user cannot land on is
 * worse than one that is not there.
 *
 * The scrubber here is **not** interactive. A 190dp-wide bar cannot be scrubbed
 * with any accuracy, and an accidental seek is far more annoying than having to
 * expand first. It reports; it does not accept.
 */
@Composable
internal fun PipePlayerCompactChrome(
    state: PipePlayerChromeState,
    callbacks: PipePlayerChromeCallbacks,
    modifier: Modifier = Modifier,
    /**
     * False in Picture-in-Picture, where the system owns input.
     *
     * A tap on a PiP window raises the system's own control strip; the app never
     * sees it. Buttons drawn here were therefore visible but dead — they sat
     * *underneath* the system overlay. Our play/pause goes into that strip as a
     * RemoteAction instead; see PipePlayerPip. The progress line stays, because
     * it is information rather than a control.
     */
    showButtons: Boolean = true,
) {
    Box(
        modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.55f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.55f),
                ),
            ),
    ) {
        if (showButtons) {
            CompactButton(
                icon = when {
                    state.ended -> Icons.Filled.Replay
                    state.playing -> Icons.Filled.Pause
                    else -> Icons.Filled.PlayArrow
                },
                onClick = if (state.ended) callbacks.onReplay else callbacks.onPlayPause,
                modifier = Modifier.align(Alignment.BottomStart),
            )

            CompactButton(
                icon = Icons.Filled.Fullscreen,
                onClick = callbacks.onExpand,
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }

        /*
         * Flush to the very bottom edge, with no inset.
         *
         * The opposite of the full-size scrubber, which is deliberately lifted
         * off the edge so it does not read as a page-loading bar. At this size
         * that is exactly what it SHOULD read as: a hairline status line that
         * costs no height, in a window with none to spare.
         */
        Canvas(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(2.5.dp),
        ) {
            val played = when {
                state.live -> 1f
                state.durationMs <= 0L -> 0f
                else -> (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
            }
            drawRect(color = Color.White.copy(alpha = 0.30f))
            drawRect(color = state.accent, size = size.copy(width = size.width * played))
        }
    }
}

/** Smaller and flatter than [ChromeButton] — the full one crowds a corner window. */
@Composable
private fun CompactButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .padding(6.dp)
            .size(32.dp)
            .clip(CircleShape)
            .background(SCRIM)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Glyph(icon, 18.dp)
    }
}
