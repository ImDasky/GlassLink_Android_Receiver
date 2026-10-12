package com.glasslink.receiver

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

/**
 * GlassLink's D-pad playback controls for both native and website video.
 */
internal class TvPlaybackOverlay(
    private val context: Context,
    root: FrameLayout,
    private val actions: Actions
) {
    interface Actions { fun onAction(action: String, value: Double? = null) }

    private val handler = Handler(Looper.getMainLooper())
    private val panel: FrameLayout
    private val playButton: TvActionButton
    private val soundButton: TvActionButton
    private val captionsButton: TvActionButton
    private val qualityButton: TvActionButton
    private val speedButton: TvActionButton
    private val progress: TvSeekRail
    private val clock: TextView
    private val statusLabel: TextView
    private val rates = doubleArrayOf(0.75, 1.0, 1.25, 1.5, 2.0)
    private var speedIndex = 1
    private var positionSeconds = 0.0
    private var durationSeconds = 0.0
    private var playing = false
    private var active = false
    private var captionsAvailable = false
    private var youtubeMode = false
    private val autoHide = Runnable { if (playing) hide() }

    val isShowing: Boolean get() = active && panel.visibility == View.VISIBLE

    init {
        panel = FrameLayout(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x00000000, 0x9E060911.toInt(), 0xF4000000.toInt())
            )
            isFocusable = false
            visibility = View.GONE
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(46), dp(52), dp(46), dp(25))
        }
        val headline = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(context).apply {
            text = "Now Playing"
            textSize = 20f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            isFocusable = false
        }
        headline.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        statusLabel = TextView(context).apply {
            text = "Preparing…"
            textSize = 13f
            setTextColor(0xFFD5DBE5.toInt())
            gravity = Gravity.END
            isFocusable = false
        }
        headline.addView(statusLabel, LinearLayout.LayoutParams(-2, -2))
        content.addView(headline)

        progress = TvSeekRail(context) { target ->
            actions.onAction("seek", target)
            positionSeconds = target
            refreshClock()
            resetTimer()
        }
        content.addView(progress, LinearLayout.LayoutParams(-1, dp(31)).apply {
            topMargin = dp(11)
        })

        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        clock = TextView(context).apply {
            textSize = 14f
            setTextColor(0xFFE2E4EC.toInt())
            text = "00:00 / --:--"
            isFocusable = false
        }
        controls.addView(clock, LinearLayout.LayoutParams(0, -2, 1f))

        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = TvActionButton(context, TvActionButton.Glyph.REWIND, "Rewind 10 seconds") {
            actions.onAction("rewind")
            resetTimer()
        }
        playButton = TvActionButton(context, TvActionButton.Glyph.PLAY, "Play or pause") {
            actions.onAction("toggle")
            resetTimer()
        }
        val forward = TvActionButton(context, TvActionButton.Glyph.FORWARD, "Forward 10 seconds") {
            actions.onAction("forward")
            resetTimer()
        }
        soundButton = TvActionButton(context, TvActionButton.Glyph.SOUND, "Mute sound") {
            actions.onAction("toggleMute")
            resetTimer()
        }
        speedButton = TvActionButton(context, TvActionButton.Glyph.SPEED, "Playback speed") {
            cycleSpeed()
        }
        captionsButton = TvActionButton(context, TvActionButton.Glyph.CAPTIONS, "Toggle captions") {
            actions.onAction(if (youtubeMode) "youtubeCaptions" else "captions")
            resetTimer()
        }
        qualityButton = TvActionButton(context, TvActionButton.Glyph.QUALITY, "YouTube quality") {
            actions.onAction("youtubeQuality")
            resetTimer()
        }
        for (button in listOf(back, playButton, forward, soundButton, speedButton,
                captionsButton, qualityButton)) {
            buttons.addView(button, LinearLayout.LayoutParams(dp(70), dp(58)).apply {
                marginStart = dp(2)
                marginEnd = dp(2)
            })
        }
        controls.addView(buttons)
        content.addView(controls, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(2)
        })
        panel.addView(content, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        root.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        qualityButton.visibility = View.GONE
        soundButton.visibility = View.GONE
        setEnabled(false)
    }

    fun setEnabled(enabled: Boolean) {
        active = enabled
        if (!enabled) {
            hide()
            youtubeMode = false
            qualityButton.visibility = View.GONE
            soundButton.visibility = View.GONE
        }
        if (enabled) {
            positionSeconds = 0.0
            durationSeconds = 0.0
            playing = false
            speedIndex = 1
            speedButton.speedText = "1×"
            captionsButton.checked = false
            statusLabel.text = "Preparing…"
            progress.update(0.0, 0.0, 0.0)
        }
    }

    fun setYouTube(enabled: Boolean) {
        youtubeMode = enabled
        qualityButton.visibility = if (enabled) View.VISIBLE else View.GONE
        captionsButton.checked = false
        captionsButton.contentDescription = "Toggle captions"
    }

    fun setSoundAvailable(available: Boolean) {
        soundButton.visibility = if (available) View.VISIBLE else View.GONE
    }

    fun setYouTubeCaptionsEnabled(enabled: Boolean) {
        if (!youtubeMode) return
        captionsButton.checked = enabled
        captionsButton.contentDescription =
            if (enabled) "Turn captions off" else "Turn captions on"
    }

    fun setCaptionsEnabled(enabled: Boolean) {
        if (youtubeMode) return
        captionsButton.checked = enabled
        captionsButton.contentDescription =
            if (enabled) "Turn captions off" else "Turn captions on"
    }

    fun setSpeed(rate: Float) {
        val index = rates.indexOfFirst { kotlin.math.abs(it - rate) < 0.01 }
        speedIndex = if (index >= 0) index else 1
        speedButton.speedText = if (index >= 0) "${rates[index]}×" else "${rate}×"
    }

    fun show() {
        if (!active) return
        panel.visibility = View.VISIBLE
        bringToFront()
        playButton.requestFocus()
        resetTimer()
    }

    fun bringToFront() {
        if (active) panel.bringToFront()
    }

    fun hide() {
        handler.removeCallbacks(autoHide)
        panel.visibility = View.GONE
        panel.clearFocus()
    }

    fun toggle() { if (isShowing) hide() else show() }

    fun update(positionSeconds: Double, durationSeconds: Double,
               isPlaying: Boolean, hasCaptions: Boolean,
               bufferedAheadSeconds: Double, muted: Boolean, status: String) {
        val wasPlaying = playing
        this.positionSeconds = positionSeconds.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0
        this.durationSeconds = durationSeconds.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        playing = isPlaying
        captionsAvailable = hasCaptions
        val bufferEnd = (this.positionSeconds +
            (bufferedAheadSeconds.takeIf { it.isFinite() } ?: 0.0).coerceAtLeast(0.0))
        progress.update(this.positionSeconds, this.durationSeconds, bufferEnd)
        playButton.glyph = if (playing) TvActionButton.Glyph.PAUSE else TvActionButton.Glyph.PLAY
        captionsButton.isEnabled = captionsAvailable || youtubeMode
        captionsButton.alpha = if (captionsButton.isEnabled) 1f else 0.45f
        soundButton.checked = muted
        soundButton.contentDescription = if (muted) "Turn sound on" else "Mute sound"
        clock.text = "${clockFormat(this.positionSeconds)}  /  ${clockFormat(this.durationSeconds)}"
        statusLabel.text = status
        if (isShowing && wasPlaying != playing) resetTimer()
    }

    fun destroy() {
        handler.removeCallbacks(autoHide)
        hide()
    }

    private fun refreshClock() {
        clock.text = "${clockFormat(positionSeconds)}  /  ${clockFormat(durationSeconds)}"
    }

    private fun cycleSpeed() {
        speedIndex = (speedIndex + 1) % rates.size
        speedButton.speedText = "${rates[speedIndex]}×"
        actions.onAction("speed", rates[speedIndex])
        resetTimer()
    }

    private fun resetTimer() {
        handler.removeCallbacks(autoHide)
        if (playing && isShowing) handler.postDelayed(autoHide, 6_500L)
    }

    private fun clockFormat(seconds: Double): String {
        if (!seconds.isFinite() || seconds < 0.0) return "--:--"
        val whole = seconds.toLong()
        val hours = whole / 3600L
        val minutes = (whole / 60L) % 60L
        val secs = whole % 60L
        return if (hours > 0L) "%d:%02d:%02d".format(hours, minutes, secs)
            else "%02d:%02d".format(minutes, secs)
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}

