# The native player

Optional. A second Capacitor plugin in this package, registered separately from
the extractor — apps that only extract never touch it and never ship its
dependencies.

It exists because a WebView cannot produce the one thing a video player is
judged on: a drag-tracked, velocity-preserving transform between an inline rect
and fullscreen, over a page that never reflows underneath it. Everything else
here follows from that.

**Opinionated on purpose.** Accent colour is the entire styling surface. Layout,
sizing, spacing, timing and behaviour are fixed, because the interaction is the
product and a half-restyled version of it is worse than either extreme. If you
need a different player, this is the wrong library.

## Dependencies

Media3 and Compose are `compileOnly` in the plugin, so a playback consumer adds
them to its own app:

```gradle
implementation platform("androidx.compose:compose-bom:2026.06.01")
implementation 'androidx.compose.ui:ui'
implementation 'androidx.compose.foundation:foundation'
implementation 'androidx.compose.material:material-icons-extended'
implementation 'androidx.compose.material3:material3'   // ModalBottomSheet only
implementation 'androidx.activity:activity-compose:1.12.4'

implementation 'androidx.media3:media3-exoplayer:1.9.0'
implementation 'androidx.media3:media3-exoplayer-hls:1.9.0'    // live streams
implementation 'androidx.media3:media3-exoplayer-dash:1.9.0'   // SABR manifests
implementation 'androidx.media3:media3-ui-compose:1.9.0'       // video sizing
implementation 'androidx.window:window:1.5.1'                  // window metrics

// Picture-in-Picture only. Skip both if you do not use PiP.
implementation 'androidx.core:core-pip:1.0.0-alpha02'
implementation 'androidx.activity:activity:1.13.0'
```

**Do not bump `core-pip` past alpha02 without reading this.** `1.0.0-alpha03`
raises its floor to `compileSdk 37` and **AGP 9.1.0**, and AGP enforces that
through AAR metadata — it is a hard failure, not a warning. Capacitor 8 apps
ship AGP 8.13.0, so taking alpha03 would make AGP 9 a requirement for every app
that installs this plugin. The API also moved between the two: alpha03 added an
`Executor` constructor parameter and an explicit `commit()` that the setters now
need. See CLAUDE.md Gotcha 1.

**The two format modules are the easy ones to miss.** Media3 resolves them
*reflectively* from the URL, so leaving them out compiles and installs
perfectly and then throws when the stream opens. `getPlayerStatus()` reports
`hlsAvailable` and `dashAvailable` so you can check rather than guess.

## Live streams

Supported, with HLS on the classpath. The player detects liveness from the
timeline, shows a LIVE badge beside the elapsed time, drops the duration from
the timestamp (a live stream has none), and makes the scrubber a state
indicator rather than a control.

Press-and-hold 2× is disabled on live. There is no buffer ahead of the live
edge to play faster through, so the rate would climb, hit the edge and stall.

Note the current scrubber treats live as "at the live edge" and does not model a
DVR window — behind-the-edge playback shows a full bar.

## Loading presentation

Until the loaded media first reports `STATE_READY`, the player presents a
black box with a centred indeterminate spinner and **no controls chrome at
all**. That window is real and long: a `dock()` normally lands before the
`load()` does, and the host may spend seconds extracting in between — showing
the full play/pause/seek chrome centred over an empty surface there read as
the player being broken rather than the video arriving.

The corner mini player is the exception: its compact chrome — close, expand,
play/pause — stays available over the spinner. The close button is the only way
out of a floating window, and a load that never resolves (a stalled extraction,
a dead network) otherwise left a spinner nothing could dismiss short of killing
the app. The failure was observed on a real device (2026-08-28); the
chrome-over-spinner behaviour itself is device-unverified.

Readiness is **latched per load**, not mirrored from the playback state: a
mid-play rebuffer (a seek, a stall) shows the spinner over the video but never
strips the chrome back off. Every `load()` — including the reload that a seek
or a quality switch is — takes the presentation back to loading, and arrival
brings the chrome up before the usual auto-hide. A load issued around a
`release()` (the session epoch — see § The player outlives your pages) can no
longer flash chrome either: detach resets the latch, so a fresh attach starts
not-ready. Device-unverified.

## Following playback

The player draws its own scrubber, so a host never needs the position to render
one. What a host does need is where the viewer got to — a resume point, a
watched percentage, a completion — and that arrives as an event:

