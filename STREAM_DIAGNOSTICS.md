# Diagnose a stream on the TV without exposing private URLs

After building and installing v3.4, cast the failing watch page again. Let it
attempt playback for approximately 30–60 seconds, then on the connected
computer run:

```sh
adb logcat -d -t 200 -s GlassLinkRx:I '*:S'
```

The receiver reports a short request ID plus safe event names and limited
status fields. It does not log the original watch URL, stream URL, cookies or
signed media tokens. **Do not share raw network request headers or private
stream links** when asking for help; the short status lines suffice.

| Status | What it means |
|---|---|
| `FRAME_DETECTION_READY` | TV's WebView supports document-start per-frame detection |
| `FRAME_DETECTION_UNAVAILABLE` | Fire TV WebView is too old or lacks this feature |
| `VIDEO_DETECTED frame-video` | An actual HTML5 video was reported from an embedded player |
| `VIDEO_DETECTED video` | Main webpage contains an HTML5 video |
| `PLAY_REQUESTED` | `play()` was sent; playback has not yet been confirmed |
| `BUFFERING` / `WEB_STALLED` | Manifest/duration may load but frames do not advance |
| `EMBED_PLAYER_FALLBACK` | Tried a recognized embedded player in the same WebView |
| `AUTO_FALLBACK manifest_hls_*` | Attempted an HLS playlist in the native Media3 engine |
| `AUTO_FALLBACK manifest_dash_*` | Attempted DASH in Media3 |
| `MEDIA_HTTP_ERROR` / `HTTP_ERROR` | A video media request was rejected; HTTP code is shown |
| `VIDEO_ONLY_READY` | Video has passed the fullscreen playback-progress gate |
| `PLAYING` | Playback really advanced, not merely duration/metadata |
| `NO_VIDEO_FOUND` | No observable HTML5 player and no usable manifest/iframe fallback |

If WebView is outdated, update **Android System WebView** using your TV's
supported system update mechanism. Some Fire OS versions bundle their own
browser engine and cannot use newer AndroidX WebKit features.

A direct `.m3u8`/`.mpd` playlist may play in Media3 even when the watch page's
JavaScript player fails, but signed/authenticated streams, DRM, protected
cookies and unsupported codecs remain provider/device limitations.

## Audio but no video (v3.4.1)

| Event | Meaning |
|---|---|
| `AUDIO_VIDEO_STARTING` | Media3 started audio/clock playback but has not produced a video frame |
| `VIDEO_CAPABILITY unsupported_rendition` | Media3 found at least one rendition outside this TV decoder's advertised capabilities |
| `VIDEO_SIZE 1920x1080` | Media3 knows a video's dimensions; pixels may still fail to render |
| `VIDEO_FIRST_FRAME` | Media3 reported its first rendered video frame |
| `AUDIO_NO_VIDEO AUDIO_ONLY` | Audio track is selected, but no video track is available |
| `AUDIO_NO_VIDEO UNSUPPORTED_CODEC` | Video is present but no supported video track is available |
| `AUDIO_NO_VIDEO VIDEO_NOT_SELECTED` | Video exists but the player did not select a video track |
| `AUDIO_NO_VIDEO VIDEO_NOT_RENDERED` | A video track was selected but no frame rendered while audio progressed |
| `AUDIO_NO_VIDEO` (WebView) | HTML5 clock is advancing without fresh decoded video frames/dimensions |
| `AUTO_FALLBACK manifest_hls_*` | Standard observed HLS manifest may be tried using Media3 |
| `VIDEO_QUALITY_RETRY SD` | User chose a lower-quality video rendition, if one exists |

**Diagnostic decision:** If `VIDEO_FIRST_FRAME` appears yet the TV is entirely
black while audio plays, suspect the device's rendering surface or a black/
protected source rather than mere playlist discovery. If no first frame and an
unsupported video track appears, suspect an incompatible codec. If there is no
video track at all, the selected manifest may be audio-only or the video
rendition may have failed to load.
