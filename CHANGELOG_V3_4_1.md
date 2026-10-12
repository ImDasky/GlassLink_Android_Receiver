# Receiver v3.4.1: audio-with-black-screen diagnostics

- WebView playback now distinguishes audio-clock progress from actual video
  progress; frozen decoded-frame counters no longer count as playing video.
- Persistent audio-only progress gets its own `AUDIO_NO_VIDEO` state. When a
  standard manifest is available, the existing native Media3 fallback is
  attempted after a short bounded delay.
- Native Media3 observes available/supported/selected audio and video tracks,
  first rendered video frame and video resolution independently of audio.
- A D-pad Menu choice lets users request a lower video-quality rendition and
  revert to default quality, when the playlist offers alternatives.
- Stop hidden audio when WebView fails and displays its black error screen.
- No changes to GlassLink's iOS casting protocol, YouTube and iframe routing,
  receiver package identity or launcher-hidden design.
- Receiver version 3.4.1, Android versionCode 12. Build in Android Studio,
  then manually install on your TV because bundled older iOS APKs are stale.

This is a source update; compile and TV-specific decoding remain unverified.