```ts
PipePlayer.addListener('playerPosition', ({ positionMs, durationMs, ended }) => {
  if (!durationMs) return;                    // not known yet, or live
  save({ positionMs, progress: (positionMs / durationMs) * 100, ended });
});
```

Roughly one event a second while playing, none at all while paused, and one
immediately on a play, a pause, a seek, an end, or the duration becoming known.
So it can be written straight to a record without a timer, a throttle or a
change check on the host side.

`getPosition()` answers the same question once, for the moments an event stream
suits badly: the `startPositionMs` for a reload at another quality, or a last
write as a page unmounts. It resolves zeroes when nothing is loaded rather than
rejecting.

## SABR sessions

`load()` takes an open session directly, which is the shortest route between
capacitor-pipe's two halves:

```ts
const { sessionId } = await Pipe.openSabrSession({ videoUrl, maxHeight: 720 });
await PipePlayer.load({ sessionId, startPositionMs: resumeAt });
```

The same session plays by passing its `manifestUrl` as `url` — identical media,
because both routes read the one synthesised manifest. This one skips the
loopback socket, the cleartext exemption its address needs, and an HTTP copy of
every segment. Prefer it whenever the player is this one; keep the URL route for
web players, which is what it exists for.

**Close the session when playback ends, not when `load()` resolves.** A session
holds a minted token, a listening socket and a spool directory, and the player
reads from it for as long as the video is on screen.

One quality per session — see the README. To change it, close, reopen with a new
`maxHeight`, and `load` again with the current position.

## Offline files

`load()` takes either a `url` or an `offline` source, never both and never
neither — a silent fall back to the network would hide a broken download behind
a data charge.

```ts
await PipePlayer.load({
  offline: {
    tracks: [
      { path: '/data/.../v.mp4', mimeType: 'video/mp4', cipher: video },
      { path: '/data/.../a.mp4', mimeType: 'audio/mp4', cipher: audio },
    ],
  },
  startPositionMs: 300_000,
});
```

### Why two tracks

YouTube muxes video and audio together only up to 360p (itag 18). Everything
above it is video-only and needs its audio merged at playback, so a two-track
source is the normal case rather than an edge one. One track is a muxed file;
two are merged with a `MergingMediaSource`.

### This package does not download

No HTTP, no queue, no `WorkManager`, no notification, no file layout, no key
storage. Paths, IVs and keys arrive from the host; the player turns them into
playable bytes and nothing else. That boundary is deliberate — Media3's
`DownloadManager` + `SimpleCache` was the first answer and is the wrong one
here, because `SimpleCache` allows one instance per directory per process, so a
downloader living in another plugin would have to export the singleton across a
plugin boundary with both sides pinned to the same Media3 version, and it forces
its opaque chunked format and its eviction model onto files the user thinks they
own.

### The cipher

**AES-128-CTR, no padding.** 16-byte key, 16-byte IV unique per file, no header,
no trailer, ciphertext the same length as the plaintext. Seeking to byte `p` is
arithmetic:

```
blockIndex   = p / 16
blockOffset  = p % 16
counterBlock = bigEndianAdd(iv, blockIndex)
```

then initialise with `counterBlock` and discard `blockOffset` keystream bytes.
The IV is not secret; store it beside the file and pass it as `ivBase64`.

Deliberately **not** Media3's `AesCipherDataSource`: it derives its nonce from
`DataSpec.key` through an internal FNV-64 hash, which would couple the on-disk
format to a Media3 implementation detail across a version bump and across two
separate codebases. The explicit IV is ours.

Honestly: this stops file-manager copying, USB pulls and other apps. It does not
stop a rooted device or someone who decompiles the APK. It is not DRM.

### Keys

`keyBase64` works and is the weaker option — the key crosses the bridge and sits
in a JS string. Prefer `keyRef`, an opaque host-defined string resolved in your
own Kotlin:

```kotlin
PipePlayerOffline.setKeyProvider { ref -> myKeyStore.unwrap(ref) }
```

A `keyRef` with no provider registered is a configuration error and rejects
saying so.

**The trap:** an `AndroidKeyStore` `SecretKey` is non-exportable, so
`secretKey.encoded` returns **null**. The provider cannot hand back a Keystore
key directly. The shape that works is a random 16-byte data key, wrapped with a
Keystore AES-GCM key and unwrapped on demand.

