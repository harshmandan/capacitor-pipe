# Extraction — what we have observed, and what we decided because of it

**The other party changes.** Everything in this file is a fact about *YouTube*
at a moment in time, not about this code. Formats appear and vanish, SABR
enforcement spreads client by client, a player request that worked in July
returns a different shape in August. So every entry here is dated and says how
it was measured, and an undated claim about YouTube's behaviour is not evidence.

**It exists because the same measurements keep being re-derived.** Each time
someone — a person or an agent — finds that a video has no progressive stream,
or that a manifest advertises formats nothing can serve, the finding costs a
device, a build and a handful of requests against a service that rate-limits.
Writing it down is cheaper than measuring it again, as long as the writing says
*when*.

## What lives where

| File               | Answers                                                                                    |
| ------------------ | ------------------------------------------------------------------------------------------ |
| `DIVERGENCES.md`   | How the two engines differ **mechanically** — API shapes, exception types, bundled deps. Asserted by `scripts/check-divergences.sh` where it can be. |
| `../CLAUDE.md`     | How to build this, and the traps in doing so.                                              |
| `PLAYER.md`        | What the player does with what extraction returns.                                         |
| **this file**      | What YouTube was observed doing, when, and which decision followed.                        |

The boundary that matters: `DIVERGENCES.md` is about **our two dependencies**
and is checkable from source. This file is about **the service**, and is only
ever checkable by asking it.

## How to re-measure without getting the address banned

Extraction is not a unit test. It costs real requests to a service that counts
them, and a loop that re-extracts on every save will get an IP blocked — which
then looks like "extraction is broken" to everyone sharing that address.

- **One video at a time, a handful of requests, then stop.** Never a loop, never
  a sweep over a class's worth of ids.
- **Prefer a video you have already used.** The set below is deliberately small
  and boring for that reason.
- **Vary nothing you do not have to.** If the question is "does this engine
  return progressive streams", one video answers it.
- **Emulators share the host's address.** So does CI. A ban earned on an
  emulator is a ban for the laptop.

The cheapest harness is the consuming app's WebView over the devtools protocol
— `window.Capacitor.Plugins.Pipe.extractStreamInfo(…)` from a CDP
`Runtime.evaluate`. tutorgrow's `scripts/device/downloads-e2e.ts` has the
transport (adb forward, `/json`, one websocket) and it is about twenty lines to
reuse. `example-app` is the other option and needs no host.

Videos used in the observations below:

| Id            | Why                                              |
| ------------- | ------------------------------------------------ |
| `jNQXAC9IVRw` | 2005, 19s, 240p-era. The floor of what YouTube has. |
| `aqz-KE-bpKQ` | 4K60, 10:35. The ceiling, and a format ladder.   |

---

## 2026-08-20 · YouTube no longer serves itag 18 to the PipePipe client

**Measured** on an API 34 emulator, both videos, `extractStreamInfo` with
`engines` pinned to one at a time:

```
jNQXAC9IVRw  pipepipe  | muxed none  | video-only 5   | audio 5
jNQXAC9IVRw  newpipe   | muxed 360p  | video-only 5   | audio 5
aqz-KE-bpKQ  pipepipe  | muxed none  | video-only 14  | audio 5
aqz-KE-bpKQ  newpipe   | muxed 360p  | video-only 14  | audio 5
```

`requiresSabr` was **false** in all four. That is the part worth pausing on: a
video can have no directly playable stream without being SABR-only, so
`requiresSabr === false` does **not** mean "there is a URL to play".

