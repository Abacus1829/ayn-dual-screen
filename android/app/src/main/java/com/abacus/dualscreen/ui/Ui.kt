package com.abacus.dualscreen.ui

import android.app.Activity
import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import com.abacus.dualscreen.Appearance
import com.abacus.dualscreen.R
import com.abacus.dualscreen.Settings

/**
 * The pieces every screen is made of.
 *
 * Fourteen screens each built their own card out of a LinearLayout, a padding value and a call to
 * [Appearance.panel]. They were all *nearly* the same, which is worse than being different: the
 * padding varied by a couple of dp, some had a bottom margin and some a top one, the section
 * headings were 11sp in one place and 12sp in another. Nothing there was a decision — it was
 * whatever the previous screen happened to do, copied and drifted.
 *
 * So these are the components, and a screen composes them rather than reinventing them. Each takes
 * an [Activity] and the [Settings] so it can honour the accent, corner radius and text scale the
 * user picked, and each returns a plain View that goes into whatever container the screen has.
 *
 * Everything here is deliberately small and deliberately boring. The value is not in any one of them
 * being clever; it is in there being exactly one of each.
 */
object Ui {

    /** A section heading: what the next few rows are about. */
    fun section(activity: Activity, @StringRes title: Int): TextView =
        section(activity, activity.getString(title))

    fun section(activity: Activity, title: String): TextView = TextView(activity).apply {
        text = title.uppercase()
        setTextColor(activity.getColor(R.color.text_faint))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        letterSpacing = 0.10f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(dp(activity, 2), dp(activity, 18), 0, dp(activity, 8))
    }

    /**
     * A line of plain text between rows: a state, a caveat, a consequence.
     *
     * Deliberately not a card and deliberately not tappable. Some rows need a sentence of context
     * that is neither a setting nor a link — what the Home button currently opens, why a control is
     * missing on this device — and the alternatives are both bad: a disabled-looking card invites a
     * tap that does nothing, and a toast is gone before it has been read.
     *
     * Indented to the same edge as a section header so it reads as belonging to the group above it.
     */
    fun note(activity: Activity, settings: Settings, text: String): View = TextView(activity).apply {
        this.text = text
        setTextColor(activity.getColor(R.color.text_dim))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setLineSpacing(dp(activity, 2).toFloat(), 1f)
        setPadding(dp(activity, 4), dp(activity, 2), dp(activity, 4), dp(activity, 10))
        // Repainted with everything else rather than carrying its own colour.
        tag = "plain"
    }

