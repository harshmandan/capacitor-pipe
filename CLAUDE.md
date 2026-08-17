# capacitor-pipe

Capacitor plugin for YouTube extraction on Android. Two extraction engines run
behind one API: **PipePipeExtractor is primary, NewPipeExtractor is the
fallback.** Supports full SABR playback, consumable from Media3/ExoPlayer or any
web player.

Formerly `capacitor-npe`. Renamed because NewPipe is no longer the only engine.

## Licence

GPL-3.0-or-later, and this is not optional. Both extractors are GPL-3.0, and we
link against them, so the plugin and anything distributing it inherit GPL-3.0.
The repo previously declared MIT while already depending on GPL-3.0
NewPipeExtractor; that was wrong and is now fixed. Do not reintroduce a
permissive licence header.

## Layout

```
src/                          TypeScript API (definitions.ts is the contract)
android/src/main/kotlin/ink/harsh/plugins/pipe/
                              plugin, engine chain, SABR
android/libs/                 built extractor jars — SHIPPED, generated, not hand-edited
submodules/PipePipeExtractor  pinned upstream, pristine
submodules/NewPipeExtractor   pinned upstream, pristine
tools/shade/                  relocates NewPipeExtractor into a private namespace
tools/force-java17.init.gradle  toolchain override for PipePipe (see Gotcha 1)
scripts/                      build / update / verify
```

**Submodules are never edited.** No patches, no vendored forks, no deleted
services. An upstream update is a pin fast-forward, never a merge. If you find
yourself wanting to change extractor source, wrap it on our side instead.

## Build

```bash
npm run extractors:init      # git submodule update --init --recursive
npm run extractors:build     # build both extractors -> android/libs/*.jar
npm run build                # TypeScript + docgen
npm run verify:android       # compile the Android module
```

`android/libs/*.jar` is generated but **committed and published** — npm tarballs
cannot carry git submodules, so a consuming app never sees `submodules/`. The
submodules are the development path; `android/libs/` is the published path.
Regenerate with `npm run extractors:build`, never by hand.

## Updating the extractors

```bash
scripts/update-extractors.sh --check     # what is new upstream, changes nothing
scripts/update-extractors.sh             # update, rebuild, verify, stage the pin
scripts/update-extractors.sh --only pipepipe
```

The script fast-forwards the pins, checks that the extractor API we call still
exists, rebuilds, compiles the plugin, and rolls back on failure. It stages the
pin bump rather than committing it.

**A green build does not mean extraction still works.** Both engines scrape a
hostile, changing target; the compiler cannot see that. After every update, run
the example app against real videos including a SABR-only one, and confirm the
fallback still engages.

`PIPEPIPE_API` / `NEWPIPE_API` in the script list the upstream files we depend
on. Add to them whenever the wrapper starts calling something new.

## Divergences — required workflow

[DIVERGENCES.md](DIVERGENCES.md) records every point where our code forks to
handle the two engines differently, with file and symbol anchors and what to
re-check per dependency. `scripts/check-divergences.sh` asserts them against both
submodules' source (29 checks), and `update-extractors.sh` runs it before
rebuilding, rolling the pins back if anything changed.

```bash
npm run extractors:check     # assert the recorded divergences still hold
```

**Whenever you write code that treats the two engines differently, add it to
DIVERGENCES.md in the same change** — a new section, plus a check in
`check-divergences.sh` if it can be asserted mechanically. This is not
documentation housekeeping; several divergences fail *silently*. Section 10's
exception matching by simple name degrades into pointless retries with no error,
no log and no failed build, and the only thing that catches it is the checker.

Triggers for a new entry — if you find yourself doing any of these, record it:

- a per-engine `if`, or a value hardcoded for one engine because the other lacks it
- a method that exists on one fork and not the other, or with a different
  signature or return type
- an `instanceof` or string-matched type name that crosses the relocation boundary
- a field present in one engine's mapper output and absent from the other's
- a dependency, toolchain or build step that applies to only one submodule

