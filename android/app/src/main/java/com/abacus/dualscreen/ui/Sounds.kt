package com.abacus.dualscreen.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.provider.Settings
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * The app's voice, synthesised rather than shipped.
 *
 * Every sound here is generated from arithmetic at runtime — sine partials, an envelope, a little
 * filtered noise for the wooden ones. Nothing is sampled, nothing is downloaded, and no audio file
 * exists in the APK to be mistaken for somebody else's. That is partly a licensing answer and mostly
 * a practical one: a handful of oscillators weighs nothing, needs no decoder, and can be tuned by
 * changing a number rather than by re-recording.
 *
 * ## What it sounds like, and why
 *
 * Console interfaces that feel good are almost always *consonant and short*. Notes are drawn from
 * one pentatonic scale, so nothing can clash with anything else no matter what order it plays in;
 * everything is under a fifth of a second except the intro; attacks are soft, because a click at
 * full amplitude on the first sample is what makes cheap UI sound cheap.
 *
 * Movement has direction. Going deeper into the app rises in pitch, coming back out falls, a toggle
 * moves the way the switch moves. None of it is decoration: after a day of use you know whether
 * something opened or closed without looking.
 *
 * ## When it stays quiet
 *
 * Three ways to be silent, checked in this order, because being noisy on a handheld somebody is
 * using next to somebody else is worse than being silent on one they wanted noise from:
 *
 * - the app's own switch, off by default until somebody turns it on;
 * - the system's **touch sounds** setting — the same one that silences the keyboard, which people
 *   who dislike interface noise have usually already turned off;
 * - the ringer being on silent or vibrate.
 */
object Sounds {

    /** The palette. Frequencies in Hz, durations in milliseconds. */
    enum class Cue {
        /** A press. The quietest thing here, and the one that plays most often. */
        TAP,

        /** Moving to a new screen or a new selection: a small step up. */
        SELECT,

        /** Coming back out: the same step, downward. */
        BACK,

        TOGGLE_ON,
        TOGGLE_OFF,

        /** Something completed — saved, applied, connected. A short major arpeggio. */
        CONFIRM,

        /** Something refused. Low and flat rather than harsh; a buzzer is a punishment. */
        ERROR,

        /** A bead arriving at its stop in the intro: a wooden knock, not a tone. */
        BEAD,

        /** The intro's rising figure, played once as the mark lands. */
        INTRO,
    }

    private const val RATE = 44_100

    /** Generated buffers, kept because they are small and generating one costs a few milliseconds. */
    private val cache = HashMap<Cue, ShortArray>()

    @Volatile
    private var enabled = false

    /**
     * Tell the engine whether the app's own switch is on.
     *
     * Set from settings rather than read here, so this object needs no preferences of its own and a
     * screen can silence it while previewing something.
     */
    fun setEnabled(on: Boolean) {
        enabled = on
    }

    /**
     * Play a cue, if everything agrees it should be heard.
     *
     * Never blocks the caller: generation and playback both happen on a short-lived thread. A
     * dropped sound is always better than a dropped frame.
     */
    fun play(context: Context, cue: Cue, volume: Float = 1f) {
        if (!enabled || !allowed(context)) return

        Thread {
            runCatching {
                val samples = cache.getOrPut(cue) { render(cue) }
                emit(samples, volume)
            }
        }.apply {
            priority = Thread.MIN_PRIORITY
            isDaemon = true
        }.start()
    }

