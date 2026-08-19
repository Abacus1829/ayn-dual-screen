package com.abacus.dualscreen.theme

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.provider.Settings
import android.util.AttributeSet
import android.view.View
import kotlin.math.sin

/**
 * The drifting ribbons behind the Wild theme.
 *
 * Four sine ribbons at different speeds and phases, drawn additively so where they cross they glow.
 * That is the whole trick — no particles, no bitmaps, no shader graph. It costs four paths a frame,
 * which a handheld can do without noticing, and it is the difference between a flat dark screen and
 * one that feels alive.
 *
 * Motion is a courtesy, not a requirement:
 *
 * - It stops when the view leaves the window, so nothing animates behind a screen nobody is looking
 *   at and no frame is drawn while the app is in the background.
 * - It honours the system's **animator duration scale**. Somebody who has turned animations off — in
 *   accessibility settings, or in developer options, or because they are on a battery saver that
 *   does it for them — gets the ribbons drawn once and left still. That setting exists precisely so
 *   an app does not have to invent its own, and reading it is how a reduced-motion preference is
 *   respected without adding a switch nobody would find.
 */
class WaveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private class Ribbon(
        val speed: Float,
        val amplitude: Float,
        val centre: Float,
        val thickness: Float,
        val colour: Int,
        val phase: Float,
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()

    /** Deep blues with one violet, so the crossings shift colour rather than just brightening. */
    private var ribbons = listOf(
        Ribbon(0.00013f, 0.055f, 0.62f, 0.055f, 0x3358B0FF, 0f),
        Ribbon(0.00021f, 0.040f, 0.68f, 0.038f, 0x2A7FD4FF, 1.9f),
        Ribbon(0.00009f, 0.075f, 0.74f, 0.070f, 0x22284FA8, 3.6f),
        Ribbon(0.00017f, 0.030f, 0.58f, 0.026f, 0x1E9C7BFF, 5.1f),
    )

    /** Set once the view knows how big it is; the top glow is proportional to the height. */
    private var sky: Shader? = null

    private var startedAt = 0L
    private var running = false

    /** Frozen when the system says animations are off, so the ribbons are drawn once and left. */
    private val animated: Boolean
        get() = runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) > 0f
        }.getOrDefault(true)

    override fun onSizeChanged(w: Int, h: Int, oldW: Int, oldH: Int) {
        super.onSizeChanged(w, h, oldW, oldH)
        if (h <= 0) return

        // A cold glow at the top fading into black, which is what gives the flat background depth.
        sky = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(0xFF0A1830.toInt(), 0xFF050A18.toInt(), 0xFF01030A.toInt()),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startedAt = System.currentTimeMillis()
        running = true
        if (animated) postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        running = false
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        // Nothing is drawn while the view is hidden, which is what stops this costing anything when
        // the app is in the background.
        if (visibility == VISIBLE && running && animated) postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        paint.shader = sky
        paint.color = Color.WHITE
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        val time = (System.currentTimeMillis() - startedAt).toFloat()

        for (ribbon in ribbons) {
            drawRibbon(canvas, w, h, ribbon, time)
        }

        if (running && isShown && animated) postInvalidateOnAnimation()
    }

    /**
     * One ribbon: a sine band closed against itself.
     *
     * Sampled every [STEP] pixels rather than every pixel. At this amplitude the difference is
     * invisible and the path is a twentieth of the size, which is most of why this is cheap.
     */
    private fun drawRibbon(canvas: Canvas, w: Float, h: Float, ribbon: Ribbon, time: Float) {
        val drift = time * ribbon.speed + ribbon.phase
        val amplitude = h * ribbon.amplitude
        val centre = h * ribbon.centre
        val thickness = h * ribbon.thickness

        path.reset()
        path.moveTo(0f, centre)

        var x = 0f
        while (x <= w) {
            val t = x / w * 6.2831853f
            path.lineTo(x, centre + sin(t * 1.35f + drift) * amplitude)
            x += STEP
        }

        // Back along the underside to close the band.
        x = w
        while (x >= 0f) {
            val t = x / w * 6.2831853f
            path.lineTo(x, centre + sin(t * 1.35f + drift) * amplitude + thickness)
            x -= STEP
        }

        path.close()

        paint.color = ribbon.colour
        canvas.drawPath(path, paint)
    }

    private companion object {
        /** Sample spacing in pixels. Small enough to look smooth, large enough to stay cheap. */
        const val STEP = 18f
    }
}
