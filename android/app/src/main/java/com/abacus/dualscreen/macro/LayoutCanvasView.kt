package com.abacus.dualscreen.macro

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.abacus.dualscreen.Macro
import kotlin.math.abs

/**
 * The layout, arranged by finger.
 *
 * Draws the buttons at the same fractional coordinates the overlay uses, on a canvas with the
 * screen's own aspect ratio — so what is arranged here is what appears on the pad, without the editor
 * and the pad each having their own idea of where things are.
 *
 * Drag the middle of a button to move it; drag the bottom-right corner to resize. Two gestures, both
 * of which work with a thumb, and no mode to be in.
 */
class LayoutCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    /** The buttons being arranged. Held, not copied: edits land straight on the caller's list. */
    var buttons: MutableList<Macro> = mutableListOf()
        set(value) {
            field = value
            selected = null
            invalidate()
        }

    var selected: Macro? = null
        private set

    /** Told when something moved or resized, so the caller can save. */
    var onChanged: ((Macro) -> Unit)? = null

    /** Told when the selection changes, so the caller can enable its buttons. */
    var onSelected: ((Macro?) -> Unit)? = null

    var accent: Int = Color.parseColor("#6EC1FF")
    var faceColor: Int = Color.parseColor("#181C2B")
    var edgeColor: Int = Color.parseColor("#3A4260")

    init {
        /*
         * Clickable, even though nothing here is a click.
         *
         * A View that reports itself unclickable is treated as scenery by parts of the input stack —
         * notably when a host turns a tap into a mouse event, which is what an emulator does — and
         * the whole gesture goes to whatever is behind it. Saying so up front is the difference
         * between an editor that responds and one that ignores every drag.
         */
        isClickable = true
        isFocusable = true
    }

    private val face = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private var dragging: Macro? = null
    private var resizing = false
    private var grabX = 0f
    private var grabY = 0f
    private var startX = 0f
    private var startY = 0f
    private var startSize = 0

    /**
     * A button's rectangle on this canvas.
     *
     * The overlay sizes buttons in dp and positions them as a fraction of the screen. The canvas is
     * narrower than the screen, so the size is scaled by the same ratio the width was — otherwise a
     * button that fits here would overflow there.
     */
    private fun rectOf(macro: Macro): RectF {
        val scale = width.toFloat() / resources.displayMetrics.widthPixels
        val size = macro.size * resources.displayMetrics.density * scale
        val left = macro.x * width
        val top = macro.y * height
        return RectF(left, top, left + size, top + size)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // A few guides, so "roughly centred" is achievable without a ruler.
        grid.color = (edgeColor and 0x00FFFFFF) or 0x40000000
        grid.strokeWidth = 1f
        canvas.drawLine(width / 2f, 0f, width / 2f, height.toFloat(), grid)
        canvas.drawLine(0f, height / 2f, width.toFloat(), height / 2f, grid)
        grid.color = edgeColor
        canvas.drawRect(0.5f, 0.5f, width - 0.5f, height - 0.5f, grid)

        for (macro in buttons) {
            val rect = rectOf(macro)
            val isSelected = macro.id == selected?.id

            face.color = faceColor
            canvas.drawRoundRect(rect, rect.width() * 0.22f, rect.width() * 0.22f, face)

            edge.color = if (isSelected) accent else edgeColor
            edge.strokeWidth = if (isSelected) 3f else 1.5f
            canvas.drawRoundRect(rect, rect.width() * 0.22f, rect.width() * 0.22f, edge)

            text.color = if (isSelected) accent else Color.parseColor("#EDF0F8")
            text.textSize = (rect.width() * 0.34f).coerceAtMost(rect.height() * 0.5f)
            val label = macro.label.take(4)
            canvas.drawText(
                label,
                rect.centerX(),
                rect.centerY() + text.textSize / 3f,
                text,
            )

            // The resize grip, only on the selected button so the rest stay uncluttered.
            if (isSelected) {
                face.color = accent
                canvas.drawCircle(rect.right, rect.bottom, GRIP_DP * resources.displayMetrics.density, face)
            }
        }
    }

    @Suppress("ClickableViewAccessibility")   // an arranging surface has no single click to announce
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)

                val grip = GRIP_DP * 1.6f * resources.displayMetrics.density
                val current = selected

                // The grip is checked before the buttons, and only on the selected one, so grabbing
                // a corner never picks up the button underneath it instead.
                if (current != null && near(rectOf(current).right, rectOf(current).bottom, event, grip)) {
                    dragging = current
                    resizing = true
                    startSize = current.size
                    grabX = event.x
                    grabY = event.y
                    return true
                }

                val hit = buttons.lastOrNull { rectOf(it).contains(event.x, event.y) }
                selected = hit
                onSelected?.invoke(hit)

                if (hit != null) {
                    dragging = hit
                    resizing = false
                    grabX = event.x
                    grabY = event.y
                    startX = hit.x
                    startY = hit.y
                }

                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val macro = dragging ?: return true

                if (resizing) {
                    // Diagonal drag: whichever axis moved more wins, so a slightly-off diagonal
                    // still resizes smoothly instead of jittering between the two.
                    val dx = event.x - grabX
                    val dy = event.y - grabY
                    val delta = if (abs(dx) > abs(dy)) dx else dy
                    val scale = resources.displayMetrics.widthPixels.toFloat() / width
                    val dp = delta * scale / resources.displayMetrics.density

                    macro.size = (startSize + dp).toInt().coerceIn(MIN_SIZE, MAX_SIZE)
                } else {
                    val rect = rectOf(macro)
                    macro.x = (startX + (event.x - grabX) / width)
                        .coerceIn(0f, 1f - rect.width() / width)
                    macro.y = (startY + (event.y - grabY) / height)
                        .coerceIn(0f, 1f - rect.height() / height)
                }

                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging?.let { onChanged?.invoke(it) }
                dragging = null
                resizing = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }

        return super.onTouchEvent(event)
    }

    private fun near(x: Float, y: Float, event: MotionEvent, radius: Float): Boolean =
        abs(event.x - x) <= radius && abs(event.y - y) <= radius

    fun select(macro: Macro?) {
        selected = macro
        onSelected?.invoke(macro)
        invalidate()
    }

    private companion object {
        const val GRIP_DP = 7f

        /** Matches the range the macro pad's own size slider allows. */
        const val MIN_SIZE = 44
        const val MAX_SIZE = 104
    }
}
