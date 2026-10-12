package com.glasslink.receiver

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import android.widget.FrameLayout
import android.widget.TextView
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Video-only, fail-closed web playback. The website is NEVER displayed while
 * its player initializes. Crucially, no DOM/CSS isolation is performed until
 * the site has actually advanced video time or decoded frames. A second
 * observation after isolation proves the site is still playing before the
 * black Android cover is lifted. Styling can be rolled back without reloading
 * the URL if entering fullscreen disrupts a stream.
 *
 * Untrusted pages receive no JavaScript interface, credentials, signed media
 * URLs, or access to Android APIs. WebView scripting stays in the page world.
 */
internal class WebPlayerController(
    private val activity: Activity,
    private val container: FrameLayout,
    private val callback: Events
) {
    interface Events {
        fun report(event: String, detail: String = "")
        fun showError(message: String)
        fun onVideoPreparing(kind: String)
        fun onVideoReady(kind: String)
        fun onVideoCovered()
        fun onPlaybackInfo(positionSeconds: Double, durationSeconds: Double,
                           playing: Boolean, captionsAvailable: Boolean,
                           bufferedAheadSeconds: Double, muted: Boolean, state: String)
        fun onNativeFallbackCandidate(url: String, referrer: String?, userAgent: String?)
        fun onYouTubeQualityOptions(options: List<String>)
        fun onYouTubeCaptionsChanged(enabled: Boolean)
        fun onYouTubeControlUnavailable(control: String)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    private var cover: FrameLayout? = null
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private val fullscreenHidden = LinkedHashMap<View, Int>()
    private var generation = 0
    private var active = false
    private var failed = false
    private var revealed = false
    private var detected = false
    private var youtubeID: String? = null
    private var openedAt = 0L
    private var detectedAt = 0L
    private var lastKind = ""
    private var lastState = ""
    private var targetID = -1
    private var playAttempted = false
    private var vimeoPlayAttempted = false
    private var vimeoSubscribeAt = 0L
    private var nativeCandidateRequested = false
    private var health = WebPlaybackHealth()
    private var startup = WebPlaybackStartup()
    private var lastMediaHttpError = 0
    private var youtubeLastTime = Double.NaN
    private var youtubePointer: YouTubeRemotePointer? = null
    private var youtubeFrameBridge: YouTubeFrameBridge? = null
    private val manifests = MediaManifestDiscovery()
    private var manifestFallbackRequested = false
    private var frameScriptAvailable = false
    private var embedNavigationAttempted = false
    private var lastObservedMuted: Boolean? = null
    private var userMutedAudio = false
    private var autoUnmuteAttempts = 0
    private var lastAutoUnmuteAt = 0L

    // Allow the D-pad Play key during the black-screen preparation phase.
    val isVideoReady: Boolean get() = active && detected && !failed

    private val poll = object : Runnable {
        override fun run() {
            if (!active || failed) return
            val now = SystemClock.elapsedRealtime()
            if (!detected && now - openedAt > 18_000L && tryManifestFallback("no_html5_player")) {
                return
            }
            if (!detected && now - openedAt > 21_000L && !embedNavigationAttempted) {
                tryEmbeddedNavigation()
            }
            if (!detected && now - openedAt > 32_000L) {
                fail(if (frameScriptAvailable)
                    "This webpage did not expose a playable HTML5 video, even in supported subframes. " +
                    "The site may require login, a protected player, or unsupported codecs."
                else
                    "The TV's Android System WebView does not support cross-frame video detection " +
                    "or the webpage did not expose a normal video element. Update Android System WebView " +
                    "or try a directly playable HLS/DASH URL.", "NO_VIDEO_FOUND")
                return
            }
            val current = web ?: return
            val epoch = generation
            if (revealed) {
                if (fullscreenView != null && !isolateFullscreenVideo()) {
                    fail("The website changed its fullscreen view to include other " +
                        "player controls.", "FULLSCREEN_NOT_VIDEO_ONLY")
                    return
                }
                if (youtubeID == null) runScript(WebVideoScript.enforceIsolation)
            }
            val script = if (youtubeID == null) WebVideoScript.probe
                         else "window.glasslinkInfo && window.glasslinkInfo()"
            current.evaluateJavascript(script) { raw ->
                if (epoch != generation || current !== web || failed) return@evaluateJavascript
                if (youtubeID == null) onPageProbe(raw) else onYouTubeProbe(raw)
            }
            handler.postDelayed(this, 1_250L)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun open(address: String, youtubeVideoID: String? = null) {
        close()
        generation++
        active = true
        youtubeID = youtubeVideoID
        openedAt = SystemClock.elapsedRealtime()
        embedNavigationAttempted = false
        resetPlaybackState()
        val view = WebView(activity).apply {
            setBackgroundColor(Color.BLACK)
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // The receiver is remote-controlled; a synthetic JavaScript click
            // is not guaranteed to count as a genuine browser user gesture.
            // Allow direct HTMLMediaElement.play() without touch input.
            settings.mediaPlaybackRequiresUserGesture = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.loadsImagesAutomatically = true
            isFocusable = false
            isFocusableInTouchMode = false
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
        // Unlike evaluateJavascript(), document-start scripts execute in
        // supported cross-origin subframes as well as the main page. They
        // exchange limited media state with their parents using postMessage.
        // No privileged Android-to-JavaScript object is exposed.
        frameScriptAvailable = false
        if (youtubeVideoID == null &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try {
                WebViewCompat.addDocumentStartJavaScript(view, FramePlaybackScript.install, setOf("*"))
                frameScriptAvailable = true
                callback.report("FRAME_DETECTION_READY")
            } catch (_: Exception) {
                callback.report("FRAME_DETECTION_UNAVAILABLE")
            }
        } else if (youtubeVideoID == null) callback.report("FRAME_DETECTION_UNAVAILABLE")
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView?, request: WebResourceRequest?): Boolean {
                if (request?.isForMainFrame == false) return false
                return !ReceiverContract.validURL(request?.url?.toString())
            }
            override fun shouldInterceptRequest(v: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                if (v === web && request?.isForMainFrame == false) {
                    // Observe only. Returning null lets WebView issue the
                    // original request with its normal browser/network context.
                    if (manifests.offer(request.url?.toString(), request.method,
                            request.requestHeaders)) {
                        val found = manifests.candidate()
                        callback.report("MANIFEST_SEEN", "${found?.kind ?: ""}_" +
                            if (found?.referrer == null) "no_referrer" else "referrer")
                    }
                }
                return null
            }
            override fun onPageStarted(v: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                if (v !== web || failed) return
                resetPlaybackState()
                openedAt = SystemClock.elapsedRealtime()
                manifests.reset()
                showCover()
                callback.report("PAGE_OPENING")
            }
            override fun onPageFinished(v: WebView?, url: String?) {
                if (v === web && !failed) callback.report("PAGE_LOADED_HIDDEN")
            }
            override fun onReceivedHttpError(v: WebView?, request: WebResourceRequest?,
                                             response: WebResourceResponse?) {
                if (v !== web || failed) return
                val status = response?.statusCode ?: 0
                if (request?.isForMainFrame == true) {
                    fail("The source webpage returned HTTP $status.", "PAGE_HTTP_ERROR")
                } else if (status >= 400 && likelyMediaResource(request?.url?.toString())) {
                    // Android invokes this for subresources too, but not every
                    // JS/fetch/DRM failure is observable through this callback.
                    lastMediaHttpError = status
                    callback.report("MEDIA_HTTP_ERROR", status.toString())
                }
            }
            override fun onReceivedError(v: WebView?, request: WebResourceRequest?,
                                         error: WebResourceError?) {
                if (v === web && request?.isForMainFrame == true)
                    fail("The source webpage could not load on this TV.", "PAGE_NETWORK_ERROR")
            }
            override fun onRenderProcessGone(v: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                if (v === web) {
                    web = null
                    if (v != null) {
                        container.removeView(v)
                        v.destroy()
                    }
                    fail("Android WebView stopped unexpectedly. Try casting again.",
                        "WEBVIEW_RENDERER_CRASH")
                }
                return true
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(custom: View?, hidden: CustomViewCallback?) {
                if (custom == null) return
                if (!active || web !== view || failed) {
                    hidden?.onCustomViewHidden()
                    return
                }
                dismissFullscreen(true)
                exitYouTubeControls()
                fullscreenView = custom
                fullscreenCallback = hidden
                container.addView(custom, FrameLayout.LayoutParams(-1, -1))
                view.visibility = View.INVISIBLE
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                // A site can fullscreen arbitrary HTML. This custom view must
                // remain covered until a progressing video is verified.
                showCover()
                startup = WebPlaybackStartup()
                callback.report("FULLSCREEN_REQUESTED")
            }
            override fun onHideCustomView() {
                dismissFullscreen(false)
                showCover()
                // The page may return with all its chrome. Require fresh
                // playback and isolation proof before uncovering it again.
                startup = WebPlaybackStartup()
                if (youtubeID == null) runScript(WebVideoScript.restore)
            }
            override fun onCreateWindow(v: WebView?, isDialog: Boolean,
                                        isUserGesture: Boolean,
                                        resultMsg: android.os.Message?): Boolean = false
        }
        web = view
        if (youtubeVideoID != null) {
            val epoch = generation
            val bridge = YouTubeFrameBridge(youtubeVideoID, object : YouTubeFrameBridge.Events {
                private fun deliver(action: () -> Unit) {
                    activity.runOnUiThread {
                        if (active && !failed && generation == epoch) action()
                    }
                }
                override fun onQualityOptions(options: List<String>) =
                    deliver { callback.onYouTubeQualityOptions(options) }
                override fun onCaptionsChanged(enabled: Boolean) =
                    deliver { callback.onYouTubeCaptionsChanged(enabled) }
                override fun onControlUnavailable(control: String) =
                    deliver { callback.onYouTubeControlUnavailable(control) }
            })
            if (bridge.attach(view)) youtubeFrameBridge = bridge
        }
        // Keep the page actually rendering/initializing behind an OPAQUE native
        // cover. WebView.INVISIBLE/GONE or display:none would pause many players.
        container.addView(view, 0, FrameLayout.LayoutParams(-1, -1))
        showCover()
        callback.report(if (youtubeVideoID == null) "PAGE_OPENING" else "YT_OPENING")
        if (youtubeVideoID == null) view.loadUrl(address)
        else view.loadDataWithBaseURL(YouTubeEmbed.BASE_URL, YouTubeEmbed.html(youtubeVideoID),
                                      "text/html", "UTF-8", null)
        handler.postDelayed(poll, 900L)
    }

    private fun resetPlaybackState() {
        revealed = false
        detected = false
        failed = false
        detectedAt = 0L
        targetID = -1
        lastKind = ""
        lastState = ""
        playAttempted = false
        vimeoPlayAttempted = false
        vimeoSubscribeAt = 0L
        nativeCandidateRequested = false
        manifestFallbackRequested = false
        lastMediaHttpError = 0
        health = WebPlaybackHealth()
        startup = WebPlaybackStartup()
        youtubeLastTime = Double.NaN
        lastObservedMuted = null
        userMutedAudio = false
        autoUnmuteAttempts = 0
        lastAutoUnmuteAt = 0L
    }

    private fun parseResult(raw: String): JSONObject? = try {
        when (val value = JSONTokener(raw).nextValue()) {
            is String -> JSONObject(value)
            is JSONObject -> value
            else -> null
        }
    } catch (_: Exception) { null }

    private fun onYouTubeProbe(raw: String) {
        val info = parseResult(raw) ?: return
        val state = info.optString("state", "OPENING")
        if (state.startsWith("ERROR_") || state == "LOAD_ERROR") {
            fail("This video is unavailable through the site's official embedded player.",
                 "YT_EMBED_UNAVAILABLE")
            return
        }
        if (info.optBoolean("found") && !detected) onDetected("youtube")
        if (!detected) return
        val position = info.optDouble("current", 0.0)
        val actualProgress = state == "PLAYING" && youtubeLastTime.isFinite() &&
            position > youtubeLastTime + 0.12
        if (position.isFinite()) youtubeLastTime = position
        if (actualProgress && !revealed) reveal("youtube")
        val label = when (state) {
            "PLAYING" -> if (actualProgress || revealed) "Playing" else "Starting…"
            "BUFFERING", "OPENING" -> "Buffering…"
            "BLOCKED" -> "Press Play on the TV remote"
            "PAUSED" -> "Paused — press Play"
            else -> "Starting…"
        }
        updateInfo(info, label, revealed && state == "PLAYING" && actualProgress)
        if (actualProgress) reportState("PLAYING")
        else if (state == "BLOCKED") reportState("YT_AUTOPLAY_BLOCKED")
        if (!revealed && detectedAt > 0L &&
            SystemClock.elapsedRealtime() - detectedAt > 32_000L) {
            fail("The embedded player loaded but no video frames started. " +
                "Try playback in the official TV app.", "YT_NO_PROGRESS")
        }
    }

    private fun onDetected(kind: String) {
        detected = true
        lastKind = kind
        detectedAt = SystemClock.elapsedRealtime()
        callback.onVideoPreparing(kind)
        callback.report("VIDEO_DETECTED", kind)
    }

    private fun onPageProbe(raw: String) {
        val info = parseResult(raw) ?: return
        if (!info.optBoolean("found")) {
            if (detected && !failed && SystemClock.elapsedRealtime() - detectedAt > 6_000L) {
                fail("The video disappeared while this website was initializing.", "VIDEO_REMOVED")
            }
            return
        }
        val kind = info.optString("kind", "")
        if (kind == "youtube-frame") {
            val id = ReceiverContract.youtubeVideoID(info.optString("sourceURL"))
            if (id != null) {
                callback.report("YT_EMBED_SWITCH")
                open(info.optString("sourceURL"), id)
            } else fail("The embedded YouTube player did not expose a valid video ID.",
                        "EMBED_UNSUPPORTED")
            return
        }
        if (kind != "video" && kind != "frame-video" && kind != "vimeo") return
        val identity = info.optInt("targetID", -1)
        if (identity != targetID || (detected && kind != lastKind)) {
            // A single-page application replaced the player. Do not inherit
            // progress proof from an unrelated video element.
            targetID = identity
            if (revealed) showCover()
            revealed = false
            health = WebPlaybackHealth()
            startup = WebPlaybackStartup()
            playAttempted = false
            vimeoPlayAttempted = false
            lastObservedMuted = null
            userMutedAudio = false
            autoUnmuteAttempts = 0
            lastAutoUnmuteAt = 0L
            onDetected(kind)
        }
        if (kind == "video" || kind == "frame-video") {
            maybeStartVideo()
        } else {
            val now = SystemClock.elapsedRealtime()
            if (now - vimeoSubscribeAt > 3_500L) {
                vimeoSubscribeAt = now
                runScript(WebVideoScript.vimeoEvents)
            }
            if (!vimeoPlayAttempted) {
                vimeoPlayAttempted = true
                runScript(WebVideoScript.iframeCommand("play"))
            }
        }
        processSample(info)
    }

    private fun maybeStartVideo() {
        if (playAttempted) return
        playAttempted = true
        // Crucially do this BEFORE changing any CSS or disabling the site's
        // original controls. HTMLMediaElement.play() can start a lazy MSE/HLS
        // pipeline even before readyState reaches HAVE_CURRENT_DATA.
        callback.report("PLAY_REQUESTED")
        runScript(WebVideoScript.attemptPlay)
    }

    private fun processSample(info: JSONObject) {
        val kind = info.optString("kind", "video")
        val source = info.optString("sourceURL", "")
        val directCandidate = (kind == "video" && ReceiverContract.validURL(source) &&
            ReceiverContract.isLikelyMedia(source, null)) || manifests.candidate() != null
        val sample = WebPlaybackHealth.Sample(
            position = info.optDouble("current", 0.0),
            duration = info.optDouble("duration", 0.0),
            decodedFrames = info.optLong("decodedFrames", -1L),
            readyState = info.optInt("readyState", 0),
            paused = info.optBoolean("paused", true),
            waiting = info.optBoolean("waiting", false),
            ended = info.optBoolean("ended", false),
            mediaError = info.optInt("mediaError", 0),
            bufferedAhead = info.optDouble("bufferedAhead", 0.0),
            playRejected = info.optBoolean("playRejected", false),
            videoWidth = info.optInt("videoWidth", 0),
            videoHeight = info.optInt("videoHeight", 0)
        )
        val now = SystemClock.elapsedRealtime()
        if (kind == "video" || kind == "frame-video") {
            val muted = info.optBoolean("muted")
            if (lastObservedMuted != muted) {
                callback.report("AUDIO_STATE", if (muted) "muted" else "unmuted")
                lastObservedMuted = muted
            }
            if (muted && info.optBoolean("playing") && !userMutedAudio &&
                autoUnmuteAttempts < 3 && now - lastAutoUnmuteAt >= 2_500L) {
                autoUnmuteAttempts++
                lastAutoUnmuteAt = now
                runScript(WebVideoScript.command("unmute"))
            }
        }
        val assessment = health.observe(sample, now, directCandidate)
        val progressed = assessment.state == WebPlaybackHealth.State.PLAYING &&
            (kind != "frame-video" || fullscreenView != null ||
                startup.phase != WebPlaybackStartup.Phase.VERIFYING ||
                info.optBoolean("isolated"))
        val status = when (assessment.state) {
            WebPlaybackHealth.State.PLAYING -> if (revealed) "Playing" else "Entering fullscreen…"
            WebPlaybackHealth.State.PAUSED -> "Paused — press Play"
            WebPlaybackHealth.State.BUFFERING -> "Buffering…"
            WebPlaybackHealth.State.STARTING -> "Starting video…"
            WebPlaybackHealth.State.STALLED -> "Video stalled"
            WebPlaybackHealth.State.NO_VIDEO -> "Audio detected · waiting for video frames"
            WebPlaybackHealth.State.ENDED -> "Finished"
            WebPlaybackHealth.State.ERROR -> "Playback error"
        }
        updateInfo(info, status, revealed && progressed)
        if (progressed && revealed) reportState("PLAYING")
        else if (assessment.state == WebPlaybackHealth.State.STALLED) reportState("WEB_STALLED")
        else if (assessment.state == WebPlaybackHealth.State.NO_VIDEO) reportState("AUDIO_NO_VIDEO")
        else if (assessment.state == WebPlaybackHealth.State.BUFFERING) reportState("BUFFERING")

        if (assessment.shouldRetryPlay && !revealed) {
            callback.report("RETRYING", "web_play")
            if (kind == "video" || kind == "frame-video") runScript(WebVideoScript.attemptPlay)
            else runScript(WebVideoScript.iframeCommand("play"))
        }
        if (assessment.shouldTryNative && directCandidate && !nativeCandidateRequested) {
            if (tryManifestFallback("buffering")) return
            if (ReceiverContract.validURL(source) && ReceiverContract.isLikelyMedia(source, null)) {
                nativeCandidateRequested = true
                callback.report("AUTO_FALLBACK", "stalled_direct_media")
                callback.onNativeFallbackCandidate(source, null, web?.settings?.userAgentString)
                return
            }
        }
        if (assessment.shouldFail) {
            val code = when {
                sample.mediaError != 0 -> "WEB_MEDIA_ERROR"
                assessment.state == WebPlaybackHealth.State.NO_VIDEO -> "AUDIO_NO_VIDEO"
                else -> "WEB_STALLED"
            }
            fail(playbackFailureReason(sample), code)
            return
        }
        when (startup.observe(progressed, now)) {
            WebPlaybackStartup.Action.APPLY_ISOLATION -> isolateAfterStartup()
            WebPlaybackStartup.Action.REVEAL -> reveal(kind)
            WebPlaybackStartup.Action.RESTORE_PAGE -> restoreAndRetry("fullscreen_stalled")
            WebPlaybackStartup.Action.NONE -> {
                if (progressed && !startup.canAttemptIsolation() && !startup.isVisible()) {
                    fail("This stream plays inside its website but stops when fullscreen is " +
                        "applied. The website is being kept hidden.", "FULLSCREEN_INTERRUPTS_PLAYBACK")
                }
            }
        }
    }

    private fun isolateAfterStartup() {
        // A custom fullscreen view may include a website's entire control
        // hierarchy. Expose it only when an actual video surface can be kept.
        if (fullscreenView != null) {
            val isolated = isolateFullscreenVideo()
            startup.isolated(isolated, SystemClock.elapsedRealtime())
            if (!isolated) fail("The website's fullscreen view has no isolated video " +
                "surface.", "FULLSCREEN_NOT_VIDEO_ONLY")
            return
        }
        val current = web ?: return
        val epoch = generation
        callback.report("FULLSCREEN_PREPARING")
        current.evaluateJavascript(WebVideoScript.isolate) { result ->
            if (epoch != generation || current !== web || failed) return@evaluateJavascript
            val success = parseResult(result)?.optBoolean("ok") == true
            startup.isolated(success, SystemClock.elapsedRealtime())
            if (!success) restoreAndRetry("fullscreen_layout")
        }
    }

    private fun restoreAndRetry(reason: String) {
        showCover()
        callback.report("FULLSCREEN_RECOVERING", reason)
        val current = web ?: return
        val epoch = generation
        current.evaluateJavascript(WebVideoScript.restore) {
            if (epoch != generation || current !== web || failed) return@evaluateJavascript
            startup.restored()
            // Recovery is a continuation of the same live player, not a page
            // reload. A retry will happen only if time is still not advancing.
            health = WebPlaybackHealth()
            if (lastKind == "video" || lastKind == "frame-video") runScript(WebVideoScript.attemptPlay)
            else if (lastKind == "vimeo") runScript(WebVideoScript.iframeCommand("play"))
        }
    }

    private fun tryEmbeddedNavigation() {
        embedNavigationAttempted = true
        val current = web ?: return
        val epoch = generation
        current.evaluateJavascript(WebVideoScript.standaloneEmbedCandidate) { result ->
            if (epoch != generation || current !== web || detected || failed) return@evaluateJavascript
            val payload = parseResult(result) ?: return@evaluateJavascript
            if (!payload.optBoolean("found")) return@evaluateJavascript
            val source = payload.optString("url", "")
            if (!ReceiverContract.validURL(source)) return@evaluateJavascript
            val referer = CastRequestHeaders.forRequest(source, current.url, null)
            callback.report("EMBED_PLAYER_FALLBACK")
            // No separate browser app, cloud service, token copying or proxy.
            // The original WebView/cookie jar stays alive, covered in black.
            current.loadUrl(source, referer)
        }
    }

    private fun tryManifestFallback(reason: String): Boolean {
        if (manifestFallbackRequested || youtubeID != null || failed) return false
        val manifest = manifests.candidate() ?: return false
        manifestFallbackRequested = true
        nativeCandidateRequested = true
        callback.report("AUTO_FALLBACK", "manifest_${manifest.kind}_$reason")
        // The player retries through Media3 *without* trying to reuse a blob:
        // MediaSource URL or extracting cookies/passwords from the WebView.
        callback.onNativeFallbackCandidate(manifest.url, manifest.referrer,
            web?.settings?.userAgentString)
        return true
    }

    private fun playbackFailureReason(sample: WebPlaybackHealth.Sample): String = when {
        lastMediaHttpError in 400..599 ->
            "The video host rejected a media request (HTTP $lastMediaHttpError). " +
            "The stream may require a TV-side login or a supported streaming app."
        sample.mediaError != 0 ->
            "Android WebView reported HTML5 media error ${sample.mediaError}. " +
            "The site may require another codec or playback environment."
        sample.playRejected ->
            "The website refused the Play request. It may require its own player " +
            "interaction or authentication."
        sample.position > 1.0 && sample.videoWidth == 0 && sample.videoHeight == 0 ->
            "The stream clock is advancing, but the TV reports no video dimensions. " +
            "Audio may be available while the video track is missing or undecodable."
        sample.position > 1.0 && sample.decodedFrames == 0L ->
            "The stream clock is advancing, but the TV has decoded zero video frames. " +
            "Try a lower-resolution source or a different codec."
        sample.readyState <= 1 ->
            "The video metadata loaded, but media segments never buffered. " +
            "The server or this TV's WebView may not support this stream."
        else -> "The video timeline loaded, but elapsed time and decoded frames " +
            "stayed at zero. Playback did not initialize on this TV."
    }

    private fun likelyMediaResource(raw: String?): Boolean {
        if (raw.isNullOrBlank() || raw.length > 8192) return false
        val path = try { java.net.URI(raw).path.orEmpty().lowercase() }
                   catch (_: Exception) { return false }
        return path.contains("/videoplayback") || path.contains("/segment") ||
            path.contains("/playlist") || path.endsWith(".m3u8") ||
            path.endsWith(".mpd") || path.endsWith(".m4s") || path.endsWith(".ts") ||
            path.endsWith(".mp4") || path.endsWith(".webm")
    }

    private fun reportState(state: String) {
        if (lastState == state) return
        lastState = state
        callback.report(state)
    }

    private fun updateInfo(info: JSONObject, state: String, playing: Boolean) {
        if (!detected) return
        callback.onPlaybackInfo(info.optDouble("current", 0.0),
            info.optDouble("duration", 0.0), playing,
            info.optBoolean("captions"), info.optDouble("bufferedAhead", 0.0),
            info.optBoolean("muted"), state)
    }

    private fun showCover() {
        exitYouTubeControls()
        if (cover == null) {
            val overlay = FrameLayout(activity).apply { setBackgroundColor(Color.BLACK) }
            val label = TextView(activity).apply {
                text = "Preparing video…"
                textSize = 17f
                setTextColor(0xFFD0D0D0.toInt())
                gravity = Gravity.CENTER
            }
            overlay.addView(label, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
            cover = overlay
            container.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        }
        revealed = false
        cover?.visibility = View.VISIBLE
        cover?.bringToFront()
        callback.onVideoCovered()
    }

    private fun reveal(kind: String) {
        if (!active || failed) return
        revealed = true
        lastKind = kind
        cover?.visibility = View.GONE
        callback.onVideoReady(kind)
        callback.report("VIDEO_ONLY_READY", kind)
    }

    private fun fail(message: String, code: String) {
        if (failed) return
        failed = true
        handler.removeCallbacks(poll)
        // A black error screen must not continue playing audio invisibly.
        // The iframe-agent command stays within the page and does not expose
        // any native bridge or media URL.
        if (lastKind == "video" || lastKind == "frame-video")
            runScript(WebVideoScript.command("pause"))
        showCover()
        callback.report("FAILED", code)
        callback.showError(message)
    }

    fun handleControl(action: String, value: Double? = null): Boolean {
        if (!isVideoReady) return false
        if (action == "toggleMute") userMutedAudio = lastObservedMuted != true
        val safeValue = value?.takeIf { it.isFinite() }?.coerceIn(0.0, 86_400.0) ?: 0.0
        val script = if (youtubeID != null) {
            "window.glasslinkRemote && window.glasslinkRemote('$action',$safeValue)"
        } else if (lastKind == "vimeo") {
            WebVideoScript.iframeCommand(action, safeValue)
        } else {
            WebVideoScript.command(action, safeValue)
        }
        if (script == null) return false
        runScript(script)
        return true
    }

    val isYouTubePointerActive: Boolean get() = youtubePointer != null

    fun requestYouTubeQuality(): Boolean =
        youtubeID != null && revealed && !failed &&
            youtubeFrameBridge?.requestQuality() == true

    fun selectYouTubeQuality(label: String): Boolean =
        youtubeFrameBridge?.selectQuality(label) == true

    fun dismissYouTubeQuality() { youtubeFrameBridge?.dismissQuality() }

    fun toggleYouTubeCaptions(): Boolean =
        youtubeID != null && revealed && !failed &&
            youtubeFrameBridge?.toggleCaptions() == true

    fun enterYouTubeControls(atCaptions: Boolean): Boolean {
        if (youtubeID == null || !revealed || failed) return false
        val current = web ?: return false
        exitYouTubeControls()
        youtubeFrameBridge?.setPlayerControlsVisible(true)
        youtubePointer = YouTubeRemotePointer(activity, container, current).also {
            it.start(atCaptions)
        }
        return true
    }

    fun handleYouTubePointerKey(event: KeyEvent): Boolean = youtubePointer?.handle(event) ?: false

    fun exitYouTubeControls(): Boolean {
        val pointer = youtubePointer ?: return false
        youtubePointer = null
        pointer.close()
        youtubeFrameBridge?.setPlayerControlsVisible(false)
        return true
    }

    fun exitFullscreenIfShown(): Boolean {
        if (fullscreenView == null) return false
        dismissFullscreen(true)
        showCover()
        return true
    }

    private fun dismissFullscreen(notify: Boolean) {
        for ((view, visibility) in fullscreenHidden) view.visibility = visibility
        fullscreenHidden.clear()
        fullscreenView?.let { container.removeView(it) }
        fullscreenView = null
        val oldCallback = fullscreenCallback
        fullscreenCallback = null
        web?.visibility = View.VISIBLE
        if (notify) oldCallback?.onCustomViewHidden()
    }

    private fun isolateFullscreenVideo(): Boolean {
        val full = fullscreenView ?: return false
        val candidates = ArrayList<View>()
        fun collect(view: View) {
            if (view is SurfaceView || view is TextureView) candidates.add(view)
            if (view is ViewGroup) for (i in 0 until view.childCount) collect(view.getChildAt(i))
        }
        collect(full)
        val video = candidates.maxByOrNull { it.width.toLong() * it.height }
            ?: return false
        if (video.width < container.width / 2 || video.height < container.height / 2)
            return false
        var child = video
        while (child !== full) {
            val parent = child.parent as? ViewGroup ?: return false
            for (i in 0 until parent.childCount) {
                val sibling = parent.getChildAt(i)
                if (sibling !== child && sibling.visibility != View.INVISIBLE) {
                    fullscreenHidden.putIfAbsent(sibling, sibling.visibility)
                    sibling.visibility = View.INVISIBLE
                }
            }
            child = parent
        }
        full.setBackgroundColor(Color.BLACK)
        return true
    }

    private fun runScript(script: String?) {
        if (script != null) web?.evaluateJavascript(script, null)
    }

    fun onResume() { web?.onResume() }
    fun onPause() { web?.onPause() }

    fun close() {
        generation++
        active = false
        handler.removeCallbacks(poll)
        exitYouTubeControls()
        dismissFullscreen(true)
        cover?.let { container.removeView(it) }
        cover = null
        web?.let { v ->
            container.removeView(v)
            v.stopLoading()
            v.loadUrl("about:blank")
            v.destroy()
        }
        web = null
        youtubeID = null
        youtubeFrameBridge?.close()
        youtubeFrameBridge = null
        manifests.reset()
        resetPlaybackState()
    }
}
