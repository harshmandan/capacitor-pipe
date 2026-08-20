#!/usr/bin/env bash
#
# Pull upstream extractor changes and re-pin, without breaking the plugin.
#
# The submodules are pristine upstream checkouts — we never edit them, so an
# update is always a fast-forward of a pin, never a merge. What can still break
# is *our* code: the extractor API surface we call, the bytecode level, and the
# namespace separation the fallback depends on. This script updates, rebuilds,
# and checks exactly those three things, then leaves the pin bump staged for
# review rather than committing it.
#
# Usage:
#   scripts/update-extractors.sh --check          show what is new upstream, change nothing
#   scripts/update-extractors.sh                  update both, rebuild, verify
#   scripts/update-extractors.sh --only pipepipe  update just one
#   scripts/update-extractors.sh --only newpipe
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

CHECK_ONLY=0
ONLY=""
while [ $# -gt 0 ]; do
    case "$1" in
        --check) CHECK_ONLY=1 ;;
        --only) ONLY="${2:?--only needs pipepipe|newpipe}"; shift ;;
        -h|--help) sed -n '2,22p' "$0"; exit 0 ;;
        *) echo "Unknown argument: $1" >&2; exit 1 ;;
    esac
    shift
done

log()  { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33mWarning:\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31mError:\033[0m %s\n' "$*" >&2; exit 1; }

# Extractor API our wrapper code calls directly. If upstream removes or renames
# any of these, the plugin stops compiling — better to find out here, with the
# old pin still recoverable, than midway through an app build.
#
# Keep in sync with android/src/main/kotlin/ink/harsh/plugins/pipe/.
PIPEPIPE_API=(
    "extractor/src/main/java/org/schabi/newpipe/extractor/NewPipe.java"
    "extractor/src/main/java/org/schabi/newpipe/extractor/StreamingService.java"
    "extractor/src/main/java/org/schabi/newpipe/extractor/ServiceList.java"
    "extractor/src/main/java/org/schabi/newpipe/extractor/stream/StreamInfo.java"
    "extractor/src/main/java/org/schabi/newpipe/extractor/downloader/Downloader.java"
    "extractor/src/main/java/org/schabi/newpipe/extractor/services/youtube/sabr/YoutubeSabrSession.java"
    "extractor/src/main/java/org/schabi/newpipe/extractor/services/youtube/sabr/YoutubeSabrInfo.java"
    "extractor/src/main/java/org/schabi/newpipe/extractor/services/youtube/sabr/YoutubeSabrRequest.java"
    "extractor/src/main/java/org/schabi/newpipe/extractor/services/youtube/sabr/YoutubeSabrFormatTimeline.java"
    "extractor/src/main/java/org/schabi/newpipe/extractor/services/youtube/sabr/media/SabrMediaSegment.java"
)
NEWPIPE_API=(
    "extractor/src/main/java/org/schabi/newpipe/extractor/NewPipe.java"
    "extractor/src/main/java/org/schabi/newpipe/extractor/ServiceList.java"
    "extractor/src/main/java/org/schabi/newpipe/extractor/stream/StreamInfo.java"
    "extractor/src/main/java/org/schabi/newpipe/extractor/downloader/Downloader.java"
)

# $1 = path, $2 = label, $3 = tracked branch
show_incoming() {
    local path="$1" label="$2" branch="$3"
    git -C "$path" fetch --quiet origin "$branch"
    local cur new count
    cur="$(git -C "$path" rev-parse HEAD)"
    new="$(git -C "$path" rev-parse "origin/$branch")"
    if [ "$cur" = "$new" ]; then
        log "$label is already at the latest $branch ($(echo "$cur" | cut -c1-9))"
        return 1
    fi
    count="$(git -C "$path" rev-list --count "$cur..$new")"
    log "$label: $count new commit(s) on $branch"
    printf '    %s..%s\n' "$(echo "$cur" | cut -c1-9)" "$(echo "$new" | cut -c1-9)"
    git -C "$path" log --oneline --no-decorate "$cur..$new" | head -30 | sed 's/^/      /'
    [ "$count" -gt 30 ] && printf '      ... and %s more\n' "$((count - 30))"

    # Surface the changes most likely to affect us.
    local touched
    touched="$(git -C "$path" diff --name-only "$cur..$new" -- \
        'extractor/src/main/java/org/schabi/newpipe/extractor/services/youtube/**' \
        'extractor/src/main/java/org/schabi/newpipe/extractor/stream/**' \
        'extractor/src/main/java/org/schabi/newpipe/extractor/downloader/**' | wc -l | tr -d ' ')"
    [ "$touched" -gt 0 ] && printf '    %s file(s) changed under youtube/, stream/ or downloader/\n' "$touched"

    # A build-file change can move the toolchain or add a dependency our
    # android/build.gradle does not declare yet.
    if git -C "$path" diff --name-only "$cur..$new" | grep -qE '(^|/)(build\.gradle(\.kts)?|libs\.versions\.toml|settings\.gradle(\.kts)?)$'; then
        warn "$label build files changed — re-check dependencies and toolchain in android/build.gradle"
        git -C "$path" diff "$cur..$new" -- '*build.gradle*' '*libs.versions.toml' | head -60 | sed 's/^/      /'
    fi
    return 0
}

# $1 = path, $2 = label, shift 2 = required file list
verify_api() {
    local path="$1" label="$2"; shift 2
    local missing=0
    for f in "$@"; do
        [ -f "$path/$f" ] || { printf '    MISSING  %s\n' "$f"; missing=1; }
    done
    [ "$missing" -eq 0 ] || die "$label removed or moved files the plugin depends on (listed above).
     Re-pin to the previous commit and adapt the wrapper before updating."
    log "$label API surface intact (${#} file(s) checked)"
}

PP="submodules/PipePipeExtractor"
NP="submodules/NewPipeExtractor"

[ -f "$PP/settings.gradle" ] || die "Submodules not initialised. Run: npm run extractors:init"

if [ "$CHECK_ONLY" -eq 1 ]; then
    [ "$ONLY" = "newpipe" ] || show_incoming "$PP" "PipePipeExtractor" main || true
    [ "$ONLY" = "pipepipe" ] || show_incoming "$NP" "NewPipeExtractor" dev || true
    log "Dry run: nothing changed. Re-run without --check to update."
    exit 0
fi

# Remember where we were, so a failed update is recoverable.
OLD_PP="$(git -C "$PP" rev-parse HEAD)"
OLD_NP="$(git -C "$NP" rev-parse HEAD)"
rollback() {
    warn "Rolling submodules back to their previous pins"
    git -C "$PP" checkout --quiet "$OLD_PP"
    git -C "$NP" checkout --quiet "$OLD_NP"
}

updated=0
if [ "$ONLY" != "newpipe" ] && show_incoming "$PP" "PipePipeExtractor" main; then
    git -C "$PP" checkout --quiet "origin/main"
    updated=1
fi
if [ "$ONLY" != "pipepipe" ] && show_incoming "$NP" "NewPipeExtractor" dev; then
    git -C "$NP" checkout --quiet "origin/dev"
    updated=1
fi

if [ "$updated" -eq 0 ]; then
    log "Nothing to update."
    exit 0
fi

trap 'rollback' ERR

log "Checking the extractor API the plugin depends on still exists"
verify_api "$PP" "PipePipeExtractor" "${PIPEPIPE_API[@]}"
verify_api "$NP" "NewPipeExtractor" "${NEWPIPE_API[@]}"

log "Checking the divergences recorded in docs/DIVERGENCES.md still hold"
# Run before the rebuild: a changed divergence explains a build failure that
# would otherwise look mysterious, and some divergences never fail the build
# at all.
if ! "$ROOT/scripts/check-divergences.sh"; then
    die "Divergences changed. Fix the code and update docs/DIVERGENCES.md before continuing.
     Pins have been rolled back."
fi

log "Rebuilding both extractors"
"$ROOT/scripts/build-extractors.sh"

log "Compiling the plugin against the new jars"
( cd "$ROOT/android" && ./gradlew --no-daemon assembleDebug )

trap - ERR

git add "$PP" "$NP"
log "Update succeeded. New pins (staged, not committed):"
git -C "$PP" log --oneline -1 | sed 's/^/    PipePipeExtractor  /'
git -C "$NP" log --oneline -1 | sed 's/^/    NewPipeExtractor   /'
cat <<'EOF'

Next steps — the build passing does not prove extraction still works:
  1. Walk docs/DIVERGENCES.md for the dependency you just bumped. Some divergences
     fail silently, not at compile time — exception matching by simple name
     (section 10) degrades into pointless retries with no error at all.
  2. Run the example app against a few real videos, including one SABR-only.
  3. Confirm the fallback still engages: force the primary to fail and check
     that `attempts` reports newpipe taking over.
  4. Commit the pin bump with a note on what upstream changed.
EOF
