package com.abacus.dualscreen.stream

/**
 * What a streaming host says about itself, from `/serverinfo`.
 *
 * Both NVIDIA's GameStream and Sunshine answer this, and it is the first thing worth asking: it says
 * whether the host knows us, whether somebody is already streaming, and what the hardware will
 * actually encode. Everything after this — pairing, the app list, the stream itself — depends on
 * facts that come from here.
 *
 * Fields the host does not send are left at their defaults rather than guessed at. A Sunshine build
 * and a GeForce Experience build answer with different subsets, and treating a missing field as
 * "unsupported" would quietly disable working features on one of them.
 */
data class HostInfo(
    /** The name the host calls itself, which is what the UI should show — not the IP. */
    val hostname: String = "",

    /** The host's own identifier, stable across reboots. */
    val uniqueId: String = "",

    /** GameStream protocol version, e.g. "7.1.431.-1". Its major number changes the pairing hash. */
    val appVersion: String = "",

    /** GeForce Experience version, absent on Sunshine. */
    val gfeVersion: String = "",

    /** Does this host already have our certificate? */
    val paired: Boolean = false,

    /**
     * The app currently running, or 0 for none.
     *
     * Non-zero with [paired] true is the "resume or quit?" case: a session is already up, possibly
     * started from another device, and launching something new would kill it.
     */
    val currentGame: Int = 0,

    /** Free text, e.g. "SUNSHINE_SERVER_FREE" or "MJOLNIR_SERVER_AVAILABLE". */
    val state: String = "",

    /** Set when the host is Sunshine rather than GeForce Experience; a few behaviours differ. */
    val isSunshine: Boolean = false,

    /** Codec support bitmask. Bit 0 H.264, bits 8-9 HEVC, bits 16+ AV1 on newer hosts. */
    val codecSupport: Int = 0,

    /** Non-zero means HEVC is offered; the number is the largest frame it will encode. */
    val maxLumaPixelsHevc: Long = 0,

    /** The modes the host advertises. Empty on hosts that do not send the list. */
    val displayModes: List<DisplayMode> = emptyList(),
) {

    data class DisplayMode(val width: Int, val height: Int, val refresh: Int)

    /** The major version, which decides which hash the pairing handshake uses. */
    val majorVersion: Int
        get() = appVersion.substringBefore('.').toIntOrNull() ?: 7

    /**
     * Protocol 7 and later derive the pairing key with SHA-256; before that it was SHA-1.
     *
     * Worth stating plainly because getting it wrong produces a pairing that fails at the very last
     * step with no useful error — the handshake completes and the host simply refuses the result.
     */
    val usesSha256: Boolean
        get() = majorVersion >= 7

    val hevcSupported: Boolean
        get() = maxLumaPixelsHevc > 0 || (codecSupport and 0x300) != 0

    /** Somebody is streaming from this host right now. */
    val busy: Boolean
        get() = currentGame != 0

    companion object {

        /**
         * Parse the XML `/serverinfo` returns.
         *
         * Read with a pull parser over the handful of elements that matter rather than a DOM: the
         * document is small, the fields are flat, and half of them are optional depending on which
         * host answered.
         */
        fun parse(xml: String): HostInfo {
            fun text(tag: String): String {
                val open = "<$tag>"
                val close = "</$tag>"
                val start = xml.indexOf(open)
                if (start < 0) return ""
                val end = xml.indexOf(close, start + open.length)
                if (end < 0) return ""
                return xml.substring(start + open.length, end).trim()
            }

            val version = text("appversion")
            val gfe = text("GfeVersion")

            return HostInfo(
                hostname = text("hostname"),
                uniqueId = text("uniqueid"),
                appVersion = version,
                gfeVersion = gfe,
                paired = text("PairStatus") == "1",
                currentGame = text("currentgame").toIntOrNull() ?: 0,
                state = text("state"),

                // Sunshine says so in its state string; GeForce Experience says MJOLNIR. Falling
                // back to "has no GfeVersion" catches Sunshine builds that changed the wording.
                isSunshine = text("state").contains("SUNSHINE", ignoreCase = true) || gfe.isEmpty(),

                codecSupport = text("ServerCodecModeSupport").toIntOrNull() ?: 0,
                maxLumaPixelsHevc = text("MaxLumaPixelsHEVC").toLongOrNull() ?: 0,
                displayModes = parseModes(xml),
            )
        }

        private fun parseModes(xml: String): List<DisplayMode> {
            val modes = mutableListOf<DisplayMode>()
            var cursor = 0

            while (true) {
                val start = xml.indexOf("<DisplayMode>", cursor)
                if (start < 0) break
                val end = xml.indexOf("</DisplayMode>", start)
                if (end < 0) break

                val block = xml.substring(start, end)
                fun field(tag: String): Int {
                    val open = "<$tag>"
                    val at = block.indexOf(open)
                    if (at < 0) return 0
                    val close = block.indexOf("</$tag>", at)
                    if (close < 0) return 0
                    return block.substring(at + open.length, close).trim().toIntOrNull() ?: 0
                }

                val mode = DisplayMode(field("Width"), field("Height"), field("RefreshRate"))
                if (mode.width > 0 && mode.height > 0) modes += mode

                cursor = end + 1
            }

            return modes
        }
    }
}
