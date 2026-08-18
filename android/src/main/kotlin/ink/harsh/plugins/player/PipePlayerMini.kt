package ink.harsh.plugins.player

/**
 * Where the corner player sits, and how big it is.
 *
 * Host-supplied, because these are layout decisions the host owns rather than
 * styling that would fragment the player's look. The margins in particular
 * cannot be derived: the mini player lives inside the app's window, so what it
 * has to clear is the host's own bottom nav or drawer, which Android has no
 * inset for. Only the host knows its tab bar is 56dp tall.
 */
data class PipePlayerMiniConfig(
    /** Width in dp. Height follows from 16:9. */
    val width: Float = 190f,
    val corner: PipePlayerCorner = PipePlayerCorner.BOTTOM_RIGHT,
    /*
     * Insets per side, in dp — not one x/y pair.
     *
     * The sides are genuinely different: a host typically has a tall bottom nav
     * and a shorter top bar, so a single vertical margin either floats the
     * player too high in one corner or lets it collide in the other. Four
     * numbers also mean moving the player between corners does not need the
     * host to reconfigure anything.
     */
    val paddingLeft: Float = 14f,
    /**
     * Larger than the sides on purpose.
     *
     * A top corner sits under the status bar and, in most hosts, under an app
     * bar as well — 14dp put the window practically on top of both. This clears
     * a standard 56dp toolbar; a host with taller chrome should raise it, which
     * is exactly why these are props rather than constants.
     */
    val paddingTop: Float = 72f,
    val paddingRight: Float = 14f,
    /** Clears a typical bottom nav; same reasoning as [paddingTop]. */
    val paddingBottom: Float = 42f,
    /**
     * Whether the user may drag it to another corner.
     *
     * The starting corner is still the host's choice; this decides whether the
     * user can override it in the moment, as system PiP allows.
     */
    val draggable: Boolean = true,
)

/**
 * Is a class on the classpath? The player's optional dependencies — Media3,
 * Compose, core-pip — are all probed this way, and catching `Throwable` is the
 * point: a missing transitive dependency surfaces as an Error, not an
 * Exception. Lives in this file deliberately: it has no optional-dependency
 * imports, so the probe itself can never trigger the class loading it exists
 * to avoid.
 */
internal fun hasPlayerClass(name: String): Boolean =
    try {
        Class.forName(name)
        true
    } catch (ignored: Throwable) {
        false
    }

enum class PipePlayerCorner {
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
    ;

    val isLeft: Boolean get() = this == TOP_LEFT || this == BOTTOM_LEFT
    val isTop: Boolean get() = this == TOP_LEFT || this == TOP_RIGHT

    companion object {
        /** Parses the strings the TypeScript side uses; unknown falls back. */
        fun parse(value: String?): PipePlayerCorner = when (value?.lowercase()) {
            "topleft", "top_left" -> TOP_LEFT
            "topright", "top_right" -> TOP_RIGHT
            "bottomleft", "bottom_left" -> BOTTOM_LEFT
            else -> BOTTOM_RIGHT
        }

        /** Nearest corner to a point, for snapping a released drag. */
        fun nearest(centreX: Float, centreY: Float, width: Float, height: Float): PipePlayerCorner {
            val left = centreX < width / 2f
            val top = centreY < height / 2f
            return when {
                top && left -> TOP_LEFT
                top -> TOP_RIGHT
                left -> BOTTOM_LEFT
                else -> BOTTOM_RIGHT
            }
        }
    }
}
