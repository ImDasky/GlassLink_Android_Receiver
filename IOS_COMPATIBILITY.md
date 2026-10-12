# Does the existing Nyxian iOS app need an update?

**Not to cast to a TV where v3.4.6 is already installed.** This receiver retains
`com.glasslink.receiver`, the same exported activity and the same ADB extras:
`gl_mode`, `gl_page`, `gl_mime`, `gl_referrer`, `gl_useragent` and `gl_request`.
It continues to accept the original watch-page URL. All new iframe/HLS logic
runs entirely in the Android receiver, locally.

**One installer limitation**: the existing iOS v3.3 build may bundle the old
receiver APK and compare installed `versionCode` against `10`. It therefore
won't upgrade an already installed v3.3 receiver to v3.4.6 code `17` on its own.
Build and **install the v3.4.6 APK from your computer once**. If later you want
zero-step first-time TV installation, only the iOS app's bundled APK resource,
checksum, size and receiver version check need updating; its Cast UI/network
code does not.

**Session limitation**: iPhone Safari/WKWebView authentication cookies and the
state of a dynamically selected video are not serialized into the cast intent.
The TV independently loads the provided watch URL, and protected streams may
require a permitted TV-local login or official provider application.

**v3.4.6 note:** No iOS code change is needed for casting to a separately
installed v3.4.6 receiver (versionCode 17). If your existing iOS build bundles
v3.3, it cannot automatically provide the new APK; install this new Android
receiver once through Android Studio / ADB. Android will refuse in-place
updates signed with a different certificate; then uninstall the old receiver
first, which may remove TV-side saved website data.