### Switching quality, or going offline↔online

Just another `load()` with `startPositionMs` set to the current position. The
position goes into `setMediaItem`/`setMediaSource` rather than a `seekTo` after
`prepare()`, so there is no flash of frame zero.

`getPlayerStatus().playingOffline` reports which kind of media is loaded. It is
reported, not rendered — the host still owns every piece of chrome text.

### Subtitles

None. The player renders no text track today, offline or online.

## Do not lock your Activity to portrait

```xml
<!-- NOT this, if you use the player -->
<activity android:screenOrientation="portrait" />
```

Fullscreen is a genuinely landscape Activity. The drag that gets you there is
faked — a config change cannot be driven continuously by a finger — but the
settled state is real, so a portrait lock silently vetoes it and leaves the
player in a rotated-inside-portrait limbo with two coordinate frames.

Keep `android:configChanges="orientation|screenSize|smallestScreenSize|screenLayout"`
on the activity. With it, rotating relayouts the Activity instead of recreating
it, so the player keeps its surface and playback never stops.

## The player outlives your pages

There is one player, and **pages lend it a rectangle**. `dock()` means "while
you are on my page, draw here"; `undock()` means "I am leaving, keep playing".
The player itself is attached to the Activity, so web navigation repaints
underneath it and playback is never interrupted — that is the whole reason it is
native rather than a `<video>` tag.

The consequence: **every page that wants the player must `dock()` on mount and
`undock()` on unmount.** A rect measured on one page is meaningless on the next.

**`undock()` enters mini mode itself — media loaded or not.** The mode and the
rect are one state, owned here: before this, the surface fell back to drawing
at the corner rect while the `mini` flag stayed wherever the last button press
left it — undock a full-size player and you got a corner-sized video wearing
the full docked chrome, immovable (the corner drag only arms in mini) and
answering the swipe-up fullscreen gesture. The same half-state reappeared when
a host undocked while its load was still in flight (back pressed during
extraction), which is why the original media-loaded guard is gone. And the
surface no longer trusts the flag alone: whenever no rect is claimed, the box
IS the corner window — compact chrome, rounded corners, corner drag, no
fullscreen gesture — whatever the mini axis says, and `dock()` re-aligns a
mini axis left mid-travel with the docked mode it arrives in. A host never
needs `setMini(true)` next to its `undock()`. The rect-keyed presentation and
the re-alignment are device-unverified; the half-state they close was observed
live.

**Mini transitions are serialised, and the chrome hides while one runs.**
`undock()` and `setMini()` animate the same axis, and each used to fire its own
launch; the launches are FIFO but their suspensions interleave, so an undock's
collapse-then-travel could land its corner animation *after* a host's
`setMini(false)` had already finished — parking the video at the corner while
the mode said docked. One owned job now replaces the previous transition
wholesale, so the newest intent always wins. And while the box travels between
the docked rect and the corner, no chrome is drawn at all: mid-travel the box
is roughly half size, and either set of controls laid out in it read as the
player broken at half height rather than as a window in motion.
Device-unverified.

**Adopting a corner video into a new page is `dock()` + `setMini(false)`.**
The corner player is the same live playback, so a page that owns the video
does not reload it: claim the rect, then ask the player out of the corner —
in that order, and the video travels into the rect with playback untouched.
`dock()` alone deliberately does not leave mini: a rect appearing is a page
mounting, not a request to expand, and the user may want the video to stay in
the corner while they read. (`load()` is the other way out of the corner: new
media claims the rect itself.)

So when the user minimises on page A, navigates to page B and presses expand,
there is nothing to expand into. The player stays in the corner and emits
`expandUnavailable` rather than guessing. Route back, the way YouTube does:

```js
PipePlayer.addListener('playerAction', (event) => {
  if (event.action === 'expandUnavailable') {
    // Send them back to whatever page owns this video. When it mounts and
    // calls dock(), the player is already there — and still playing.
    router.push(`/watch/${currentVideoId}`);
  }
});
```

Loading a new video is handled for you: `load()` clears the previous video's
aspect ratio, duration, position and ended state, cancels an in-flight 2× boost,
and animates the player out of the corner into the host's rect. Playback speed
survives on purpose — it is a user preference, not a property of the media.

