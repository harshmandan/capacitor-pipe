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

## The four documents, and which answers what

| File                                     | Answers                                                                                                                        |
| ---------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------ |
| this file                                | How to build it, and every trap in doing so.                                                                                   |
| [docs/DIVERGENCES.md](docs/DIVERGENCES.md)         | How the two engines differ **mechanically**. Checkable from source, and partly asserted by a script.                           |
| [docs/EXTRACTION.md](docs/EXTRACTION.md) | What **YouTube** was observed doing, when, and which decision followed. Dated, because the other party changes without asking. |
| [docs/PLAYER.md](docs/PLAYER.md)                   | The optional native player.                                                                                                    |

**A claim about YouTube's behaviour goes in docs/EXTRACTION.md with a date and the
measurement that produced it, or it is not evidence.** A claim about our own two
dependencies goes in docs/DIVERGENCES.md. Putting the first kind in a code comment is
how a finding from last quarter comes to read as a law of nature.

## Layout

```
docs/                         DIVERGENCES · EXTRACTION · PLAYER (the table above)
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

[docs/DIVERGENCES.md](docs/DIVERGENCES.md) records every point where our code forks to
handle the two engines differently, with file and symbol anchors and what to
re-check per dependency. `scripts/check-divergences.sh` asserts them against both
submodules' source (39 checks), and `update-extractors.sh` runs it before
rebuilding, rolling the pins back if anything changed.

```bash
npm run extractors:check     # assert the recorded divergences still hold
```

**Whenever you write code that treats the two engines differently, add it to
docs/DIVERGENCES.md in the same change** — a new section, plus a check in
`check-divergences.sh` if it can be asserted mechanically. This is not
documentation housekeeping; several divergences fail _silently_. Section 10's
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
the docs/DIVERGENCES.md section, re-run until clean. Never silence a check to make it
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

`scripts/verify-bytecode.sh` enforces this on every build. If you ever _do_ need
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
  (`stream.resolution`) binds to the deprecated _field_, not the getter. Call
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

| Dependency | PipePipe                     | NewPipe             | Resolution                                                                                                                                                                                                                                          |
| ---------- | ---------------------------- | ------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| protobuf   | `protobuf-java` (full)       | `protobuf-javalite` | Collide on `com.google.protobuf.*`, not co-installable. NewPipe's is bundled + relocated.                                                                                                                                                           |
| nanojson   | commit `1d9e1aea`            | commit `e9d656dd`   | Same artifact, different pins. NewPipe's is bundled + relocated.                                                                                                                                                                                    |
| jsoup      | 1.22.2                       | 1.22.2              | Identical — shared, not bundled.                                                                                                                                                                                                                    |
| rhino      | not used                     | 1.8.1               | NewPipe only. **Stay on 1.8.1** — 1.9.0 requires minSdk 26.                                                                                                                                                                                         |
| okhttp     | **5.4.0, in its public API** | test-only           | PipePipe leaks okhttp into the `Downloader` contract: `CancellableCall(okhttp3.Call)`. Our PipePipe `Downloader` must return okhttp-backed calls, and the version must match what the extractor was compiled against. NewPipe has no such coupling. |
| wire       | 6.4.1, used                  | —                   | Needed at runtime (18 references). Must declare `wire-runtime`.                                                                                                                                                                                     |

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
flag defaulting to `true`, routing _everything_ through SABR. It has since been
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
must be defined per call site: for a segment fetch it is _that_ segment
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

### 15. `displayMetrics` is not the overlay's coordinate space

The player overlay is a square centred in the Activity's **content view**. A dock
rect arrives from `getBoundingClientRect`, so it is in **WebView** coordinates.
The fullscreen target is in **display** coordinates. Three spaces, and system
bars inset some of them and not others.

Deriving the origin from `displayMetrics` conflated all three and produced two
bugs that looked unrelated: the docked video drew about half the system-bar
height too high, and fullscreen was centred on the content view instead of the
screen. The arithmetic fix is a trap — it has to know whether the host is
edge-to-edge, whether it opted out of Android 15's enforcement, and which bars
are hidden right now.

`PipePlayerOverlay.measureGeometry` therefore measures all of it with
`getLocationOnScreen` on every layout pass and publishes a `PipePlayerGeometry`.
Never reintroduce a screen size captured once at attach: rotation, immersive
mode and host resizes all invalidate it.

Related: hiding the system bars is not enough to draw under them. While the
decor still fits system windows the content view keeps its inset padding and
**clips the overlay**, so fullscreen stops short no matter how large the video is
sized. `setDecorFitsSystemWindows(window, false)` on the way in, and `true` on
the way out so the host page is not left underneath the status bar.

### 16. `detectTransformGestures` also eats single-finger drags

It is documented as a pan/zoom/rotate detector, and the pan half responds to
**one** pointer and consumes it. Dropped onto the video to add pinch zoom, it
silently swallowed the vertical drag that moves the player between docked and
fullscreen. Nothing failed, nothing logged — the gesture simply stopped
existing.

The pinch handler is hand-rolled with `awaitEachGesture` and ignores every event
until at least two pointers are down, so one-finger gestures are never touched.
Same hazard applies to `detectDragGestures` layered over a tap detector.

### 17. A `ModalBottomSheet` lives in its own window — twice over

It renders in its **own window**, not in your composable tree, so no transform
of yours can turn it; a landscape sheet requires a genuinely landscape Activity
(which fullscreen now is — `alignActivityWithVideo` remains as a belt-and-braces
nudge for the async gap between commit and configuration change).

Same trap, second form: that window does not inherit the Activity's immersive
flags, so sheets un-hid the system bars in fullscreen. The `DialogWindowProvider`
remedy only works when the lookup runs **inside** the `ModalBottomSheet` content
lambda, where `LocalView` is the sheet window's view. At the composable's top
level the cast silently returns null and the fix never runs — which is exactly
how it shipped broken the first time.

### 18. `SCREEN_ORIENTATION_SENSOR_LANDSCAPE` rotates twice

It lets the system choose either landscape, and it chooses the one it last used
— so turning the phone left rotated the player right and then swung it 180°
once the sensor caught up.

Name the exact constant. The two scales are mirrored, which is the easy thing to
get backwards: `OrientationEventListener` reports how far the **device** has
turned clockwise from natural, while the `ActivityInfo` constants name where the
**content** ends up. A device at 90° wants `REVERSE_LANDSCAPE`, at 270°
`LANDSCAPE`.

### 19. core-pip is pinned to alpha02, and the pin is load-bearing

Picture-in-Picture uses `androidx.core:core-pip`, not the platform API. The
hand-rolled version entered PiP and then rendered the host's web page: on
Android 17 the mode-change callback it waited on never arrives, because the
Activity-recreation defaults changed.

**Do not bump it to alpha03.** Its AAR metadata declares `minCompileSdk=37` and
`minAndroidGradlePluginVersion=9.1.0`, and AGP enforces both — a hard build
failure, not a warning. That is exactly the AGP 9 requirement Gotcha 1 exists to
keep off consumers. alpha01/alpha02 declare 36 and 8.9.1, which our build meets.

The API also moved between those two releases, so a bump is not a version-number
change: alpha03 added an `Executor` constructor parameter and an explicit
`commit()`, without which the fluent setters only stage changes.

`core-pip` needs `androidx.core:1.18.0`, declared at _runtime_ scope, so it is
absent from the compile classpath. Capacitor's graph settles on 1.15.0 and AGP's
consistent resolution then refuses 1.18.0 — hence the `resolutionStrategy.force`
applied to compile classpaths only in `android/build.gradle`.

### 20. A window does not always start at screen 0,0

`getLocationOnScreen` is absolute, so deriving the window origin from it means
subtracting the window's own position — `bounds.left/top`, not zero. Docked and
fullscreen both report 0,0, so the assumption held everywhere until PiP, where
it put the video's centre at a negative y: the frame was drawn entirely above
the PiP window and the host's page showed through, looking exactly like the
player ignoring PiP.

### 21. `Size.Unspecified` crashes `resizeWithContentScale`

`Size.Unspecified` is `Size(NaN, NaN)`, and Media3's `resizeWithContentScale`
rounds the dimensions it computes — so passing it kills the frame with
`IllegalArgumentException: Cannot round NaN value`, from inside the library.

Media3's own samples never hit it because they withhold the surface until a
video size is known; `PresentationState.coverSurface` exists for exactly that.
We cannot, because the TextureView must exist for ExoPlayer to attach to and for
core-pip's `setPlayerView` to track. So before the first frame reports a size,
pass the **box's own aspect** — `Fit` then becomes a no-op and the video fills
the box, which is what the hand-written arithmetic did in that case.

Related, on the same artifact: `media3-ui-compose` is NOT `media3-ui`. The
warning elsewhere about avoiding media3-ui is about `PlayerView` bundling a
controller; `media3-ui-compose` contains no `PlayerView` and no
`PlayerControlView` — only `PlayerSurface`, state holders and this modifier. We
take the modifier but keep our own TextureView, because `PlayerSurface` owns its
view internally and core-pip needs a handle to ours.

### 22. A gesture inside `graphicsLayer` reads rotated coordinates

Swipe-down-to-exit-fullscreen was reported broken three times, and two plausible
causes were fixed before the real one was found. It was placement: the drag
modifier sat **after** `graphicsLayer { rotationZ = 90f }` in the chain, so it
ran in the box's local space. At 90° a screen-vertical swipe is _horizontal_
there — `Orientation.Vertical` never passed touch slop, and the gesture silently
did not exist. Dragging _into_ fullscreen always worked, because at p = 0 there
is no rotation, which is what made it look like an exit-only bug.

Pointer coordinates are transformed by every layer between the gesture and the
root. Put drag handling **before** any `graphicsLayer` that rotates.

What actually found it: instrumenting the drag and seeing **zero** events. An
empty log proves the gesture never arrived, which is a different bug from one
that arrives and computes the wrong thing. Reach for that earlier.

### 23. `travel` and `COMMIT_VELOCITY` are one setting with two names

`PipePlayerSurface`'s `travel` converts finger pixels into progress units, and
`PipePlayerMotion.COMMIT_VELOCITY` is the release speed that commits a flick —
**in progress units**, i.e. already divided by `travel`.

So lengthening `travel` to slow the drag down silently raises the real-world
speed needed to flick. Going from ~486px to ~1333px made even a fast 1400px/s
flick score 1.05 against a threshold of 1.2: velocity could never win, releases
fell back to "did you pass halfway", and swipe-to-exit stopped working while the
tracking it was meant to fix looked fine.

Change one, recompute the other. And test with a SHORT flick: a long synthetic
swipe commits on distance alone and hides exactly this failure.

### 24. Fullscreen is a discrete switch behind a shutter

Fullscreen was once a continuous interpolation between the docked rect and the
screen, rotating 90° as it went. Every intermediate frame was then a video at
some angle, mid-reshape — not a tuning problem but the wrong model, which is why
YouTube never shows you one.

The model now: the drag **translates** the video with rubber-band resistance and
nothing else; the state change is **discrete, on release**, carried by the
system's own rotation; and steady-state fullscreen is a genuinely landscape
Activity, so there is one coordinate frame rather than two.

Rules that each cost a debugging round:

- **Intent reads `motion.committed`; geometry reads the MEASURED window.**
  Bars, chrome layout and the orientation request hang off the committed latch
  (never `progress` — a drag past halfway must change nothing until release).
  But the video's _rect_ keys off `screenW > screenH`: keyed to `committed` it
  resized to fullscreen while the window was still portrait, and that one wrong
  frame was the only reason the shutter ever had to cover the video. Caveat
  this inherits: a landscape-_shaped_ docked window (tablet, DeX, desktop
  windowing) reads as fullscreen — acceptable for a portrait-locked phone host,
  wrong the day that assumption moves.
- **One black layer, behind the video.** It hides the host's page and doubles as
  the shutter (`alpha = max(backdrop, curtain)`); the chrome hides on
  `transitioning`. The video itself stays visible and rotates with the system.
  Entering, the shutter **fades** in (the fade is the start of the transition);
  leaving, it **snaps** — fading up over a playing video reads as the video
  dissolving.
- **The commit outlives the gesture, and commits can overlap.** Launch the
  choreography on the surface's scope (the drag modifier's own coroutine dies
  when committing detaches it), and every stage plus the `finally` checks a
  generation counter — a superseded commit must not lower the curtain or clear
  `transitioning` under its successor. Gestures are disabled while
  `transitioning`, because the shutter deliberately does not intercept touches.
- **The sensor and the buttons share custody of orientation.**
  `suppressAutoFullscreen` stops a still-sideways phone re-entering after a
  swipe-down exit; `holdLandscape` is its mirror — button-fullscreen while the
  phone is upright must not be undone by the very next (portrait) sensor
  reading. Every programmatic `requestedOrientation` write syncs the listener's
  dedup via `noteRequested`, or it swallows and repeats requests unpredictably.

Lowering the shutter waits for the switch to _begin_ (configuration matches the
committed state) and then for the geometry to go quiet — never a fixed delay;
the orientation change, window resize and the host's re-`dock()` land whenever
they land. See `PipePlayerOverlay.awaitStableGeometry`, bounded so a host that
re-docks forever cannot pin the shutter up.

### 25. Never read `progress` or `curtain` in composition

Both Animatables move every frame of a drag or fade, and a composition-scope
read re-executes the entire surface — rect maths, modifier chains, gesture
nodes — per frame, allocating as it goes. The pattern that replaced it: rest
geometry is composition state (changes rarely); the per-frame values are read
in **deferred scopes** — the `offset {}` lambda for placement, `graphicsLayer`
for the pick-up scale, `drawBehind` for the black layer — and the few
structural questions ("is it at docked rest?") go through `derivedStateOf` so
composition invalidates only when the answer changes. Same idea one level up:
the 4 Hz playhead travels as `State<Long>` read only where it is drawn, never
as a field of the chrome-state data class.

### 26. KDoc is Markdown, not Javadoc

Six player files carried `<p>`, `<b>`, `<pre>` and `{@link}` — habits that came
across with the ported Java sources. None of it renders: KDoc uses Markdown, so
those tags show up literally in quick-docs and Dokka. Use blank lines, `**bold**`,
fenced blocks and `[Symbol]` links. `@param` and `@return` are genuine KDoc tags
and stay.

### 27. Window insets are a second position source — pad the chrome in fullscreen only

The docked chrome once carried `windowInsetsPadding(WindowInsets.displayCutout)`
unconditionally, believed free because Compose insets are positional and the
docked box sits nowhere near the cutout. That belief holds only while the
window keeps its shape. A host that allows rotation can let the window flip to
landscape for a moment (sensor noise, an unlock window) while the box — placed
by the MEASURED geometry, which stays correct across the flip — remains where
the portrait page put it. The box then overlaps the landscape cutout region,
and the entire chrome, scrim included, pads itself sideways by the cutout
height while the video underneath does not move. On device: chrome shifted
right by exactly the cutout (~170px), an unscrimmed strip of video at the left
edge, the offset wandering as the window flipped back — which read as a stale
drag translation and burned a day pointed at the mini player.

The rule: the box's position comes from `PipePlayerGeometry` and from nothing
else. Window-derived insets may only be applied when the box actually spans the
window — fullscreen (and PiP, which uses the compact chrome and takes no
insets). Anything inside a docked or corner box must lay out against the box.

### 28. Capacitor's `PluginCall.getLong` cannot read a JavaScript number

Its implementation returns the stored object only `if (value instanceof Long)`
— and a number sent from JS lands in the bridge's JSONObject as an Integer or
a Double, never a Long. So `call.getLong("startPositionMs") ?: 0L` compiled,
looked idiomatic beside `getString`/`getBoolean`, and silently turned EVERY
start position a web caller sent into 0 — resume points, highlight seeks,
quality-switch positions, on all three load paths. Found on device as "a 33s
seek that reloads and lands at 0:00", diagnosed as everything except a parse
bug first. `getInt`, `getFloat` and `getDouble` do convert; `getLong` alone
does not. Read longs as `(call.data.opt(name) as? Number)?.toLong()`.

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
**PipePipeClient**, which cannot be a dependency: it is an Android _application_
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
