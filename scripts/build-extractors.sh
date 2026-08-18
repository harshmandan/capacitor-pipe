#!/usr/bin/env bash
#
# Build both extractors from their pinned submodules and stage the jars that
# ship in the npm package.
#
# Why jars and not a composite Gradle build: npm tarballs cannot carry git
# submodules, so a consuming app never sees submodules/. The submodules are the
# development path; android/libs/ is the published path.
#
# Outputs (all under android/libs/):
#   pipepipe-extractor.jar          primary engine, unrelocated
#   pipepipe-timeago-parser.jar     its sibling module
#   newpipe-extractor-shaded.jar    fallback engine, relocated
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PPE="$ROOT/submodules/PipePipeExtractor"
NPE="$ROOT/submodules/NewPipeExtractor"
LIBS="$ROOT/android/libs"
INIT="$ROOT/tools/force-java17.init.gradle"

log() { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
die() { printf '\033[1;31mError:\033[0m %s\n' "$*" >&2; exit 1; }

[ -f "$PPE/settings.gradle" ] || die "PipePipeExtractor submodule is empty. Run: npm run extractors:init"
[ -f "$NPE/settings.gradle.kts" ] || die "NewPipeExtractor submodule is empty. Run: npm run extractors:init"

mkdir -p "$LIBS"

log "Building PipePipeExtractor (toolchain forced to 17 for D8 compatibility)"
( cd "$PPE" && ./gradlew --no-daemon -I "$INIT" :extractor:jar :timeago-parser:jar )

# NewPipe asks for a JDK 11 toolchain. Its bytecode is already Android-safe, but
# pinning both builds to the same installed JDK avoids requiring developers to
# keep an extra JDK around just for this submodule.
log "Building NewPipeExtractor"
( cd "$NPE" && ./gradlew --no-daemon -I "$INIT" :extractor:jar )

PPE_JAR="$(find "$PPE/extractor/build/libs" -name '*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' | head -1)"
PPE_TIMEAGO_JAR="$(find "$PPE/timeago-parser/build/libs" -name '*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' | head -1)"
NPE_JAR="$(find "$NPE/extractor/build/libs" -name '*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' | head -1)"

[ -n "$PPE_JAR" ] || die "PipePipeExtractor jar not produced"
[ -n "$PPE_TIMEAGO_JAR" ] || die "PipePipe timeago-parser jar not produced"
[ -n "$NPE_JAR" ] || die "NewPipeExtractor jar not produced"

log "Relocating NewPipeExtractor out of org.schabi.newpipe"
# tools/shade has no wrapper of its own. Reuse the plugin's (Gradle 8.14.3):
# Shadow 8.3.x does not support Gradle 9, so the submodule wrappers are unsuitable.
"$ROOT/android/gradlew" --no-daemon -p "$ROOT/tools/shade" shadowJar \
    -PnewPipeJar="$NPE_JAR" \
    -PoutputDir="$LIBS"

cp -f "$PPE_JAR" "$LIBS/pipepipe-extractor.jar"
cp -f "$PPE_TIMEAGO_JAR" "$LIBS/pipepipe-timeago-parser.jar"

log "Encrypting identifying string literals (see ENCRYPTED-STRINGS.md)"
# Runs on the FINISHED, already-relocated jars. The shared Codec class is
# injected into pipepipe-extractor.jar ONLY — all three jars reference the same
# ink/harsh/pipe/strenc/Codec, so a second copy would be a duplicate-class error
# at dex time and none would leave the reference unresolved. Relocation ran
# earlier and does not touch ink.harsh.pipe.strenc, so the shaded NewPipe classes
# resolve the same symbol.
"$ROOT/android/gradlew" --no-daemon -p "$ROOT/tools/strenc" -q installDist
STRENC="$ROOT/tools/strenc/build/install/pipe-strenc/bin/pipe-strenc"
REPORT_DIR="$(mktemp -d)"

encrypt_jar() {
    jar="$1"; inject="$2"
    tmp="$(mktemp -d)/$(basename "$jar")"
    "$STRENC" "$LIBS/$jar" "$tmp" "$inject" "$REPORT_DIR/$jar.txt"
    mv -f "$tmp" "$LIBS/$jar"
}

encrypt_jar pipepipe-extractor.jar        true    # hosts the shared Codec
encrypt_jar newpipe-extractor-shaded.jar  false
encrypt_jar pipepipe-timeago-parser.jar   false

# The committed registry snapshot: the sorted union of every literal encrypted.
# A diff here on an extractor bump is the signal that the identifying surface
# changed — see ENCRYPTED-STRINGS.md.
sort -u "$REPORT_DIR"/*.txt > "$ROOT/tools/strenc/encrypted-strings.txt"
printf '    %s distinct strings encrypted across all jars\n' \
    "$(wc -l < "$ROOT/tools/strenc/encrypted-strings.txt" | tr -d ' ')"

log "Verifying encryption round-trips on a canary (build-time proof Codec decrypts)"
"$ROOT/scripts/verify-strenc.sh" "$LIBS/pipepipe-extractor.jar"

log "Verifying no class exceeds Java 17 bytecode (major 61)"
"$ROOT/scripts/verify-bytecode.sh" "$LIBS"

log "Verifying the two engines no longer share a namespace"
# Note: count first, test second. `unzip -l | grep -q` would exit on the first
# match, SIGPIPE the unzip, and `set -o pipefail` would report that success as a
# failure — inverting the check.
SHADED_LISTING="$(unzip -l "$LIBS/newpipe-extractor-shaded.jar")"
UNRELOCATED="$(printf '%s\n' "$SHADED_LISTING" | grep -cE ' org/schabi/newpipe/' || true)"
RELOCATED="$(printf '%s\n' "$SHADED_LISTING" | grep -c 'ink/harsh/pipe/shaded/org/schabi/newpipe/' || true)"

if [ "$UNRELOCATED" -ne 0 ]; then
    die "Relocation failed: $UNRELOCATED entries still under org/schabi/newpipe/.
     Both engines would occupy the same namespace and one would silently win."
fi
if [ "$RELOCATED" -eq 0 ]; then
    die "Relocation produced no ink/harsh/pipe/shaded/ classes; the shaded jar looks empty."
fi
printf '    %s relocated classes, 0 left in the shared namespace\n' "$RELOCATED"

log "Done. Staged in android/libs:"
ls -lh "$LIBS" | sed 's/^/    /'
