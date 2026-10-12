# Build / update only the Android companion on your computer

No GitHub Actions or Xcode required.

1. Extract `GlassLink_v3_4_Android_Studio.zip`.
2. Open the resulting `GlassLink_Android_Receiver` folder in Android Studio.
3. Gradle sync with JDK 17 and Android SDK 35.
4. Build → Build APK(s).
5. Locate the **new** `app/build/outputs/apk/debug/app-debug.apk`.
6. With your TV already connected to ADB:

   ```sh
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

The binary needs to report package `com.glasslink.receiver`,
`versionCode=17`, `versionName=3.4.6`. You may check with Android SDK tools:

```sh
aapt dump badging app/build/outputs/apk/debug/app-debug.apk
```

If the install reports `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, Android has
rejected a different signing key; remove the prior receiver first. No iOS
rebuild is required after manually installing v3.4, because its casting
command syntax is unchanged.

**Important:** The existing iOS app may still have the **older v3.3 receiver APK
embedded**. That affects one-tap automatic deployment to a fresh TV, but does
not prevent casting to the newer manually installed receiver.

Android Studio can run the ordinary JUnit tests:

```sh
gradle :app:testDebugUnitTest :app:assembleDebug
```

Portable JavaScript tests (Node.js, Kotlin CLI, JDK):

```sh
./qa/test_frame_scripts.sh
```

After building, inspect video diagnostics with `adb logcat -d -t 200 -s
GlassLinkRx:I '*:S'`. See `STREAM_DIAGNOSTICS.md` for audio-with-black-screen
codes. No iOS project update is required when the receiver is installed
separately and your iPhone is already authorized to cast to it.
