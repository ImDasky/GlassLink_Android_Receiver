package com.glasslink.receiver

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.google.common.util.concurrent.ListenableFuture

/**
 * One TV-player shell, two backends: Media3 for direct streams and WebView for
 * website-owned playback. Exactly ONE surface is visible for each cast.
 *
 * Both backends use the same GlassLink TV controls. PlayerView only renders
 * native video, so changing from a website to its media stream keeps the UI.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PlayerActivity : Activity(), WebPlayerController.Events {
    private lateinit var root: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var errorLabel: TextView
    private lateinit var optionsButton: TextView
    private var webPlayer: WebPlayerController? = null
    private lateinit var tvControls: TvPlaybackOverlay
    private var consumedBackKey = false
    private var mediaController: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var activeRequest: String? = null
    private var activeURL: String? = null
    private var fallbackPage: String? = null
    private var attemptedNativeRetry = false
    private var liveEdgeRecoveries = 0
    private var lastLiveEdgeRecoveryMs = 0L
    private var decoderRecoveries = 0
    private var currentNativeURL: String? = null
    private var failedNativeURL: String? = null
    private var usingWeb = false
    private var currentYouTubeID: String? = null
    private var currentYouTubeCaptionsEnabled = false
    private var pageFallbackAttempted = false
    private var attemptedWebToNative = false
    private var pendingIntent: Intent? = null
    private var qualityDialog: AlertDialog? = null

    private val nativeHandler = Handler(Looper.getMainLooper())
    private var videoMonitor = NativeVideoOutputMonitor()
    private var nativeVideoExpected = false
    private var nativeWarningShown = false
    private var nativeQualityGraceUntilMs = 0L
    private val nativeControlsTick = object : Runnable {
        override fun run() {
            if (usingWeb || isFinishing || isDestroyed) return
            updateNativeControls()
            nativeHandler.postDelayed(this, 500L)
        }
    }
    private val nativeVideoWatchdog = object : Runnable {
        override fun run() {
            if (usingWeb || !nativeVideoExpected || isFinishing || isDestroyed) return
            val current = mediaController ?: return
            val now = SystemClock.elapsedRealtime()
            if (now < nativeQualityGraceUntilMs) {
                nativeHandler.postDelayed(this, 1600L)
                return
            }
            val diagnosis = videoMonitor.observe(now,
                current.currentPosition, current.isPlaying, nativeVideoExpected)
            if (!nativeWarningShown && diagnosis !in setOf(
                    NativeVideoOutputMonitor.Result.WAITING,
                    NativeVideoOutputMonitor.Result.FRAME_RENDERED)) {
                nativeWarningShown = true
                emit("AUDIO_NO_VIDEO", diagnosis.name.take(28))
                val detail = when (diagnosis) {
                    NativeVideoOutputMonitor.Result.AUDIO_ONLY ->
                        "Audio plays, but this source has no selectable video track."
                    NativeVideoOutputMonitor.Result.UNSUPPORTED_CODEC ->
                        "The TV can play the audio, but doesn't support the video track's codec or format."
                    NativeVideoOutputMonitor.Result.VIDEO_NOT_SELECTED ->
                        "The stream has video, but no compatible video track was selected."
                    else -> "The stream's audio plays, but no video frame has appeared on the TV."
                }
                showError("$detail Press Menu and try lower video quality when another rendition exists.")
            }
            nativeHandler.postDelayed(this, 1600L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        createViews()
        connectPlayer()
    }

    private fun createViews() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        playerView = PlayerView(this).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        }
        root.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        errorLabel = TextView(this).apply {
            textSize = 17f
            setTextColor(Color.WHITE)
            setBackgroundColor(0xDD202020.toInt())
            setPadding(dp(20), dp(20), dp(20), dp(20))
            visibility = View.GONE
            gravity = Gravity.CENTER
            isFocusable = true
        }
        root.addView(errorLabel, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply {
            marginStart = dp(32)
            marginEnd = dp(32)
        })
        optionsButton = TextView(this).apply {
            text = "Options"
            textSize = 16f
            setTextColor(Color.WHITE)
            setBackgroundColor(0xBB252525.toInt())
            setPadding(dp(16), dp(11), dp(16), dp(11))
            isFocusable = true
            setOnClickListener { showNativeOptions() }
        }
        root.addView(optionsButton, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(14)
            marginEnd = dp(18)
        })
        setContentView(root)
        webPlayer = WebPlayerController(this, root, this)
        tvControls = TvPlaybackOverlay(this, root, object : TvPlaybackOverlay.Actions {
            override fun onAction(action: String, value: Double?) {
                if (!usingWeb) {
                    handleNativeControl(action, value)
                    return
                }
                when (action) {
                    "youtubeQuality" -> {
                        if (webPlayer?.requestYouTubeQuality() != true)
                            Toast.makeText(this@PlayerActivity,
                                "Quality settings aren't available for this video",
                                Toast.LENGTH_SHORT).show()
                    }
                    "youtubeCaptions" -> {
                        if (webPlayer?.toggleYouTubeCaptions() != true)
                            Toast.makeText(this@PlayerActivity,
                                "Captions aren't available for this video",
                                Toast.LENGTH_SHORT).show()
                    }
                    else -> webPlayer?.handleControl(action, value)
                }
            }
        })
        playerView.requestFocus()
    }

    private fun connectPlayer() {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            if (isFinishing || isDestroyed) return@addListener
            try {
                val player = future.get()
                mediaController = player
                playerView.player = player
                player.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        if (!usingWeb) onNativeError(error)
                    }
                    override fun onPlaybackStateChanged(state: Int) {
                        if (usingWeb) return
                        when (state) {
                            Player.STATE_BUFFERING -> emit("BUFFERING")
                            Player.STATE_READY -> { hideError(); emit("READY") }
                            Player.STATE_ENDED -> {
                                nativeHandler.removeCallbacks(nativeVideoWatchdog)
                                emit("ENDED")
                            }
                        }
                        updateNativeControls()
                    }
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (!usingWeb && isPlaying) {
                            emit(if (nativeVideoExpected && !videoMonitor.hasRenderedFrame())
                                "AUDIO_VIDEO_STARTING" else "PLAYING")
                        }
                        if (!usingWeb) updateNativeControls()
                    }
                    override fun onTracksChanged(tracks: Tracks) {
                        if (usingWeb) return
                        val groups = tracks.groups
                        val video = groups.filter { it.type == C.TRACK_TYPE_VIDEO }
                        videoMonitor.updateTracks(NativeVideoOutputMonitor.TracksInfo(
                            audioSelected = groups.any {
                                it.type == C.TRACK_TYPE_AUDIO && it.isSelected
                            },
                            videoPresent = video.isNotEmpty(),
                            videoSupported = video.any { it.isSupported },
                            videoSelected = video.any { it.isSelected }
                        ))
                        if (video.any { !it.isSupported }) emit("VIDEO_CAPABILITY", "unsupported_rendition")
                        updateNativeControls()
                    }
                    override fun onRenderedFirstFrame() {
                        if (usingWeb) return
                        videoMonitor.onRenderedFrame()
                        hideError()
                        emit("VIDEO_FIRST_FRAME")
                        if (mediaController?.isPlaying == true) emit("PLAYING")
                    }
                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        if (!usingWeb && videoSize.width > 0 && videoSize.height > 0)
                            emit("VIDEO_SIZE", "${videoSize.width}x${videoSize.height}")
                    }
                })
                val next = pendingIntent ?: intent
                pendingIntent = null
                play(next)
            } catch (_: Exception) {
                emit("FAILED", "native_initialization")
                showError("The TV player couldn't initialize. Try updating Android System WebView or reinstalling the receiver.")
            }
        }, java.util.concurrent.Executor { command -> runOnUiThread(command) })
    }

    override fun onNewIntent(newIntent: Intent) {
        super.onNewIntent(newIntent)
        setIntent(newIntent)
        if (mediaController == null) pendingIntent = newIntent else play(newIntent)
    }

    private fun play(incoming: Intent) {
        nativeHandler.removeCallbacks(nativeVideoWatchdog)
        nativeHandler.removeCallbacks(nativeControlsTick)
        qualityDialog?.dismiss()
        qualityDialog = null
        tvControls.setEnabled(false)
        webPlayer?.close()
        mediaController?.stop()
        LiveDvr.stop()
        usingWeb = false
        activeRequest = incoming.getStringExtra(ReceiverContract.EXTRA_REQUEST)
            ?.takeIf { ReceiverContract.validRequestID(it) }
        activeURL = incoming.dataString
        fallbackPage = incoming.getStringExtra(ReceiverContract.EXTRA_PAGE)
            ?.takeIf { ReceiverContract.validURL(it) }
        attemptedNativeRetry = false
        liveEdgeRecoveries = 0
        lastLiveEdgeRecoveryMs = 0L
        decoderRecoveries = 0
        currentNativeURL = null
        failedNativeURL = null
        pageFallbackAttempted = false
        attemptedWebToNative = false
        currentYouTubeID = null
        currentYouTubeCaptionsEnabled = false
        hideError()
        emit("RECEIVED")
        val address = activeURL
        if (!ReceiverContract.validURL(address)) {
            emit("FAILED", "invalid_url")
            showError("GlassLink didn't send a valid HTTPS or HTTP video address.")
            return
        }
        val safeURL = address!!
        val mode = incoming.getStringExtra(ReceiverContract.EXTRA_MODE)
        val hint = incoming.getStringExtra(ReceiverContract.EXTRA_MIME)
        ReceiverHttpContext.configure(
            incoming.getStringExtra(ReceiverContract.EXTRA_REFERRER) ?: fallbackPage,
            incoming.getStringExtra(ReceiverContract.EXTRA_USER_AGENT))

        when (ReceiverContract.playbackKind(mode, safeURL, hint, fallbackPage)) {
            ReceiverContract.PlaybackKind.YOUTUBE -> {
                val id = ReceiverContract.youtubeVideoID(fallbackPage)
                    ?: ReceiverContract.youtubeVideoID(safeURL)
                if (id == null) {
                    emit("FAILED", "youtube_url")
                    showError("This link doesn't identify an individual YouTube video.")
                } else {
                    currentYouTubeID = id
                    startWeb(safeURL, id)
                }
            }
            ReceiverContract.PlaybackKind.WEB_PAGE -> startWeb(
                if (mode == "page") safeURL else (fallbackPage ?: safeURL))
            ReceiverContract.PlaybackKind.MEDIA -> startNative(safeURL, hint,
                incoming.getStringExtra(ReceiverContract.EXTRA_TITLE) ?: "Video")
        }
    }

    private fun startNative(url: String, hint: String?, title: String) {
        nativeHandler.removeCallbacks(nativeVideoWatchdog)
        videoMonitor = NativeVideoOutputMonitor()
        nativeWarningShown = false
        nativeQualityGraceUntilMs = 0L
        nativeVideoExpected = !hint.orEmpty().startsWith("audio/", true) &&
            !url.substringBefore('?').lowercase().endsWith(".mp3") &&
            !url.substringBefore('?').lowercase().endsWith(".m4a")
        webPlayer?.close()
        usingWeb = false
        currentNativeURL = url
        playerView.visibility = View.VISIBLE
        optionsButton.visibility = View.VISIBLE
        val player = mediaController ?: return
        tvControls.setEnabled(true)
        tvControls.setYouTube(false)
        tvControls.setSoundAvailable(true)
        tvControls.show()
        if (ReceiverContract.mediaMime(hint, url) == "application/x-mpegURL")
            LiveDvr.start(url)
        else LiveDvr.stop()
        val media = MediaItem.Builder().setUri(Uri.parse(url))
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title.take(120)).build())
        ReceiverContract.mediaMime(hint, url)?.let { media.setMimeType(it) }
        player.setMediaItem(media.build())
        player.prepare()
        player.play()
        nativeHandler.removeCallbacks(nativeControlsTick)
        nativeHandler.post(nativeControlsTick)
        if (nativeVideoExpected) nativeHandler.postDelayed(nativeVideoWatchdog, 1700L)
        emit("BUFFERING")
    }

    private fun startWeb(url: String, youtubeVideoID: String? = null) {
        nativeHandler.removeCallbacks(nativeVideoWatchdog)
        nativeHandler.removeCallbacks(nativeControlsTick)
        nativeVideoExpected = false
        tvControls.setEnabled(false)
        mediaController?.pause()
        mediaController?.stop()
        LiveDvr.stop()
        usingWeb = true
        playerView.visibility = View.GONE
        optionsButton.visibility = View.GONE
        hideError()
        webPlayer?.open(url, youtubeVideoID)
    }

    private fun onNativeError(error: PlaybackException) {
        if (error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED &&
            decoderRecoveries < 2) {
            decoderRecoveries++
            emit("RETRYING", "native_decoder")
            mediaController?.seekToDefaultPosition()
            mediaController?.prepare()
            mediaController?.play()
            return
        }
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastLiveEdgeRecoveryMs > 120_000L) liveEdgeRecoveries = 0
            if (liveEdgeRecoveries < 4) {
                liveEdgeRecoveries++
                lastLiveEdgeRecoveryMs = now
                emit("RETRYING", "live_edge")
                mediaController?.seekToDefaultPosition()
                mediaController?.prepare()
                mediaController?.play()
                return
            }
        }
        val code = ReceiverPlaybackErrors.httpStatus(error)
        val badContainer = error.errorCodeName.contains("PARSING_CONTAINER") ||
            error.errorCodeName.contains("INVALID_HTTP_CONTENT_TYPE")
        if (code != null) emit("HTTP_ERROR", code.toString())
        if (PlaybackFailurePolicy.retryWithoutHints(code,
                ReceiverHttpContext.hasHints(), attemptedNativeRetry)) {
            attemptedNativeRetry = true
            emit("RETRYING", "native_http")
            ReceiverHttpContext.configure(null, null)
            mediaController?.prepare()
            mediaController?.play()
            return
        }
        failedNativeURL = currentNativeURL
        val page = fallbackPage ?: activeURL
        if (!pageFallbackAttempted && PlaybackFailurePolicy.useWebPage(code, badContainer,
                page != null, page != activeURL, attemptedWebToNative)) {
            pageFallbackAttempted = true
            emit("AUTO_FALLBACK", code?.let { "HTTP_$it" } ?: "not_media")
            startWeb(page!!)
            return
        }
        val reason = when {
            code != null -> PlaybackFailurePolicy.readableHttpStatus(code)
            badContainer && attemptedWebToNative ->
                "The website's media playlist loaded, but its segments were not compatible video data."
            badContainer -> "The address returned a webpage instead of compatible video data."
            else -> "Playback failed (${error.errorCodeName})."
        }
        emit("FAILED", code?.let { "HTTP_$it" } ?: error.errorCodeName)
        showError(if (badContainer && attemptedWebToNative) reason
            else "$reason The stream may require browser sign-in or a different codec.")
    }

    override fun report(event: String, detail: String) = emit(event, detail)

    override fun showError(message: String) {
        tvControls.setEnabled(false)
        errorLabel.text = message
        errorLabel.visibility = View.VISIBLE
        errorLabel.bringToFront()
    }

    private fun hideError() { errorLabel.visibility = View.GONE }
    private fun emit(event: String, detail: String = "") {
        ReceiverStatus.report(activeRequest, event, detail)
    }

    override fun onVideoPreparing(kind: String) {
        if (!usingWeb) return
        hideError()
        tvControls.setEnabled(true)
        tvControls.setYouTube(kind == "youtube")
        tvControls.setSoundAvailable(kind == "video" || kind == "frame-video")
        if (kind == "youtube")
            tvControls.setYouTubeCaptionsEnabled(currentYouTubeCaptionsEnabled)
        tvControls.show() // Android TV Play works even while the page is hidden.
    }

    override fun onVideoReady(kind: String) {
        if (!usingWeb) return
        hideError()
        if (!tvControls.isShowing) tvControls.show() else tvControls.bringToFront()
    }

    override fun onVideoCovered() {
        if (usingWeb) tvControls.bringToFront()
    }

    override fun onPlaybackInfo(positionSeconds: Double, durationSeconds: Double,
                                playing: Boolean, captionsAvailable: Boolean,
                                bufferedAheadSeconds: Double, muted: Boolean, state: String) {
        if (!usingWeb) return
        tvControls.update(positionSeconds, durationSeconds, playing,
            captionsAvailable, bufferedAheadSeconds, muted, state)
    }

    override fun onNativeFallbackCandidate(url: String, referrer: String?, userAgent: String?) {
        if (!usingWeb || attemptedWebToNative || url == failedNativeURL ||
            !ReceiverContract.validURL(url) ||
            !ReceiverContract.isLikelyMedia(url, null)) return
        attemptedWebToNative = true
        emit("RETRYING", "web_to_native")
        ReceiverHttpContext.configure(referrer ?: fallbackPage ?: activeURL, userAgent)
        startNative(url, null, "Video")
    }

    override fun onYouTubeQualityOptions(options: List<String>) {
        if (!usingWeb || currentYouTubeID == null || options.isEmpty()) return
        qualityDialog?.dismiss()
        qualityDialog = AlertDialog.Builder(this)
            .setTitle("YouTube quality")
            .setItems(options.toTypedArray()) { _, index ->
                webPlayer?.selectYouTubeQuality(options[index])
            }
            .setNegativeButton("Cancel") { _, _ ->
                webPlayer?.dismissYouTubeQuality()
            }
            .setOnCancelListener {
                webPlayer?.dismissYouTubeQuality()
            }
            .create().also {
                it.show()
                it.window?.setBackgroundDrawable(ColorDrawable(0xFF202124.toInt()))
            }
    }

    override fun onYouTubeCaptionsChanged(enabled: Boolean) {
        if (!usingWeb || currentYouTubeID == null) return
        currentYouTubeCaptionsEnabled = enabled
        tvControls.setYouTubeCaptionsEnabled(enabled)
    }

    override fun onYouTubeControlUnavailable(control: String) {
        if (!usingWeb || currentYouTubeID == null) return
        Toast.makeText(this,
            if (control == "quality") "Quality settings aren't available for this video"
            else "Captions aren't available for this video", Toast.LENGTH_SHORT).show()
    }

    private fun handleNativeControl(action: String, value: Double?) {
        val player = mediaController ?: return
        when (action) {
            "toggle" -> if (player.isPlaying) player.pause() else player.play()
            "play" -> player.play()
            "pause" -> player.pause()
            "rewind" -> jump(-10_000L)
            "forward" -> jump(10_000L)
            "seek" -> if (value != null && player.isCurrentMediaItemSeekable) {
                val target = (value.coerceAtLeast(0.0) * 1000.0).toLong()
                val duration = player.duration
                player.seekTo(if (duration > 0L) target.coerceAtMost(duration) else target)
            }
            "speed" -> value?.let { player.setPlaybackSpeed(it.toFloat()) }
            "toggleMute" -> player.volume = if (player.volume <= 0f) 1f else 0f
            "captions" -> {
                val current = player.trackSelectionParameters
                val disabled = current.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
                player.trackSelectionParameters = current.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !disabled).build()
            }
        }
        updateNativeControls()
    }

    private fun updateNativeControls() {
        if (usingWeb) return
        val player = mediaController ?: return
        val duration = player.duration.takeIf { it > 0L && it != C.TIME_UNSET } ?: 0L
        val position = player.currentPosition.coerceAtLeast(0L)
        val bufferedAhead = (player.bufferedPosition - position).coerceAtLeast(0L)
        val captionsAvailable = player.currentTracks.groups.any {
            it.type == C.TRACK_TYPE_TEXT && it.isSupported
        }
        val captionsEnabled = !player.trackSelectionParameters.disabledTrackTypes
            .contains(C.TRACK_TYPE_TEXT)
        val status = when (player.playbackState) {
            Player.STATE_BUFFERING -> "Buffering…"
            Player.STATE_ENDED -> "Ended"
            Player.STATE_READY -> if (player.isPlaying) "Playing" else "Paused"
            else -> "Preparing…"
        }
        tvControls.setCaptionsEnabled(captionsEnabled)
        tvControls.setSpeed(player.playbackParameters.speed)
        tvControls.update(position / 1000.0, duration / 1000.0,
            player.isPlaying, captionsAvailable, bufferedAhead / 1000.0,
            player.volume <= 0f, status)
    }

    private fun showNativeOptions() {
        if (usingWeb) return
        val player = mediaController ?: return
        val menu = PopupMenu(this, optionsButton)
        listOf("Play / pause", "Rewind 10 seconds", "Forward 10 seconds",
            "Speed 0.75×", "Speed 1×", "Speed 1.25×", "Speed 1.5×", "Speed 2×",
            "Captions on / off", "Repeat on / off",
            "Try lower video quality", "Default video quality", "Close player",
            "Go live")
            .forEachIndexed { index, title -> menu.menu.add(0, index + 1, index, title) }
        menu.setOnMenuItemClickListener { option ->
            when (option.itemId) {
                1 -> if (player.isPlaying) player.pause() else player.play()
                2 -> jump(-10_000L)
                3 -> jump(10_000L)
                4 -> player.setPlaybackSpeed(0.75f)
                5 -> player.setPlaybackSpeed(1f)
                6 -> player.setPlaybackSpeed(1.25f)
                7 -> player.setPlaybackSpeed(1.5f)
                8 -> player.setPlaybackSpeed(2f)
                9 -> {
                    handleNativeControl("captions", null)
                }
                10 -> player.repeatMode = if (player.repeatMode == Player.REPEAT_MODE_OFF)
                    Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                11 -> {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setMaxVideoSizeSd().build()
                    nativeWarningShown = false
                    nativeQualityGraceUntilMs = SystemClock.elapsedRealtime() + 12_000L
                    hideError()
                    emit("VIDEO_QUALITY_RETRY", "SD")
                }
                12 -> {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .clearVideoSizeConstraints().build()
                    nativeWarningShown = false
                    nativeQualityGraceUntilMs = SystemClock.elapsedRealtime() + 12_000L
                    hideError()
                    emit("VIDEO_QUALITY_RETRY", "DEFAULT")
                }
                13 -> { player.stop(); finish() }
                14 -> if (LiveDvr.isRecording()) {
                    player.seekToDefaultPosition()
                    player.play()
                    tvControls.show()
                }
            }
            updateNativeControls()
            true
        }
        menu.show()
    }

    private fun jump(offset: Long) {
        val player = mediaController ?: return
        if (!player.isCurrentMediaItemSeekable) return
        val duration = player.duration
        val target = (player.currentPosition + offset).coerceAtLeast(0L)
        player.seekTo(if (duration > 0) target.coerceAtMost(duration) else target)
        tvControls.show()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (usingWeb) {
            // No DPAD event is forwarded to the touch/mouse-driven WebView.
            // These are Android TV actions, regardless of the website used.
            if (event.keyCode == KeyEvent.KEYCODE_BACK &&
                (tvControls.isShowing || consumedBackKey)) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    consumedBackKey = true
                    tvControls.hide()
                } else if (event.action == KeyEvent.ACTION_UP) {
                    consumedBackKey = false
                }
                return true
            }
            if (event.action == KeyEvent.ACTION_DOWN) {
                val command = when(event.keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "toggle"
                    KeyEvent.KEYCODE_MEDIA_PLAY -> "play"
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> "pause"
                    KeyEvent.KEYCODE_MEDIA_REWIND -> "rewind"
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> "forward"
                    else -> null
                }
                if (command != null) {
                    webPlayer?.handleControl(command)
                    return true
                }
                if (event.keyCode == KeyEvent.KEYCODE_MENU ||
                    event.keyCode == KeyEvent.KEYCODE_INFO) {
                    tvControls.toggle()
                    return true
                }
                if (webPlayer?.isVideoReady == true) {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                            if (!tvControls.isShowing) {
                                tvControls.show()
                                return true
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT -> if (!tvControls.isShowing) {
                            webPlayer?.handleControl("rewind")
                            tvControls.show()
                            return true
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> if (!tvControls.isShowing) {
                            webPlayer?.handleControl("forward")
                            tvControls.show()
                            return true
                        }
                        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN ->
                            if (!tvControls.isShowing) {
                                tvControls.show()
                                return true
                            }
                    }
                }
            }
            // Key events that aren't part of the receiver's TV controls are
            // swallowed, not left for a touch-oriented website to consume.
            if (event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_UP,
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                return if (tvControls.isShowing) super.dispatchKeyEvent(event) else true
            }
            return super.dispatchKeyEvent(event)
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK &&
            (tvControls.isShowing || consumedBackKey)) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                consumedBackKey = true
                tvControls.hide()
            } else if (event.action == KeyEvent.ACTION_UP) {
                consumedBackKey = false
            }
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MENU -> { showNativeOptions(); return true }
                KeyEvent.KEYCODE_INFO -> { tvControls.toggle(); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { handleNativeControl("toggle", null); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY -> { handleNativeControl("play", null); return true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { handleNativeControl("pause", null); return true }
                KeyEvent.KEYCODE_MEDIA_REWIND -> { handleNativeControl("rewind", null); return true }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { handleNativeControl("forward", null); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN -> if (!tvControls.isShowing) {
                    tvControls.show()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> if (!tvControls.isShowing) {
                    jump(-10_000L)
                    tvControls.show()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (!tvControls.isShowing) {
                    jump(10_000L)
                    tvControls.show()
                    return true
                }
            }
        }
        if (event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)) {
            return if (tvControls.isShowing) super.dispatchKeyEvent(event) else true
        }
        return super.dispatchKeyEvent(event)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (tvControls.isShowing) {
            tvControls.hide()
            return
        }
        if (usingWeb && webPlayer?.exitFullscreenIfShown() == true) return
        mediaController?.stop()
        finish()
    }

    override fun onResume() { super.onResume(); webPlayer?.onResume() }
    override fun onPause() { webPlayer?.onPause(); super.onPause() }

    override fun onDestroy() {
        nativeHandler.removeCallbacks(nativeVideoWatchdog)
        nativeHandler.removeCallbacks(nativeControlsTick)
        qualityDialog?.dismiss()
        qualityDialog = null
        tvControls.destroy()
        webPlayer?.close()
        webPlayer = null
        mediaController?.stop()
        LiveDvr.stop()
        playerView.player = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        mediaController = null
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
