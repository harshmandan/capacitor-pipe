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
}

export interface PlayerStatus {
  /** True when both Media3 and Compose are present, so the player can run. */
  available: boolean;
  media3Available: boolean;
  composeAvailable: boolean;
  /** True once the overlay has been added to the host Activity. */
  attached: boolean;
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
   * this is how a host says "I am navigating away but keep playing".
   */
  undock(): Promise<void>;

  /** Load a media URL and prepare it. Does not start playback. */
  load(options: { url: string }): Promise<void>;

  play(): Promise<void>;

  pause(): Promise<void>;

  /** Tear down the player and remove the overlay. */
  release(): Promise<void>;
}
