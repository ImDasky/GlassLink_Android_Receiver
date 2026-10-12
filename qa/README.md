# Portable iframe playback regression checks

With a JDK, Kotlin compiler and Node.js installed:

```sh
./qa/test_frame_scripts.sh
```

This assembles the exact WebView scripts from Kotlin, checks JavaScript syntax,
and simulates a video in a third-party iframe, a video two iframes deep,
remote Play/Pause, fullscreen isolation and restoration, and a non-video iframe
that must be rejected. It does NOT contact a video site or substitute for TV
hardware testing.

Android Studio can run the Kotlin/JUnit tests through `:app:testDebugUnitTest`.