When upstream changes and a check goes red, do all three: fix the code, update
the DIVERGENCES.md section, re-run until clean. Never silence a check to make it
pass — if the forks genuinely converged, remove the code branch first, then the
row, then the check.

---

# Gotchas

These have all cost real debugging time. Read before touching the build.

### 1. Do not "just bump AGP" to fix bytecode errors

PipePipeExtractor pins `JavaLanguageVersion.of(25)` and JitPack builds it on
openjdk25, producing **class-file major 69**. AGP 8 / D8 refuses to dex that.

The tempting fix — bump our AGP to 9 like PipePipe does — is wrong here. A
Capacitor plugin's Android module and its jars are dexed by the **consuming
app's** AGP, not ours. Shipping major 69 would make AGP 9 a hard requirement for
every app that installs this plugin, and Capacitor 8 apps ship AGP 8.13.0.
Bumping our own AGP changes only our local `verify:android`.

Instead `tools/force-java17.init.gradle` overrides the toolchain to 17, applied
via `-I` so the submodule stays pristine. This costs nothing: the source uses no
Java 21+ syntax or APIs — verified, no records, no sealed types, no
pattern-matching switch, and the only `getFirst`/`reversed` hits are
`Pair.getFirst()` and `Comparator.reversed()` (Java 8), not
`SequencedCollection`. All 559 classes compile clean to major 61.

`scripts/verify-bytecode.sh` enforces this on every build. If you ever *do* need
JDK 25 semantics, compile with JDK 25 and set `options.release = 17` — same
result, no AGP change anywhere.

### 2. The two extractors occupy the same namespace

PipePipeExtractor is a 2022 hard fork of NewPipeExtractor that **kept the
original package and class names**. Both ship
`org.schabi.newpipe.extractor.NewPipe`, `ServiceList`, `StreamInfo`. PipePipe's
own build proves they are interchangeable, not composable:

```gradle
substitute module('com.github.TeamNewPipe:NewPipeExtractor') using project(':extractor')
```

Worse than a duplicate-class error: `NewPipe` holds a **global static**
downloader, so on one classpath one engine silently wins and the "fallback" is
an illusion. `tools/shade/` therefore relocates NewPipeExtractor to
`ink.harsh.pipe.shaded.org.schabi.newpipe.*`, giving two independent statics and
a fallback that genuinely falls back.

`scripts/build-extractors.sh` fails the build if relocation did not take effect.
If you change the relocation prefix, update `NewPipeEngine.kt` to match.

### The codebase is Kotlin

All plugin sources are Kotlin (`android/src/main/kotlin/`). There is no Java
left under `android/src`.

One consequence to be aware of when porting fixes: six files are ports of
**Java** sources in PipePipeClient — `PipeSabrBridge`, `PipeSabrManifest`,
`PipeSabrRequestCoordinator`, `PipeSabrAttestationRetry`, `PipeWebViewRuntime`
and the Media3 `PipeSabrDataSource`. Re-syncing an upstream fix into those is a
translation, not a diff. The `youtube/` attestation files are ports of Kotlin
upstream and do still diff cleanly.

Two Kotlin-specific hazards found during the migration, worth knowing before
touching the mappers:

- `VideoStream` declares **public fields** `resolution` and `isVideoOnly`
  alongside an `isVideoOnly()` getter. Kotlin property syntax
  (`stream.resolution`) binds to the deprecated *field*, not the getter. Call
  `getResolution()` / `isVideoOnly()` explicitly. These are the only shadowing
  fields in either fork — audited.
- `String.split()` keeps trailing empty strings in Kotlin and drops them in
  Java. Every length-sensitive split in `PipeSabrServer` uses
  `.dropLastWhile { it.isEmpty() }` to preserve the original behaviour.

Consequence: mapping code cannot be shared between engines. The relocated types
are unrelated to the originals as far as the compiler is concerned, so each
engine has its own mapper. That duplication is deliberate — do not try to
generify it.

### 3. Dependency collisions between the two forks

