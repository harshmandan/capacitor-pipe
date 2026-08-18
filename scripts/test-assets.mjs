/*
 * Functional test for the OBFUSCATED WebView asset.
 *
 * Deliberately runs android/src/main/assets/sabr_po_token.js — the generated,
 * shipped artefact — and not the readable source in tools/js/. A test against
 * the source would pass forever while the thing we actually ship was broken by
 * a terser upgrade or a mangling-option change.
 *
 * The BotGuard VM is stubbed. We are not testing Google's attestation; we are
 * testing that mangling preserved the three contracts that cross a language
 * boundary and that no compiler can check:
 *
 *   1. the pipepipeSabr* entry points Kotlin invokes by name
 *   2. the bridge.onSabrLocalDom* callbacks (@JavascriptInterface on the Kotlin
 *      Bridge, matched by name at runtime)
 *   3. the payload keys Google and BotGuard own (globalName, program,
 *      interpreterJavascript, asyncSnapshotFunction, ...)
 *
 * Run: bun run test:assets
 */

import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import assert from "node:assert/strict";

const here = dirname(fileURLToPath(import.meta.url));
const ASSET = join(here, "..", "android", "src", "main", "assets", "sabr_po_token.js");

const source = readFileSync(ASSET, "utf8");

/* ---------------------------------------------------------------- shape --- */

const failures = [];
function check(name, condition, detail) {
  if (condition) return;
  failures.push(`${name}: ${detail}`);
}

// The asset must actually be obfuscated. If a future change drops the mangle
// step, everything below would still pass — so assert it explicitly.
for (const identifier of ["loadBotGuard", "createPoTokenMinter", "obtainPoToken"]) {
  check(
    "mangled",
    !new RegExp(`\\b${identifier}\\b *[(=]`).test(source),
    `internal name '${identifier}' is still present — obfuscation did not run`,
  );
}

// runBotGuard relies on `this` being window, which means the file must NOT be
// wrapped in a module/IIFE and must NOT be in strict mode. Both would make
// `this` undefined and kill BotGuard at the first property access.
check("no strict mode", !source.includes("use strict"), "'use strict' was added — `this` will not be window");
check("this preserved", source.includes("this"), "`this` disappeared — the window binding was lost");

/* ----------------------------------------------------------- behaviour --- */

// Minimal window/global stub. The asset is non-module script text, so it is
// evaluated with indirect eval in the global scope, exactly as the WebView does.
const calls = [];
const bridge = {
  onSabrLocalDomRunBotguardResult: (...a) => calls.push(["runBotguardResult", ...a]),
  onSabrLocalDomMinterReady: (...a) => calls.push(["minterReady", ...a]),
  onSabrLocalDomObtainPoTokenResult: (...a) => calls.push(["poToken", ...a]),
  onSabrLocalDomObtainPoTokenError: (...a) => calls.push(["poTokenError", ...a]),
  onSabrLocalDomJsInitializationError: (...a) => calls.push(["initError", ...a]),
};

globalThis.window = globalThis;
globalThis.PipePipeWebViewBridge = bridge;

// Stand-in for Google's BotGuard VM: `vm.a(...)` hands back the four functions
// through a callback and returns [syncSnapshotFunction], and the async snapshot
// pushes a minter into webPoSignalOutput[0] the way the real one does.
const INTERPRETER_JS = `
  globalThis.TESTVM = {
    a: function (program, vmFunctionsCallback, flag, element, noOp, signals) {
      vmFunctionsCallback(
        function asyncSnapshot(cb, args) {
          var out = args[2];
          out[0] = function getMinter(integrityToken) {
            return function mint(identifier) { return new Uint8Array([1, 2, 3, 4]); };
          };
          cb("BOTGUARD_RESPONSE");
        },
        function shutdown() {},
        function passEvent() {},
        function checkCamera() {}
      );
      return ["SYNC_SNAPSHOT"];
    }
  };
`;

(0, eval)(source);

// Check the entry points exist BEFORE calling them. Without this a mangled-away
// entry point dies with a bare "is not a function" TypeError and a stack trace
// into this file, which reads like a broken test rather than a broken asset.
for (const entry of [
  "pipepipeSabrRunBotguard",
  "pipepipeSabrCreateMinter",
  "pipepipeSabrObtainPoToken",
  "pipepipeSabrDeleteSession",
]) {
  check("entry point", typeof globalThis[entry] === "function", `'${entry}' is missing — Kotlin calls it by name`);
}

if (failures.length > 0) {
  console.error("FAIL — obfuscated asset is broken:");
  for (const f of failures) console.error(`  - ${f}`);
  process.exit(1);
}

const challengeData = {
  globalName: "TESTVM",
  program: "PROGRAM",
  interpreterJavascript: {
    privateDoNotAccessOrElseSafeScriptWrappedValue: INTERPRETER_JS,
  },
};

globalThis.pipepipeSabrRunBotguard("session-1", "event-1", challengeData);

// loadBotGuard polls on a 1ms interval before reporting readiness.
await new Promise((resolve) => setTimeout(resolve, 60));

const botguard = calls.find((c) => c[0] === "runBotguardResult");
check("runBotguard", botguard !== undefined, `no result callback; got ${JSON.stringify(calls)}`);
check("runBotguard sessionId", botguard?.[1] === "session-1", `wrong sessionId: ${botguard?.[1]}`);
check("runBotguard response", botguard?.[2] === "BOTGUARD_RESPONSE", `wrong response: ${botguard?.[2]}`);

globalThis.pipepipeSabrCreateMinter("session-1", "INTEGRITY_TOKEN");
check("minter ready", calls.some((c) => c[0] === "minterReady"), `minter never became ready; got ${JSON.stringify(calls)}`);

globalThis.pipepipeSabrObtainPoToken("session-1", "ident-1", new Uint8Array([9]));
const token = calls.find((c) => c[0] === "poToken");
check("poToken", token !== undefined, `no token; got ${JSON.stringify(calls.map((c) => c[0]))}`);
// The Kotlin side parses this comma-joined form back into bytes.
check("poToken encoding", token?.[3] === "1,2,3,4", `wrong byte encoding: ${token?.[3]}`);

// Deleting a session must make a subsequent mint fail rather than silently
// reuse a stale minter.
globalThis.pipepipeSabrDeleteSession("session-1");
globalThis.pipepipeSabrObtainPoToken("session-1", "ident-2", new Uint8Array([9]));
check(
  "session deleted",
  calls.some((c) => c[0] === "poTokenError" && c[2] === "ident-2"),
  "minting succeeded after the session was deleted",
);

/* --------------------------------------------------------------- report --- */

if (failures.length > 0) {
  console.error("FAIL — obfuscated asset is broken:");
  for (const f of failures) console.error(`  - ${f}`);
  process.exit(1);
}

console.log(`assets: obfuscated asset passes ${9} contract checks`);