**`release()` beats a pending `dock()`/`load()`.** Those two are the methods
that attach the overlay, and each does its work on a posted main-thread
runnable — so a `release()` can land in the gap between the call arriving and
its runnable running. The plugin keeps a session epoch: `release()` bumps it at
bridge entry, `dock()` and `load()` capture it at theirs, and a runnable whose
epoch has moved on rejects as stale (`"the player was released while this load
was pending"`) instead of touching the overlay. So a close issued while a load
is in flight tears the surface down at once, the stale load can neither
re-attach the overlay nor leak a prepared ExoPlayer, and PiP stays disarmed —
detach clears both the Activity's sticky params and the overlay's own latch. A
load issued *after* the release captures the new epoch and proceeds:
release-then-load is the normal way to start a fresh video. What this cannot
cover is work the plugin never sees — a host that is still extracting has not
called `load()` yet, and cancelling that is the host's job. Device-unverified.

## Picture-in-Picture

Opt in with `pip: true`, **and** declare it on your own Activity. A plugin's
manifest cannot do this for you: merging needs the activity's real name, which
the plugin does not know.

```xml
<activity
    android:name=".MainActivity"
    android:supportsPictureInPicture="true"
    android:configChanges="screenSize|smallestScreenSize|screenLayout|orientation" />
```

PiP is delegated to the Jetpack library rather than the platform API, so entry
is one `setEnabled` on every version — the library picks auto-enter on Android
12+ and `onUserLeaveHint` below it, reaching the latter through
`ComponentActivity`, which Capacitor's `Plugin` never exposed to us. `enterPip()`
exists for an explicit button; you rarely need it.

That is not a tidy-up. The hand-rolled version entered PiP and then showed the
host's web page, because the mode-change callback it waited on does not arrive on
Android 17 — which changed the Activity-recreation defaults.

