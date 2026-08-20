# capacitor-pipe

YouTube extraction for Capacitor on Android, with **two engines behind one API**:
[PipePipeExtractor](https://github.com/InfinityLoop1308/PipePipeExtractor) is
primary and [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor)
is the fallback. Includes full SABR support, playable from Media3/ExoPlayer or
any web player.

Formerly `capacitor-npe`.

## Install

```bash
npm install capacitor-pipe
npx cap sync
```

Requires Capacitor 8 (minSdk 24, compileSdk 36, JDK 21).

## Extraction

```ts
import { Pipe } from 'capacitor-pipe';

const result = await Pipe.extractStreamInfo({ videoUrl });

if (result.success) {
  console.log(result.engine);            // 'pipepipe' | 'newpipe'
  console.log(result.streamInfo.title);
  console.log(result.streamInfo.videoStreams);
}
```

`attempts` records every engine tried, so a degraded primary is visible even
when the call succeeded:

```ts
// [{ engine: 'pipepipe', ok: false, errorType: '...', durationMs: 812 },
//  { engine: 'newpipe',  ok: true,                    durationMs: 3676 }]
result.attempts.forEach((a) => console.log(a.engine, a.ok, a.error));
```

The two engines are not interchangeable — they differ in ways worth knowing
before you render their output. See [DIVERGENCES.md](DIVERGENCES.md); the
short version is that `dislikeCount`, `description` markup and SABR support all
depend on which engine answered.

They also differ in what YouTube *gives* them, which is not a property of this
library and changes without notice. As of 2026-08-20 the primary engine gets no
progressive stream at all for videos where the fallback gets 360p, so a consumer
that wants a plain URL should ask the other engine before concluding there is
none. [EXTRACTION.md](EXTRACTION.md) records that and every other dated
observation, with how it was measured.

Pass `sponsorBlock: true` to also fetch SponsorBlock segments
(`streamInfo.sponsorBlockSegments`). Best-effort, and PipePipe-only — the
NewPipe fallback has no SponsorBlock support, so results that fell through
carry none.

## SABR

Some videos are delivered only over SABR, YouTube's session-based protocol.
There is **no URL to fetch** — bytes arrive only by driving the protocol. Those
results set `requiresSabr: true`.

```ts
const { streamInfo } = await Pipe.extractStreamInfo({ videoUrl });

if (streamInfo.requiresSabr) {
  const session = await Pipe.openSabrSession({ videoUrl });
  videoElement.src = session.manifestUrl;   // a DASH manifest on 127.0.0.1
  // ...
  await Pipe.closeSabrSession({ sessionId: session.sessionId });
}
```

`manifestUrl` is an ordinary DASH manifest served over loopback, so dash.js,
Shaka or Media3's `DashMediaSource` all play it unmodified.

### One session, one quality

**A SABR session opens on one video format and serves only that one.** It is
not an adaptive ladder with a preferred rung: the protocol requires a concrete
selection up front, and the manifest advertises exactly what the session can
serve, so there is nothing for a player to switch between.

That makes `maxHeight` the quality knob, and omitting it the loudest possible
choice — the tallest format there is, which on a 4K source is 2160p onto
whatever handset asked:

```ts
const session = await Pipe.openSabrSession({ videoUrl, maxHeight: 720 });
```

To change quality, close the session and open another with a different cap,
passing the current position as `startPositionMs` so playback resumes where it
was. When every format is taller than the cap the shortest is used, so a
1080p-only video asked for 720p plays rather than failing.

Two things followed from getting this wrong once, and both are worth knowing.
The manifest used to advertise every format the extraction knew about — 6 video
and 8 audio Representations of which 2 were servable — and an adaptive player
picked one of the others and failed on its initialisation segment. Both
consumers failed identically, because both read the same manifest: the loopback
server answered `503 Initialisation not ready` and `PipeSabrDataSource` threw
`SABR initialisation missing`.

**Always close sessions.** Each one holds a WebView-minted token, a listening
socket and segments spooled to disk.

### Proof-of-Origin tokens

SABR requires an attested PO token, minted by running Google's BotGuard
challenge in a WebView. The plugin does this automatically. If your app already
mints tokens, supply your own instead and it takes precedence:

```ts
await Pipe.providePoToken({ videoId, visitorData, clientVersion, playerPoToken });
```

There is no offline path — the signing secret never leaves Google's servers.

### Cleartext to loopback

The SABR server speaks plain HTTP on `127.0.0.1`, and Android blocks cleartext
from API 28. Most Capacitor apps already permit localhost; if yours does not,
point your application at the config this library ships:

```xml
<application android:networkSecurityConfig="@xml/pipe_network_security_config">
```

Nothing there loosens policy for a real host — loopback only.

### Native playback (Media3)

Apps using ExoPlayer or the Media3 UI can skip the loopback hop entirely:

```java
// sessionId comes back from openSabrSession over the bridge
MediaSource source = PipeSabrMedia3.mediaSource(sessionId);
player.setMediaSource(source);
player.prepare();
```

Media3 is an **optional** dependency. Apps without it are unaffected and use the
loopback manifest instead. Check `getEngineStatus().media3Available` before
calling into this path.

## The player

**Required contract: every page that shows the player must `dock()` on mount and
`undock()` on unmount.**

The player is a native overlay on the Activity, not an element in your page. It
outlives web navigation — that is the point — so a rect measured on one page is
meaningless on the next. Pages *lend* the player a rectangle; they do not own it.

```js
// on mount
const r = stage.getBoundingClientRect();
await PipePlayer.dock({ x: r.x, y: r.y, w: r.width, h: r.height, dpr: devicePixelRatio });
// on unmount — the video keeps playing, it just has nowhere to sit
await PipePlayer.undock();
// and on resize/orientation change, dock() again with fresh numbers
```

Skip it and the failure is quiet rather than loud: minimise on page A, navigate
to page B, press expand, and there is no rect to expand into. The player stays in
the corner and emits `expandUnavailable` — listen for it and route back to the
page that owns the video, the way YouTube does.

The package also ships an optional native player — a separate Capacitor plugin
with its own API, dependencies and docs. It handles the docked ⟷ fullscreen
transform, a corner mini player, Picture-in-Picture, live streams and gestures.

See **[PLAYER.md](PLAYER.md)** for setup, the manifest changes PiP needs, and
the full API. Apps that only extract can ignore it entirely and ship none of its
dependencies.

## Licence

GPL-3.0-or-later. Both extractors are GPL-3.0 and this plugin links against
them, so anything distributing it inherits GPL-3.0.

## API

<docgen-index>

* [`extractStreamInfo(...)`](#extractstreaminfo)
* [`openSabrSession(...)`](#opensabrsession)
* [`closeSabrSession(...)`](#closesabrsession)
* [`providePoToken(...)`](#providepotoken)
* [`getEngineStatus()`](#getenginestatus)
* [Interfaces](#interfaces)
* [Type Aliases](#type-aliases)

</docgen-index>

<docgen-api>
<!--Update the source file JSDoc comments and rerun docgen to update the docs below-->

### extractStreamInfo(...)

```typescript
extractStreamInfo(options: ExtractStreamInfoOptions) => any
```

Extract stream information for a YouTube URL.

Tries PipePipeExtractor first, then falls back to NewPipeExtractor.
`attempts` reports what each engine did, so a partial failure is visible
even when the call ultimately succeeds.

| Param         | Type                                                                          |
| ------------- | ----------------------------------------------------------------------------- |
| **`options`** | <code><a href="#extractstreaminfooptions">ExtractStreamInfoOptions</a></code> |

**Returns:** <code>any</code>

--------------------


### openSabrSession(...)

```typescript
openSabrSession(options: OpenSabrSessionOptions) => any
```

Open a SABR playback session.

Required for videos where `streamInfo.requiresSabr` is true — SABR serves no
plain URL, so bytes only arrive by driving the protocol. Returns a loopback
DASH manifest any player can consume. Always pair with
{@link PipePlugin.closeSabrSession}; sessions hold a segment cache and a
WebView-minted token.

Call {@link PipePlugin.providePoToken} for this video first — without a
token this call fails.

| Param         | Type                                                                      |
| ------------- | ------------------------------------------------------------------------- |
| **`options`** | <code><a href="#opensabrsessionoptions">OpenSabrSessionOptions</a></code> |

**Returns:** <code>any</code>

--------------------


### closeSabrSession(...)

```typescript
closeSabrSession(options: { sessionId: string; }) => any
```

Tear down a SABR session and release its cache, tokens and loopback routes.

| Param         | Type                                |
| ------------- | ----------------------------------- |
| **`options`** | <code>{ sessionId: string; }</code> |

**Returns:** <code>any</code>

--------------------


### providePoToken(...)

```typescript
providePoToken(options: PoTokenOptions) => any
```

Supply a Proof-of-Origin token for a video, before opening its SABR session.

YouTube refuses media for gated videos without one, and the extractor never
mints tokens — producing one means running Google's BotGuard challenge in a
real JavaScript runtime, exchanging the snapshot for an integrity token
(~12h), then deriving a per-video token from it. There is no offline path;
the signing secret never leaves Google's servers.

Tokens are per-video: reusing one across videos fails attestation.

**Required, not optional.** The MWEB player request that SABR depends on
dereferences the token for its client version, visitor data and poToken, so
`openSabrSession` fails outright without one — it does not fall back to an
unattested session.

| Param         | Type                                                      |
| ------------- | --------------------------------------------------------- |
| **`options`** | <code><a href="#potokenoptions">PoTokenOptions</a></code> |

**Returns:** <code>any</code>

--------------------


### getEngineStatus()

```typescript
getEngineStatus() => any
```

Report which engines and playback paths are available at runtime.

**Returns:** <code>any</code>

--------------------


### Interfaces


#### ExtractStreamInfoOptions

| Prop                 | Type                 | Description                                                                                 |
| -------------------- | -------------------- | ------------------------------------------------------------------------------------------- |
| **`videoUrl`**       | <code>string</code>  |                                                                                             |
| **`engines`**        | <code>{}</code>      | Restrict extraction to these engines, in this order. Defaults to `['pipepipe', 'newpipe']`. |
| **`sponsorBlock`**   | <code>boolean</code> | Fetch SponsorBlock segments. Best-effort; never blocks extraction. Default false.           |
| **`localization`**   | <code>string</code>  | BCP-47 localisation, e.g. `en-GB`. Affects what YouTube returns, not just formatting.       |
| **`contentCountry`** | <code>string</code>  | ISO-3166 country code. Affects availability and recommendations.                            |


#### StreamInfoResult

| Prop             | Type                                                          | Description                                                             |
| ---------------- | ------------------------------------------------------------- | ----------------------------------------------------------------------- |
| **`success`**    | <code>boolean</code>                                          |                                                                         |
| **`error`**      | <code>string</code>                                           |                                                                         |
| **`engine`**     | <code><a href="#extractionengine">ExtractionEngine</a></code> | The engine that produced `streamInfo`. Absent when every engine failed. |
| **`attempts`**   | <code>{}</code>                                               | Every engine tried, in order, including the ones that failed.           |
| **`streamInfo`** | <code><a href="#streaminfo">StreamInfo</a></code>             |                                                                         |


#### EngineAttempt

One engine's attempt at an extraction, successful or not.

| Prop             | Type                                                          | Description                                                                   |
| ---------------- | ------------------------------------------------------------- | ----------------------------------------------------------------------------- |
| **`engine`**     | <code><a href="#extractionengine">ExtractionEngine</a></code> |                                                                               |
| **`ok`**         | <code>boolean</code>                                          |                                                                               |
| **`error`**      | <code>string</code>                                           | Failure reason. Absent when `ok` is true.                                     |
| **`errorType`**  | <code>string</code>                                           | Exception class name, useful for distinguishing age-gating from geo-blocking. |
| **`durationMs`** | <code>number</code>                                           |                                                                               |


#### StreamInfo

| Prop                          | Type                                              | Description                                                                                                                                     |
| ----------------------------- | ------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------- |
| **`id`**                      | <code>string</code>                               |                                                                                                                                                 |
| **`url`**                     | <code>string</code>                               |                                                                                                                                                 |
| **`title`**                   | <code>string</code>                               |                                                                                                                                                 |
| **`duration`**                | <code>number</code>                               | Duration in seconds.                                                                                                                            |
| **`streamType`**              | <code><a href="#streamtype">StreamType</a></code> |                                                                                                                                                 |
| **`isLive`**                  | <code>boolean</code>                              |                                                                                                                                                 |
| **`uploader`**                | <code>string</code>                               |                                                                                                                                                 |
| **`uploaderUrl`**             | <code>string</code>                               |                                                                                                                                                 |
| **`uploaderAvatarUrl`**       | <code>string</code>                               |                                                                                                                                                 |
| **`uploaderVerified`**        | <code>boolean</code>                              |                                                                                                                                                 |
| **`uploaderSubscriberCount`** | <code>number</code>                               |                                                                                                                                                 |
| **`viewCount`**               | <code>number</code>                               |                                                                                                                                                 |
| **`likeCount`**               | <code>number</code>                               |                                                                                                                                                 |
| **`dislikeCount`**            | <code>number</code>                               | Restored via ReturnYouTubeDislike where the engine supports it.                                                                                 |
| **`description`**             | <code>string</code>                               |                                                                                                                                                 |
| **`textualUploadDate`**       | <code>string</code>                               |                                                                                                                                                 |
| **`uploadDate`**              | <code>string</code>                               | ISO-8601 upload timestamp.                                                                                                                      |
| **`category`**                | <code>string</code>                               |                                                                                                                                                 |
| **`tags`**                    | <code>{}</code>                                   |                                                                                                                                                 |
| **`thumbnailUrl`**            | <code>string</code>                               | Highest-resolution thumbnail, kept for convenience.                                                                                             |
| **`thumbnails`**              | <code>{}</code>                                   |                                                                                                                                                 |
| **`videoStreams`**            | <code>{}</code>                                   |                                                                                                                                                 |
| **`audioStreams`**            | <code>{}</code>                                   |                                                                                                                                                 |
| **`videoOnlyStreams`**        | <code>{}</code>                                   |                                                                                                                                                 |
| **`subtitles`**               | <code>{}</code>                                   |                                                                                                                                                 |
| **`sponsorBlockSegments`**    | <code>{}</code>                                   |                                                                                                                                                 |
| **`requiresSabr`**            | <code>boolean</code>                              | True when YouTube served this video only over SABR, meaning the stream arrays contain no directly playable URL. Open a SABR session to play it. |


#### Thumbnail

| Prop                           | Type                                                  |
| ------------------------------ | ----------------------------------------------------- |
| **`url`**                      | <code>string</code>                                   |
| **`width`**                    | <code>number</code>                                   |
| **`height`**                   | <code>number</code>                                   |
| **`estimatedResolutionLevel`** | <code>'HIGH' \| 'MEDIUM' \| 'LOW' \| 'UNKNOWN'</code> |


#### VideoStream

| Prop              | Type                 | Description                                         |
| ----------------- | -------------------- | --------------------------------------------------- |
| **`resolution`**  | <code>string</code>  |                                                     |
| **`width`**       | <code>number</code>  |                                                     |
| **`height`**      | <code>number</code>  |                                                     |
| **`fps`**         | <code>number</code>  |                                                     |
| **`isVideoOnly`** | <code>boolean</code> | True for adaptive video tracks that carry no audio. |


#### AudioStream

| Prop                 | Type                | Description                                     |
| -------------------- | ------------------- | ----------------------------------------------- |
| **`bitrate`**        | <code>number</code> | Average bitrate in bits per second.             |
| **`audioTrackId`**   | <code>string</code> | Track id for multi-language audio, e.g. `en.4`. |
| **`audioTrackName`** | <code>string</code> |                                                 |


#### SubtitleStream

| Prop                | Type                 | Description                                                     |
| ------------------- | -------------------- | --------------------------------------------------------------- |
| **`languageCode`**  | <code>string</code>  |                                                                 |
| **`autoGenerated`** | <code>boolean</code> | True when the track was machine-generated rather than authored. |


#### SponsorBlockSegment

| Prop              | Type                                                                  | Description                                           |
| ----------------- | --------------------------------------------------------------------- | ----------------------------------------------------- |
| **`uuid`**        | <code>string</code>                                                   |                                                       |
| **`category`**    | <code><a href="#sponsorblockcategory">SponsorBlockCategory</a></code> |                                                       |
| **`action`**      | <code>'SKIP' \| 'POI'</code>                                          | Either a span to skip, or a single point of interest. |
| **`startTimeMs`** | <code>number</code>                                                   |                                                       |
| **`endTimeMs`**   | <code>number</code>                                                   |                                                       |


#### OpenSabrSessionOptions

| Prop                  | Type                | Description                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| --------------------- | ------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **`videoUrl`**        | <code>string</code> |                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| **`startPositionMs`** | <code>number</code> | Start position in milliseconds. The session preloads around this point, so setting it avoids a wasted seek on resume. Default 0.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| **`maxHeight`**       | <code>number</code> | The tallest video format to open on, in pixels — 720 for 720p. **This is the quality the video will play at, not a ceiling it adapts under.** SABR selects one format when the session opens and serves only that one, and the manifest advertises only what the session can serve, so there is nothing for a player to adapt between. Omitted means the tallest format available, which on a 4K source is 2160p onto whatever handset asked — a real cost in decode and in data. Any host with an opinion about quality should state it. When every format is taller than the cap the shortest one is used, so a 1080p-only video asked for 720p plays at 1080p rather than failing. |


#### SabrSessionResult

| Prop                          | Type                 | Description                                                                                                                                                                                                                             |
| ----------------------------- | -------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **`success`**                 | <code>boolean</code> |                                                                                                                                                                                                                                         |
| **`error`**                   | <code>string</code>  |                                                                                                                                                                                                                                         |
| **`sessionId`**               | <code>string</code>  | Handle for {@link PipePlugin.closeSabrSession} and for native player lookup.                                                                                                                                                            |
| **`manifestUrl`**             | <code>string</code>  | A DASH manifest served on loopback, e.g. `http://127.0.0.1:52731/&lt;sessionId&gt;/manifest.mpd`. Playable by any web player (dash.js, Shaka) and by Media3's `DashMediaSource`. This is the portable path — it needs no native wiring. |
| **`nativePlaybackAvailable`** | <code>boolean</code> | True when Media3 is on the app's classpath, meaning native code can build a zero-copy MediaSource with `PipeSabr.mediaSourceFactory(sessionId)` instead of going over loopback.                                                         |
| **`formats`**                 | <code>{}</code>      |                                                                                                                                                                                                                                         |
| **`isLive`**                  | <code>boolean</code> |                                                                                                                                                                                                                                         |
| **`durationMs`**              | <code>number</code>  |                                                                                                                                                                                                                                         |


#### SabrFormat

A format offered by a SABR session.

| Prop                   | Type                            |
| ---------------------- | ------------------------------- |
| **`itag`**             | <code>number</code>             |
| **`mimeType`**         | <code>string</code>             |
| **`kind`**             | <code>'audio' \| 'video'</code> |
| **`width`**            | <code>number</code>             |
| **`height`**           | <code>number</code>             |
| **`bitrate`**          | <code>number</code>             |
| **`audioTrackId`**     | <code>string</code>             |
| **`approxDurationMs`** | <code>number</code>             |


#### PoTokenOptions

| Prop                | Type                | Description                                                                                                                               |
| ------------------- | ------------------- | ----------------------------------------------------------------------------------------------------------------------------------------- |
| **`videoId`**       | <code>string</code> | The 11-character YouTube video id the token was minted for.                                                                               |
| **`visitorData`**   | <code>string</code> | Visitor identity the token is bound to.                                                                                                   |
| **`clientVersion`** | <code>string</code> | InnerTube client version the token was minted against.                                                                                    |
| **`playerPoToken`** | <code>string</code> | The minted Proof-of-Origin token.                                                                                                         |
| **`ttlMs`**         | <code>number</code> | Lifetime in milliseconds. Defaults to just under 12 hours, matching the integrity token a PO token is derived from. Pass 0 for no expiry. |


#### EngineStatus

Availability of each engine and of the optional native playback path.

| Prop                  | Type                 | Description                                                                |
| --------------------- | -------------------- | -------------------------------------------------------------------------- |
| **`available`**       | <code>{}</code>      | Engines present on the classpath, in fallback order.                       |
| **`pipePipeVersion`** | <code>string</code>  |                                                                            |
| **`newPipeVersion`**  | <code>string</code>  |                                                                            |
| **`media3Available`** | <code>boolean</code> | True when androidx.media3 was found, enabling the native MediaSource path. |


### Type Aliases


#### ExtractionEngine

Which extractor produced a result.

<code>'pipepipe' | 'newpipe'</code>


#### StreamType

<code>'VIDEO_STREAM' | 'AUDIO_STREAM' | 'LIVE_STREAM' | 'AUDIO_LIVE_STREAM' | 'POST_LIVE_STREAM' | 'POST_LIVE_AUDIO_STREAM' | 'NONE'</code>


#### SponsorBlockCategory

<code>'SPONSOR' | 'INTRO' | 'OUTRO' | 'INTERACTION' | 'HIGHLIGHT' | 'SELF_PROMO' | 'NON_MUSIC' | 'PREVIEW' | 'FILLER' | 'PENDING'</code>

</docgen-api>
