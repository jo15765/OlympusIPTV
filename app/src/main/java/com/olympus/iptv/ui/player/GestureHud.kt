package com.olympus.iptv.ui.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/** Vertical pill shown while a phone user drags brightness or volume. */
class GestureHud @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFFFFF }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE0B15A.toInt() }
    private val plate = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC141210.toInt() }
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF4E7C5.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF4E7C5.toInt()
        textAlign = Paint.Align.CENTER
        textSize = 28f
    }
    private val box = RectF()
    private var fraction = 0f
    private var brightness = true
    private var caption: String? = null

    fun showLevel(brightness: Boolean, percent: Int) {
        this.brightness = brightness
        caption = null
        fraction = percent.coerceIn(0, 100) / 100f
        animate().cancel()
        alpha = 1f
        visibility = VISIBLE
        invalidate()
    }

    fun showSeek(text: String) {
        caption = text
        fraction = 1f
        animate().cancel()
        alpha = 1f
        visibility = VISIBLE
        invalidate()
    }

    fun fadeOut() {
        animate().cancel()
        animate().alpha(0f).setDuration(600L).withEndAction {
            if (alpha == 0f) visibility = GONE
        }.start()
    }

    override fun onDraw(canvas: Canvas) {
        val radius = min(width, height) / 2f
        box.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(box, radius, radius, plate)
        val inset = width * 0.28f
        val top = height * 0.22f
        val bottom = height * 0.90f
        box.set(inset, top, width - inset, bottom)
        canvas.drawRoundRect(box, inset, inset, track)
        val filled = top + (bottom - top) * (1f - fraction)
        if (fraction > 0f) {
            canvas.save()
            canvas.clipRect(inset, filled, width - inset, bottom)
            canvas.drawRoundRect(box, inset, inset, fill)
            canvas.restore()
        }
        val note = caption
        if (note != null) {
            canvas.drawText(note, width / 2f, height * 0.14f, label)
        } else if (brightness) {
            drawSun(canvas, width / 2f, height * 0.11f)
        } else {
            drawSpeaker(canvas, width / 2f, height * 0.11f)
        }
    }

    private fun drawSun(canvas: Canvas, cx: Float, cy: Float) {
        canvas.drawCircle(cx, cy, 8f, icon)
        for (index in 0 until 8) {
            val angle = Math.toRadians((index * 45).toDouble())
            val cos = kotlin.math.cos(angle).toFloat()
            val sin = kotlin.math.sin(angle).toFloat()
            canvas.drawLine(cx + cos * 12f, cy + sin * 12f, cx + cos * 18f, cy + sin * 18f, icon)
        }
    }

    private fun drawSpeaker(canvas: Canvas, cx: Float, cy: Float) {
        canvas.drawLine(cx - 8f, cy - 4f, cx - 8f, cy + 4f, icon)
        canvas.drawLine(cx - 8f, cy - 4f, cx + 2f, cy - 10f, icon)
        canvas.drawLine(cx - 8f, cy + 4f, cx + 2f, cy + 10f, icon)
        canvas.drawLine(cx + 2f, cy - 10f, cx + 2f, cy + 10f, icon)
        canvas.drawLine(cx + 8f, cy - 6f, cx + 8f, cy + 6f, icon)
    }
}