/** Slim focusable timeline, including a separate buffered range. */
private class TvSeekRail(
    context: Context,
    private val seek: (Double) -> Unit
) : View(context) {
    private var position = 0.0
    private var duration = 0.0
    private var buffered = 0.0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = context.resources.displayMetrics.density

    init {
        isFocusable = true
        isClickable = true
        contentDescription = "Timeline, left or right to seek 10 seconds"
        setOnKeyListener { _, key, e ->
            if (e.action != KeyEvent.ACTION_DOWN || duration <= 0.0) return@setOnKeyListener false
            val delta = when (key) {
                KeyEvent.KEYCODE_DPAD_LEFT -> -10.0
                KeyEvent.KEYCODE_DPAD_RIGHT -> 10.0
                else -> return@setOnKeyListener false
            }
            position = (position + delta).coerceIn(0.0, duration)
            seek(position)
            invalidate()
            true
        }
        setOnFocusChangeListener { _, _ -> invalidate() }
    }

    fun update(positionSeconds: Double, durationSeconds: Double, bufferedEnd: Double) {
        position = positionSeconds
        duration = durationSeconds
        buffered = bufferedEnd
        isEnabled = durationSeconds > 1.0
        alpha = if (isEnabled) 1f else 0.55f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = 4f * density
        val right = width.toFloat() - 4f * density
        val y = height / 2f
        val radius = (if (hasFocus()) 3.8f else 2.4f) * density
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = radius * 2f
        paint.color = 0x88FFFFFF.toInt()
        canvas.drawLine(left, y, right, y, paint)
        if (duration > 0.0 && right > left) {
            val bufferedEnd = left + ((buffered / duration).coerceIn(0.0, 1.0) * (right - left)).toFloat()
            paint.color = 0xFFD3D7DF.toInt()
            canvas.drawLine(left, y, bufferedEnd, y, paint)
            paint.color = Color.WHITE
            val playEnd = left + ((position / duration).coerceIn(0.0, 1.0) * (right - left)).toFloat()
            canvas.drawLine(left, y, playEnd, y, paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            canvas.drawCircle(playEnd, y, (if (hasFocus()) 7f else 4.5f) * density, paint)
        }
    }
}

/** Vector-drawn remote buttons, avoiding text-only, touch-style controls. */
private class TvActionButton(
    context: Context,
    initial: Glyph,
    label: String,
    onActivate: () -> Unit
) : View(context) {
    enum class Glyph { PLAY, PAUSE, REWIND, FORWARD, SOUND, SPEED, CAPTIONS, QUALITY }
    var glyph = initial
        set(value) { field = value; invalidate() }
    var speedText = "1×"
        set(value) { field = value; invalidate() }
    var checked = false
        set(value) { field = value; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = context.resources.displayMetrics.density

    init {
        isFocusable = true
        isClickable = true
        contentDescription = label
        setOnClickListener { onActivate() }
        setOnFocusChangeListener { _, _ -> invalidate() }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val scale = density
        paint.style = Paint.Style.FILL
        paint.color = when {
            hasFocus() -> Color.WHITE
            checked -> 0xFF177E92.toInt()
            else -> 0x3FFFFFFF
        }
        val radius = min(width, height).toFloat() * 0.45f
        canvas.drawRoundRect(RectF(cx - radius, cy - radius, cx + radius, cy + radius),
            radius, radius, paint)
        paint.color = if (hasFocus()) 0xFF101219.toInt() else Color.WHITE
        when (glyph) {
            Glyph.PLAY -> {
                val shape = Path().apply {
                    moveTo(cx - 6f*scale, cy - 11f*scale)
                    lineTo(cx + 11f*scale, cy)
                    lineTo(cx - 6f*scale, cy + 11f*scale)
                    close()
                }
                canvas.drawPath(shape, paint)
            }
            Glyph.PAUSE -> {
                canvas.drawRoundRect(RectF(cx - 9f*scale, cy - 10f*scale,
                    cx - 3f*scale, cy + 10f*scale), 1.5f*scale, 1.5f*scale, paint)
                canvas.drawRoundRect(RectF(cx + 3f*scale, cy - 10f*scale,
                    cx + 9f*scale, cy + 10f*scale), 1.5f*scale, 1.5f*scale, paint)
            }
            Glyph.REWIND, Glyph.FORWARD -> {
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeWidth = 2.3f*scale
                val rect = RectF(cx-11f*scale, cy-10f*scale, cx+11f*scale, cy+12f*scale)
                val backwards = glyph == Glyph.REWIND
                canvas.drawArc(rect, if (backwards) 200f else -20f, 275f, false, paint)
                paint.style = Paint.Style.FILL
                val x = if (backwards) cx - 11f*scale else cx + 11f*scale
                val triangle = Path().apply {
                    moveTo(x, cy - 10f*scale)
                    lineTo(x + (if (backwards) -7f else 7f)*scale, cy - 7f*scale)
                    lineTo(x, cy - 3f*scale)
                    close()
                }
                canvas.drawPath(triangle, paint)
                paint.textSize = 12f*scale
                paint.typeface = android.graphics.Typeface.create("sans-serif-medium", 0)
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText("10", cx, cy + 5f*scale, paint)
            }
            Glyph.SOUND -> {
                val speaker = Path().apply {
                    moveTo(cx - 12f*scale, cy - 5f*scale)
                    lineTo(cx - 6f*scale, cy - 5f*scale)
                    lineTo(cx + 1f*scale, cy - 11f*scale)
                    lineTo(cx + 1f*scale, cy + 11f*scale)
                    lineTo(cx - 6f*scale, cy + 5f*scale)
                    lineTo(cx - 12f*scale, cy + 5f*scale)
                    close()
                }
                canvas.drawPath(speaker, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2.2f*scale
                if (checked) {
                    canvas.drawLine(cx + 5f*scale, cy - 7f*scale,
                        cx + 13f*scale, cy + 7f*scale, paint)
                } else {
                    canvas.drawArc(RectF(cx - 2f*scale, cy - 8f*scale,
                        cx + 13f*scale, cy + 8f*scale), -58f, 116f, false, paint)
                }
            }
            Glyph.SPEED, Glyph.CAPTIONS, Glyph.QUALITY -> {
                paint.textSize = (if (glyph == Glyph.SPEED) 14f else 17f)*scale
                paint.textAlign = Paint.Align.CENTER
                paint.typeface = android.graphics.Typeface.create("sans-serif-medium", 0)
                canvas.drawText(when (glyph) {
                    Glyph.SPEED -> speedText
                    Glyph.CAPTIONS -> "CC"
                    else -> "Q"
                },
                    cx, cy - (paint.ascent() + paint.descent())/2f, paint)
                if (glyph == Glyph.CAPTIONS && checked) {
                    paint.color = if (hasFocus()) 0xFF177E92.toInt() else Color.WHITE
                    canvas.drawRoundRect(RectF(cx - 11f*scale, cy + 14f*scale,
                        cx + 11f*scale, cy + 17f*scale), 1.5f*scale, 1.5f*scale, paint)
                }
            }
        }
    }
}
