# Receiver v3.4 — stream playback engineering changes

Built directly on the **user-supplied v3.3 ZIP**, preserving all custom YouTube
quality, captions, official iframe behavior, D-pad pointer and TV overlay code.

## Previously confirmed defects

- `WebVideoScript.glTarget()` only found `<video>` in the parent DOM, then
  recognized only YouTube and Vimeo iframe URLs. Cross-origin live-stream
  players hosted in arbitrary iframe domains were categorically missed.
- If the webpage player used MediaSource Extensions, its `<video src="blob:…">`
  did not identify a transferable HTTP media file for ExoPlayer.
- Direct media was routed to Media3 only for URLs known at Cast time or exposed
  as plain HTTP video `src`, leaving standard HLS/DASH media manifests fetched
  by a webpage invisible to the native fallback.

## Targeted changes

- `FramePlaybackScript.kt`: unprivileged document-start JS runs in supported
  cross-origin frames and relays bounded media state upward by postMessage,
  matching message senders to actual iframe windows.
- `WebVideoScript.kt`: probes `frame-video` in addition to existing video/
  YouTube/Vimeo paths; Play/Pause/seek commands travel to the actual player;
  parent and descendant frame isolation/restore are coordinated; conservative
  standalone iframe fallback for old WebView engines.
- `WebPlayerController.kt`: registers document-start script when AndroidX
  WebKit feature is supported; observes HLS/DASH manifest fetches without
  replacing requests; activates native Media3 or a one-time embedded-player
  navigation when no compatible web player is found.
- `MediaManifestDiscovery.kt`: validates clear HTTP(S) manifests, rejects
  obvious ads, private hosts and requests with bearer authorization, sanitizes
  only the standard referrer, and keeps signed URLs in memory without logging.
- `PlayerActivity.kt`: existing Media3 engine consumes fallback URLs with the
  associated TV-browser user-agent and sanitized referrer.
- Android receiver versionCode 11 / versionName 3.4.0; iOS protocol unchanged.
- Added JUnit manifest/script regression tests and a portable end-to-end
  nested-frame JavaScript simulation.

## Still not possible to guarantee

Websites may restrict embedding, browser automation, media requests, login
cookies, codecs or DRM. The example site was not retrievable from this
execution environment, so exact compatibility remains unverified. This is a
source package, not a precompiled or hardware-tested APK.
