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

In PiP the system shrinks the *whole* window, WebView included, so the player
claims the entire window and shows its compact controls: play/pause bottom-left,
expand bottom-right, and a progress line flush to the bottom edge — the same
chrome the corner mini player uses, because they are the same idea.

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
* [`pause()`](#pause)
* [`release()`](#release)
* [`configure(...)`](#configure)
* [`setFullscreen(...)`](#setfullscreen)
* [`setMini(...)`](#setmini)
* [`enterPip()`](#enterpip)
* [`addListener('playerAction', ...)`](#addlistenerplayeraction-)
* [Interfaces](#interfaces)

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
this is how a host says "I am navigating away but keep playing".

**Returns:** <code>any</code>

--------------------


### load(...)

```typescript
load(options: { url: string; }) => any
```

Load a media URL and prepare it. Does not start playback.

| Param         | Type                          |
| ------------- | ----------------------------- |
| **`options`** | <code>{ url: string; }</code> |

**Returns:** <code>any</code>

--------------------


### play()

```typescript
play() => any
```

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


#### DockRect

A rect in CSS pixels, as measured by the host's layout.

| Prop    | Type                |
| ------- | ------------------- |
| **`x`** | <code>number</code> |
| **`y`** | <code>number</code> |
| **`w`** | <code>number</code> |
| **`h`** | <code>number</code> |


#### PlayerConfig

The player's entire styling surface.

Deliberately this small. Layout, sizing, spacing, timing and behaviour are
fixed: the interaction is the product, and a half-restyled version of it is
worse than either extreme. If you need a different player, this is the wrong
library.

| Prop                   | Type                                                  | Description                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| ---------------------- | ----------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **`accentColor`**      | <code>string</code>                                   | The one colour you control. Accepts `#RGB`, `#RRGGBB`, `#AARRGGBB`.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| **`showPreviousNext`** | <code>boolean</code>                                  | Show previous/next. Off unless the host actually has a queue.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| **`title`**            | <code>string</code>                                   | Shown in fullscreen only — docked, the host page already has it.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| **`subtitle`**         | <code>string</code>                                   | Second line under the title, e.g. a channel name. Fullscreen only.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| **`speedLabel`**       | <code>string</code>                                   | Current playback rate, shown on the speed button, e.g. `'1x'`. Displayed, not owned. The player surfaces the tap and you decide what a speed menu contains.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| **`qualityLabel`**     | <code>string</code>                                   | Current quality, shown on the quality button, e.g. `'720p'`.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| **`speeds`**           | <code>{}</code>                                       | Rows for the speed sheet. Plain strings are accepted as their own label. The player applies the speed itself and emits `speedSelected`.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| **`qualities`**        | <code>{}</code>                                       | Rows for the quality sheet. Unlike speed, the player *cannot* apply this — which track to use is an extraction decision, not a playback one. It emits `qualitySelected` and leaves you to reload at the chosen level.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| **`pip`**              | <code>boolean</code>                                  | Allow Picture-in-Picture. Off unless asked for. **This alone is not enough.** PiP shrinks the host's entire window, so the host must also declare it on its own Activity — a plugin's manifest cannot merge into an activity whose name it does not know: ```xml &lt;activity android:name=".MainActivity" android:supportsPictureInPicture="true" android:configChanges="screenSize\|smallestScreenSize\|screenLayout\|orientation" /&gt; ``` It also needs `androidx.core:core-pip` on your classpath — check `corePipAvailable`. With both in place the system enters PiP by itself when the user leaves the app, on **every** supported version: the library reaches `onUserLeaveHint` through `ComponentActivity` on Android 8–11 and auto-enter on 12+, so there is nothing version-specific for you to write. Pin `core-pip` to `1.0.0-alpha02`. alpha03 requires AGP 9.1.0, which Capacitor 8 apps do not ship — see PLAYER.md. |
| **`secure`**           | <code>boolean</code>                                  | Block screenshots and screen recording (`FLAG_SECURE`). A **window** flag, not a view one, so it necessarily covers the host's page too — there is no way to secure only the video. It also blanks the recents thumbnail and the PiP window, and on some devices disables casting.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| **`button`**           | <code><a href="#playerbutton">PlayerButton</a></code> | One custom control, placed after speed and quality. Exactly one, deliberately: a variable number would shuffle the fixed buttons around and cost them their stable position.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |


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

| Prop           | Type                | Description                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| -------------- | ------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **`action`**   | <code>string</code> | One of: - `speed` / `quality` — the button was tapped and a sheet opened - `speedSelected` / `qualitySelected` — a row was chosen; `buttonId` is its id - `minimise` — shrunk to the corner; the player keeps playing - `expand` — left the corner window or PiP - `expandUnavailable` — expand was pressed with no rect claimed on this page. Route back to the page that owns the video; see PLAYER.md. - `previous` / `next` — queue controls, if you enabled them - `button` — your custom control; `buttonId` is its id |
| **`buttonId`** | <code>string</code> | The id of the button or the selected row, depending on `action`.                                                                                                                                                                                                                                                                                                                                                                                                                                                             |

</docgen-api>
