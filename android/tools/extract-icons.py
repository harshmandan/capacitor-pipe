"""
Recover the player's Material glyphs from the compiled `material-icons-extended`
artefact and write `PlayerIcons.kt`.

The player wanted the standard glyph set so its controls look like Android; the
artefact that carried it is 34 MB of ~2,100 icon classes for the thirteen used.
This reads the path calls straight out of that artefact's own bytecode, so the
glyphs are the same shapes rather than redrawn ones.

    unzip the aar's classes.jar into ./work, then:
    python3 extract-icons.py

Run it only to change the glyph set. `PlayerIcons.kt` is generated; editing it
by hand loses the guarantee that the shapes are upstream's.
"""

import re, subprocess, os
JAVAP = "/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home/bin/javap"
WORK = os.path.join(os.path.dirname(os.path.abspath(__file__)), "work")
NAMES = ["Check","Close","FastForward","FastRewind","Fullscreen","FullscreenExit",
         "HighQuality","Pause","PlayArrow","Replay","SkipNext","SkipPrevious","Speed"]

def fmt(v):
    return ("true" if v else "false") if isinstance(v, bool) else f"{v:g}f"

def calls_for(name):
    out = subprocess.run(
        [JAVAP, "-c", "-p", f"{WORK}/androidx/compose/material/icons/filled/{name}Kt.class"],
        capture_output=True, text=True).stdout
    stack, calls = [], []
    for line in out.splitlines():
        line = line.strip()
        m = re.search(r"//\s*float (-?[\d.E]+)f", line)
        if m:
            stack.append(float(m.group(1))); continue
        if re.search(r"\bfconst_(\d)\b", line):
            stack.append(float(re.search(r"fconst_(\d)", line).group(1))); continue
        if re.search(r"\biconst_(\d)\b", line):
            stack.append(bool(int(re.search(r"iconst_(\d)", line).group(1)))); continue
        m = re.search(r"PathBuilder\.(\w+):\(([^)]*)\)", line)
        if m:
            n = len(m.group(2))
            calls.append((m.group(1), stack[-n:] if n else []))
            stack = stack[:-n] if n else stack
            continue
        if "invoke" in line:
            stack = []
    first = next(i for i, (mth, _) in enumerate(calls) if mth.startswith("moveTo"))
    return calls[first:]

head = '''package ink.harsh.plugins.player

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
 * classes, and R8 models every one of them to throw away all but these
 * thirteen. R8 is a whole-program optimiser, so it pays that cost once per
 * flavour — 246 times a release, at roughly 95 seconds each. Eleven of the
 * thirteen are not in the 808 KB core set, so trimming to core was not an
 * option; carrying the paths is.
 *
 * The parameters mirror `materialIcon`/`materialPath` exactly: a 24 dp icon on
 * a 24-unit viewport, filled solid black with no stroke, `Butt` cap and `Bevel`
 * join, so a caller tinting with `LocalContentColor` gets what it always got.
 *
 * Regenerate with the extractor recorded in `docs/MOBILE.md` if the glyph set
 * ever needs to change. Do not hand-edit.
 */
object PlayerIcons {
'''

body = []
for n in NAMES:
    lines = [f"            {m}({', '.join(fmt(a) for a in args)})" for m, args in calls_for(n)]
    body.append(f'''
    val {n}: ImageVector by lazy {{
        ImageVector.Builder(
            name = "Filled.{n}",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {{
            path(
                fill = SolidColor(Color.Black),
                stroke = null,
                strokeLineWidth = 1f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Bevel,
                strokeLineMiter = 1f,
                pathFillType = PathFillType.NonZero,
            ) {{
{chr(10).join(lines)}
            }}
        }}.build()
    }}''')

out = head + "\n".join(body) + "\n}\n"
dest = "/Users/harsh/Documents/GitHub/tutorgrow/vendor/capacitor-pipe/android/src/main/kotlin/ink/harsh/plugins/player/PlayerIcons.kt"
open(dest, "w").write(out)
print("wrote", dest, len(out), "bytes")