**Auto-enter is armed only while the player is alive.** `setEnabled(true)`
lands on `Activity.setPictureInPictureParams`, which is sticky for the
Activity's lifetime, and core-pip's own `close()` releases only its bounds
tracker — it never clears the params (verified against alpha02's bytecode). So
`PipePlayerPip.close()` calls `setEnabled(false)` first, and the overlay drops
its `pipEnabled` latch on detach. Without both, releasing the player left
auto-enter armed and swiping home put the host's whole app — a web page, no
video — into a PiP window. Unverified on a physical device.

In PiP the system shrinks the *whole* window, WebView included, so the player
claims the entire window and shows its compact chrome — the same chrome the
corner mini player uses, because they are the same idea. In the corner that is
play/pause bottom-left, expand bottom-right, close top-right, and a progress
line flush to the bottom edge; in PiP only the progress line survives, because
the system owns input there — our buttons would sit dead under its control
strip, so play/pause goes into that strip as a RemoteAction instead.

The close button is the corner window's one irreversible control, kept
top-right where every floating window puts it and away from the two bottom
buttons. By default, pressing it emits `closed` and then the player releases
**itself** — surface down, playback stopped, PiP disarmed — so it works even
if no listener is registered. The event is the host's cue to end what only it
owns: an open SABR session, a download pin, whatever record says a video
floats.

A host can take the decision instead: `configure({ handleClose: true })` makes
the X emit `closeRequested` and nothing else. That exists because "close" is
ambiguous while the page that owns the video is on screen — the user usually
means "put it back", not "kill the lecture". The host answers by re-docking
(`dock()` + `setMini(false)`) when the owning page is mounted, or by calling
`release()` anywhere else. Opting in and not answering leaves the X inert;
that is the contract the flag signs. Device-unverified.

Known rough edge: the system's own PiP controls appear over ours on tap.

## Secure mode

`secure: true` sets `FLAG_SECURE`, which blocks screenshots and screen
recording. It is a **window** flag, so it necessarily covers the host's page as
well as the video; there is no way to secure only the player's pixels. It also
blanks the recents thumbnail and the PiP window, and disables casting on some
devices.

## Gestures

| Gesture | Effect |
| --- | --- |
| Drag up / down | Docked ⟷ fullscreen, tracked continuously and released with momentum |
| Tap | Toggle controls |
| Double tap | Seek ±10s, accumulating, with an edge ripple |
| Press and hold | 2× while held, restoring the *previous* rate on release |
| Pinch | Zoom to 2×, magnetic at the point where the video fills the shorter edge |
| Rotate the device | Follows the sensor into real landscape and back |

<docgen-index>

* [`getPlayerStatus()`](#getplayerstatus)
* [`dock(...)`](#dock)
* [`undock()`](#undock)
* [`load(...)`](#load)
* [`play()`](#play)
* [`getPosition()`](#getposition)
* [`pause()`](#pause)
* [`release()`](#release)
* [`configure(...)`](#configure)
* [`setFullscreen(...)`](#setfullscreen)
* [`setMini(...)`](#setmini)
* [`enterPip()`](#enterpip)
* [`addListener('playerAction', ...)`](#addlistenerplayeraction-)
* [`addListener('playerPosition', ...)`](#addlistenerplayerposition-)
* [Interfaces](#interfaces)
* [Type Aliases](#type-aliases)

</docgen-index>

<docgen-api>
<!--Update the source file JSDoc comments and rerun docgen to update the docs below-->

### getPlayerStatus()

```typescript
getPlayerStatus() => any
```

Whether the player can run in this app.

Its dependencies are optional, so absence is a normal state rather than an
error — check this before calling anything else. Every other method rejects
with an explanation rather than throwing `NoClassDefFoundError`, but asking
first is better than catching.

**Returns:** <code>any</code>

--------------------


### dock(...)

```typescript
dock(options: DockRect) => any
```

Declare where the host has reserved space for video.

Hosts declare a rect; they do not command the player into a position. The
player owns every transition between rects, which is what lets it animate
continuously rather than snapping between host-dictated states. Call again
on resize.

| Param         | Type                                          |
| ------------- | --------------------------------------------- |
| **`options`** | <code><a href="#dockrect">DockRect</a></code> |

**Returns:** <code>any</code>

--------------------


### undock()

```typescript
undock() => any
```

Release the claimed rect.

Claiming no rect is the signal to fall back to a floating mini-player, so
this is how a host says "I am navigating away but keep playing" — and the
player takes that signal literally: undocking with media loaded enters
mini mode itself (corner window, compact chrome, corner drag), so the
host does not need a `setMini(true)` alongside. `setMini(false)` — or the
expand button — brings it back once a rect is claimed again.

**Returns:** <code>any</code>

--------------------


### load(...)

```typescript
load(options: { url?: string; offline?: OfflineSource; sessionId?: string; startPositionMs?: number; }) => any
```

Load media and prepare it. Does not start playback.

Exactly one of `url`, `offline` and `sessionId`. Passing more than one, or
none, rejects — there is no implicit fallback, because a silent fall back
to the network would hide a broken download behind a data charge.

| Param         | Type                                                                                                                               |
| ------------- | ---------------------------------------------------------------------------------------------------------------------------------- |
| **`options`** | <code>{ url?: string; offline?: <a href="#offlinesource">OfflineSource</a>; sessionId?: string; startPositionMs?: number; }</code> |

**Returns:** <code>any</code>

--------------------


### play()

```typescript
play() => any
```

**Returns:** <code>any</code>

--------------------


### getPosition()

```typescript
getPosition() => any
```

Where playback is now, without waiting for the next event.

For the one-shot questions the event stream answers awkwardly: the position
to pass as `startPositionMs` when reloading at another quality, or what to
store as a page unmounts. Resolves zeroes when nothing is loaded — "no
video" is a state to read, not an error to handle.

**Returns:** <code>any</code>

--------------------


### pause()

```typescript
pause() => any
```

**Returns:** <code>any</code>

--------------------


### release()

```typescript
release() => any
```

Tear down the player and remove the overlay.

**Returns:** <code>any</code>

--------------------


### configure(...)

```typescript
configure(options: PlayerConfig) => any
```

Apply the accent colour and the two extension points.

| Param         | Type                                                  |
| ------------- | ----------------------------------------------------- |
| **`options`** | <code><a href="#playerconfig">PlayerConfig</a></code> |

**Returns:** <code>any</code>

--------------------


### setFullscreen(...)

```typescript
setFullscreen(options: { fullscreen: boolean; }) => any
```

Animate to fullscreen or back. The swipe gesture is the primary route in.

| Param         | Type                                  |
| ------------- | ------------------------------------- |
| **`options`** | <code>{ fullscreen: boolean; }</code> |

**Returns:** <code>any</code>

--------------------


### setMini(...)

```typescript
setMini(options: { mini: boolean; }) => any
```

Shrink to a corner window, or bring it back.

The same player animates to the corner — it is not a second, smaller
player — so playback is never interrupted. Combine with `undock()` when the
host navigates away from the page that owned the rect.

| Param         | Type                            |
| ------------- | ------------------------------- |
| **`options`** | <code>{ mini: boolean; }</code> |

**Returns:** <code>any</code>

--------------------


### enterPip()

```typescript
enterPip() => any
```

Enter Picture-in-Picture immediately.

Requires `pip: true` and the manifest declaration described on
{@link <a href="#playerconfig">PlayerConfig.pip</a>}; resolves `{ entered: false }` when either is
missing or the device does not support PiP. On Android 12+ you usually do
not need this — the system enters PiP on its own.

**Returns:** <code>any</code>

--------------------


### addListener('playerAction', ...)

```typescript
addListener(eventName: 'playerAction', listener: (event: PlayerActionEvent) => void) => any
```

Controls the host owns rather than the player.

Quality, speed and minimise are surfaced rather than implemented, because
the player has no opinion about your quality list, your speed menu, or what
minimising means in your layout.

| Param           | Type                                                                                |
| --------------- | ----------------------------------------------------------------------------------- |
| **`eventName`** | <code>'playerAction'</code>                                                         |
| **`listener`**  | <code>(event: <a href="#playeractionevent">PlayerActionEvent</a>) =&gt; void</code> |

**Returns:** <code>any</code>

--------------------


### addListener('playerPosition', ...)

```typescript
addListener(eventName: 'playerPosition', listener: (event: PlayerPosition) => void) => any
```

Follow playback position.

The player owns the scrubber, so this is not for drawing one — it is for
hosts that store progress: a resume point, a watched percentage, a
completion. Roughly one event a second while playing and none at all while
paused, so it can be written straight to a record.

| Param           | Type                                                                          |
| --------------- | ----------------------------------------------------------------------------- |
| **`eventName`** | <code>'playerPosition'</code>                                                 |
| **`listener`**  | <code>(event: <a href="#playerposition">PlayerPosition</a>) =&gt; void</code> |

**Returns:** <code>any</code>

--------------------


### Interfaces


#### PlayerStatus

| Prop                           | Type                 | Description                                                                                                                                                                                                                                                                                            |
| ------------------------------ | -------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| **`available`**                | <code>boolean</code> | True when both Media3 and Compose are present, so the player can run.                                                                                                                                                                                                                                  |
| **`media3Available`**          | <code>boolean</code> |                                                                                                                                                                                                                                                                                                        |
| **`composeAvailable`**         | <code>boolean</code> |                                                                                                                                                                                                                                                                                                        |
| **`attached`**                 | <code>boolean</code> | True once the overlay has been added to the host Activity.                                                                                                                                                                                                                                             |
| **`hlsAvailable`**             | <code>boolean</code> | Whether `media3-exoplayer-hls` is on the classpath. Worth checking before playing a live stream. Media3 loads format modules *reflectively* from the URL, so a missing one is invisible at build time and throws when the stream opens — an `.m3u8` will fail with this false.                         |
| **`dashAvailable`**            | <code>boolean</code> | Whether `media3-exoplayer-dash` is present — needed for SABR manifests.                                                                                                                                                                                                                                |
| **`media3UiComposeAvailable`** | <code>boolean</code> | Whether `media3-ui-compose` is present. **Required** for the player, not optional: the surface sizes the video with its `resizeWithContentScale`. `available` already accounts for it; this is here so a missing one is nameable rather than just "unavailable".                                       |
| **`corePipAvailable`**         | <code>boolean</code> | Whether `androidx.core:core-pip` is on the classpath. Reported separately from `pipSupported` so you can tell "this device cannot do PiP" apart from "you did not add the dependency".                                                                                                                 |
| **`pipSupported`**             | <code>boolean</code> | Whether Picture-in-Picture could work on this device. Note this reports the OS and hardware only. It cannot see whether your Activity declares `android:supportsPictureInPicture`, so this being true is necessary but not sufficient — see `pip` in {@link <a href="#playerconfig">PlayerConfig</a>}. |
| **`playingOffline`**           | <code>boolean</code> | True when the current media came from `offline` rather than `url`. Reported rather than rendered — the host still owns `qualityLabel` and every other piece of chrome text. This exists so a host does not have to shadow the state and drift out of sync with it.                                     |


#### DockRect

A rect in CSS pixels, as measured by the host's layout.

| Prop      | Type                | Description                                                                                                                                                                                                                                                                                                                                                    |
| --------- | ------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **`x`**   | <code>number</code> |                                                                                                                                                                                                                                                                                                                                                                |
| **`y`**   | <code>number</code> |                                                                                                                                                                                                                                                                                                                                                                |
| **`w`**   | <code>number</code> |                                                                                                                                                                                                                                                                                                                                                                |
| **`h`**   | <code>number</code> |                                                                                                                                                                                                                                                                                                                                                                |
| **`dpr`** | <code>number</code> | The WebView's `devicePixelRatio`. These numbers are CSS pixels and the player draws in device pixels, so something has to supply the ratio between them. Optional, and Android's `displayMetrics.density` stands in when it is absent — but the two do not always agree, and where they disagree the video lands short of the rect the host reserved. Send it. |


#### OfflineSource

| Prop         | Type                                                        | Description                                                                                                                                                                                                                        |
| ------------ | ----------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **`tracks`** | <code>[OfflineTrack] \| [OfflineTrack, OfflineTrack]</code> | One track when the file is muxed, two when video and audio were downloaded separately. Two is not an edge case: YouTube muxes only up to 360p (itag 18). Everything above it is video-only and needs its audio merged at playback. |


#### OfflineTrack

| Prop           | Type                                                    | Description                                                               |
| -------------- | ------------------------------------------------------- | ------------------------------------------------------------------------- |
| **`path`**     | <code>string</code>                                     | Absolute path to a local file. The player never guesses a directory.      |
| **`mimeType`** | <code>string</code>                                     | Container hint, e.g. 'video/mp4'. Used only to tell the two tracks apart. |
| **`cipher`**   | <code><a href="#offlinecipher">OfflineCipher</a></code> | Omit for a plaintext file.                                                |


#### PlayerPosition

Where playback has got to.

Sampled rather than pushed — ExoPlayer has no position callback — and
emitted about once a second while playing, plus immediately on a play,
pause, seek, end, or the duration becoming known. A host that records
progress writes on the event and needs no timer of its own.

| Prop             | Type                 | Description                                                           |
| ---------------- | -------------------- | --------------------------------------------------------------------- |
| **`positionMs`** | <code>number</code>  |                                                                       |
| **`durationMs`** | <code>number</code>  | 0 when the duration is not yet known, and always 0 for a live stream. |
| **`bufferedMs`** | <code>number</code>  |                                                                       |
| **`playing`**    | <code>boolean</code> |                                                                       |
| **`ended`**      | <code>boolean</code> | True once the video has run to its end. Cleared by the next `load()`. |
| **`live`**       | <code>boolean</code> |                                                                       |


#### PlayerConfig

| Prop                   | Type                                                  | Description                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| ---------------------- | ----------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **`accentColor`**      | <code>string</code>                                   | The one colour you control. Accepts `#RGB`, `#RRGGBB`, `#AARRGGBB`.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          |
| **`showPreviousNext`** | <code>boolean</code>                                  | Show previous/next. Off unless the host actually has a queue.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| **`title`**            | <code>string</code>                                   | Shown in fullscreen only — docked, the host page already has it.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| **`subtitle`**         | <code>string</code>                                   | Second line under the title, e.g. a channel name. Fullscreen only.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| **`speedLabel`**       | <code>string</code>                                   | Current playback rate, shown on the speed button, e.g. `'1x'`. Displayed, not owned. The player surfaces the tap and you decide what a speed menu contains.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| **`qualityLabel`**     | <code>string</code>                                   | Current quality, shown on the quality button, e.g. `'720p'`.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| **`speeds`**           | <code>{}</code>                                       | Rows for the speed sheet. Plain strings are accepted as their own label. The player applies the speed itself and emits `speedSelected`.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| **`qualities`**        | <code>{}</code>                                       | Rows for the quality sheet. Unlike speed, the player *cannot* apply this — which track to use is an extraction decision, not a playback one. It emits `qualitySelected` and leaves you to reload at the chosen level.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| **`pip`**              | <code>boolean</code>                                  | Allow Picture-in-Picture. Off unless asked for. **This alone is not enough.** PiP shrinks the host's entire window, so the host must also declare it on its own Activity — a plugin's manifest cannot merge into an activity whose name it does not know: ```xml &lt;activity android:name=".MainActivity" android:supportsPictureInPicture="true" android:configChanges="screenSize\|smallestScreenSize\|screenLayout\|orientation" /&gt; ``` It also needs `androidx.core:core-pip` on your classpath — check `corePipAvailable`. With both in place the system enters PiP by itself when the user leaves the app, on **every** supported version: the library reaches `onUserLeaveHint` through `ComponentActivity` on Android 8–11 and auto-enter on 12+, so there is nothing version-specific for you to write. Pin `core-pip` to `1.0.0-alpha02`. alpha03 requires AGP 9.1.0, which Capacitor 8 apps do not ship — see docs/PLAYER.md. |
| **`secure`**           | <code>boolean</code>                                  | Block screenshots and screen recording (`FLAG_SECURE`). A **window** flag, not a view one, so it necessarily covers the host's page too — there is no way to secure only the video. It also blanks the recents thumbnail and the PiP window, and on some devices disables casting.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| **`handleClose`**      | <code>boolean</code>                                  | The corner close button asks you instead of acting. Off by default: the X emits `closed` and the player releases itself, so it works even with no listener registered. Turned on, the X emits `closeRequested` and nothing else — you answer it: re-dock the video into its owning page (`dock()` + `setMini(false)`) when it is on screen, `release()` when it is not. Opting in and not answering leaves the X inert.                                                                                                                                                                                                                       |
| **`button`**           | <code><a href="#playerbutton">PlayerButton</a></code> | One custom control, placed after speed and quality. Exactly one, deliberately: a variable number would shuffle the fixed buttons around and cost them their stable position.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 |


#### PlayerOption

One row in the speed or quality sheet.

The player presents these; it does not invent them. It has no way to know
which quality levels your stream actually has.

| Prop        | Type                |
| ----------- | ------------------- |
| **`id`**    | <code>string</code> |
| **`label`** | <code>string</code> |


#### PlayerButton

A consumer-added control.

It gets *our* button — same size, hit area and press feedback as the
built-ins — with your glyph inside, so additions cannot look bolted on.

| Prop       | Type                | Description                                                                                                                                                                                                  |
| ---------- | ------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| **`id`**   | <code>string</code> | Returned in the `playerAction` event when tapped.                                                                                                                                                            |
| **`icon`** | <code>string</code> | SVG path data on a 24x24 viewBox, e.g. `'M8 5v14l11-7z'`. A path rather than an image so the icon crosses the bridge as plain data and renders in our style at any density — no asset packaging, no bitmaps. |


#### PlayerActionEvent

Emitted when a control the host owns is tapped.

| Prop           | Type                | Description                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| -------------- | ------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **`action`**   | <code>string</code> | One of: - `speed` / `quality` — the button was tapped and a sheet opened - `speedSelected` / `qualitySelected` — a row was chosen; `buttonId` is its id - `minimise` — shrunk to the corner; the player keeps playing - `expand` — left the corner window or PiP - `expandUnavailable` — expand was pressed with no rect claimed on this page. Route back to the page that owns the video; see docs/PLAYER.md. - `closed` — the corner mini player's close button. The player has already released itself when this arrives; the host's job is its OWN state — a SABR session, a download pin, whatever record says a video floats. - `closeRequested` — the same button with `handleClose` on. The player has done NOTHING: answer by re-docking the video into its owning page (`dock()` + `setMini(false)`) or by calling `release()`. - `previous` / `next` — queue controls, if you enabled them - `button` — your custom control; `buttonId` is its id |
| **`buttonId`** | <code>string</code> | The id of the button or the selected row, depending on `action`.                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |


### Type Aliases


#### OfflineCipher

AES-128-CTR, no padding.

No header, no trailer, and ciphertext is the same length as plaintext, so
the file seeks by arithmetic. The IV is not secret; the downloader stores it
beside the file and passes it here.

This stops file-manager copying, USB pulls and other apps. It does not stop
a rooted device or someone who decompiles the APK.

<code>{ kind: 'aes-ctr'; /** 16 bytes, base64. Unique per file. */ ivBase64: string; } & <a href="#offlinekey">OfflineKey</a></code>


#### OfflineKey

Where the decryption key comes from.

`keyRef` is the path to prefer: the key is resolved natively by a provider
the host registers, so it never crosses the bridge and never sits in a JS
string. `keyBase64` exists for hosts with no native code of their own — it
works, and it is weaker.

<code>{ keyRef: string } | { keyBase64: string }</code>

</docgen-api>
