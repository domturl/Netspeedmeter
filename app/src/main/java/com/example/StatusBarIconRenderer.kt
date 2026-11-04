package com.example

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface

/**
 * Draws the live download/upload rates into a small square bitmap used as the notification's
 * small icon, which Android shows directly in the status bar.
 *
 * Layout (two rows):
 *   ▼ 12M     <- download
 *   ▲ 340k    <- upload
 *
 * Status bar icons are rendered as alpha masks and tinted by the system, so everything is drawn
 * in opaque white on a transparent background.
 */
class StatusBarIconRenderer(private val sizePx: Int = 96) {

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        textAlign = Paint.Align.LEFT
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val path = Path()

    fun render(downloadBps: Long, uploadBps: Long, useBits: Boolean): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val rowHeight = sizePx / 2f
        drawRow(canvas, SpeedFormatter.formatCompact(downloadBps, useBits), top = 0f, rowHeight, down = true)
        drawRow(canvas, SpeedFormatter.formatCompact(uploadBps, useBits), top = rowHeight, rowHeight, down = false)
        return bitmap
    }

    private fun drawRow(canvas: Canvas, text: String, top: Float, rowHeight: Float, down: Boolean) {
        val arrowW = sizePx * 0.20f
        val gap = sizePx * 0.03f
        val textAreaW = sizePx - arrowW - gap

        // Text: as tall as the row allows, squeezed horizontally if it would overflow.
        textPaint.textSize = rowHeight * 1.08f
        textPaint.textScaleX = 1f
        val measured = textPaint.measureText(text)
        if (measured > textAreaW) textPaint.textScaleX = textAreaW / measured

        val fm = textPaint.fontMetrics
        val capHeight = -fm.ascent * 0.72f // approx digit height
        val baseline = top + (rowHeight + capHeight) / 2f
        canvas.drawText(text, arrowW + gap, baseline, textPaint)

        // Arrow triangle, vertically centred on the digits.
        val cy = baseline - capHeight / 2f
        val h = capHeight * 0.75f
        path.reset()
        if (down) {
            path.moveTo(0f, cy - h / 2f)
            path.lineTo(arrowW, cy - h / 2f)
            path.lineTo(arrowW / 2f, cy + h / 2f)
        } else {
            path.moveTo(0f, cy + h / 2f)
            path.lineTo(arrowW, cy + h / 2f)
            path.lineTo(arrowW / 2f, cy - h / 2f)
        }
        path.close()
        canvas.drawPath(path, arrowPaint)
    }
}
