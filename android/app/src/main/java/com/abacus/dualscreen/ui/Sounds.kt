package com.abacus.dualscreen.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.provider.Settings
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
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

        /**
         * Two glass beads striking each other.
         *
         * Not a note and not a wooden knock. It is the sound the intro makes now, and the only sound
         * the intro makes: one of these on each genuine contact, pitched and levelled by how hard the
         * beads actually hit. Everything else that used to play over the animation is gone.
         */
        BEAD,


    }

    private const val TAG = "AynSound"

    private const val RATE = 44_100

    /**
     * Generated buffers, kept because they are small and generating one costs a few milliseconds.
     *
     * **Concurrent, and that is the whole point.** Every cue plays on its own short-lived thread, and
     * the intro fires eight of them inside a second — six bead knocks, a tap and the figure itself.
     * Eight threads calling `getOrPut` on a plain HashMap is a data race on the table's own array,
     * and a HashMap that is resized by two threads at once can come back corrupted or not come back
     * at all. Every failure was then swallowed by the `runCatching` around the call, so the symptom
     * was not a crash but an intro that was simply silent.
     */
    private val cache = ConcurrentHashMap<Cue, ShortArray>()

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
        if (!enabled) {
            Log.d(TAG, "silent: the app's own sound switch is off")
            return
        }
        if (!allowed(context, cue)) return

        Thread {
            runCatching {
                emit(cache.getOrPut(cue) { render(cue) }, volume)
            }.onFailure {
                // Logged rather than swallowed. Silence with no explanation is why this took a
                // report to find in the first place: nothing was ever written down when it failed.
                Log.w(TAG, "could not play $cue", it)
            }
        }.apply {
            priority = Thread.MIN_PRIORITY
            isDaemon = true
        }.start()
    }

    /**
     * Render the cues the intro needs before the intro needs them.
     *
     * The figure is about forty thousand samples of arithmetic. Generating it on the frame the mark
     * lands puts that work between the animation and the sound it is supposed to be synchronised
     * with, which is audible as a late chord. Called when the boot view starts, so by the time
     * anything asks for these they are already sitting in the cache.
     */
    fun warmUp() {
        Thread {
            runCatching {
                for (cue in listOf(Cue.BEAD, Cue.TAP, Cue.CONFIRM))
                    cache.getOrPut(cue) { render(cue) }
            }.onFailure { Log.w(TAG, "warm-up failed", it) }
        }.apply {
            priority = Thread.MIN_PRIORITY
            isDaemon = true
        }.start()
    }

    /** The system's own opinions about interface noise, both of which outrank the app's. */
    /**
     * Whether a cue should be heard.
     *
     * Two rules now, where there were three, and the two that went are the two that were wrong on
     * this hardware.
     *
     * **The ringer no longer decides.** `RINGER_MODE_NORMAL` is about incoming calls, and a handheld
     * with no telephony can sit in vibrate or silent permanently without that meaning anything about
     * whether interface audio is wanted. Gating on it silenced the whole app on a device that had
     * never expressed an opinion. What actually matters is whether the stream this plays on is
     * turned up, so that is what is checked.
     *
     * **The system's touch-sounds switch no longer silences [Cue.BEAD].** It still silences the
     * small per-press cues, which is what it is for and what somebody who turned it off was asking
     * for. But the introduction is a thing this app's own switch opted into deliberately, and on a
     * device where touch sounds ship off by default, honouring it there meant the introduction was
     * silent for everybody.
     */
    private fun allowed(context: Context, cue: Cue): Boolean {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audio == null) {
            Log.w(TAG, "silent: no AudioManager")
            return false
        }

        val volume = runCatching { audio.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(1)
        if (volume <= 0) {
            Log.d(TAG, "silent: media volume is at zero")
            return false
        }

        if (cue == Cue.BEAD) return true

        val touchSounds = runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SOUND_EFFECTS_ENABLED, 1)
        }.getOrDefault(1)

        if (touchSounds == 0) {
            Log.d(TAG, "silent: system touch sounds are off")
            return false
        }

        return true
    }

    /**
     * Why nothing is playing, in words, for the developer screen.
     *
     * Three switches can silence this and two of them are Android's rather than the app's, which
     * means "I turned sounds on and hear nothing" is usually correct *and* not a bug. Being able to
     * read the reason off the device beats guessing at it from a desk.
     */
    fun diagnose(context: Context): String {
        val lines = mutableListOf<String>()

        lines += "app switch: " + if (enabled) "on" else "OFF — interface sounds are off in this app's settings"

        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audio == null) {
            lines += "audio service: unavailable"
            return lines.joinToString("\n")
        }

        val music = runCatching { audio.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrNull()
        val musicMax = runCatching { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrNull()
        val system = runCatching { audio.getStreamVolume(AudioManager.STREAM_SYSTEM) }.getOrNull()
        val systemMax = runCatching { audio.getStreamMaxVolume(AudioManager.STREAM_SYSTEM) }.getOrNull()

        lines += "media volume: $music of $musicMax" + if (music == 0) "  ← everything is silent at zero" else ""
        lines += "system volume: $system of $systemMax  (no longer used; sounds play on media)"

        val touchSounds = runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SOUND_EFFECTS_ENABLED, 1)
        }.getOrDefault(1)
        lines += "touch sounds: " + if (touchSounds == 0)
            "off — silences the small press cues, but not the intro"
        else "on"

        lines += "ringer mode: ${audio.ringerMode} (0 silent, 1 vibrate, 2 normal) — no longer used"
        lines += "rendered: ${cache.size} of ${Cue.entries.size} cues"
        lines += "output: ${outputs(audio)}"

        return lines.joinToString("\n")
    }

    /** Where audio is actually going, which is the other half of "I hear nothing". */
    private fun outputs(audio: AudioManager): String = runCatching {
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .joinToString { "${it.productName} (type ${it.type})" }
            .ifBlank { "no output devices reported" }
    }.getOrDefault("could not be read")

    // ── playback ────────────────────────────────────────────────────────────

    private fun emit(samples: ShortArray, volume: Float) {
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    /*
                     * Media, not sonification — and the reason is the device this runs on.
                     *
                     * USAGE_ASSISTANCE_SONIFICATION routes to STREAM_SYSTEM. On a phone that is
                     * reasonable; on a gaming handheld it is close to useless, because the system
                     * stream is very often sitting at zero while media volume is up. The volume keys
                     * on the Thor move media. So every sound this app made was being sent to a
                     * channel nobody had turned up, and it was inaudible with nothing wrong.
                     *
                     * CONTENT_TYPE_SONIFICATION is kept, so it is still identified as interface
                     * audio and still ducks under whatever else is playing rather than pausing it.
                     */
                    .setUsage(AudioAttributes.USAGE_MEDIA)
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

        val written = track.write(samples, 0, samples.size)
        if (written < samples.size) {
            // A static track that did not take the whole buffer will play a fragment or nothing at
            // all, and it returns a negative error code rather than throwing.
            Log.w(TAG, "AudioTrack took $written of ${samples.size} samples")
            runCatching { track.release() }
            return
        }

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

        Cue.BEAD -> bead(hz = 1_320.0, ms = 210, gain = 0.30)

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
     * The intro figure.
     *
     * Rises through the pentatonic scale and lands on the octave, with a soft pad underneath so the
     * arrival has somewhere to sit. Timed to finish as the mark settles rather than to fill the
     * whole animation: the last half second of the intro is deliberately near-silent, which is what
     * makes the home screen appearing feel like the end of something.
     */
/**
     * One small glass bead, struck.
     *
     * The difference between glass and the sine-plus-octave used everywhere else is *inharmonicity*.
     * A struck bar or a glass bead does not resonate at whole-number multiples of its fundamental —
     * the partials sit at roughly 1 : 2.76 : 5.40 : 8.93, the ratios of a free-free bar, and that
     * irrational spacing is exactly what the ear hears as "struck object" rather than as "note".
     * Feed a sine the same envelope and it sounds like a doorbell.
     *
     * The upper partials also decay faster than the fundamental, which is the other half of it: a
     * strike is bright for a few milliseconds and then rings dark. Holding them all for the same
     * length gives a chime, which is a bigger and more expensive object than the one we want.
     */
    private fun bead(hz: Double, ms: Int, gain: Double, attack: Double = 1.6): ShortArray {
        val count = (RATE * ms / 1000.0).toInt()
        val out = ShortArray(count)

        // Free-free bar ratios. The fourth is faint and only widens the strike.
        val partials = doubleArrayOf(1.0, 2.756, 5.404, 8.933)
        val levels = doubleArrayOf(1.0, 0.46, 0.22, 0.09)
        val decays = doubleArrayOf(6.5, 11.0, 17.0, 24.0)

        for (i in 0 until count) {
            val t = i.toDouble() / RATE
            val seconds = ms / 1000.0

            // Never zero: a waveform that starts at full amplitude clicks, and on a bead that click
            // would be the loudest part of it.
            val rise = 1 - exp(-t * 1000.0 / attack)

            var value = 0.0
            for (partial in partials.indices) {
                val ratio = partials[partial]
                if (hz * ratio > RATE / 2.2) continue
                value += levels[partial] *
                    exp(-decays[partial] * t / seconds) *
                    sin(2 * PI * hz * ratio * t)
            }

            out[i] = clamp((soften(value * rise * gain) * Short.MAX_VALUE).toInt())
        }

        return out
    }


    private fun clamp(value: Int): Short =
        value.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

    /** Unused today; kept because a soft-clip is the right answer if these ever stack up louder. */
    private fun soften(value: Double): Double = tanhApprox(value)

    private fun tanhApprox(x: Double): Double = x / (1 + x.pow(2) / 3)
}
