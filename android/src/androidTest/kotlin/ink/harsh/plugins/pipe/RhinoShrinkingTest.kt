package ink.harsh.plugins.pipe

import androidx.test.ext.junit.runners.AndroidJUnit4
import ink.harsh.pipe.shaded.org.schabi.newpipe.extractor.utils.JavaScript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Guards the narrowed Rhino keep rule in `android/proguard-rules.pro`.
 *
 * The rule drops `org.mozilla.javascript.optimizer/tools/engine` on the grounds
 * that NewPipe always calls `setInterpretedMode(true)`, and keeps the rest
 * because Rhino resolves its standard objects reflectively through
 * `LazilyLoadedCtor`, which takes class names as **strings** R8 cannot follow.
 *
 * Get that wrong and the failure is a `ClassNotFoundException` at runtime,
 * inside the FALLBACK engine — a path that only executes once PipePipe has
 * already failed, which is the worst possible place for a latent bug. Nothing
 * in a compile catches it.
 *
 * **This test only proves anything when run against a MINIFIED build.** On a
 * debug build R8 never runs, every class is present, and it passes vacuously.
 *
 * ```
 * ./gradlew connectedAndroidTest -Pandroid.testBuildType=release \
 *     -Pandroid.testInstrumentationRunnerArguments.class=\
 * ink.harsh.plugins.pipe.RhinoShrinkingTest
 * ```
 */
@RunWith(AndroidJUnit4::class)
class RhinoShrinkingTest {

    /**
     * The narrow path: exactly what deciphering does — evaluate a function
     * definition, then call it by name.
     *
     * This alone exercises `Context`, `ScriptableObject`, `ScriptRuntime`,
     * `Interpreter` and `NativeFunction`, all of which reach each other
     * reflectively.
     */
    @Test
    fun runsAJavaScriptFunction() {
        val result = JavaScript.run(
            "function decode(a) { return a.split('').reverse().join(''); }",
            "decode",
            "abcdef",
        )
        assertEquals("fedcba", result)
    }

    /**
     * Signature deobfuscation functions are not toy code. Real ones index into
     * arrays, splice, swap via a temporary and call helpers on a shared object —
     * which pulls in the lazily-loaded natives (`NativeArray`, `NativeString`,
     * `regexp.*`) that the keep list exists to protect.
     */
    @Test
    fun runsARealisticDeobfuscationFunction() {
        val function = """
            var helper = {
                swap: function (arr, i) { var t = arr[0]; arr[0] = arr[i % arr.length]; arr[i % arr.length] = t; },
                slice: function (arr, i) { arr.splice(0, i); },
                reverse: function (arr) { arr.reverse(); }
            };
            function decipher(sig) {
                var a = sig.split("");
                helper.swap(a, 3);
                helper.reverse(a);
                helper.slice(a, 2);
                return a.join("");
            }
        """.trimIndent()

        val result = JavaScript.run(function, "decipher", "ABCDEFGH")

        // D BCA EFGH -> reverse -> HGFE ACB D -> drop 2 -> FEACBD
        assertEquals("FEACBD", result)
    }

    /**
     * Regex lives in `org.mozilla.javascript.regexp`, a separate sub-package
     * reached only through a reflective name — precisely the shape most likely
     * to be shrunk away by an over-eager rule.
     */
    @Test
    fun runsRegexBackedJavaScript() {
        val result = JavaScript.run(
            "function n(p) { return p.replace(/[^a-z]/g, '').toUpperCase(); }",
            "n",
            "a1b2c3d4",
        )
        assertEquals("ABCD", result)
    }

    /** `compileOrThrow` is the other entry point, used to validate an extracted function. */
    @Test
    fun compilesWithoutThrowing() {
        JavaScript.compileOrThrow("function f(x) { return x + 1; }")
    }

    /**
     * A malformed function must still throw. If shrinking broke Rhino's error
     * path this would pass silently, and the extractor would treat unparseable
     * player JS as usable.
     */
    @Test
    fun rejectsMalformedJavaScript() {
        val threw = try {
            JavaScript.compileOrThrow("function f( { return")
            false
        } catch (expected: Exception) {
            true
        }
        assertTrue("malformed JavaScript compiled without error", threw)
    }
}
