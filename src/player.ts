/**
 * Optional native player.
 *
 * A second, independent Capacitor plugin in this package. Apps that only
 * extract never register it and never ship its dependencies — Media3 and
 * Compose are `compileOnly` in the Android module, so playback consumers add
 * them to their own app.
 *
 * Android only, matching the rest of the package. The API is shaped so an iOS
 * implementation can be dropped in behind it later without moving call sites.
 */

/** A rect in CSS pixels, as measured by the host's layout. */
export interface DockRect {
  x: number;
  y: number;
  w: number;
  h: number;
  /**
   * The WebView's `devicePixelRatio`.
   *
   * These numbers are CSS pixels and the player draws in device pixels, so
   * something has to supply the ratio between them. Optional, and Android's
   * `displayMetrics.density` stands in when it is absent — but the two do not
   * always agree, and where they disagree the video lands short of the rect the
   * host reserved. Send it.
   */
  dpr?: number;
}

export interface PlayerStatus {
  /** True when both Media3 and Compose are present, so the player can run. */
  available: boolean;
  media3Available: boolean;
  composeAvailable: boolean;
  /** True once the overlay has been added to the host Activity. */
  attached: boolean;
  /**
   * Whether `media3-exoplayer-hls` is on the classpath.
   *
   * Worth checking before playing a live stream. Media3 loads format modules
   * *reflectively* from the URL, so a missing one is invisible at build time
   * and throws when the stream opens — an `.m3u8` will fail with this false.
   */
  hlsAvailable: boolean;
  /** Whether `media3-exoplayer-dash` is present — needed for SABR manifests. */
  dashAvailable: boolean;
  /**
   * Whether `media3-ui-compose` is present.
   *
   * **Required** for the player, not optional: the surface sizes the video with
   * its `resizeWithContentScale`. `available` already accounts for it; this is
   * here so a missing one is nameable rather than just "unavailable".
   */
  media3UiComposeAvailable: boolean;
  /**
   * Whether `androidx.core:core-pip` is on the classpath.
   *
   * Reported separately from `pipSupported` so you can tell "this device cannot
   * do PiP" apart from "you did not add the dependency".
   */
  corePipAvailable: boolean;
  /**
   * Whether Picture-in-Picture could work on this device.
   *
   * Note this reports the OS and hardware only. It cannot see whether your
   * Activity declares `android:supportsPictureInPicture`, so this being true
   * is necessary but not sufficient — see `pip` in {@link PlayerConfig}.
   */
  pipSupported: boolean;
  /**
   * True when the current media came from `offline` rather than `url`.
   *
   * Reported rather than rendered — the host still owns `qualityLabel` and
   * every other piece of chrome text. This exists so a host does not have to
   * shadow the state and drift out of sync with it.
   */
  playingOffline: boolean;
}

/**
 * One row in the speed or quality sheet.
 *
 * The player presents these; it does not invent them. It has no way to know
 * which quality levels your stream actually has.
 */
export interface PlayerOption {
  id: string;
  label: string;
}

/**
 * A consumer-added control.
 *
 * It gets *our* button — same size, hit area and press feedback as the
 * built-ins — with your glyph inside, so additions cannot look bolted on.
 */
export interface PlayerButton {
  /** Returned in the `playerAction` event when tapped. */
  id: string;
  /**
   * SVG path data on a 24x24 viewBox, e.g. `'M8 5v14l11-7z'`.
   *
   * A path rather than an image so the icon crosses the bridge as plain data
   * and renders in our style at any density — no asset packaging, no bitmaps.
   */
  icon: string;
}

/**
 * The player's entire styling surface.
 *
 * Deliberately this small. Layout, sizing, spacing, timing and behaviour are
 * fixed: the interaction is the product, and a half-restyled version of it is
 * worse than either extreme. If you need a different player, this is the wrong
 * library.
 */
/**
 * Where the decryption key comes from.
 *
 * `keyRef` is the path to prefer: the key is resolved natively by a provider
 * the host registers, so it never crosses the bridge and never sits in a JS
 * string. `keyBase64` exists for hosts with no native code of their own — it
 * works, and it is weaker.
 */
export type OfflineKey = { keyRef: string } | { keyBase64: string };

/**
 * AES-128-CTR, no padding.
 *
 * No header, no trailer, and ciphertext is the same length as plaintext, so
 * the file seeks by arithmetic. The IV is not secret; the downloader stores it
 * beside the file and passes it here.
 *
 * This stops file-manager copying, USB pulls and other apps. It does not stop
 * a rooted device or someone who decompiles the APK.
 */
export type OfflineCipher = {
  kind: 'aes-ctr';
  /** 16 bytes, base64. Unique per file. */
  ivBase64: string;
} & OfflineKey;

