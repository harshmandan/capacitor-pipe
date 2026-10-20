#!/usr/bin/env bash
#
# Obfuscate the WebView JavaScript assets.
#
#   tools/js/*.js  ->  android/src/main/assets/*.js
#
# The output is GENERATED but COMMITTED, exactly like android/libs/*.jar: an npm
# tarball ships android/src/main/assets/ and a consuming app never runs our npm
# scripts, so the obfuscated file has to be in the tree. Regenerate with
# `npm run assets:build`, never by hand.
#
# Why the source lives outside assets/: these files are ports of PipePipeClient
# and get re-synced from upstream (see CLAUDE.md). Obfuscating in place would
# turn every future upstream fix into an archaeology exercise. Readable source
# in tools/js/, obfuscated artefact in assets/.

set -euo pipefail

cd "$(dirname "$0")/.."

SRC_DIR="tools/js"
OUT_DIR="android/src/main/assets"

mkdir -p "$OUT_DIR"

#
# Names that MUST survive mangling. Each is a cross-language contract that the
# compiler cannot see, so getting this list wrong produces a WebView that loads
# fine and then silently never mints a token.
#
#   pipepipeSabr*  - invoked by name from Kotlin via evaluateJavascript
#                    (PipeBotGuardMinter / PipeWebViewRuntime)
#
# Property names are deliberately NOT mangled at all. They carry three separate
# contracts: the @JavascriptInterface method names on the Kotlin Bridge
# (bridge.onSabrLocalDom*), Google's own challenge payload keys
# (interpreterJavascript, privateDoNotAccessOrElseSafeScriptWrappedValue,
# globalName, program) and BotGuard's VM surface (vm.a, asyncSnapshotFunction).
#
RESERVED='["pipepipeSabrRunBotguard","pipepipeSabrCreateMinter","pipepipeSabrObtainPoToken","pipepipeSabrDeleteSession"]'

for src in "$SRC_DIR"/*.js; do
    name="$(basename "$src")"
    out="$OUT_DIR/$name"

    #
    # toplevel:true renames the internal helpers (loadBotGuard, snapshot,
    # runBotGuard, ...) which is most of the descriptive value an analyst gets.
    #
    # There is NO module/IIFE wrapping and no "use strict", and that is load
    # bearing rather than laziness: runBotGuard does `var root = this` and
    # relies on being called as a plain function so `this` is window. Wrapping
    # it, or letting a tool add strict mode, makes `this` undefined and the
    # whole BotGuard path dies at the first property access.
    #
    npx terser "$src" \
        --compress \
        --mangle "toplevel=true,reserved=$RESERVED" \
        --format "comments=false" \
        --output "$out"

    printf '  %-24s %6s B -> %6s B\n' \
        "$name" "$(wc -c <"$src" | tr -d ' ')" "$(wc -c <"$out" | tr -d ' ')"
done

#
# Fail loudly rather than shipping a readable asset. Every name below is one the
# file is worth obfuscating FOR, so its survival means mangling silently did not
# happen -- the exact failure this script exists to prevent.
#
# Matched as whole words only. Terser does not touch STRING literals, so
# "[BotGuardClient]: ..." in the thrown diagnostics survives by design and a
# naive substring grep would flag it forever. Those messages are deliberately
# kept: they leak nothing an analyst does not already get from the
# jnn-pa.googleapis.com endpoint, and they are the only field diagnostics on a
# notoriously fragile path.
#
for leaked in loadBotGuard createPoTokenMinter obtainPoToken runBotGuard pipepipeBridge; do
    if grep -qE "\b$leaked\b *[(=]" "$OUT_DIR"/*.js; then
        echo "ERROR: '$leaked' survived into $OUT_DIR — mangling did not take effect" >&2
        exit 1
    fi
done

# Conversely, the contract names MUST still be there.
for required in pipepipeSabrRunBotguard pipepipeSabrCreateMinter \
                pipepipeSabrObtainPoToken pipepipeSabrDeleteSession; do
    if ! grep -q "$required" "$OUT_DIR"/*.js; then
        echo "ERROR: entry point '$required' was mangled away — Kotlin calls it by name" >&2
        exit 1
    fi
done

echo "assets: obfuscated, entry points intact"