**Why.** The engines talk to different InnerTube clients. YouTube enforces SABR
on the one PipePipe uses; NewPipe's upstream works around that by using another
client (`DIVERGENCES.md` §8, and its PR #1508). The muxed 360p stream is a
property of the client asked, not of the video.

**Decided.** A consumer that wants a plain URL should ask the *other* engine
before concluding there is none. tutorgrow's playback ladder is muxed →
manifest → **ask NewPipe** → SABR session.

**Open question for this library.** Arguably the engine chain should do that
itself: `extractStreamInfo` falls back on *failure*, not on "succeeded but
returned nothing playable". Every consumer that wants a URL will otherwise
write the same retry. Not changed yet because "playable" is the consumer's
definition — a downloader and a player disagree about whether a video-only
track counts.

## 2026-08-20 · SABR is the normal path, not the exception

Follows directly from the above: with no muxed stream and two remote tracks
that a single-URL load cannot merge, **every** ordinary video reaches
`openSabrSession`. The session path is not a fallback for gated content; it is
the main road.

**Decided.** SABR's cost is now everyone's cost — a BotGuard mint in a WebView,
a loopback server or a native source, and a spool directory per session. Treat
its latency and its failure modes as ordinary, not exceptional. In particular a
host must close sessions: on a normal viewing session that is now one per video
rather than one per gated video.

## 2026-08-20 · A session serves one format, and the manifest lied about it

**Measured** by probing the loopback server from the WebView, one open session:

```
v133/init  → 200, 804 bytes
v133/1     → 200, 116 892 bytes
a140/init  → 200, 779 bytes
v134/init  → 503 "Initialisation not ready"   ← still 503 after 20s of polling
```

The manifest advertised 6 video and 8 audio Representations; two were servable.
Media3 did what an adaptive player does, selected another, and failed with
`EOFException` inside `InitializationChunk`. The native `PipeSabrDataSource`
route failed identically (`SABR initialisation missing`) because both routes
read the same synthesised manifest.

**Cause.** `PipeSabrManifest.usable()` filtered on whether a *timeline* was
known — true for every format the extraction found — while both consumers
require an *init segment*, which only the bootstrapped audio and video formats
have. The server's own comment already stated the invariant it was failing:
"should be impossible: the manifest only advertises formats whose init segment
has been parsed."

**Fixed** in `45abd4c` by making the filter test what the consumers test.

**Decided, and this is the durable part.** SABR requires a concrete format
selection when the session opens and serves only that one. There is no
adaptation inside a session and there cannot be one without driving the protocol
to switch formats mid-stream (PipePipeClient does this with a pending-segment
exception and a retry policy — see `reference/PipePipeClient`,
`SabrLoadErrorHandlingPolicy`). So:

- the bootstrap choice **is** the playback quality, not a starting guess;
- `openSabrSession({ maxHeight })` is therefore the quality control;
- and a manifest must never advertise more than the session can serve.

## 2026-08-20 · Uncapped means 2160p onto a phone

`PipeSabrSpec.bootstrapVideo()` took the tallest format available, which for a
4K source is what the session opens on and therefore what plays. Nobody chose
that.

**Decided.** `maxHeight` added, defaulting to uncapped in the library — a
library that silently caps quality is its own surprise — and hosts are told
plainly in the README that omitting it is a choice, not a default. When every
format is taller than the cap the **shortest** is used, so a 1080p-only video
asked for 720p plays rather than fails.

## 2026-08-20 · The BotGuard mint works on a stock emulator

An API 34 AVD with no Play Services tricks minted a PO token and opened a
session in a few seconds — 32 formats reported for the 4K video, 14 for the
2005 one, `nativePlaybackAvailable: true`. Worth recording because the opposite
was plausible: attestation that only works on a real handset would make every
emulator run a false negative.

`pipSupported` was **false** on that image, so Picture-in-Picture needs a real
device to test. That one is hardware, not us.

---

## What to check first when extraction breaks

In this order, because each rules out the layer below:

1. **`getEngineStatus()`** — is the engine even on the classpath? A shaded jar
   that failed to build looks exactly like YouTube changing.
2. **`attempts`** on the result — which engine failed and with what
   `errorType`. Age-gating, geo-blocking and "both engines are broken" are three
   different problems and this is the only place they are distinguishable.
3. **One engine at a time**, via `engines: ['pipepipe']` then `['newpipe']`. If
   they disagree, the answer is a client change at YouTube and belongs in this
   file with a date.
4. **The stream arrays**, not `requiresSabr`. As of 2026-08-20, false does not
   mean playable.
5. **Only then the SABR layer** — session opens, manifest parses, segments
   serve. Probe the server directly before blaming the player.
