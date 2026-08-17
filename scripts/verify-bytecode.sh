#!/usr/bin/env bash
#
# Fail if any class in the given jars targets bytecode newer than Java 17
# (major 61). AGP 8 / D8 rejects anything higher, and because a Capacitor
# plugin is dexed by the consuming app's toolchain, a too-new class here
# becomes a hard AGP-9 requirement for every downstream app.
#
# Usage: verify-bytecode.sh <dir-of-jars|jar> [max-major]
#
set -euo pipefail

TARGET="${1:?usage: verify-bytecode.sh <dir|jar> [max-major]}"
MAX_MAJOR="${2:-61}"

# Built for bash 3.2, which is what macOS ships — no mapfile, no associative arrays.
JARS=()
if [ -d "$TARGET" ]; then
    while IFS= read -r line; do
        JARS+=("$line")
    done < <(find "$TARGET" -name '*.jar' | sort)
else
    JARS=("$TARGET")
fi

[ "${#JARS[@]:-0}" -gt 0 ] || { echo "No jars found in $TARGET" >&2; exit 1; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

fail=0
for jar in "${JARS[@]}"; do
    rm -rf "${TMP:?}/x" && mkdir -p "$TMP/x"
    unzip -qo "$jar" -d "$TMP/x" '*.class' 2>/dev/null || true

    worst=0
    offender=""
    while IFS= read -r class; do
        # Bytes 6-7 of a .class file are the big-endian major version.
        # NR==1 because od may wrap its output across lines.
        major="$(od -An -tu1 -j6 -N2 "$class" | awk 'NR==1{print $1*256+$2; exit}')"
        [ -n "$major" ] || continue
        if [ "$major" -gt "$worst" ]; then
            worst="$major"
            offender="${class#"$TMP"/x/}"
        fi
    done < <(find "$TMP/x" -name '*.class')

    if [ "$worst" -eq 0 ]; then
        printf '    %-40s no classes\n' "$(basename "$jar")"
    elif [ "$worst" -gt "$MAX_MAJOR" ]; then
        printf '    %-40s major %s  <-- TOO NEW (%s)\n' "$(basename "$jar")" "$worst" "$offender"
        fail=1
    else
        printf '    %-40s major %s  ok\n' "$(basename "$jar")" "$worst"
    fi
done

if [ "$fail" -ne 0 ]; then
    echo "Error: bytecode newer than major $MAX_MAJOR would force consuming apps onto AGP 9." >&2
    exit 1
fi