| Dependency | PipePipe | NewPipe | Resolution |
|---|---|---|---|
| protobuf | `protobuf-java` (full) | `protobuf-javalite` | Collide on `com.google.protobuf.*`, not co-installable. NewPipe's is bundled + relocated. |
| nanojson | commit `1d9e1aea` | commit `e9d656dd` | Same artifact, different pins. NewPipe's is bundled + relocated. |
| jsoup | 1.22.2 | 1.22.2 | Identical — shared, not bundled. |
| rhino | not used | 1.8.1 | NewPipe only. **Stay on 1.8.1** — 1.9.0 requires minSdk 26. |
| okhttp | **5.4.0, in its public API** | test-only | PipePipe leaks okhttp into the `Downloader` contract: `CancellableCall(okhttp3.Call)`. Our PipePipe `Downloader` must return okhttp-backed calls, and the version must match what the extractor was compiled against. NewPipe has no such coupling. |
| wire | 6.4.1, used | — | Needed at runtime (18 references). Must declare `wire-runtime`. |

### 4. Capacitor 8 build requirements

minSdk 24, compileSdk 36, targetSdk 36, AGP 8.13.0, Gradle 8.14.3, Kotlin
2.2.20. Capacitor **8.5 is iOS-only** (UIScene lifecycle) — no Android impact
here, and this plugin has no iOS side at all.

### 5. PipePipe's YouTube path depends on a remote service

PipePipe replaced NewPipe's local Rhino execution with
`YoutubeApiDecoder.decodeSignature(...)` against a **PipePipe-hosted** decoder.
Signature and `n`-parameter deciphering therefore require reaching their
infrastructure. NewPipe still deciphers locally in Rhino.

This is the main reason the fallback earns its keep: the two engines fail for
genuinely different reasons. It is also a privacy consideration worth surfacing
to users.

### 6. SABR is a session, not a URL

For SABR-delivered video there is **no fetchable URL**. Bytes only arrive by
driving the protocol, so `extractStreamInfo` sets `requiresSabr: true` and the
stream arrays contain nothing playable. Callers must open a SABR session.

The extractor deliberately does not mint tokens: per upstream docs it "never
mints a token and never decodes a pixel". The host supplies a PO token minted by
running BotGuard in a **real WebView** (integrity token ~12h, per-video token
derived from it). There is no offline path — the signing secret is Google's and
stays server-side. Do not attempt to forge or simulate it.

### 7. `mweb` means "prefer SABR", not "support SABR"

`NewPipe.setYoutubePlayerClient(...)` selects the extraction path globally:

```java
if ("mweb".equals(client) && !live && hasSabrStreamingUrl())
    buildSabrStreams(...);      // wins whenever a SABR URL exists
else
    extractDirectFormats(...);  // progressive / DASH
```

Modern player responses usually include `serverAbrStreamingUrl` **alongside**
ordinary formats, so pinning `mweb` globally forces a session driver, a PO token
and a WebView onto videos that had a perfectly good URL. The extractor's default
is `visionos`, not `mweb`.

`PipePipeEngine` therefore extracts with the default client first and retries on
`mweb` only when that yields nothing playable. The setting is global mutable
state, so that two-pass sequence holds a lock and always restores the default.

Historical note: PR #69 (which added SABR) shipped a `FORCE_SABR_FOR_TESTING`
flag defaulting to `true`, routing *everything* through SABR. It has since been
removed, and our pin is well past it — but if you ever move the pin backwards,
check for it.

**Measured 2026-08-17:** of 12 videos reported as SABR-broken in
`InfinityLoop1308/PipePipe#2330`, **none** are SABR-only today — all extract
direct formats via `visionos`. PipePipe's default client is currently dodging
SABR enforcement, the same client-hopping strategy NewPipe took in PR #1508.

Two consequences. First, the direct-first policy is doing real work: SABR is
currently a contingency for when that client stops working, not the everyday
path. Second, **you do not need a SABR-only video to test SABR** — a session
forces `mweb`, which takes the SABR path for any video carrying a
`serverAbrStreamingUrl`. Re-run `SabrCandidateProbeTest` to re-measure; the
answer changes as YouTube moves.

