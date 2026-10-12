package com.glasslink.receiver

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout

/** Lets a D-pad operate YouTube's own quality and caption menus in the iframe. */
internal class YouTubeRemotePointer(
    context: Context,
    private val root: FrameLayout,
    private val web: WebView
) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var pointerX = 0f
    private var pointerY = 0f

    init {
        isFocusable = false
        isClickable = false
        contentDescription = "YouTube controls. Use arrows to move the cursor, Select to click, Back to return."
        root.addView(this, FrameLayout.LayoutParams(-1, -1))
        bringToFront()
    }

    fun start(atCaptions: Boolean) {
        // Start near the corresponding control. YouTube can rearrange its bar,
        // so these are only starting points; the cursor can reach any option.
        pointerX = web.width * (if (atCaptions) 0.90f else 0.95f)
        pointerY = web.height * 0.05f
        hover(MotionEvent.ACTION_HOVER_ENTER)
        hover(MotionEvent.ACTION_HOVER_MOVE)
        invalidate()
    }

    fun handle(event: KeyEvent): Boolean {
        val key = event.keyCode
        if (key !in setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER)) return false
        if (event.action == KeyEvent.ACTION_DOWN) {
            val step = (if (event.repeatCount > 2) 42f else 23f) * density
            when (key) {
                KeyEvent.KEYCODE_DPAD_LEFT -> pointerX -= step
                KeyEvent.KEYCODE_DPAD_RIGHT -> pointerX += step
                KeyEvent.KEYCODE_DPAD_UP -> pointerY -= step
                KeyEvent.KEYCODE_DPAD_DOWN -> pointerY += step
                else -> tap()
            }
            pointerX = pointerX.coerceIn(0f, (web.width - 1).coerceAtLeast(0).toFloat())
            pointerY = pointerY.coerceIn(0f, (web.height - 1).coerceAtLeast(0).toFloat())
            if (key in setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) hover(MotionEvent.ACTION_HOVER_MOVE)
            invalidate()
        }
        return true
    }

    fun close() { root.removeView(this) }

    private fun hover(action: Int) {
        val now = SystemClock.uptimeMillis()
        val motion = MotionEvent.obtain(now, now, action, pointerX, pointerY, 0)
        motion.source = InputDevice.SOURCE_MOUSE
        web.dispatchGenericMotionEvent(motion)
        motion.recycle()
    }

    private fun tap() {
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, pointerX, pointerY, 0)
        val up = MotionEvent.obtain(now, now + 60, MotionEvent.ACTION_UP, pointerX, pointerY, 0)
        down.source = InputDevice.SOURCE_TOUCHSCREEN
        up.source = InputDevice.SOURCE_TOUCHSCREEN
        web.dispatchTouchEvent(down)
        web.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.style = Paint.Style.FILL
        paint.color = 0xD9000000.toInt()
        canvas.drawRoundRect(RectF(16f*density, 14f*density,
            520f*density, 80f*density), 9f*density, 9f*density, paint)
        paint.color = Color.WHITE
        paint.textSize = 19f*density
        canvas.drawText("YouTube quality and captions", 29f*density, 41f*density, paint)
        paint.textSize = 14f*density
        canvas.drawText("Arrows: move    Select: click    Back: return",
            29f*density, 66f*density, paint)

        paint.color = Color.BLACK
        canvas.drawCircle(pointerX, pointerY, 14f*density, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(pointerX, pointerY, 11f*density, paint)
        paint.color = 0xFF111111.toInt()
        canvas.drawCircle(pointerX, pointerY, 4f*density, paint)
    }
}
