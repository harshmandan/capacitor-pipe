package ink.harsh.plugins.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The thirteen Material glyphs the player draws, as vectors of their own.
 *
 * **Extracted from `material-icons-extended`, not redrawn.** Every path call
 * below was read out of that artefact's own compiled classes, so each glyph is
 * the same shape it has always been — the controls still look like Android
 * rather than like hand-rolled paths, which is why the dependency was taken in
 * the first place.
 *
 * **Why they are here instead.** That artefact is 34 MB of about 2,100 icon
 * classes for these thirteen, and eleven of them are not in the 808 KB core set,
 * so trimming to core was never an option. What is bought is a smaller APK and
 * one fewer dependency.
 *
 * **Not build time, which is what it was tried for.** R8 went from 150.2s to
 * 145.4s, about 3%. Measured afterwards: unreachable classes die in R8's
 * Enqueuer, which is 16% of its work, and never reach IR conversion, which is
 * 54%. Deleting dead code barely moves R8 — see `docs/MOBILE.md` in the app
 * repo, "Where R8's two minutes actually go".
 *
 * The parameters mirror `materialIcon`/`materialPath` exactly: a 24 dp icon on
 * a 24-unit viewport, filled solid black with no stroke, `Butt` cap and `Bevel`
 * join, so a caller tinting with `LocalContentColor` gets what it always got.
 *
 * Regenerate with the extractor recorded in `docs/MOBILE.md` if the glyph set
 * ever needs to change. Do not hand-edit.
 */
object PlayerIcons {