### 8. Upstream docs lag the code

The published docs describe `pumpOnce(localization)` and `fetchSegment(...)`.
The actual API is `requestOnce(YoutubeSabrRequest, SegmentConsumer)` returning
`RequestResult`. **Trust the source, not the docs.** Same for the services list
and anything else load-bearing.

### 9. okhttp's RequestBody MediaType silently overrides your Content-Type

`BridgeInterceptor` writes `RequestBody.contentType()` over a `Content-Type`
header already set on the request. So this:

```java
RequestBody.create(bytes, MediaType.parse("application/json; charset=utf-8"));
builder.header("Content-Type", "application/json+protobuf");   // silently lost
```

sends `application/json`, not what you asked for. It cost hours: Google's
attestation endpoint answers **200** for `application/json+protobuf` and **404**
for `application/json`, so the symptom was a 404 that looked like a wrong URL.
The `$` in `/$rpc/` is a red herring — okhttp does not encode it.

`HttpCore` therefore builds bodies with a **null** MediaType and lets the
caller's header stand, which is what PipePipeClient's downloader does. Do not
"helpfully" reattach a MediaType.

### 10. The loopback SABR server needs a cleartext exemption

SABR media is served from `http://127.0.0.1:<port>`, and Android blocks
cleartext from API 28. Without an exemption the failure is
`UnknownServiceException: CLEARTEXT communication to 127.0.0.1 not permitted by
network security policy` — which reads like a bug in the server rather than a
policy problem.

The library ships `res/xml/pipe_network_security_config.xml` allowing cleartext
to loopback only. Most Capacitor apps already permit localhost, since Capacitor
serves its own assets locally; apps that do not must point
`android:networkSecurityConfig` at it or merge the domain-config in. The
instrumented tests opt in via `src/androidTest/AndroidManifest.xml`.

### 11. One `requestOnce` is not one request

`YoutubeSabrSession.requestOnce` is a single protocol round, and a round
legitimately carries no media: policy-only responses, server-requested backoff,
or a demand for a fresh PO token. Treating one call as one logical request
"works" for the first few segments and then quietly stops — on device it died
around segment 12 and seeks hung until the socket timed out.

`PipeSabrRequestCoordinator` is what makes a request a request. It loops until
real progress, honours backoff with bounded continuous budgets, retries empty
responses, and — critically — catches `SabrAttestationException` to mint a fresh
token and inject it with `session.setPoToken(...)`, resetting its budget
whenever media arrives. **The token minted at session start does not last the
whole session.**

Never call `requestOnce` directly from the bridge. Note also that "progress"
must be defined per call site: for a segment fetch it is *that* segment
arriving, since the server interleaves both tracks and a round advancing only
the counterpart would otherwise look like success.

Verified: 12 sequential segments (30 MB), forward seek to 30/32, and backward
seek 16 → 1 all succeed with the coordinator and all fail without it.

### 12. `ComposeView.apply { setContent { Content() } }` recurses forever

`ComposeView` has its own `protected @Composable fun Content()`. Inside an
`apply` block it is the implicit receiver, so a composable of yours named
`Content()` resolves to **ComposeView's**, which invokes the content lambda,
which calls it again:

```kotlin
ComposeView(activity).apply { setContent { Content() } }   // StackOverflowError
```

It compiles cleanly and the crash blames Compose internals
(`RecomposeScopeImpl.recordRead`), so the trace points nowhere useful. Assign
the view to a local and call `view.setContent { ... }` instead, keeping
ComposeView out of receiver scope entirely — and give the composable a
distinctive name.

### 13. The example app compiled a stale copy of the plugin

`example-app/package.json` used `"capacitor-pipe": "file:.."`. pnpm resolves
`file:` by **copying into its store**, so every edit to the plugin was invisible
to the app until `pnpm install` ran again — builds succeeded and the APK simply
contained old code. Diagnosing a fixed bug that "won't go away" starts here.

