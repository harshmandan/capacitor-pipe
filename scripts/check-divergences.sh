#!/usr/bin/env bash
#
# Verify the divergences recorded in DIVERGENCES.md still hold.
#
# Most of them are self-catching: if PipePipe's Response constructor changes
# arity, the build breaks and you go fix it. The dangerous ones are those that
# fail *silently* — section 10's exception matching by simple name degrades into
# pointless retries with no error, no log and no failed build.
#
# This script asserts the shape of both forks' source against what our code
# assumes. A CHANGED line means: update the code, then update DIVERGENCES.md.
#
# Usage: scripts/check-divergences.sh
#
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PP="$ROOT/submodules/PipePipeExtractor/extractor/src/main/java/org/schabi/newpipe/extractor"
NP="$ROOT/submodules/NewPipeExtractor/extractor/src/main/java/org/schabi/newpipe/extractor"
SRC="$ROOT/android/src/main/kotlin/ink/harsh/plugins/pipe"

changed=0
checks=0

ok()      { printf '  \033[32mok\033[0m       §%-3s %s\n' "$1" "$2"; }
changed() { printf '  \033[31mCHANGED\033[0m  §%-3s %s\n' "$1" "$2"; changed=$((changed + 1)); }
note()    { printf '           %s\n' "$1"; }
head2()   { printf '\n\033[1m%s\033[0m\n' "$1"; }

[ -d "$PP" ] || { echo "PipePipeExtractor submodule missing. Run: npm run extractors:init" >&2; exit 1; }
[ -d "$NP" ] || { echo "NewPipeExtractor submodule missing. Run: npm run extractors:init" >&2; exit 1; }

# assert_grep <section> <expect:yes|no> <file> <pattern> <description>
assert_grep() {
    local section="$1" expect="$2" file="$3" pattern="$4" desc="$5"
    checks=$((checks + 1))
    if [ ! -f "$file" ]; then
        changed "$section" "$desc"
        note "file no longer exists: ${file#"$ROOT"/}"
        return
    fi
    local found=no
    grep -qE "$pattern" "$file" && found=yes
    if [ "$found" = "$expect" ]; then
        ok "$section" "$desc"
    else
        changed "$section" "$desc"
        note "expected ${expect}, found ${found} in ${file#"$ROOT"/}"
    fi
}

