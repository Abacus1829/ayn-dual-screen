package com.abacus.dualscreen.scribble

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * A pad you draw on with a finger.
 *
 * Strokes are kept as a list rather than painted straight into a bitmap, which is what makes undo a
 * single `removeLast` instead of a snapshot stack. Redrawing the whole list every frame sounds
 * wasteful and is not: a doodle is tens of strokes, and the alternative costs a full-size bitmap per
 * undo step.
 *
 * Everything is drawn inside a `saveLayer`, because the eraser is a stroke with [PorterDuff.Mode.CLEAR]
 * and CLEAR against the window itself would punch a hole through to whatever is behind it.
 */
class DoodleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private class Stroke(val path: Path, val color: Int, val width: Float, val erase: Boolean)

    private val strokes = mutableListOf<Stroke>()
    private var current: Path? = null

    /** Where the last touch was, so the curve can be smoothed rather than drawn as corners. */
    private var lastX = 0f
    private var lastY = 0f

    var penColor: Int = Color.WHITE
    var penWidth: Float = 6f
    var erasing: Boolean = false

    /** Told when the pad goes empty or stops being empty, so a Send button can grey itself out. */
    var onChanged: (() -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val clear = PorterDuffXfermode(PorterDuff.Mode.CLEAR)

    val isEmpty: Boolean get() = strokes.isEmpty() && current == null

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // An offscreen layer, so an eraser stroke removes ink rather than the window.
        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        drawStrokes(canvas)
        canvas.restoreToCount(layer)
    }

    private fun drawStrokes(canvas: Canvas) {
        for (stroke in strokes) paintStroke(canvas, stroke)

        current?.let {
            paintStroke(canvas, Stroke(it, penColor, penWidth, erasing))
        }
    }

    private fun paintStroke(canvas: Canvas, stroke: Stroke) {
        paint.color = if (stroke.erase) Color.TRANSPARENT else stroke.color
        paint.strokeWidth = stroke.width
        paint.xfermode = if (stroke.erase) clear else null
        canvas.drawPath(stroke.path, paint)
        paint.xfermode = null
    }

    @Suppress("ClickableViewAccessibility")   // a drawing surface has no click to announce
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // The parent is a scrolling list on most screens this sits in. Without this, the
                // first drag scrolls the page instead of drawing a line.
                parent?.requestDisallowInterceptTouchEvent(true)

                current = Path().apply {
                    moveTo(x, y)
                    // A tap with no movement should still leave a dot. A path with only a moveTo
                    // draws nothing at all, so nudge it a fraction of a pixel.
                    lineTo(x + 0.1f, y + 0.1f)
                }
                lastX = x
                lastY = y
            }

            MotionEvent.ACTION_MOVE -> {
                // Quadratic through the midpoint: joining raw touch samples with lineTo gives
                // visible corners at every sample on a slow drag.
                current?.quadTo(lastX, lastY, (x + lastX) / 2f, (y + lastY) / 2f)
                lastX = x
                lastY = y
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                current?.let { strokes += Stroke(it, penColor, penWidth, erasing) }
                current = null
                parent?.requestDisallowInterceptTouchEvent(false)
                onChanged?.invoke()
            }
        }

        invalidate()
        return true
    }

    fun undo() {
        if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex)
        invalidate()
        onChanged?.invoke()
    }

    fun clearAll() {
        strokes.clear()
        current = null
        invalidate()
        onChanged?.invoke()
    }

    /**
     * The doodle as an image, cropped to the ink, or null when nothing has been drawn.
     *
     * Cropped rather than the whole pad, which is the difference between a message bubble the width
     * of the screen and one the width of the drawing. The pad is as wide as the display and a doodle
     * usually occupies a corner of it; saving all that transparent space makes every bubble
     * enormous, every PNG bigger than it needs to be, and every send slower.
     *
     * Transparent rather than filled, so the bubble supplies the paper colour and a doodle looks the
     * same on a light skin as on a dark one. Anything opening the PNG on a PC gets transparency too,
     * which is the right answer for ink.
     */
    fun toBitmap(): Bitmap? {
        if (isEmpty || width <= 0 || height <= 0) return null

        val bounds = inkBounds() ?: return null

        val bitmap = Bitmap.createBitmap(
            bounds.width().toInt().coerceAtLeast(1),
            bounds.height().toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )

        val canvas = Canvas(bitmap)
        canvas.translate(-bounds.left, -bounds.top)
        drawStrokes(canvas)
        return bitmap
    }

    /**
     * The rectangle the ink actually occupies, padded by half the widest stroke.
     *
     * Eraser strokes are left out on purpose: they take ink away, so letting them widen the box
     * would pad the picture with the empty space somebody just rubbed out.
     */
    private fun inkBounds(): android.graphics.RectF? {
        val ink = strokes.filterNot { it.erase }
        if (ink.isEmpty()) return null

        val union = android.graphics.RectF()
        val each = android.graphics.RectF()
        var widest = 0f

        for (stroke in ink) {
            stroke.path.computeBounds(each, true)
            if (union.isEmpty) union.set(each) else union.union(each)
            widest = maxOf(widest, stroke.width)
        }

        // A stroke's path is its centre line; half its width hangs outside the bounds on each side.
        val pad = widest / 2f + 2f
        union.inset(-pad, -pad)

        // Never past the pad itself, or the bitmap carries transparent margins again.
        union.intersect(0f, 0f, width.toFloat(), height.toFloat())
        return union.takeIf { it.width() >= 1f && it.height() >= 1f }
    }
}