    /** The system's own opinions about interface noise, both of which outrank the app's. */
    private fun allowed(context: Context): Boolean {
        val touchSounds = runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SOUND_EFFECTS_ENABLED, 1)
        }.getOrDefault(1)
        if (touchSounds == 0) return false

        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return audio.ringerMode == AudioManager.RINGER_MODE_NORMAL
    }

    // ── playback ────────────────────────────────────────────────────────────

    private fun emit(samples: ShortArray, volume: Float) {
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // Sonification, not media: it ducks under music rather than pausing it, and it
                    // follows the system volume somebody actually reaches for.
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(samples.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        track.write(samples, 0, samples.size)
        track.setVolume(volume.coerceIn(0f, 1f))
        track.play()

        // Released once the sound has had time to finish. Holding a track open per cue would run
        // the device out of them within a minute of ordinary use.
        val ms = samples.size * 1000L / RATE + 60
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            runCatching { track.stop() }
            runCatching { track.release() }
        }, ms)
    }

    // ── synthesis ───────────────────────────────────────────────────────────

    /*
     * One pentatonic scale for the whole app, so no two sounds can clash whatever order they play
     * in. C major pentatonic: nothing in it forms a semitone with anything else in it.
     */
    private const val C5 = 523.25
    private const val D5 = 587.33
    private const val E5 = 659.25
    private const val G5 = 783.99
    private const val A5 = 880.00
    private const val C6 = 1046.50
    private const val G4 = 392.00
    private const val E4 = 329.63
    private const val C4 = 261.63

    /**
     * Internal rather than private so the tests can render every cue on the desktop JVM.
     *
     * This is pure arithmetic that produces something nobody can inspect by reading it: a wrong
     * envelope is silence, a wrong gain is a click, and either one is only discovered by someone
     * wearing headphones. Rendering it in a test catches the failures that have a shape -- silence,
     * clipping, a NaN -- without needing ears.
     */
    internal fun render(cue: Cue): ShortArray = when (cue) {
        /*
         * Barely there: this plays on every press, and a press sound you *notice* is one you will
         * switch off within a day.
         *
         * The attack was 2ms over a 38ms note, which the tests caught as a click — at one
         * millisecond it was already at nine tenths of its peak, which is a step rather than an
         * onset and is audible as a tick on the small speakers a handheld has. Ten milliseconds of
         * rise against a gentler decay is still under a tenth of a second in total and now actually
         * begins rather than starting.
         */
        Cue.TAP -> note(A5, 70, gain = 0.16, attack = 10.0, curve = 9.0)

        Cue.SELECT -> sequence(
            Step(E5, 34, 0.22),
            Step(A5, 60, 0.22),
        )

        Cue.BACK -> sequence(
            Step(A5, 34, 0.20),
            Step(E5, 60, 0.20),
        )

        Cue.TOGGLE_ON -> sequence(
            Step(G4, 26, 0.20),
            Step(D5, 55, 0.24),
        )

        Cue.TOGGLE_OFF -> sequence(
            Step(D5, 26, 0.20),
            Step(G4, 55, 0.22),
        )

        // A major arpeggio, close to the top of its decay: the sound of something landing right.
        Cue.CONFIRM -> sequence(
            Step(C5, 45, 0.24),
            Step(E5, 45, 0.24),
            Step(G5, 130, 0.28),
        )

        // Low, soft and slightly detuned. Unpleasant enough to notice, not so much that being wrong
        // feels like being told off.
        Cue.ERROR -> mix(
            note(C4, 190, gain = 0.22, attack = 6.0, curve = 9.0),
            note(C4 * 0.97, 190, gain = 0.16, attack = 6.0, curve = 9.0),
        )

        Cue.BEAD -> knock()

        Cue.INTRO -> intro()
    }

    private data class Step(val hz: Double, val ms: Int, val gain: Double)

    /**
     * One note: two partials and an exponential decay.
     *
     * The octave above at a fifth of the level is the whole trick — a bare sine reads as a test
     * tone, and one harmonic is enough to make it read as an instrument. [attack] is in
     * milliseconds and is never zero: a waveform that begins at full amplitude clicks, and that
     * click is most of what makes interface audio sound cheap.
     */
    private fun note(
        hz: Double,
        ms: Int,
        gain: Double,
        attack: Double = 4.0,
        curve: Double = 7.0,
    ): ShortArray {
        val count = (RATE * ms / 1000.0).toInt()
        val out = ShortArray(count)
        val attackSamples = (RATE * attack / 1000.0).coerceAtLeast(1.0)

        for (i in 0 until count) {
            val t = i.toDouble() / RATE
            val fraction = i.toDouble() / count

            val rise = (i / attackSamples).coerceAtMost(1.0)
            val fall = exp(-curve * fraction)
            val envelope = rise * fall

            val wave = sin(2 * PI * hz * t) + 0.2 * sin(4 * PI * hz * t)
            out[i] = (wave * envelope * gain * Short.MAX_VALUE / 1.2).toInt().toShort()
        }
        return out
    }

    /** Notes one after another, each overlapping the last slightly so it phrases rather than stutters. */
    private fun sequence(vararg steps: Step): ShortArray {
        val overlapMs = 12
        val total = steps.sumOf { it.ms } - overlapMs * (steps.size - 1)
        val out = ShortArray((RATE * total / 1000.0).toInt().coerceAtLeast(1))

        var at = 0
        for (step in steps) {
            val rendered = note(step.hz, step.ms + 40, step.gain)
            for (i in rendered.indices) {
                val target = at + i
                if (target >= out.size) break
                out[target] = clamp(out[target] + rendered[i])
            }
            at += (RATE * (step.ms - overlapMs) / 1000.0).toInt()
        }
        return out
    }

    private fun mix(vararg parts: ShortArray): ShortArray {
        val out = ShortArray(parts.maxOf { it.size })
        for (part in parts) {
            for (i in part.indices) out[i] = clamp(out[i] + part[i])
        }
        return out
    }

    /**
     * A bead hitting its stop: a wooden knock rather than a note.
     *
     * Noise through a very fast decay, with a low sine under it for body. Wood is mostly transient —
     * pitch it and it becomes a marimba, which is a different and much busier instrument.
     */
    private fun knock(): ShortArray {
        val ms = 55
        val count = (RATE * ms / 1000.0).toInt()
        val out = ShortArray(count)
        var noise = 0.0
        val random = java.util.Random(7)   // fixed seed: the same knock every time, not a new one

        for (i in 0 until count) {
            val fraction = i.toDouble() / count
            val envelope = exp(-30.0 * fraction)

            // One-pole low pass, which is what turns hiss into something with a body to it.
            noise = noise * 0.6 + (random.nextDouble() * 2 - 1) * 0.4
            val body = sin(2 * PI * 180.0 * i / RATE)

            out[i] = ((noise * 0.7 + body * 0.5) * envelope * 0.30 * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    /**
     * The intro figure.
     *
     * Rises through the pentatonic scale and lands on the octave, with a soft pad underneath so the
     * arrival has somewhere to sit. Timed to finish as the mark settles rather than to fill the
     * whole animation: the last half second of the intro is deliberately near-silent, which is what
     * makes the home screen appearing feel like the end of something.
     */
    private fun intro(): ShortArray {
        val figure = sequence(
            Step(C5, 130, 0.20),
            Step(E5, 130, 0.21),
            Step(G5, 130, 0.22),
            Step(C6, 420, 0.26),
        )

        // A quiet fifth underneath, entering late and fading with the figure.
        val pad = mix(
            note(C4, 900, gain = 0.10, attack = 220.0, curve = 3.0),
            note(G4, 900, gain = 0.07, attack = 260.0, curve = 3.0),
        )

        val out = ShortArray(maxOf(figure.size, pad.size))
        for (i in figure.indices) out[i] = clamp(out[i] + figure[i])
        for (i in pad.indices) out[i] = clamp(out[i] + pad[i])
        return out
    }

    private fun clamp(value: Int): Short =
        value.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

    /** Unused today; kept because a soft-clip is the right answer if these ever stack up louder. */
    private fun soften(value: Double): Double = tanhApprox(value)

    private fun tanhApprox(x: Double): Double = x / (1 + x.pow(2) / 3)
}