head2 "1. Relocation prefix consistency"
# The prefix is defined once in Gradle; the Java side must agree or the fallback
# silently stops being a separate engine.
PREFIX="$(grep -oE "relocate 'org\.schabi\.newpipe', '[^']+'" "$ROOT/tools/shade/build.gradle" \
    | sed -E "s/.*, '([^']+)'/\1/")"
checks=$((checks + 1))
if [ -z "$PREFIX" ]; then
    changed 1 "relocation prefix not found in tools/shade/build.gradle"
else
    bad=0
    for f in "$SRC/engine/NewPipeEngine.kt" "$SRC/net/NewPipeDownloader.kt"; do
        grep -q "$PREFIX" "$f" || { bad=1; note "does not reference $PREFIX: ${f#"$ROOT"/}"; }
    done
    # The primary must NOT be relocated.
    grep -q "$PREFIX" "$SRC/engine/PipePipeEngine.kt" && {
        bad=1; note "PipePipeEngine unexpectedly references the shaded prefix"; }
    [ "$bad" -eq 0 ] && ok 1 "Kotlin imports match Gradle prefix ($PREFIX)" \
                     || changed 1 "Kotlin imports out of sync with Gradle prefix ($PREFIX)"
fi

head2 "2-4. Downloader / Response / Request contracts"
assert_grep 2 yes "$PP/downloader/Downloader.java" \
    'public abstract CancellableCall executeAsync' "PipePipe still requires executeAsync"
assert_grep 2 no  "$NP/downloader/Downloader.java" \
    'executeAsync' "NewPipe still has no async path"
assert_grep 2 yes "$PP/downloader/CancellableCall.java" \
    'okhttp3\.Call' "PipePipe still leaks okhttp3.Call (pins our okhttp version)"
assert_grep 3 yes "$PP/downloader/Response.java" \
    'rawResponseBody' "PipePipe Response still carries a raw byte[] (SABR needs it)"
assert_grep 3 no  "$NP/downloader/Response.java" \
    'rawResponseBody' "NewPipe Response still has no raw body"
assert_grep 4 yes "$PP/downloader/Request.java" \
    'public boolean followRedirects\(\)' "PipePipe Request still exposes followRedirects()"
assert_grep 4 no  "$NP/downloader/Request.java" \
    'followRedirects' "NewPipe Request still lacks followRedirects() (we hardcode true)"

head2 "5. Localization return type"
assert_grep 5 yes "$PP/localization/Localization.java" \
    'public static Localization fromLocalizationCode' "PipePipe returns Localization"
assert_grep 5 yes "$NP/localization/Localization.java" \
    'public static Optional<Localization> fromLocalizationCode' "NewPipe returns Optional (we .orElse)"

head2 "6. Thumbnail / avatar accessors"
assert_grep 6 yes "$PP/stream/StreamInfo.java" \
    'String getThumbnailUrl\(\)' "PipePipe still has getThumbnailUrl()"
assert_grep 6 no  "$NP/stream/StreamInfo.java" \
    'String getThumbnailUrl\(\)' "NewPipe still lacks it (we derive from getThumbnails)"
assert_grep 6 yes "$PP/stream/StreamInfo.java" \
    'String getUploaderAvatarUrl\(\)' "PipePipe still has getUploaderAvatarUrl()"
assert_grep 6 no  "$NP/stream/StreamInfo.java" \
    'String getUploaderAvatarUrl\(\)' "NewPipe still lacks it"

head2 "7. SponsorBlock"
assert_grep 7 yes "$PP/stream/StreamInfo.java" \
    'getSponsorBlockSegments' "PipePipe still exposes SponsorBlock segments"
assert_grep 7 yes "$PP/sponsorblock/SponsorBlockSegment.java" \
    'public (String uuid|double startTime)' "SponsorBlockSegment still uses public fields, not getters"
assert_grep 7 no  "$NP/stream/StreamInfo.java" \
    'getSponsorBlockSegments' "NewPipe still has no SponsorBlock"

head2 "8. SABR"
assert_grep 8 yes "$PP/stream/DeliveryMethod.java" \
    '^\s+SABR' "PipePipe still has DeliveryMethod.SABR"
assert_grep 8 no  "$NP/stream/DeliveryMethod.java" \
    '^\s+SABR' "NewPipe still has NO SABR (fallback cannot rescue SABR-only videos)"
checks=$((checks + 1))
NP_SABR_FILES="$(grep -ril sabr "$NP" 2>/dev/null | wc -l | tr -d ' ')"
if [ "$NP_SABR_FILES" -le 2 ]; then
    ok 8 "NewPipe SABR footprint still negligible ($NP_SABR_FILES file(s), javadoc only)"
else
    changed 8 "NewPipe now mentions SABR in $NP_SABR_FILES files — upstream may be implementing it"
    note "If so, revisit DIVERGENCES.md §8 entirely: requiresSabr:false becomes a lie,"
    note "and the two engines may become genuinely equivalent."
fi

head2 "9. Player-client constants"
for client in visionos mweb; do
    assert_grep 9 yes "$PP/NewPipe.java" \
        "\"$client\"" "setYoutubePlayerClient still accepts \"$client\""
done
assert_grep 9 yes "$ROOT/submodules/PipePipeExtractor/extractor/src/main/java/org/schabi/newpipe/extractor/services/youtube/extractors/YoutubeStreamExtractor.java" \
    'buildSabrStreams' "SABR branch still keyed on the player client"
assert_grep 9 no  "$ROOT/submodules/PipePipeExtractor/extractor/src/main/java/org/schabi/newpipe/extractor/services/youtube/extractors/YoutubeStreamExtractor.java" \
    'FORCE_SABR_FOR_TESTING' "FORCE_SABR_FOR_TESTING has not returned"

head2 "10. Exception names (silent-failure risk)"
# Parsed out of the Kotlin rather than duplicated here, so the two cannot drift.
RETRY_NAMES="$(sed -n '/NOT_WORTH_RETRYING/,/^ *)/p' "$SRC/PipeExtractor.kt" \
    | grep -oE '"[A-Za-z]+Exception"' | tr -d '"' | sort -u)"
checks=$((checks + 1))
if [ -z "$RETRY_NAMES" ]; then
    changed 10 "could not parse NOT_WORTH_RETRYING from PipeExtractor.kt"
else
    missing=0
    for name in $RETRY_NAMES; do
        in_pp=no; in_np=no
        [ -f "$PP/exceptions/$name.java" ] && in_pp=yes
        [ -f "$NP/exceptions/$name.java" ] && in_np=yes
        if [ "$in_pp" = no ] && [ "$in_np" = no ]; then
            note "NOT in either fork: $name  <- renamed or removed; the match now never fires"
            missing=$((missing + 1))
        fi
    done
    if [ "$missing" -eq 0 ]; then
        ok 10 "all $(printf '%s\n' $RETRY_NAMES | wc -l | tr -d ' ') retry-skip names still resolve"
    else
        changed 10 "$missing retry-skip name(s) no longer exist in either fork"
    fi
fi

# Informational: content-level exceptions we do not classify. Not a failure —
# NewPipe is last in the chain today, so an unclassified one costs nothing. It
# would start costing a wasted request if the chain order ever changed.
UNCLASSIFIED=""
for f in "$PP/exceptions"/*.java "$NP/exceptions"/*.java; do
    n="$(basename "$f" .java)"
    case "$n" in
        *ContentException|*RestrictionException|*TerminatedException|*InCountryException)
            printf '%s\n' "$RETRY_NAMES" | grep -qx "$n" || UNCLASSIFIED="$UNCLASSIFIED $n" ;;
    esac
done
UNCLASSIFIED="$(printf '%s\n' $UNCLASSIFIED | sort -u | tr '\n' ' ')"
[ -n "${UNCLASSIFIED// /}" ] && {
    printf '  \033[33mreview\033[0m   §10  content-level exceptions not in NOT_WORTH_RETRYING:\n'
    note "$UNCLASSIFIED"
    note "Harmless while newpipe is last in the chain; revisit if the order changes."
}

head2 "12. Build-level assumptions"
assert_grep 12 yes "$ROOT/submodules/PipePipeExtractor/extractor/build.gradle" \
    'com\.squareup\.wire' "PipePipe still uses wire (we declare wire-runtime)"
assert_grep 12 yes "$ROOT/submodules/PipePipeExtractor/extractor/build.gradle" \
    'protobuf-java' "PipePipe still on full protobuf-java"
assert_grep 12 yes "$ROOT/submodules/NewPipeExtractor/gradle/libs.versions.toml" \
    'protobuf-javalite' "NewPipe still on protobuf-javalite (collides; stays relocated)"
assert_grep 12 yes "$ROOT/submodules/NewPipeExtractor/gradle/libs.versions.toml" \
    'rhino = "1\.8' "NewPipe still pins rhino 1.8.x (1.9.0 needs minSdk 26)"

printf '\n'
if [ "$changed" -eq 0 ]; then
    printf '\033[32mAll %s divergence checks hold.\033[0m\n' "$checks"
    exit 0
fi
printf '\033[31m%s of %s divergence checks CHANGED.\033[0m\n' "$changed" "$checks"
cat <<'EOF'

A changed divergence means upstream moved. Do all three:
  1. Fix the affected code (see the §section in DIVERGENCES.md for file:line).
  2. Update that section of DIVERGENCES.md to describe the new reality.
  3. Re-run this script until clean.

Do not silence a check by deleting it. If a divergence genuinely disappeared —
the forks converged — remove the code branch first, then the row, then the check.
EOF
exit 1