Now `"link:.."`, which symlinks the working tree. Verify with:

```bash
ls -la example-app/node_modules | grep capacitor-pipe   # -> ../..
```

### 14. PipePipeClient lives on Codeberg

The reference SABR player integration is **not** on GitHub. `PipePipe` on GitHub
is a superproject whose `PipePipeClient` submodule points at
`codeberg.org/NullPointerException/PipePipeClient` (branch `dev`). The GitHub
mirror 404s. Useful files there: `player/datasource/Sabr*.java`,
`youtube/LocalDomPoTokenProvider.kt`, `assets/sabr_po_token.js`.

---

# Architecture

## Engine chain

`PipeExtractor` tries engines in order and records every attempt:

```
extractStreamInfo(url)
  -> PipePipeEngine   (org.schabi.newpipe.extractor.*)
  -> NewPipeEngine    (ink.harsh.pipe.shaded.org.schabi.newpipe.extractor.*)
```

Each engine owns its own `Downloader` and its own `NewPipe.init(...)`; they
share no state. Results carry `engine` and an `attempts[]` array so a caller can
see the primary failed even when the call ultimately succeeded — surface that,
don't swallow it.

Only fall through on errors that another engine could plausibly fix. Age-gating,
geo-blocking and private videos fail identically on both; retrying wastes a
round trip and doubles the latency of a guaranteed failure.

## SABR

The core is transport-agnostic, with two adapters, because the plugin must serve
both native and web players:

```
YoutubeSabrInfo (probe) -> YoutubeSabrSession.requestOnce(request, consumer)
   -> PipeSabrBridge      serialises segment demand, ahead-cache, timelines
   -> PipeSabrManifest    synthesises a DASH manifest
        |
        +-- Adapter A: Media3 DataSource      native ExoPlayer / Media3 UI
        +-- Adapter B: loopback HTTP server   any web player (dash.js, Shaka)
```

Both adapters resolve through the same `bridge.awaitSegment(key)`. This mirrors
PipePipe, which synthesises a DASH manifest with `sabr://<formatKey>/<seq>` URIs
and resolves them in a custom `DataSource`; we keep the manifest synthesis and
swap only the transport.

Media3 is **`compileOnly` and runtime-guarded** — apps without Media3 must still
work, falling back to the loopback path. Never make it a hard dependency.

### Why we hand-write a SABR client layer

Recurring question, so: the extractor **is** used as a dependency. Every SABR
protocol class — `YoutubeSabrSession`, the UMP reader, the proto codec, the
segment collector, the mp4/webm index parsers — is called, never copied.

What the extractor deliberately will not do is turn segments into something
playable. Per its own docs it "understands and drives the protocol; it does not
mint tokens and does not render media." That client half lives in
**PipePipeClient**, which cannot be a dependency: it is an Android *application*
rather than a library, it is Codeberg-only, and its SABR layer is an ExoPlayer
`DataSource`/`MediaSource` that a WebView cannot consume.

So a client layer has to exist here. `PipeSabrBridge` and `PipeSabrManifest` are
**ports** of PipePipeClient's equivalents, carrying attribution headers — ported
rather than reinvented because the buffered-range accounting and segment matching
encode detail that is easy to get subtly wrong. `PipeSabrServer` is new: PipePipe
has no HTTP transport, and that is precisely what makes web players work.

When porting a fix from PipePipeClient, keep the attribution headers accurate and
note any behavioural change in the header, as `PipeSabrBridge` does for its
`awaitSegment` divergence.

Sessions hold a segment cache and a WebView-minted token. They must be closed.

## Conventions

- Android-only. The web implementation is a stub that reports unavailability —
  extraction cannot run in a browser (CORS, and SABR needs a WebView for BotGuard).
- `src/definitions.ts` is the source of truth for the API; run `npm run docgen`
  after changing it, which rewrites the README API section.
- Prefer surfacing structured failure (`attempts`, `errorType`) over throwing.
  Callers need to distinguish "age-restricted" from "both engines are broken".
