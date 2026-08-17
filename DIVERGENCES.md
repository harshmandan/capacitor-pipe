# Engine divergences

Every place **our code** forks to handle PipePipeExtractor and NewPipeExtractor
differently.

The two are hard forks of each other with the same class names, so code that
looks like it should be shared usually cannot be. Each entry below records what
differs, why, and what to re-check when you bump that dependency.

**Read this before running `scripts/update-extractors.sh`.** When you add a
divergence, add a row here — a silent one is the failure mode this file exists
to prevent.

Verified against PipePipeExtractor `8d2799a` and NewPipeExtractor `v0.26.5`
(2026-08-17).

---

## Index

| # | Divergence | Ours | Triggered by |
|---|---|---|---|
| [1](#1-namespace) | Namespace / imports | `NewPipeEngine`, `NewPipeDownloader` | either |
| [2](#2-downloader-contract) | `Downloader` contract | `net/*Downloader.kt` | PipePipe |
| [3](#3-response-shape) | `Response` constructor | `net/*Downloader.kt` | either |
| [4](#4-request-followredirects) | `Request.followRedirects()` | `NewPipeDownloader` | NewPipe |
| [5](#5-localization-return-type) | `fromLocalizationCode` return type | `NewPipeEngine` | NewPipe |
| [6](#6-thumbnail--avatar-accessors) | Thumbnail / avatar accessors | both engines | NewPipe |
| [7](#7-sponsorblock) | SponsorBlock | `PipePipeEngine` | PipePipe |
| [8](#8-sabr-and-requiressabr) | SABR / `requiresSabr` | `PipePipeEngine` | PipePipe |
| [9](#9-player-client-two-pass) | Player-client two-pass | `PipePipeEngine` | PipePipe |
| [10](#10-exception-matching-by-simple-name) | Exception matching | `PipeExtractor` | either |
| [11](#11-duplicated-mappers) | Duplicated mappers | both engines | either |
| [12](#12-build-level-divergences) | Build / toolchain / deps | `tools/`, `android/build.gradle` | either |

---

## 1. Namespace

NewPipe is relocated so both engines can load at once; PipePipe is not.

| | Import root |
|---|---|
| PipePipe | `org.schabi.newpipe.extractor.*` |
| NewPipe | `ink.harsh.pipe.shaded.org.schabi.newpipe.extractor.*` |

- `engine/NewPipeEngine.kt` — relocated imports
- `engine/NewPipeEngine.kt` — `Class.forName("ink.harsh.pipe.shaded...")`
- `engine/PipePipeEngine.kt` — plain imports
- `net/NewPipeDownloader.kt` — relocated imports
- `tools/shade/build.gradle:55-57` — the prefix, and the only place it is defined

**On upgrade:** if you change the relocation prefix, all four files above must
change with it. `scripts/build-extractors.sh` fails the build if relocation stops
taking effect, but it cannot catch a prefix that changed consistently in Gradle
and not in Java — that shows up as a compile error instead.

---

## 2. `Downloader` contract

PipePipe requires an async path; NewPipe does not.

| | Abstract methods |
|---|---|
| PipePipe | `execute` **and** `executeAsync(Request, AsyncCallback) → CancellableCall` |
| NewPipe | `execute` only |

`CancellableCall` wraps an `okhttp3.Call` directly, so PipePipe leaks okhttp into
its public API and our downloader must return okhttp-backed calls.

- `net/PipePipeDownloader.kt` — `executeAsync`
- `net/NewPipeDownloader.kt` — no equivalent

Why PipePipe needs it: its YouTube extractor fans several InnerTube client
requests out in parallel and drops the losers, so cancellation is normal control
flow, not an error. Our `onFailure` suppresses errors for cancelled calls.

**On upgrade (PipePipe):** if `CancellableCall` stops wrapping `okhttp3.Call`, or
`AsyncCallback` gains methods, `PipePipeDownloader` breaks. Also re-check the
okhttp version pin in `android/build.gradle` — it must match what the extractor
was compiled against.

---

## 3. `Response` shape

| | Constructor |
|---|---|
| PipePipe | `(code, message, headers, body, **rawResponseBody**, latestUrl)` |
| NewPipe | `(code, message, headers, body, latestUrl)` |

PipePipe carries a raw `byte[]` because SABR responses are binary UMP; decoding
them as text would corrupt them. NewPipe has no SABR, so no raw body.

- `net/PipePipeDownloader.kt` — passes `result.body`
- `net/NewPipeDownloader.kt` — text only

`HttpCore.Result` deliberately holds bytes and exposes `text()` on demand, so the
shared layer can feed both.

**On upgrade:** a changed constructor arity is a compile error, so this is
self-catching. The silent risk is the reverse — NewPipe *gaining* a raw body and
us not using it.

---

## 4. `Request.followRedirects()`

PipePipe's `Request` exposes it; NewPipe's does not, so we hardcode `true`.

- `net/PipePipeDownloader.kt` — honours the request
- `net/NewPipeDownloader.kt` — hardcoded `true`, with a comment

Matters for SABR, where the session handles CDN redirects itself and needs them
*not* followed transparently.

**On upgrade (NewPipe):** if it gains `followRedirects()`, stop hardcoding.

---

## 5. `Localization` return type

Same method name, different signature — this one broke the build.

| | `Localization.fromLocalizationCode(String)` |
|---|---|
| PipePipe | returns `Localization` |
| NewPipe | returns `Optional<Localization>` |

- `engine/NewPipeEngine.kt` — `.orElse(Localization.DEFAULT)`
- `engine/PipePipeEngine.kt` — direct assignment

**On upgrade:** compile error if either flips. Cheap to fix, but note the
*semantic* difference: NewPipe returns empty for an unparseable code where
PipePipe may throw or return a default.

---

## 6. Thumbnail / avatar accessors

NewPipe removed the single-URL shortcuts; PipePipe kept them.

| Field | PipePipe | NewPipe |
|---|---|---|
| `thumbnailUrl` | `getThumbnailUrl()` | derive from `getThumbnails()` |
| `uploaderAvatarUrl` | `getUploaderAvatarUrl()` | derive from `getUploaderAvatars()` |

- `engine/PipePipeEngine.kt`
- `engine/NewPipeEngine.kt` — via `firstImageUrl()`, highest-resolution

**Behavioural difference worth knowing:** our `firstImageUrl()` picks the tallest
image. PipePipe's `getThumbnailUrl()` uses its own selection. The two engines can
therefore return *different* thumbnail URLs for the same video. That is
acceptable, but do not treat `thumbnailUrl` as engine-stable.

**Related, currently unmapped:** `AudioStream.getAudioLocale()` returns `String`
in PipePipe and `Locale` in NewPipe. We do not surface it today — if you start
mapping it, it needs a per-engine branch.

### 6a. `dislikeCount` (observed live, not compile-visible)

Same video, same run: PipePipe returned `89`, NewPipe returned `-1` (its
"unknown" sentinel). PipePipe restores dislikes via ReturnYouTubeDislike as a
best-effort side call; NewPipe does not.

Observed on `iUtnZpzkbG8`, 2026-08-17.

**Consequence:** `dislikeCount` is engine-dependent and `-1` means "unknown",
not "zero". Check `result.engine` before showing it.

### 6b. `description` content format (observed live, not compile-visible)

Both engines expose `getDescription().getContent()`, and both compile fine, but
they return **different markup** for the same video:

| Engine | Output |
|---|---|
| PipePipe | plain text with `\n` line breaks |
| NewPipe | HTML with `<br>` and `<a href="...">` anchors |

Observed on `iUtnZpzkbG8`, 2026-08-17. `Description` carries a type flag
(`HTML` / `MARKDOWN` / `PLAIN_TEXT`) that we currently ignore.

**Consequence:** a caller rendering `description` as HTML gets escaped-looking
plain text from the primary, and one rendering it as text gets visible `<br>`
tags from the fallback. Neither is wrong; they are just different, and which one
you get depends on which engine answered.

Not fixed yet — the correct fix is to map `getDescription().getType()` into the
result and let callers branch, rather than normalising on our side and losing
the uploader's links. Until then, treat `description` as engine-dependent.

---

## 7. SponsorBlock

PipePipe only.

- `engine/PipePipeEngine.kt` — `getSponsorBlockSegments()`, mapped
- `NewPipeEngine` — key omitted entirely

Fields are public (`segment.uuid`, `.startTime`, `.endTime`), not getters, and
times are fractional milliseconds that we cast to `long`.

**Consequence for callers:** `sponsorBlockSegments` is absent when the fallback
served the result. Read `result.engine` before assuming it is missing because the
video has no segments.

**On upgrade (PipePipe):** if the public fields become getters, this breaks.

---

## 8. SABR and `requiresSabr`

The largest behavioural split. PipePipe **implements** SABR; NewPipe
deliberately **avoids** it.

| | PipePipe `8d2799a` | NewPipe `v0.26.5` |
|---|---|---|
| `DeliveryMethod.SABR` | yes | no |
| SABR classes | 21 | 0 |
| Landed via | PR #69, 2026-06-24, +8611 lines | — |
| Upstream stance | — | PR #1508 *"Workaround SABR enforcement by using another player client"* |
| Tracking | — | `TeamNewPipe/NewPipe#12248`, open, "help wanted" |

- `engine/PipePipeEngine.kt` — computes `requiresSabr`
- `engine/NewPipeEngine.kt` — hardcoded `false`, because this fork
  cannot produce SABR streams at all

**What NewPipe did instead (PR #1508, merged 2026-06-09, in our pin).** YouTube
began *enforcing* SABR on the InnerTube client NewPipe used. Rather than
implement the protocol, upstream switched to a different client "not yet
affected" — client-hopping, explicitly a workaround. Two consequences we inherit:

- Like `ANDROID_VR`, the new client **does not support made-for-kids videos** —
  no audio or video-only streams for those. Upstream's own
  `RatingsDisabled` test fails because of it, and `YoutubeStreamExtractorUnlistedTest`
  is noted as failing and "to be investigated later".
- It gained multi-audio-track support, and allows DASH/HLS-only results
  (livestreams).

"Not yet affected" is the operative phrase: the workaround has an expiry date
set by YouTube, which is why #12248 stays open. Treat NewPipe's SABR-adjacent
viability as time-limited.

**Consequence for the fallback:** NewPipe cannot rescue a SABR-only video. The
fallback covers a different failure — PipePipe defers signature deciphering to a
PipePipe-hosted service, NewPipe does it locally in Rhino, so NewPipe survives
outages of that service. Do not expect it to cover SABR.

**On upgrade (NewPipe):** if upstream implements SABR, revisit this whole file —
the engines would become genuinely equivalent and `requiresSabr: false` would
become a lie.

---

## 9. Player-client two-pass

PipePipe only. Its global player-client setting decides the extraction path:

```java
if ("mweb".equals(client) && !live && hasSabrStreamingUrl())
    buildSabrStreams(...);      // wins whenever a SABR URL exists
else
    extractDirectFormats(...);
```

So `mweb` means *prefer SABR*, not *support SABR*. Pinning it globally would
force a session driver onto videos that have a perfectly good URL.

- `engine/PipePipeEngine.kt` — `visionos` default, `mweb` for SABR
- `engine/PipePipeEngine.kt` — pass 1 default, pass 2 `mweb`, always
  restored in `finally`, whole sequence under `CLIENT_LOCK`

The lock exists because `youtubePlayerClient` is global mutable state inside the
extractor; concurrent extractions would otherwise interleave.

NewPipe has no equivalent setting.

**On upgrade (PipePipe):** re-read `YoutubeStreamExtractor.onFetchPage`. If the
client list or the branch condition changes, the two-pass logic needs updating.
Historically PR #69 shipped a `FORCE_SABR_FOR_TESTING` flag defaulting to `true`
that routed *everything* through SABR; it is gone now, but check for its return.

---

## 10. Exception matching by simple name

`PipeExtractor` decides whether to fall through by exception type. After
relocation the two engines' exception classes are unrelated to the compiler, so
we cannot `catch` a shared type or use `instanceof`.

- `PipeExtractor.kt` — `NOT_WORTH_RETRYING`, a set of simple names
- `PipeExtractor.kt` — `e.getClass().getSimpleName()`

Listed: `AgeRestrictedContentException`, `GeographicRestrictionException`,
`PrivateContentException`, `PaidContentException`, `AccountTerminatedException`,
`YoutubeMusicPremiumContentException`, `SoundCloudGoPlusContentException`,
`LiveNotStartException`, `VideoNotReleaseException`.

These describe the *content*, not the extractor — both engines hit the same age
gate — so retrying doubles the latency of a guaranteed failure.

**This is the most fragile divergence in the codebase.** String matching gives no
compile-time safety: if either fork renames an exception, the name silently stops
matching and we start making pointless second requests. Nothing fails, nothing
logs an error, it just gets slower.

**On upgrade (either):** diff the `exceptions/` package of whichever fork you
bumped and re-check every name above.

---

## 11. Duplicated mappers

`PipePipeEngine.map()` and `NewPipeEngine.map()` are near-identical by eye and
cannot be unified. After relocation, `StreamInfo`, `VideoStream`, `AudioStream`
and the rest are *different types* with no common supertype and genuinely
different signatures (see 5, 6).

**Do not try to generify this.** Reflection or a shared interface would trade a
compile error for a runtime one, in code whose whole job is surviving upstream
churn.

**On upgrade:** a change to the output shape must be applied to *both* mappers.
The compiler will not tell you that you missed one — it will just produce results
whose shape depends on which engine answered. `src/definitions.ts` is the
contract both must satisfy.

---

## 12. Build-level divergences

| Concern | PipePipe | NewPipe | Ours |
|---|---|---|---|
| Toolchain | 25 | 11 | both forced to 17 via `tools/force-java17.init.gradle` |
| Module layout | `:extractor` + `:timeago-parser` (separate jars) | timeago compiled *into* the extractor jar | 3 jars vs 1 |
| protobuf | `protobuf-java` (full) | `protobuf-javalite` | NewPipe's bundled + relocated |
| nanojson | commit `1d9e1aea` | commit `e9d656dd` | NewPipe's bundled + relocated |
| jsoup | 1.22.2 | 1.22.2 | shared, not bundled |
| rhino | not used | 1.8.1 | declared normally; **stay on 1.8.1**, 1.9.0 needs minSdk 26 |
| wire | 6.4.1, used | not used | `wire-runtime` declared |
| okhttp | 5.4.0, **in its public API** | test-only | pinned to 5.4.0 |

The toolchain override is forced for *different reasons*: PipePipe's 25 produces
class-file 69 that AGP 8 / D8 rejects; NewPipe's 11 simply is not installed.

- `tools/force-java17.init.gradle` — applied to both
- `scripts/build-extractors.sh` — different task lists per fork
- `scripts/verify-bytecode.sh` — enforces the ceiling

**On upgrade (either):** diff the fork's `build.gradle` / `libs.versions.toml`.
A new dependency there is invisible until something throws
`NoClassDefFoundError` at runtime, because our `android/build.gradle` declares
these by hand. `scripts/update-extractors.sh` prints build-file diffs for exactly
this reason.