export interface OfflineTrack {
  /** Absolute path to a local file. The player never guesses a directory. */
  path: string;
  /** Container hint, e.g. 'video/mp4'. Used only to tell the two tracks apart. */
  mimeType?: string;
  /** Omit for a plaintext file. */
  cipher?: OfflineCipher;
}

export interface OfflineSource {
  /**
   * One track when the file is muxed, two when video and audio were
   * downloaded separately.
   *
   * Two is not an edge case: YouTube muxes only up to 360p (itag 18).
   * Everything above it is video-only and needs its audio merged at playback.
   */
  tracks: [OfflineTrack] | [OfflineTrack, OfflineTrack];
}

export interface PlayerConfig {
  /** The one colour you control. Accepts `#RGB`, `#RRGGBB`, `#AARRGGBB`. */
  accentColor?: string;
  /** Show previous/next. Off unless the host actually has a queue. */
  showPreviousNext?: boolean;
  /** Shown in fullscreen only — docked, the host page already has it. */
  title?: string;
  /** Second line under the title, e.g. a channel name. Fullscreen only. */
  subtitle?: string;
  /**
   * Current playback rate, shown on the speed button, e.g. `'1x'`.
   *
   * Displayed, not owned. The player surfaces the tap and you decide what a
   * speed menu contains.
   */
  speedLabel?: string;
  /** Current quality, shown on the quality button, e.g. `'720p'`. */
  qualityLabel?: string;
  /**
   * Rows for the speed sheet. Plain strings are accepted as their own label.
   *
   * The player applies the speed itself and emits `speedSelected`.
   */
  speeds?: (PlayerOption | string)[];
  /**
   * Rows for the quality sheet.
   *
   * Unlike speed, the player *cannot* apply this — which track to use is an
   * extraction decision, not a playback one. It emits `qualitySelected` and
   * leaves you to reload at the chosen level.
   */
  qualities?: (PlayerOption | string)[];
  /**
   * Allow Picture-in-Picture. Off unless asked for.
   *
   * **This alone is not enough.** PiP shrinks the host's entire window, so the
   * host must also declare it on its own Activity — a plugin's manifest cannot
   * merge into an activity whose name it does not know:
   *
   * ```xml
   * <activity
   *     android:name=".MainActivity"
   *     android:supportsPictureInPicture="true"
   *     android:configChanges="screenSize|smallestScreenSize|screenLayout|orientation" />
   * ```
   *
   * It also needs `androidx.core:core-pip` on your classpath — check
   * `corePipAvailable`. With both in place the system enters PiP by itself when
   * the user leaves the app, on **every** supported version: the library reaches
   * `onUserLeaveHint` through `ComponentActivity` on Android 8–11 and
   * auto-enter on 12+, so there is nothing version-specific for you to write.
   *
   * Pin `core-pip` to `1.0.0-alpha02`. alpha03 requires AGP 9.1.0, which
   * Capacitor 8 apps do not ship — see docs/PLAYER.md.
   */
  pip?: boolean;
  /**
   * Block screenshots and screen recording (`FLAG_SECURE`).
   *
   * A **window** flag, not a view one, so it necessarily covers the host's page
   * too — there is no way to secure only the video. It also blanks the recents
   * thumbnail and the PiP window, and on some devices disables casting.
   */
  secure?: boolean;
  /**
   * One custom control, placed after speed and quality.
   *
   * Exactly one, deliberately: a variable number would shuffle the fixed
   * buttons around and cost them their stable position.
   */
  button?: PlayerButton;
}

/** Emitted when a control the host owns is tapped. */
export interface PlayerActionEvent {
  /**
   * One of:
   *
   * - `speed` / `quality` — the button was tapped and a sheet opened
   * - `speedSelected` / `qualitySelected` — a row was chosen; `buttonId` is its id
   * - `minimise` — shrunk to the corner; the player keeps playing
   * - `expand` — left the corner window or PiP
   * - `expandUnavailable` — expand was pressed with no rect claimed on this
   *   page. Route back to the page that owns the video; see docs/PLAYER.md.
   * - `previous` / `next` — queue controls, if you enabled them
   * - `button` — your custom control; `buttonId` is its id
   */
  action: string;
  /** The id of the button or the selected row, depending on `action`. */
  buttonId?: string;
}

/**
 * Where playback has got to.
 *
 * Sampled rather than pushed — ExoPlayer has no position callback — and
 * emitted about once a second while playing, plus immediately on a play,
 * pause, seek, end, or the duration becoming known. A host that records
 * progress writes on the event and needs no timer of its own.
 */
export interface PlayerPosition {
  positionMs: number;
  /** 0 when the duration is not yet known, and always 0 for a live stream. */
  durationMs: number;
  bufferedMs: number;
  playing: boolean;
  /** True once the video has run to its end. Cleared by the next `load()`. */
  ended: boolean;
  live: boolean;
}

