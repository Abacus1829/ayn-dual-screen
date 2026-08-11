package com.abacus.dualscreen

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

/**
 * The keys themselves, drawn rather than inflated.
 *
 * A keyboard is dozens of near-identical views that all need to repaint the instant the accent changes,
 * so one canvas is both faster and far less code than a tree of Buttons. It also makes the split layout
 * a matter of arithmetic instead of nested weights.
 */
@SuppressLint("ViewConstructor")
class KeyboardView(
    context: Context,
    private val settings: Settings,
    private val onKey: (KeyboardLayouts.Key) -> Unit
) : View(context) {

    /** A key with the rectangle it was laid out into. */
    private class Placed(val key: KeyboardLayouts.Key, val rect: RectF)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    private val placed = mutableListOf<Placed>()

    private var rows: List<KeyboardLayouts.Row> = KeyboardLayouts.LETTERS
    private var layer = Layer.LETTERS
    private var shifted = false
    private var pressed: Placed? = null

    /** Repeat while held, so deleting a long line doesn't mean tapping thirty times. */
    private var repeating: Placed? = null
    private val repeat = object : Runnable {
        override fun run() {
            repeating?.let {
                onKey(it.key)
                postDelayed(this, REPEAT_MS)
            }
        }
    }

    private enum class Layer { LETTERS, SYMBOLS, MORE }

    init {
        isHapticFeedbackEnabled = true
        refresh()
    }

    /** Re-read the settings and redraw. Called when the keyboard is shown, in case they changed. */
    fun refresh() {
        text.typeface = Typeface.create(
            settings.fontFamily.ifBlank { "sans-serif" }, Typeface.NORMAL
        )
        requestLayout()
        invalidate()
    }

    /*********
     * Layout
     *********/
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val width = MeasureSpec.getSize(widthSpec)
        val height = (rows.size * keyHeight() + padding() * 2).toInt()
        setMeasuredDimension(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        place(w.toFloat(), h.toFloat())
    }

    /**
     * Work out where every key sits.
     *
     * In split mode the row is divided at its own [KeyboardLayouts.Row.splitAfter] and a gap is left in
     * the middle — the whole point on a handheld, where the reachable area is under two thumbs at the
     * edges and the centre of the screen is the hardest place to hit.
     */
    private fun place(width: Float, height: Float) {
        placed.clear()
        if (width <= 0f) return

        val pad = padding()
        val gutter = dp(2f)
        val keyH = keyHeight()
        val split = settings.keyboardSplit
        val gap = if (split) width * 0.16f else 0f

        var y = pad
        for (row in rows) {
            val leftKeys = row.keys.take(row.splitAfter.coerceAtMost(row.keys.size))
            val rightKeys = row.keys.drop(leftKeys.size)

            if (split && rightKeys.isNotEmpty()) {
                val half = (width - pad * 2 - gap) / 2f
                lay(leftKeys, pad, half, y, keyH, gutter)
                lay(rightKeys, pad + half + gap, half, y, keyH, gutter)
            } else {
                lay(row.keys, pad, width - pad * 2, y, keyH, gutter)
            }
            y += keyH
        }
    }

    private fun lay(
        keys: List<KeyboardLayouts.Key>,
        startX: Float,
        available: Float,
        y: Float,
        keyH: Float,
        gutter: Float
    ) {
        if (keys.isEmpty()) return
        val total = keys.sumOf { it.weight.toDouble() }.toFloat()
        var x = startX
        for (key in keys) {
            val w = available * (key.weight / total)
            placed += Placed(
                key,
                RectF(x + gutter, y + gutter, x + w - gutter, y + keyH - gutter)
            )
            x += w
        }
    }

    /*********
     * Drawing
     *********/
    override fun onDraw(canvas: Canvas) {
        val accent = Appearance.accentOf(settings)
        val radius = settings.corners * resources.displayMetrics.density * 0.6f

        canvas.drawColor(context.getColor(R.color.bg))

        for (item in placed) {
            val special = item.key.code != KeyboardLayouts.Code.NONE
            val isPressed = item === pressed

            fill.color = when {
                isPressed -> accent
                special -> context.getColor(R.color.card)
                else -> context.getColor(R.color.card_hi)
            }
            canvas.drawRoundRect(item.rect, radius, radius, fill)

            stroke.color = when {
                isPressed -> accent
                special -> Appearance.blend(context.getColor(R.color.edge), accent, 0.35f)
                else -> context.getColor(R.color.edge)
            }
            stroke.strokeWidth = dp(1f)
            canvas.drawRoundRect(item.rect, radius, radius, stroke)

            val label = labelFor(item.key)
            if (label.isEmpty()) continue

            text.color = when {
                isPressed -> context.getColor(R.color.bg)
                special -> accent
                else -> context.getColor(R.color.text)
            }
            text.textSize = (if (label.length > 2) dp(13f) else dp(18f)) * settings.fontScale
            // baseline that centres the glyph box rather than its ascent, so keys look level
            val metrics = text.fontMetrics
            val baseline = item.rect.centerY() - (metrics.ascent + metrics.descent) / 2f
            canvas.drawText(label, item.rect.centerX(), baseline, text)
        }
    }

    private fun labelFor(key: KeyboardLayouts.Key): String = when {
        key.code == KeyboardLayouts.Code.SPACE -> "space"
        key.code == KeyboardLayouts.Code.SHIFT && layer == Layer.LETTERS && shifted -> "⇪"
        else -> key.label
    }

    /*********
     * Touch
     *********/
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = at(event.x, event.y)
                pressed?.let { press(it) }
                invalidate()
            }

            MotionEvent.ACTION_MOVE -> {
                // sliding off a key cancels it, the way every soft keyboard behaves
                val now = at(event.x, event.y)
                if (now !== pressed) {
                    pressed = null
                    stopRepeat()
                    invalidate()
                }
            }

            MotionEvent.ACTION_UP -> {
                pressed?.let { if (repeating == null) fire(it.key) }
                pressed = null
                stopRepeat()
                invalidate()
            }

            MotionEvent.ACTION_CANCEL -> {
                pressed = null
                stopRepeat()
                invalidate()
            }
        }
        return true
    }

    private fun press(item: Placed) {
        if (settings.keyboardHaptics)
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

        if (item.key.code == KeyboardLayouts.Code.DELETE) {
            repeating = item
            postDelayed(repeat, FIRST_REPEAT_MS)
        }
    }

    private fun stopRepeat() {
        repeating = null
        removeCallbacks(repeat)
    }

    /** Layer switching stays here; anything that produces input goes out to the service. */
    private fun fire(key: KeyboardLayouts.Key) {
        when (key.code) {
            KeyboardLayouts.Code.SHIFT -> {
                when (layer) {
                    Layer.LETTERS -> shifted = !shifted
                    Layer.SYMBOLS -> layer = Layer.MORE
                    Layer.MORE -> layer = Layer.SYMBOLS
                }
                applyLayer()
            }

            KeyboardLayouts.Code.SYMBOLS -> {
                layer = Layer.SYMBOLS
                applyLayer()
            }

            KeyboardLayouts.Code.LETTERS -> {
                layer = Layer.LETTERS
                applyLayer()
            }

            else -> {
                onKey(key)
                // one shifted letter, then back down — holding shift is what the ⇪ state is for
                if (shifted && key.output != null && key.output.length == 1 && key.output[0].isLetter()) {
                    shifted = false
                    applyLayer()
                }
            }
        }
    }

    private fun applyLayer() {
        rows = when (layer) {
            Layer.LETTERS -> if (shifted) KeyboardLayouts.uppercase(KeyboardLayouts.LETTERS)
            else KeyboardLayouts.LETTERS
            Layer.SYMBOLS -> KeyboardLayouts.SYMBOLS
            Layer.MORE -> KeyboardLayouts.MORE
        }
        place(width.toFloat(), height.toFloat())
        invalidate()
    }

    /** Reset to lower-case letters, so a new text field doesn't inherit the last one's layer. */
    fun reset() {
        layer = Layer.LETTERS
        shifted = false
        applyLayer()
        requestLayout()
    }

    private fun at(x: Float, y: Float): Placed? =
        placed.firstOrNull { it.rect.contains(x, y) }

    /*********
     * Metrics
     *********/
    /** 38dp..64dp of key height, from the size setting. */
    private fun keyHeight(): Float = dp(38f + settings.keyboardSize.coerceIn(0, 100) * 0.26f)

    private fun padding(): Float = dp(4f)

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private companion object {
        const val FIRST_REPEAT_MS = 400L
        const val REPEAT_MS = 55L
    }
}
