# GlassLink Receiver v3.4.6

The GlassLink Receiver is the Android TV / Fire TV companion for the
[GlassLink iOS app](https://github.com/ImDasky). It is not a standalone
streaming app or a replacement for the iPhone app. Its purpose is to provide a
playback surface on the TV so the iOS app can send a webpage or stream to a
larger screen.

The receiver is designed to be used **in conjunction with** GlassLink on iOS:

1. GlassLink on the iPhone discovers or opens the content and sends the
   authorized cast request to the TV.
2. This receiver accepts that request and opens the content on the Android TV
   or Fire TV device.
3. The receiver identifies the actual video player, keeps the TV display
   focused on video rather than webpage controls, and reports playback status
   back to the GlassLink control flow.
4. The iPhone remains the companion controller, while the TV performs the
   playback and decoding locally.

This project contains the receiver that runs on the TV. It does not include the
GlassLink iOS app, replace iOS discovery or authorization, host a streaming
service, or provide a general-purpose TV browser. No cloud service or desktop
bridge is required during playback.

## What this companion tool is for

The receiver is intended for GlassLink users who want to cast supported
web-based video from the iPhone to an Android TV or Fire TV device. It is
especially useful for watch pages where the video is embedded inside one or
more frames, or where the page needs browser JavaScript before its HLS or DASH
stream becomes available.

It provides:

- A hidden TV playback surface launched by the GlassLink iOS app.
- Video-only presentation that suppresses surrounding webpage controls when
  the receiver can safely isolate the player.
- TV-remote playback controls and the shared GlassLink playback overlay.
- Browser playback for HTML5 and embedded players, with a safe native Media3
  fallback for supported public HLS/DASH streams.
- Local live-session DVR behavior for supported plain HLS streams.
- Playback diagnostics that explain whether a page, manifest, video track, or
  decoder is preventing playback.

The iOS app and this receiver are separate components. Installing this APK on
the TV does not install GlassLink on the iPhone, and installing GlassLink on
iOS does not automatically make every Android TV capable of playing every
website. The source site, its authentication, available video format, device
codec support, and any DRM policy still determine whether playback is possible.

- Package: `com.glasslink.receiver`
- Android receiver: **3.4.6** / `versionCode 17`
- Android Studio: JDK 17, Android SDK 35 and Gradle sync
- Android OS: minSdk 23 (specific WebView features also depend on the
  installed Android System WebView / Fire OS browser engine)

## Typical GlassLink workflow

Before installing the receiver, authorize the TV with the GlassLink iOS app
using its normal setup flow. Then install or update this receiver on the
authorized Android TV / Fire TV device. After that, use GlassLink on iOS to
choose content and cast it as usual; the receiver is started in the
background-style TV playback role and remains hidden from the TV launcher.

The iPhone sends the original page or stream request. The receiver does not
copy Safari's private cookies or arbitrary JavaScript state from the iPhone,
so a site that requires a TV-local login may need to be signed in on the TV
browser as well.

## Root problem: a watch page is not a video URL

When the iPhone casts `https://example.com/watch/channel/abc`, that URL points
at an HTML webpage. The page may create a player in an iframe hosted on a
*different* origin, then use hls.js or another JavaScript player to fetch an
HLS (`.m3u8`) or DASH (`.mpd`) manifest and segments. The visible HTML5
`<video>` element can have a temporary `blob:` MediaSource URL. That `blob:`
URL is **not transferable** to Media3 on Android: its data and lifetime belong
to the original browser document.

**Confirmed v3.3 defect:** The receiver only probed the *top-level document*
for HTML5 video. Its iframe recognition contained only special handling for
YouTube and Vimeo. It missed real video players running inside other vendors'
cross-origin frames and eventually said `NO_VIDEO_FOUND`, even when the player
loaded successfully in its own iframe.

## Changes in v3.4.6

- The receiver reasserts video-only styling as webpages update their controls
  or overlays. It only reveals an embedded player after its inner video is
  identified and isolated; an opaque fullscreen view must contain a separate
  video surface before it can be shown.
- The GlassLink controls remain above website fullscreen views. YouTube embeds
  use the documented setting that hides the provider control bar; quality or
  captions may be unavailable if the provider does not expose them separately.

## Changes in v3.4.5

- Web page playback and native stream playback now use the same GlassLink TV
  control overlay. When a page hands off to Media3, the control layout stays
  consistent. The native player still renders and decodes the stream.
- The shared controls operate native play/pause, rewind, forward, seeking,
  speed, captions, and sound. The native Options menu still offers quality,
  repeat, and Go live.

## Changes in v3.4.4

- Plain HLS MPEG transport streams now have a session DVR on the TV. The
  receiver records every segment it sees, feeds Media3 a growing local HLS
  playlist, and retains the existing native player. The
  playlist does not shrink to the origin server's short live window.
- The recorded session is deleted when the viewer casts another stream or
  closes the player. Recording stops if TV storage falls below 512 MiB free;
  segments already recorded in the current session remain available until the
  viewer switches streams or closes the player. The native Options menu has
  **Go live**.
- A transient Fire TV hardware decoder failure gets two native playback retries
  before the receiver considers its ordinary error fallback.
- Streams using encryption, fragmented MP4, byte ranges, or other HLS features
  outside this recorder's supported subset continue through ordinary playback.

## Changes in v3.4.3

- The native player now checks media response bytes for a PNG container whose
  pixels carry a compressed MPEG transport stream. It decodes that container
  for HLS playback on any host that serves the same format. No website domain
  or channel URL is hardcoded.
- Live streams that fall behind their available segment window seek back to
  the live edge automatically, with a bounded retry count.

## Changes in v3.4.2

- When a URL presented as video actually returns a webpage, the receiver tries
  that address in its covered browser player. This also recovers the original
  watch page after a browser-to-native manifest attempt fails.
- If the browser reports a decode error after requesting a public HLS/DASH
  manifest, the receiver tries that manifest in Media3 with the watch page as
  its referrer when the browser did not supply one.
- HTML5 video starts with sound enabled. The TV playback overlay now has a
  Sound button to mute or unmute regular and embedded HTML5 players.

## Changes in v3.4.1

1. **Subframe video detection**: Uses AndroidX WebKit's supported
   `DOCUMENT_START_SCRIPT` capability to run a small playback detector *inside*
   ordinary and nested third-party frames. Reports cross origins' **media state
   only** up to the main webpage via standard `window.postMessage`; it never
   sends private URLs, cookies or authorization credentials over the bridge.
2. **Real embedded playback controls**: Native TV remote commands are relayed
   to the frame that owns the HTML5 `<video>`, including Play/Pause, seeking,
   speed and captions when available.
3. **Video-only display**: Each frame in the active player chain hides its own
   surrounding page controls. GlassLink keeps a native black cover in front of
   the webpage until real frame/time progress has been verified before and
   after isolation. Non-video iframes are rejected.
4. **Native HLS/DASH fallback**: `shouldInterceptRequest` observes HTTP(S)
   `.m3u8` / `.mpd` playlist requests without modifying browser networking.
   If a stream stays invisible or stuck, a public manifest can be tried by
   Media3/ExoPlayer. Headers use a sanitized original referrer and the TV
   browser's User-Agent when available. Authenticated requests are not replayed
   with copied cookies or tokens.
5. **Older Fire TV compatibility**: If cross-frame document-start scripts are
   unavailable, and no video or manifest works, GlassLink can try ONE large,
   recognizable video-player iframe URL directly in the *same WebView* after
   about 21 seconds. It excludes obvious advertising frames and never exposes
   an ordinary webpage if the fallback fails.
6. **YouTube route**: The general frame detector defers to the official YouTube
   iframe. In v3.4.6 the provider's own control bar is hidden during playback.

## Build and install **locally** (no GitHub Actions)

1. Unzip the ZIP and open `GlassLink_Android_Receiver` in Android Studio.
2. Allow Gradle sync, then **Build > Build APK(s)**.
3. Find the fresh APK in `app/build/outputs/apk/debug/app-debug.apk`.
4. Update the receiver over your already authorized ADB device connection:

   ```sh
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

   Or use Android Studio's Run/Install, or your existing iOS app's generic APK
   installer. The receiver remains hidden from the TV launcher.
5. Use the existing GlassLink iOS app as before and cast the watch page.

Android rejects upgrades signed with a different key; in that case uninstall
only `com.glasslink.receiver` on the TV and install again, understanding that
its TV-local login cookies may be removed. Keep the same signing key on future
receiver builds.

## What the iOS app does and doesn't need to change

**No iOS code or casting-protocol changes are necessary** for using a v3.4.6
receiver already installed on your TV. The iPhone still sends the original
webpage URL and the TV makes playback decisions locally.

However, an iOS app *bundling v3.3 code 10* won't automatically upgrade that
receiver to v3.4.6 code 17. Install the new APK from your computer once. To ship
v3.4.6 *inside* a future iOS IPA for automatic first-time installation, the
bundled APK, its SHA-256 and its expected receiver version need updating.
The iPhone also doesn't copy Safari login cookies or selected in-page JavaScript
state; the TV may need its own permitted login for private streams.

## Device verification (2026-10-12)

- The v3.4.6 APK was installed on Fire TV AFTKRT. Sportsbite showed only the
  broadcast video and GlassLink's controls when opened; TVNow handed off to
  Media3 and displayed video without webpage controls. The JavaScript frame
  simulations passed, including attempts to restore hidden controls.
- The v3.4.5 APK was installed on Fire TV AFTKRT. TVNow played through Media3
  with the GlassLink control overlay visible; after more than two minutes the
  same overlay showed a 2:28 DVR timeline and private cached segments remained
  on the TV. The device's own audio mute was cleared separately; its active
  output route was a connected Sony Bluetooth speaker.
- Gradle built the APK and all 33 Android unit tests passed. The portable
  JavaScript simulations passed for direct video, embedded and nested players,
  sound controls, fullscreen isolation, and fallback selection.
- On a Fire TV AFTKRT, `https://sportsbite.org/watch/channel/abc` reached
  video-only playback. The embedded player initially reported muted and then
  reported unmuted after the receiver's sound correction. The Sound button is
  available in the TV playback overlay. Audible output requires the TV's active
  audio device and system volume to be set correctly.
- On the same Fire TV, `https://tvnow.st/watch/abc` first returned HTML. The
  receiver opened its watch page and observed an HLS playlist. Its segments
  arrived as PNG images carrying gzip-compressed MPEG transport data. The new
  format decoder unwrapped them; Media3 reported a 1280×720 first frame and
  `PLAYING`, and screen captures more than a minute apart confirmed changing
  video while new segments kept arriving. One earlier test fell behind its
  short live window, so the receiver also retries at the live edge.
- With the session DVR enabled, the same Fire TV showed more than 1 minute 40
  seconds on the native seek bar, and rewinding resumed earlier video. The TV
  saved transport segments in its private cache and removed them after a cast
  to another stream.

Stream ownership, authentication, content protection and DRM still apply. No
player can guarantee every codec or custom format; unsupported formats need
additional decoders or a compatible source. Some sites require a first-party
app or a particular device codec. This
receiver deliberately does not bypass DRM, proxy protected segments, export
cookies, intercept protected traffic or strip access controls.

See `STREAM_DIAGNOSTICS.md` for actionable status codes and
`CHANGELOG_V3_4.md` for detailed engineering changes.

## Audio with a black picture (v3.4.1)

Added a separate diagnostic state for **audio time advancing without new video
frames or valid video dimensions**. The WebView path now attempts its existing
safe HLS/DASH native fallback earlier if a standard manifest is available and
video decoding remains absent. It no longer considers a frozen frame count and
advancing audio clock to be successful video output.

The native Media3 player checks actual track selection and
`Player.Listener.onRenderedFirstFrame()`, instead of assuming that
`Player.isPlaying` proves video. After sustained audio-only playback it reports
whether no video track exists, video is unsupported, video was not selected, or
video was selected but produced no frame.

In **Media3 mode**, press **Menu > Try lower video quality** as an optional,
non-destructive attempt to select another advertised HLS/DASH rendition. Return
to **Default video quality** to remove that preference. This will only help when
the source offers an alternate stream compatible with the TV.

**Limit:** Neither this nor any simple receiver can decode arbitrary unsupported
video codecs, bypass DRM, or render a video that the server never supplies.
`VIDEO_FIRST_FRAME` proves the player submitted a frame to the display, not
that the frame's pixels are nonblack or that an overlay/DRM policy isn't hiding
it. Full Gradle/APK compilation and physical TV playback are still unverified.
