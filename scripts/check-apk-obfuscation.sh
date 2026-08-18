#!/usr/bin/env bash
#
# Assert what a RELEASE APK actually gives away.
#
#   scripts/check-apk-obfuscation.sh [path/to/app-release.apk]
#
# Defaults to the example app's release APK. Build it first:
#
#   cd example-app/android && ./gradlew :app:assembleRelease
#
# Why a script over the APK rather than a unit test: every guarantee here is a
# property of the SHIPPED artefact after R8 and asset packaging have run. None of
# it is observable from source, and all of it regresses silently — a dropped
# consumer rule, a terser flag, a keep rule widened "just to make the build go
# green".
#
# This checks the floor, not the ceiling. Passing does NOT mean the APK conceals
# how extraction works; strings and assets leak plenty (see the KNOWN-VISIBLE
# section at the bottom, which is asserted too, so the day it changes we notice).

set -euo pipefail

cd "$(dirname "$0")/.."

APK="${1:-example-app/android/app/build/outputs/apk/release/app-release-unsigned.apk}"

if [ ! -f "$APK" ]; then
    echo "no APK at $APK" >&2
    echo "build one: cd example-app/android && ./gradlew :app:assembleRelease" >&2
    exit 2
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

unzip -qo "$APK" 'classes*.dex' 'assets/*' -d "$WORK"

# LC_ALL=C so grep treats the dex as bytes. Without it, macOS grep aborts with
# "Illegal byte sequence" on the first non-UTF-8 run and silently checks nothing.
export LC_ALL=C

failures=0
checks=0

# dex_has <pattern> — is this byte sequence anywhere in the dex?
dex_has() {
    grep -aqc "$1" "$WORK"/classes*.dex 2>/dev/null
}

want_absent() {
    checks=$((checks + 1))
    if dex_has "$1"; then
        printf '  \033[31mFAIL\033[0m  %s\n        found %s in the dex\n' "$2" "$1"
        failures=$((failures + 1))
    else
        printf '  \033[32mok\033[0m    %s\n' "$2"
    fi
}

want_present() {
    checks=$((checks + 1))
    if dex_has "$1"; then
        printf '  \033[32mok\033[0m    %s\n' "$2"
    else
        printf '  \033[31mFAIL\033[0m  %s\n        expected %s in the dex\n' "$2" "$1"
        failures=$((failures + 1))
    fi
}

printf '\n\033[1mR8 ran and renamed things\033[0m\n'

# The canary. NewPipe's entry class is referenced from our engine, so if R8 had
# not run -- or a blanket keep had crept in -- this name would survive. It is the
# single cheapest proof that obfuscation is on.
want_absent 'Lorg/schabi/newpipe/extractor/NewPipe;' \
    'extractor class names are obfuscated'
want_absent 'Link/harsh/plugins/pipe/engine/PipePipeEngine;' \
    'our engine class names are obfuscated'

# ...but the Capacitor bridge surface MUST survive, because the bridge matches it
# by name at runtime. Obfuscating these compiles fine and then fails every call
# from JavaScript with "not implemented".
want_present 'Link/harsh/plugins/pipe/PipePlugin;' \
    'the Capacitor plugin class is kept (JS calls it by name)'

printf '\n\033[1mRhino was narrowed, not blanket-kept\033[0m\n'

# Dead in interpreted mode -- see proguard-rules.pro. Their return means the
# narrowed keep rule was reverted to org.mozilla.javascript.**.
want_absent 'Lorg/mozilla/javascript/optimizer/' \
    'Rhino optimizer is shrunk away'
want_absent 'Lorg/mozilla/javascript/tools/' \
    'Rhino tools/debugger are shrunk away'
want_absent 'Lorg/mozilla/javascript/engine/' \
    'Rhino JSR-223 binding is shrunk away'

# ...while the reflection targets stay. Rhino resolves these by STRING through
# LazilyLoadedCtor, so losing one is a runtime ClassNotFoundException in the
# fallback engine, which only runs after the primary has already failed.
want_present 'Lorg/mozilla/javascript/Context;' \
    'Rhino Context survives (resolved reflectively)'
want_present 'Lorg/mozilla/javascript/ScriptRuntime;' \
    'Rhino ScriptRuntime survives (resolved reflectively)'

printf '\n\033[1mThe WebView asset shipped obfuscated\033[0m\n'

ASSET="$WORK/assets/sabr_po_token.js"
checks=$((checks + 1))
if [ ! -f "$ASSET" ]; then
    printf '  \033[31mFAIL\033[0m  sabr_po_token.js is in the APK\n'
    failures=$((failures + 1))
else
    printf '  \033[32mok\033[0m    sabr_po_token.js is in the APK\n'

    for identifier in loadBotGuard createPoTokenMinter obtainPoToken; do
        checks=$((checks + 1))
        if grep -qE "\b$identifier\b *[(=]" "$ASSET"; then
            printf '  \033[31mFAIL\033[0m  asset identifier %s is mangled\n' "$identifier"
            printf '        the READABLE source shipped — run bun run assets:build\n'
            failures=$((failures + 1))
        else
            printf '  \033[32mok\033[0m    asset identifier %s is mangled\n' "$identifier"
        fi
    done

    # Mangled but still callable: Kotlin invokes these four by name.
    for entry in pipepipeSabrRunBotguard pipepipeSabrCreateMinter \
                 pipepipeSabrObtainPoToken pipepipeSabrDeleteSession; do
        checks=$((checks + 1))
        if grep -q "$entry" "$ASSET"; then
            printf '  \033[32mok\033[0m    asset entry point %s survives\n' "$entry"
        else
            printf '  \033[31mFAIL\033[0m  asset entry point %s was mangled away\n' "$entry"
            failures=$((failures + 1))
        fi
    done
fi

printf '\n\033[1mKNOWN-VISIBLE — asserted so a change is noticed, not because it is good\033[0m\n'

# R8 renames symbols; it never touches string literals. These are in the dex in
# plain text and no keep rule can change that. They are asserted PRESENT
# deliberately: if one disappears, either an extractor stopped using it (worth
# knowing) or string encryption landed (also worth knowing) -- and this file
# should then be updated rather than the check deleted.
want_present 'youtubei/v1' \
    'InnerTube endpoint is plaintext (R8 never encrypts strings)'
want_present 'adaptiveFormats' \
    'InnerTube payload keys are plaintext'

printf '\n'
if [ "$failures" -eq 0 ]; then
    printf '\033[32mAll %s APK obfuscation checks hold.\033[0m\n' "$checks"
    exit 0
fi
printf '\033[31m%s of %s APK obfuscation checks FAILED.\033[0m\n' "$failures" "$checks"
exit 1
