package com.abacus.dualscreen.connect

import android.app.ActivityOptions
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display

/** A display this device has right now, with a name worth putting in front of somebody. */
data class Panel(val id: Int, val label: String, val isMain: Boolean, val isPresentation: Boolean)

/**
 * Which screens exist, and which one a [DisplayChoice] means today.
 *
 * Everything here is resolved at the moment it is asked. Display ids are handed out by the system
 * and change — unplug a dock, reboot, and yesterday's "display 2" is either gone or is something
 * else. So nothing is cached and nothing is stored: a profile keeps an intent, and this turns that
 * intent into whatever is currently plugged in.
 *
 * The Thor's lower panel appears in this list like any other display. Nothing here knows or cares
 * that it is a Thor.
 */
object Displays {

    fun all(context: Context): List<Panel> {
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            ?: return emptyList()

        return manager.displays
            .filter { it.isValid }
            .sortedBy { it.displayId }
            .map { display ->
                Panel(
                    id = display.displayId,
                    label = display.name?.takeIf { it.isNotBlank() } ?: "Display ${display.displayId}",
                    isMain = display.displayId == Display.DEFAULT_DISPLAY,
                    isPresentation = (display.flags and Display.FLAG_PRESENTATION) != 0,
                )
            }
    }

    fun exists(context: Context, displayId: Int): Boolean =
        all(context).any { it.id == displayId }

    /** More than one screen to choose between. */
    fun hasSecond(context: Context): Boolean = all(context).count { !it.isMain } > 0

    /**
     * The display a choice resolves to, or null when the session should stay where it is.
     *
     * Null is the honest answer for [DisplayChoice.MAIN] — launching onto the display the launcher
     * is already on wants no options bundle at all — and for a choice whose screen is not there. The
     * caller treats null as "open here", which is the graceful fallback the whole feature needs:
     * a profile that wants the second panel on a device that no longer has one still opens.
     */
    fun resolve(context: Context, choice: DisplayChoice): Int? {
        val panels = all(context)
        val others = panels.filterNot { it.isMain }

        return when (choice) {
            DisplayChoice.MAIN -> null
            DisplayChoice.SECOND -> others.firstOrNull()?.id
            DisplayChoice.EXTERNAL -> others.firstOrNull { it.isPresentation }?.id ?: others.firstOrNull()?.id
            DisplayChoice.AUTO -> others.firstOrNull()?.id
            // Resolved by asking, before this is ever called. Treated as automatic if it slips
            // through, because a silent no-op would look like a broken button.
            DisplayChoice.ASK -> others.firstOrNull()?.id
        }
    }

    /**
     * The launch options for a target display, or null to launch normally.
     *
     * setLaunchDisplayId arrived in Oreo, which is this app's minimum — except under the test-only
     * `-PtestMinSdk` flag, where the guard below is the difference between a degraded build and a
     * crash on the first connect.
     */
    fun optionsFor(displayId: Int?): android.os.Bundle? {
        if (displayId == null) return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null

        return runCatching {
            ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
        }.getOrNull()
    }

    /**
     * Watch for screens appearing and disappearing.
     *
     * Returns the listener so the caller can unregister it; a display listener that outlives its
     * activity keeps a reference to it and fires into a dead window.
     */
    fun listen(context: Context, onChanged: () -> Unit): DisplayManager.DisplayListener? {
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return null

        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = onChanged()
            override fun onDisplayRemoved(displayId: Int) = onChanged()
            override fun onDisplayChanged(displayId: Int) = onChanged()
        }

        manager.registerDisplayListener(listener, null)
        return listener
    }

    fun stopListening(context: Context, listener: DisplayManager.DisplayListener?) {
        if (listener == null) return
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        runCatching { manager.unregisterDisplayListener(listener) }
    }
}