export interface PipePlayerPlugin {
  /**
   * Whether the player can run in this app.
   *
   * Its dependencies are optional, so absence is a normal state rather than an
   * error — check this before calling anything else. Every other method rejects
   * with an explanation rather than throwing `NoClassDefFoundError`, but asking
   * first is better than catching.
   */
  getPlayerStatus(): Promise<PlayerStatus>;

  /**
   * Declare where the host has reserved space for video.
   *
   * Hosts declare a rect; they do not command the player into a position. The
   * player owns every transition between rects, which is what lets it animate
   * continuously rather than snapping between host-dictated states. Call again
   * on resize.
   */
  dock(options: DockRect): Promise<void>;

  /**
   * Release the claimed rect.
   *
   * Claiming no rect is the signal to fall back to a floating mini-player, so
   * this is how a host says "I am navigating away but keep playing" — and the
   * player takes that signal literally: undocking with media loaded enters
   * mini mode itself (corner window, compact chrome, corner drag), so the
   * host does not need a `setMini(true)` alongside. `setMini(false)` — or the
   * expand button — brings it back once a rect is claimed again.
   */
  undock(): Promise<void>;

  /**
   * Load media and prepare it. Does not start playback.
   *
   * Exactly one of `url`, `offline` and `sessionId`. Passing more than one, or
   * none, rejects — there is no implicit fallback, because a silent fall back
   * to the network would hide a broken download behind a data charge.
   */
  load(options: {
    url?: string;
    offline?: OfflineSource;
    /**
     * An open SABR session, from `Pipe.openSabrSession`, played through its
     * segment bridge directly.
     *
     * The same session is playable by passing its `manifestUrl` as `url`;
     * this skips the loopback socket, the cleartext exemption and an HTTP copy
     * of every segment. Identical media either way — both routes read the one
     * synthesised manifest — so this is an optimisation, not a capability.
     *
     * The session must outlive playback: close it when the player is done, not
     * when `load` resolves.
     */
    sessionId?: string;
    /**
     * Where to start, in milliseconds.
     *
     * Default 0, matching the existing behaviour: a new video starts at the
     * beginning. Set it when the media is changing but the *video* is not —
     * a quality switch, or going offline↔online — so the switch does not
     * restart playback.
     */
    startPositionMs?: number;
  }): Promise<void>;

  play(): Promise<void>;

  /**
   * Where playback is now, without waiting for the next event.
   *
   * For the one-shot questions the event stream answers awkwardly: the position
   * to pass as `startPositionMs` when reloading at another quality, or what to
   * store as a page unmounts. Resolves zeroes when nothing is loaded — "no
   * video" is a state to read, not an error to handle.
   */
  getPosition(): Promise<PlayerPosition>;

  pause(): Promise<void>;

  /** Tear down the player and remove the overlay. */
  release(): Promise<void>;

  /** Apply the accent colour and the two extension points. */
  configure(options: PlayerConfig): Promise<void>;

  /** Animate to fullscreen or back. The swipe gesture is the primary route in. */
  setFullscreen(options: { fullscreen: boolean }): Promise<void>;

  /**
   * Shrink to a corner window, or bring it back.
   *
   * The same player animates to the corner — it is not a second, smaller
   * player — so playback is never interrupted. Combine with `undock()` when the
   * host navigates away from the page that owned the rect.
   */
  setMini(options: { mini: boolean }): Promise<void>;

  /**
   * Enter Picture-in-Picture immediately.
   *
   * Requires `pip: true` and the manifest declaration described on
   * {@link PlayerConfig.pip}; resolves `{ entered: false }` when either is
   * missing or the device does not support PiP. On Android 12+ you usually do
   * not need this — the system enters PiP on its own.
   */
  enterPip(): Promise<{ entered: boolean }>;

  /**
   * Controls the host owns rather than the player.
   *
   * Quality, speed and minimise are surfaced rather than implemented, because
   * the player has no opinion about your quality list, your speed menu, or what
   * minimising means in your layout.
   */
  addListener(
    eventName: 'playerAction',
    listener: (event: PlayerActionEvent) => void,
  ): Promise<import('@capacitor/core').PluginListenerHandle>;

  /**
   * Follow playback position.
   *
   * The player owns the scrubber, so this is not for drawing one — it is for
   * hosts that store progress: a resume point, a watched percentage, a
   * completion. Roughly one event a second while playing and none at all while
   * paused, so it can be written straight to a record.
   */
  addListener(
    eventName: 'playerPosition',
    listener: (event: PlayerPosition) => void,
  ): Promise<import('@capacitor/core').PluginListenerHandle>;
}
