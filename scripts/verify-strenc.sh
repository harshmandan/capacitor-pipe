#!/usr/bin/env bash
#
# Prove, at build time, that string encryption is both HIDDEN and REVERSIBLE.
#
#   scripts/verify-strenc.sh <pipepipe-extractor.jar>
#
# Two failure modes this guards, both silent otherwise:
#
#   1. The plaintext is still in the class bytes  -> encryption did not happen.
#   2. The field no longer decrypts to the original -> encryption BROKE the jar,
#      and extraction would fail on device with a mangled URL.
#
# It checks a canary: YoutubeApiDecoder.API_BASE_URL, a `static final String`
# ConstantValue -- the strictest path (attribute stripped, reassigned in
# <clinit> via Codec). If the canary survives both checks the mechanism works;
# whether EVERY string was caught is the job of check-apk-obfuscation.sh over the
# shipped APK.

set -euo pipefail

JAR="${1:?usage: verify-strenc.sh <jar>}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

CANARY_CLASS="org.schabi.newpipe.extractor.services.youtube.YoutubeApiDecoder"
CANARY_FIELD="API_BASE_URL"
CANARY_VALUE="https://api.pipepipe.dev/decoder/decode"

JAVA_BIN="$(command -v java)"
[ -n "$JAVA_BIN" ] || { echo "no java on PATH" >&2; exit 2; }

# --- 1. the plaintext must be gone from the class bytes ---
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
( cd "$WORK" && unzip -qo "$JAR" '*.class' )
export LC_ALL=C
if grep -raq "$CANARY_VALUE" "$WORK"; then
    echo "FAIL: '$CANARY_VALUE' is still plaintext in the jar — encryption did not run" >&2
    exit 1
fi
echo "    hidden:    '$CANARY_VALUE' absent from class bytes"

# --- 2. it must still decrypt to the original at runtime ---
NANOJSON="$(find "$HOME/.gradle/caches/modules-2" -name 'nanojson-*.jar' 2>/dev/null | head -1)"

cat > "$WORK/VerifyStrenc.java" <<JAVA
import java.lang.reflect.Field;
public class VerifyStrenc {
    public static void main(String[] a) throws Exception {
        Class<?> c = Class.forName("$CANARY_CLASS");
        Field f = c.getDeclaredField("$CANARY_FIELD");
        f.setAccessible(true);
        String v = (String) f.get(null);
        if (!"$CANARY_VALUE".equals(v)) {
            System.out.println("FAIL: decrypted to [" + v + "]");
            System.exit(1);
        }
        System.out.println("    reversible: '$CANARY_FIELD' decrypts to the original at runtime");
    }
}
JAVA

JAVAC="$(dirname "$JAVA_BIN")/javac"
"$JAVAC" -cp "$JAR" -d "$WORK" "$WORK/VerifyStrenc.java"
"$JAVA_BIN" -cp "$JAR:$NANOJSON:$WORK" VerifyStrenc