    val Check: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.Check",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.Black),
                stroke = null,
                strokeLineWidth = 1f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Bevel,
                strokeLineMiter = 1f,
                pathFillType = PathFillType.NonZero,
            ) {
            moveTo(9f, 16.17f)
            lineTo(4.83f, 12f)
            lineToRelative(-1.42f, 1.41f)
            lineTo(9f, 19f)
            lineTo(21f, 7f)
            lineToRelative(-1.41f, -1.41f)
            close()
            getNodes()
            }
        }.build()
    }

    val Close: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.Close",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.Black),
                stroke = null,
                strokeLineWidth = 1f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Bevel,
                strokeLineMiter = 1f,
                pathFillType = PathFillType.NonZero,
            ) {
            moveTo(19f, 6.41f)
            lineTo(17.59f, 5f)
            lineTo(12f, 10.59f)
            lineTo(6.41f, 5f)
            lineTo(5f, 6.41f)
            lineTo(10.59f, 12f)
            lineTo(5f, 17.59f)
            lineTo(6.41f, 19f)
            lineTo(12f, 13.41f)
            lineTo(17.59f, 19f)
            lineTo(19f, 17.59f)
            lineTo(13.41f, 12f)
            close()
            getNodes()
            }
        }.build()
    }

    val FastForward: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.FastForward",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.Black),
                stroke = null,
                strokeLineWidth = 1f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Bevel,
                strokeLineMiter = 1f,
                pathFillType = PathFillType.NonZero,
            ) {
            moveTo(4f, 18f)
            lineToRelative(8.5f, -6f)
            lineTo(4f, 6f)
            verticalLineToRelative(12f)
            close()
            moveTo(13f, 6f)
            verticalLineToRelative(12f)
            lineToRelative(8.5f, -6f)
            lineTo(13f, 6f)
            close()
            getNodes()
            }
        }.build()
    }

    val FastRewind: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.FastRewind",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.Black),
                stroke = null,
                strokeLineWidth = 1f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Bevel,
                strokeLineMiter = 1f,
                pathFillType = PathFillType.NonZero,
            ) {
            moveTo(11f, 18f)
            lineTo(11f, 6f)
            lineToRelative(-8.5f, 6f)
            lineToRelative(8.5f, 6f)
            close()
            moveTo(11.5f, 12f)
            lineToRelative(8.5f, 6f)
            lineTo(20f, 6f)
            lineToRelative(-8.5f, 6f)
            close()
            getNodes()
            }
        }.build()
    }

    val Fullscreen: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.Fullscreen",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.Black),
                stroke = null,
                strokeLineWidth = 1f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Bevel,
                strokeLineMiter = 1f,
                pathFillType = PathFillType.NonZero,
            ) {
            moveTo(7f, 14f)
            lineTo(5f, 14f)
            verticalLineToRelative(5f)
            horizontalLineToRelative(5f)
            verticalLineToRelative(-2f)
            lineTo(7f, 17f)
            verticalLineToRelative(-3f)
            close()
            moveTo(5f, 10f)
            horizontalLineToRelative(2f)
            lineTo(7f, 7f)
            horizontalLineToRelative(3f)
            lineTo(10f, 5f)
            lineTo(5f, 5f)
            verticalLineToRelative(5f)
            close()
            moveTo(17f, 17f)
            horizontalLineToRelative(-3f)
            verticalLineToRelative(2f)
            horizontalLineToRelative(5f)
            verticalLineToRelative(-5f)
            horizontalLineToRelative(-2f)
            verticalLineToRelative(3f)
            close()
            moveTo(14f, 5f)
            verticalLineToRelative(2f)
            horizontalLineToRelative(3f)
            verticalLineToRelative(3f)
            horizontalLineToRelative(2f)
            lineTo(19f, 5f)
            horizontalLineToRelative(-5f)
            close()
            getNodes()
            }
        }.build()
    }

    val FullscreenExit: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.FullscreenExit",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.Black),
                stroke = null,
                strokeLineWidth = 1f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Bevel,
                strokeLineMiter = 1f,
                pathFillType = PathFillType.NonZero,
            ) {
            moveTo(5f, 16f)
            horizontalLineToRelative(3f)
            verticalLineToRelative(3f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(-5f)
            lineTo(5f, 14f)
            verticalLineToRelative(2f)
            close()
            moveTo(8f, 8f)
            lineTo(5f, 8f)
            verticalLineToRelative(2f)
            horizontalLineToRelative(5f)
            lineTo(10f, 5f)
            lineTo(8f, 5f)
            verticalLineToRelative(3f)
            close()
            moveTo(14f, 19f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(-3f)
            horizontalLineToRelative(3f)
            verticalLineToRelative(-2f)
            horizontalLineToRelative(-5f)
            verticalLineToRelative(5f)
            close()
            moveTo(16f, 8f)
            lineTo(16f, 5f)
            horizontalLineToRelative(-2f)
            verticalLineToRelative(5f)
            horizontalLineToRelative(5f)
            lineTo(19f, 8f)
            horizontalLineToRelative(-3f)
            close()
            getNodes()
            }
        }.build()
    }

    val HighQuality: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.HighQuality",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.Black),
                stroke = null,
                strokeLineWidth = 1f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Bevel,
                strokeLineMiter = 1f,
                pathFillType = PathFillType.NonZero,
            ) {
            moveTo(19f, 4f)
            lineTo(5f, 4f)
            curveToRelative(-1.11f, 0f, -2f, 0.9f, -2f, 2f)
            verticalLineToRelative(12f)
            curveToRelative(0f, 1.1f, 0.89f, 2f, 2f, 2f)
            horizontalLineToRelative(14f)
            curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
            lineTo(21f, 6f)
            curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
            close()
            moveTo(11f, 15f)
            lineTo(9.5f, 15f)
            verticalLineToRelative(-2f)
            horizontalLineToRelative(-2f)
            verticalLineToRelative(2f)
            lineTo(6f, 15f)
            lineTo(6f, 9f)
            horizontalLineToRelative(1.5f)
            verticalLineToRelative(2.5f)
            horizontalLineToRelative(2f)
            lineTo(9.5f, 9f)
            lineTo(11f, 9f)
            verticalLineToRelative(6f)
            close()
            moveTo(18f, 14f)
            curveToRelative(0f, 0.55f, -0.45f, 1f, -1f, 1f)
            horizontalLineToRelative(-0.75f)
            verticalLineToRelative(1.5f)
            horizontalLineToRelative(-1.5f)
            lineTo(14.75f, 15f)
            lineTo(14f, 15f)
            curveToRelative(-0.55f, 0f, -1f, -0.45f, -1f, -1f)
            verticalLineToRelative(-4f)
            curveToRelative(0f, -0.55f, 0.45f, -1f, 1f, -1f)
            horizontalLineToRelative(3f)
            curveToRelative(0.55f, 0f, 1f, 0.45f, 1f, 1f)
            verticalLineToRelative(4f)
            close()
            moveTo(14.5f, 13.5f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(-3f)
            horizontalLineToRelative(-2f)
            verticalLineToRelative(3f)
            close()
            getNodes()
            }
        }.build()
    }

    val Pause: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.Pause",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.Black),
                stroke = null,
                strokeLineWidth = 1f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Bevel,
                strokeLineMiter = 1f,
                pathFillType = PathFillType.NonZero,
            ) {
            moveTo(6f, 19f)
            horizontalLineToRelative(4f)
            lineTo(10f, 5f)
            lineTo(6f, 5f)
            verticalLineToRelative(14f)
            close()
            moveTo(14f, 5f)
            verticalLineToRelative(14f)
            horizontalLineToRelative(4f)
            lineTo(18f, 5f)
            horizontalLineToRelative(-4f)
            close()
            getNodes()
            }
        }.build()
    }

    val PlayArrow: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.PlayArrow",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.Black),
                stroke = null,
                strokeLineWidth = 1f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Bevel,
                strokeLineMiter = 1f,
                pathFillType = PathFillType.NonZero,
            ) {
            moveTo(8f, 5f)
            verticalLineToRelative(14f)
            lineToRelative(11f, -7f)
            close()
            getNodes()
            }
        }.build()
    }

    val Replay: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.Replay",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.Black),
                stroke = null,
                strokeLineWidth = 1f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Bevel,
                strokeLineMiter = 1f,
                pathFillType = PathFillType.NonZero,
            ) {
            moveTo(12f, 5f)
            verticalLineTo(1f)
            lineTo(7f, 6f)
            lineToRelative(5f, 5f)
            verticalLineTo(7f)
            curveToRelative(3.31f, 0f, 6f, 2.69f, 6f, 6f)
            reflectiveCurveToRelative(-2.69f, 6f, -6f, 6f)
            reflectiveCurveToRelative(-6f, -2.69f, -6f, -6f)
            horizontalLineTo(4f)
            curveToRelative(0f, 4.42f, 3.58f, 8f, 8f, 8f)
            reflectiveCurveToRelative(8f, -3.58f, 8f, -8f)
            reflectiveCurveToRelative(-3.58f, -8f, -8f, -8f)
            close()
            getNodes()
            }
        }.build()
    }

    val SkipNext: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.SkipNext",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.Black),
                stroke = null,
                strokeLineWidth = 1f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Bevel,
                strokeLineMiter = 1f,
                pathFillType = PathFillType.NonZero,
            ) {
            moveTo(6f, 18f)
            lineToRelative(8.5f, -6f)
            lineTo(6f, 6f)
            verticalLineToRelative(12f)
            close()
            moveTo(16f, 6f)
            verticalLineToRelative(12f)
            horizontalLineToRelative(2f)
            verticalLineTo(6f)
            horizontalLineToRelative(-2f)
            close()
            getNodes()
            }
        }.build()
    }

    val SkipPrevious: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.SkipPrevious",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.Black),
                stroke = null,
                strokeLineWidth = 1f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Bevel,
                strokeLineMiter = 1f,
                pathFillType = PathFillType.NonZero,
            ) {
            moveTo(6f, 6f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(12f)
            lineTo(6f, 18f)
            close()
            moveTo(9.5f, 12f)
            lineToRelative(8.5f, 6f)
            lineTo(18f, 6f)
            close()
            getNodes()
            }
        }.build()
    }

    val Speed: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.Speed",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.Black),
                stroke = null,
                strokeLineWidth = 1f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Bevel,
                strokeLineMiter = 1f,
                pathFillType = PathFillType.NonZero,
            ) {
            moveTo(20.38f, 8.57f)
            lineToRelative(-1.23f, 1.85f)
            arcToRelative(8f, 8f, 0f, false, true, -0.22f, 7.58f)
            lineTo(5.07f, 18f)
            arcTo(8f, 8f, 0f, false, true, 15.58f, 6.85f)
            lineToRelative(1.85f, -1.23f)
            arcTo(10f, 10f, 0f, false, false, 3.35f, 19f)
            arcToRelative(2f, 2f, 0f, false, false, 1.72f, 1f)
            horizontalLineToRelative(13.85f)
            arcToRelative(2f, 2f, 0f, false, false, 1.74f, -1f)
            arcToRelative(10f, 10f, 0f, false, false, -0.27f, -10.44f)
            close()
            moveTo(10.59f, 15.41f)
            arcToRelative(2f, 2f, 0f, false, false, 2.83f, 0f)
            lineToRelative(5.66f, -8.49f)
            lineToRelative(-8.49f, 5.66f)
            arcToRelative(2f, 2f, 0f, false, false, 0f, 2.83f)
            close()
            getNodes()
            }
        }.build()
    }
}