    /**
     * A card: the container everything else sits in.
     *
     * [accented] outlines it in the user's accent instead of the neutral edge, for the one card on a
     * screen that is the answer rather than an option.
     */
    fun card(
        activity: Activity,
        settings: Settings,
        accented: Boolean = false,
    ): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(activity, 14), dp(activity, 12), dp(activity, 14), dp(activity, 12))
        background = Appearance.panel(
            activity,
            settings,
            activity.getColor(R.color.card),
            if (accented) Appearance.accentOf(settings) else activity.getColor(R.color.edge),
        )
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(activity, 8) }
    }

    /**
     * A row that goes somewhere.
     *
     * The chevron is not decoration: it is the difference between a row that opens a screen and a
     * row that is a setting, and having it mean exactly that everywhere is most of what makes a list
     * readable at a glance.
     */
    fun link(
        activity: Activity,
        settings: Settings,
        @StringRes title: Int,
        @StringRes detail: Int = 0,
        glyph: String = "",
        onClick: () -> Unit,
    ): View {
        val row = card(activity, settings).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 14), dp(activity, 14), dp(activity, 14), dp(activity, 14))
            minimumHeight = dp(activity, 56)
            setOnClickListener {
                Feedback.select(it)
                onClick()
            }
        }
        Motion.pressable(row, scale = 0.985f)

        if (glyph.isNotBlank()) {
            row.addView(TextView(activity).apply {
                text = glyph
                setTextColor(Appearance.accentOf(settings))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(activity, 34), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
        }

        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        column.addView(TextView(activity).apply {
            setText(title)
            setTextColor(activity.getColor(R.color.text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        })

        if (detail != 0) {
            column.addView(TextView(activity).apply {
                setText(detail)
                setTextColor(activity.getColor(R.color.text_dim))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, dp(activity, 2), 0, 0)
            })
        }

        row.addView(column)

        row.addView(TextView(activity).apply {
            text = "›"
            setTextColor(Appearance.accentOf(settings))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        })

        return row
    }

    /**
     * A switch, with the explanation under it rather than hidden behind a question mark.
     *
     * The detail text is part of the control, not an optional extra: a setting whose consequence is
     * not stated is one people leave alone because they cannot afford to find out.
     */
    fun toggle(
        activity: Activity,
        settings: Settings,
        @StringRes title: Int,
        @StringRes detail: Int = 0,
        initial: Boolean,
        onChange: (Boolean) -> Unit,
    ): View {
        val row = card(activity, settings)

        val check = CheckBox(activity).apply {
            setText(title)
            setTextColor(activity.getColor(R.color.text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            minHeight = dp(activity, 44)
            isChecked = initial
            setOnCheckedChangeListener { view, on ->
                Feedback.toggle(view, on)
                onChange(on)
            }
        }

        row.addView(check)

        if (detail != 0) {
            row.addView(TextView(activity).apply {
                setText(detail)
                setTextColor(activity.getColor(R.color.text_dim))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(dp(activity, 34), 0, 0, 0)
            })
        }

        // The whole card toggles, not just the 20dp box. On a handheld held in two hands, the box
        // alone is a target you have to aim at.
        row.setOnClickListener { check.toggle() }
        Motion.pressable(row, scale = 0.985f)

        return row
    }

    /**
     * Nothing here — and why, and what to do about it.
     *
     * Empty lists used to be a single grey sentence, or in a few places nothing at all. An empty
     * state is a screen somebody is looking at while confused, which makes it the *most* important
     * text on it rather than the least.
     */
    fun empty(
        activity: Activity,
        settings: Settings,
        glyph: String,
        @StringRes title: Int,
        @StringRes detail: Int,
        @StringRes action: Int = 0,
        onAction: (() -> Unit)? = null,
    ): View {
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(activity, 24), dp(activity, 36), dp(activity, 24), dp(activity, 36))
        }

        column.addView(TextView(activity).apply {
            text = glyph
            setTextColor(Appearance.blend(activity.getColor(R.color.text_faint), Appearance.accentOf(settings), 0.5f))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
            gravity = Gravity.CENTER
        })

        column.addView(TextView(activity).apply {
            setText(title)
            setTextColor(activity.getColor(R.color.text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            setPadding(0, dp(activity, 12), 0, 0)
        })

        column.addView(TextView(activity).apply {
            setText(detail)
            setTextColor(activity.getColor(R.color.text_dim))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setLineSpacing(dp(activity, 3).toFloat(), 1f)
            setPadding(0, dp(activity, 6), 0, 0)
        })

        if (action != 0 && onAction != null) {
            column.addView(Button(activity).apply {
                setText(action)
                isAllCaps = false
                setTextColor(activity.getColor(R.color.text))
                background = Appearance.actionButton(activity, settings, Appearance.accentOf(settings))
                setPadding(dp(activity, 22), dp(activity, 12), dp(activity, 22), dp(activity, 12))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(activity, 16) }
                setOnClickListener {
                    Feedback.select(it)
                    onAction()
                }
            })
        }

        return column
    }

    /**
     * Working on it.
     *
     * A line of text and three breathing dots rather than a spinner, because the spinner Android
     * gives you is the one every app uses and says nothing about which app you are in.
     */
    fun loading(activity: Activity, settings: Settings, @StringRes message: Int): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(activity, 16), dp(activity, 30), dp(activity, 16), dp(activity, 30))
        }

        row.addView(TextView(activity).apply {
            setText(message)
            setTextColor(activity.getColor(R.color.text_dim))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        })

        row.addView(BreathingDots(activity).apply {
            colour = Appearance.accentOf(settings)
            layoutParams = LinearLayout.LayoutParams(dp(activity, 34), dp(activity, 14)).apply {
                marginStart = dp(activity, 10)
            }
        })

        return row
    }

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}

/**
 * Three dots, breathing in sequence.
 *
 * Its own view rather than three animated TextViews: one invalidation a frame, no layout passes, and
 * it stops itself when it leaves the window so a forgotten loading row cannot animate forever behind
 * a screen nobody is looking at.
 */
class BreathingDots @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
) : View(context, attrs) {

    var colour: Int = 0xFF6EC1FF.toInt()

    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private var running = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = Motion.animated(context)
        if (running) postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        running = false
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        val radius = height * 0.22f
        val spacing = radius * 3.2f
        val centre = width / 2f
        val phase = (System.currentTimeMillis() % 1_200L) / 1_200f

        for (i in -1..1) {
            val local = ((phase + i * 0.16f) % 1f)
            val lift = (Math.sin(local * Math.PI * 2).toFloat() + 1f) / 2f
            paint.color = android.graphics.Color.argb(
                (70 + 150 * (if (running) lift else 0.6f)).toInt().coerceIn(0, 255),
                android.graphics.Color.red(colour),
                android.graphics.Color.green(colour),
                android.graphics.Color.blue(colour),
            )
            canvas.drawCircle(
                centre + i * spacing,
                height / 2f,
                radius * (0.8f + 0.35f * (if (running) lift else 0.5f)),
                paint,
            )
        }

        if (running) postInvalidateOnAnimation()
    }
}
