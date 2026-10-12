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
