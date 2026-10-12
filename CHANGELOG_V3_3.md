# GlassLink 3.3 — source diff rationale

## Root issue

In v3.1–v3.2, a webpage could be isolated with CSS before its HTML5 player
successfully decoded anything. On live playlists, `duration` grew while
`currentTime` remained 0, and a Play/Pause tap only changed the playback intent.

## Startup changes

- The website initializes behind an opaque Android cover with **no CSS changes**.
- Start request goes to `HTMLMediaElement.play()` as soon as a candidate exists.
- Actual progress is determined from decoded video frames or a progressing clock
  with positive video dimensions; the duration does not count as progress.
- Only after the first progress sample, apply reversible fullscreen CSS.
- Require an additional progress sample before removing the cover.
- On a fullscreen-induced stall, undo CSS and allow one new isolation attempt.
- The frame observer uses `requestVideoFrameCallback()` when available, with
  standard decoded frame counters as fallback.
- A media subresource 4xx/5xx may be reported by Android WebView and is mapped
  to a human-readable error without logging sensitive URLs.
- D-pad Play is possible during preparation; the website never becomes visible.

## What is NOT solved by these source changes

- WebView can still fail to decode unsupported DRM/codecs.
- Some streaming servers prohibit playback outside a specific user session.
- Some sites require custom JS, authentication or a browser gesture not exposed
  by the HTMLMediaElement interface.
- An opaque overlay may affect rendering behavior on a specific TV chipset.

The correct follow-up if playback is still stuck is to capture
`adb logcat -d -t 150 -s GlassLinkRx:I '*:S'` during that stream, and share the
status events (no private URLs needed).
