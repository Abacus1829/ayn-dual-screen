package com.abacus.dualscreen

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.util.TypedValue
import android.view.Display
import android.view.HapticFeedbackConstants
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.children
import com.abacus.dualscreen.databinding.ActivityHomeBinding

/**
 * The hub: pick what to connect to, and reach the handheld's tools.
 *
 * The connection half stays deliberately small — a dropdown and one button in Simple mode — because
 * everything else on this screen is now about the device rather than about a game.
 */
class HomeActivity : AppCompatActivity() {

    private enum class State { IDLE, BUSY, OK, BAD }

    private lateinit var binding: ActivityHomeBinding
    private lateinit var settings: Settings

    private lateinit var selected: Game
    private val modeButtons = mutableMapOf<Boolean, Button>()

    private var advanced = false
    private var suppressSpinner = false

    private var targetDisplayId: Int? = null
    private var displayCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = Settings(this)
        selected = settings.lastGame
        advanced = settings.advanced

        buildModePicker()
        buildGameSpinner()
        buildToggles()
        buildTools()
        populateDisplays()
        applyGame(selected)
        applyMode()

        binding.testButton.setOnClickListener { test() }
        binding.findButton.setOnClickListener { find(thenOpen = false) }
        binding.detectButton.setOnClickListener { detect(announce = true) }
        binding.openButton.setOnClickListener {
            if (!advanced && binding.hostField.text.isBlank()) find(thenOpen = true) else open()
        }
    }

    override fun onResume() {
        super.onResume()
        // appearance may have been changed in the Appearance screen and we've come back: the grid and the
        // mode buttons are drawn in code, so they need rebuilding rather than just repainting
        Appearance.apply(this, binding.root, settings, binding.backgroundImage)
        binding.accentBar.setBackgroundColor(Appearance.accentOf(settings))
        maybeAutoStartFtp()
        buildTools()
        applyMode()

        if (settings.autoDetect) detect(announce = false)
    }

    /*********
     * Picker
     *********/
    /**
     * The dropdown is fed straight from [Game.entries], so a new entry needs no layout change — which
     * matters now that this list is meant to grow past the two mods.
     */
    private fun buildGameSpinner() {
        val labels = Game.entries.map { getString(it.label) }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.gameSpinner.adapter = adapter
        binding.gameSpinner.setSelection(Game.entries.indexOf(selected))

        binding.gameSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressSpinner) return
                select(Game.entries[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun select(game: Game) {
        if (game == selected) return

        commitFields(selected)
        selected = game
        settings.lastGame = game
        applyGame(game)
        applyMode()
        say(State.IDLE, getString(R.string.status_idle))
    }

    /** Move the dropdown without re-triggering the listener that moved it. */
    private fun showInSpinner(game: Game) {
        suppressSpinner = true
        binding.gameSpinner.setSelection(Game.entries.indexOf(game))
        suppressSpinner = false
    }

    private fun applyGame(game: Game) {
        binding.gameHint.text = getString(game.hint)
        binding.hostField.setText(settings.hostFor(game))
        binding.portField.setText(settings.portFor(game).toString())
        binding.accentBar.setBackgroundColor(Appearance.accentOf(settings))
        showInSpinner(game)

        if (settings.rememberDisplay) {
            settings.displayFor(game).takeIf { it >= 0 }?.let { saved ->
                targetDisplayId = saved
                binding.displayGroup.children.firstOrNull { it.tag == saved }?.let {
                    binding.displayGroup.check(it.id)
                }
            }
        }
    }

    /*********
     * Auto-detect
     *********/
    /**
     * Check the saved address and switch the dropdown to whatever is actually running there.
     *
     * Cheap enough to do on every resume: one request to an address we already know. The network sweep
     * behind *Find my PC* is the expensive one, and stays behind an explicit tap.
     */
    private fun detect(announce: Boolean) {
        val host = binding.hostField.text.toString().trim()
        val port = binding.portField.text.toString().trim().toIntOrNull()
        if (host.isEmpty() || port == null || host.lowercase() in LOOPBACK_HOSTS) {
            if (announce) find(thenOpen = false) // nothing saved to check, so go and look properly
            return
        }

        if (announce) say(State.BUSY, getString(R.string.status_detecting))

        Thread {
            val result = Probe.run("http://$host:$port")
            runOnUiThread {
                val found = result.game
                if (found == null) {
                    if (announce) say(State.BAD, getString(R.string.status_detect_none))
                    return@runOnUiThread
                }

                if (found != selected) {
                    commitFields(selected)
                    selected = found
                    settings.lastGame = found
                    applyGame(found)
                    applyMode()
                }
                say(State.OK, getString(R.string.status_detected, getString(found.label)))
            }
        }.start()
    }

    /*********
     * Tools
     *********/
    /**
     * The tool grid, in the user's order, column count and icon style.
     *
     * Rebuilt from scratch on every resume rather than patched, because coming back from the Appearance
     * screen can have changed the count, the order, the icons and the colours all at once.
     */
    private fun buildTools() {
        binding.toolGrid.removeAllViews()
        /*
         * A console skin, if one is chosen.
         *
         * The grid is styled rather than replaced. Rebuilding it from the skin would mean
         * duplicating the tap, the long-press-to-hide and the ordering that already live here —
         * and every one of those would then need fixing twice. A skin decides how a tile looks; it
         * has no business deciding what a tile does.
         */
        val skin = com.abacus.dualscreen.theme.ThemeStore(this).byId(settings.consoleTheme)
        val skinned = skin.id != "default"

        binding.toolGrid.columnCount =
            if (skinned) skin.columns else settings.gridColumns.coerceIn(2, 5)

        if (skinned) {
            // The skin owns the whole surface, not just the tiles — a 3DS on a black page would
            // look like a mistake rather than a 3DS, and a Vita needs its blue gradient.
            binding.root.background = com.abacus.dualscreen.theme.ConsoleSkin.backdrop(skin)
            binding.backgroundImage.visibility = View.GONE
            addStatusBar(skin)
        }

        val accent = Appearance.accentOf(settings)
        val hidden = settings.hiddenTools

        // saved order first, then anything added by a later version that the saved list can't know about
        val ordered = settings.toolOrder.mapNotNull { Tool.byId(it) }
        val tools = (ordered + Tool.entries).distinct().filter { it.id !in hidden }

        if (tools.isEmpty()) {
            binding.toolGrid.visibility = View.GONE
            return
        }
        binding.toolGrid.visibility = View.VISIBLE

        for (tool in tools) {
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, dp(14), 0, dp(12))
                background = if (skinned) {
                    com.abacus.dualscreen.theme.ConsoleSkin.tileFace(this@HomeActivity, skin)
                } else {
                    Appearance.tile(this@HomeActivity, settings, accent, tool.available)
                }
                alpha = if (tool.available) 1f else 0.45f
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    height = GridLayout.LayoutParams.WRAP_CONTENT
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                }
                setOnClickListener {
                    // a short tick on tap: on a handheld this is most of what makes a grid feel solid
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    openTool(tool)
                }

                // Hiding a tool was buried three screens deep in Appearance. Holding the tile itself is
                // where anyone would reach for it first, so it works there too.
                setOnLongClickListener {
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    confirmHide(tool)
                    true
                }
            }

            val glyph = TextView(this).apply {
                text = Appearance.iconFor(settings, tool)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (settings.iconSet == "text") 13f else 22f)
                setTextColor(
                    when {
                        // A skin's glyph colour, not the app accent: dark glyphs on a 3DS's white
                        // tiles, pale ones on a PSP's near-black field.
                        skinned && tool.available -> skin.tileGlyph
                        skinned -> android.graphics.Color.argb(
                            110,
                            android.graphics.Color.red(skin.tileGlyph),
                            android.graphics.Color.green(skin.tileGlyph),
                            android.graphics.Color.blue(skin.tileGlyph),
                        )
                        tool.available -> accent
                        else -> getColor(R.color.text_faint)
                    }
                )
                gravity = Gravity.CENTER
            }

            val label = TextView(this).apply {
                // A live dot on the FTP tile while the server is up. Without it the only way to
                // know is to open the screen, and "did I leave that running?" is a question worth
                // answering from the home grid — especially for a thing that shares your files.
                text = if (tool == Tool.FTP && FtpService.live != null) {
                    "● " + getString(tool.label)
                } else {
                    getString(tool.label)
                }
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(getColor(R.color.text_dim))
                gravity = Gravity.CENTER
                setPadding(dp(2), dp(4), dp(2), 0)
            }

            cell.addView(glyph)
            cell.addView(label)
            binding.toolGrid.addView(cell)
        }
    }

    /**
     * Start the FTP server on launch, if that was asked for.
     *
     * Only when nothing is already running: coming back to the home screen from another app calls
     * this again, and a second start on a live server would bind a port that is already bound and
     * report a failure for something that is working perfectly.
     */
    private fun maybeAutoStartFtp() {
        if (!settings.ftpAutoStart || FtpService.live != null) return

        runCatching {
            FtpService.start(
                context = this,
                port = settings.ftpPort,
                user = settings.ftpUser,
                pass = settings.ftpPassword,
                wholeDevice = settings.ftpWholeDevice,
            )
        }
    }

    /**
     * The console's own status strip, above the tools.
     *
     * Inserted into the grid's parent rather than added to the layout file, because it only exists
     * for a skin — the app's own look has no such bar, and an empty strip taking up eighteen pixels
     * on the default theme would be worse than none.
     *
     * Tagged so a rebuild can find and replace it: buildTools() runs again whenever a tool is
     * hidden or a theme changes, and without the tag each pass would leave another bar behind.
     */
    private fun addStatusBar(skin: com.abacus.dualscreen.theme.ConsoleTheme) {
        val parent = binding.toolGrid.parent as? ViewGroup ?: return

        parent.findViewWithTag<View>(STATUS_BAR_TAG)?.let { parent.removeView(it) }

        val battery = runCatching {
            getSystemService(android.os.BatteryManager::class.java)
                ?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0
        }.getOrDefault(0)

        val connection = settings.hostFor(settings.lastGame).ifEmpty { getString(R.string.app_name) }

        val bar = com.abacus.dualscreen.theme.ConsoleSkin
            .buildStatusBar(this, skin, connection, battery) ?: return

        bar.tag = STATUS_BAR_TAG
        parent.addView(bar, parent.indexOfChild(binding.toolGrid))
    }

    /**
     * Offer to hide a tool, with a way straight back.
     *
     * Confirmed rather than immediate: a long press is easy to trigger by accident while scrolling, and
     * a tile vanishing with no explanation reads as a bug. The message says where to undo it.
     */
    private fun confirmHide(tool: Tool) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(tool.label))
            .setMessage(R.string.hide_tool_message)
            .setPositiveButton(R.string.hide_tool) { _, _ ->
                settings.hiddenTools = settings.hiddenTools.toMutableSet().apply { add(tool.id) }
                buildTools()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /** Tools that aren't built yet say so, rather than doing nothing. */
    private fun openTool(tool: Tool) {
        if (!tool.available) {
            Toast.makeText(
                this,
                getString(R.string.blocked_toast, getString(tool.label), getString(tool.blockedReason)),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        when (tool) {
            Tool.SECOND_SCREEN -> open()
            Tool.NOTES -> startActivity(Intent(this, NotesActivity::class.java))
            Tool.VOLUME, Tool.BRIGHTNESS -> startActivity(Intent(this, ControlsActivity::class.java))
            Tool.APPEARANCE -> startActivity(Intent(this, AppearanceActivity::class.java))
            Tool.KEYBOARD -> startActivity(Intent(this, KeyboardActivity::class.java))
            Tool.MIRROR -> startActivity(Intent(this, MirrorActivity::class.java))
            Tool.MACROS -> startActivity(Intent(this, MacrosActivity::class.java))
            Tool.FTP -> startActivity(Intent(this, FtpActivity::class.java))
            Tool.STREAM -> startActivity(Intent(this, StreamActivity::class.java))
            Tool.THEMES -> startActivity(Intent(this, ThemesActivity::class.java))
            else -> Unit
        }
    }

    /*********
     * Chrome
     *********/
    private fun buildModePicker() {
        binding.modeGroup.removeAllViews()
        for (isAdvanced in listOf(false, true)) {
            val button = Button(this).apply {
                text = getString(if (isAdvanced) R.string.mode_advanced else R.string.mode_simple)
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { if (!isAdvanced) marginEnd = dp(6) }
                setOnClickListener {
                    if (advanced != isAdvanced) {
                        advanced = isAdvanced
                        settings.advanced = isAdvanced
                        applyMode()
                    }
                }
            }
            modeButtons[isAdvanced] = button
            binding.modeGroup.addView(button)
        }
    }

    private fun applyMode() {
        val accent = Appearance.accentOf(settings)
        for ((isAdvanced, button) in modeButtons) {
            val chosen = isAdvanced == advanced
            button.background = Appearance.panel(
                this, settings,
                getColor(if (chosen) R.color.card_hi else R.color.card),
                if (chosen) accent else getColor(R.color.edge),
                if (chosen) 2 else 1
            )
            button.setTextColor(getColor(if (chosen) R.color.text else R.color.text_faint))
        }

        val visibility = if (advanced) View.VISIBLE else View.GONE
        binding.addressCard.visibility = visibility
        binding.behaviourCard.visibility = visibility
        binding.displayCard.visibility = if (advanced && displayCount > 1) View.VISIBLE else View.GONE

        binding.openButton.text = getString(if (advanced) R.string.action_open else R.string.action_connect)
        binding.modeBlurb.text = getString(if (advanced) R.string.advanced_blurb else R.string.simple_blurb)
    }

    private fun buildToggles() {
        binding.toggleGroup.removeAllViews()
        addToggle(R.string.opt_autodetect, R.string.opt_autodetect_detail, settings.autoDetect) {
            settings.autoDetect = it
        }
        addToggle(R.string.opt_reconnect, R.string.opt_reconnect_detail, settings.autoReconnect) {
            settings.autoReconnect = it
        }
        addToggle(R.string.opt_keep_awake, R.string.opt_keep_awake_detail, settings.keepAwake) {
            settings.keepAwake = it
        }
        addToggle(R.string.opt_remember_display, R.string.opt_remember_display_detail, settings.rememberDisplay) {
            settings.rememberDisplay = it
        }
    }

    private fun addToggle(title: Int, detail: Int, initial: Boolean, onChange: (Boolean) -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }

        val check = CheckBox(this).apply {
            text = getString(title)
            isChecked = initial
            setTextColor(getColor(R.color.text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setOnCheckedChangeListener { _, value -> onChange(value) }
        }

        val caption = TextView(this).apply {
            text = getString(detail)
            setTextColor(getColor(R.color.text_faint))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dp(32), 0, 0, 0)
        }

        row.addView(check)
        row.addView(caption)
        binding.toggleGroup.addView(row)
    }

    private fun say(state: State, message: String) {
        binding.statusText.text = message
        binding.statusDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(
                getColor(
                    when (state) {
                        State.IDLE -> R.color.state_idle
                        State.BUSY -> R.color.state_busy
                        State.OK -> R.color.state_ok
                        State.BAD -> R.color.state_bad
                    }
                )
            )
        }
    }

    /*********
     * Displays
     *********/
    private fun populateDisplays() {
        val manager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val displays = manager.displays.filter { it.isValid }
        displayCount = displays.size
        if (displays.size < 2) return

        displays.forEachIndexed { index, display ->
            val button = RadioButton(this).apply {
                id = View.generateViewId()
                text = describe(display, index)
                setTextColor(getColor(R.color.text))
                tag = display.displayId
            }
            binding.displayGroup.addView(button)
        }

        val preferred = displays.getOrNull(1) ?: displays.first()
        targetDisplayId = preferred.displayId
        binding.displayGroup.children.firstOrNull { it.tag == preferred.displayId }?.let {
            binding.displayGroup.check(it.id)
        }

        binding.displayGroup.setOnCheckedChangeListener { _, checkedId ->
            targetDisplayId = findViewById<RadioButton>(checkedId)?.tag as? Int
            targetDisplayId?.let { if (settings.rememberDisplay) settings.setDisplayFor(selected, it) }
        }
    }

    private fun describe(display: Display, index: Int): String {
        val label = display.name?.takeIf { it.isNotBlank() } ?: "Display ${display.displayId}"
        return if (index == 0) "${getString(R.string.display_builtin)} — $label" else label
    }

    /*********
     * Address
     *********/
    private fun readUrl(): String? {
        val host = binding.hostField.text.toString().trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .trimEnd('/')

        if (host.isEmpty()) {
            say(State.BAD, getString(R.string.status_need_host))
            return null
        }

        // The mods print "http://localhost:27301/" in their logs, which is the address to use *on the
        // PC*. Typed here it points this device at itself, so catch it before it becomes a mystery.
        if (host.lowercase() in LOOPBACK_HOSTS) {
            say(State.BAD, getString(R.string.status_localhost))
            return null
        }

        val port = binding.portField.text.toString().trim().toIntOrNull()
        if (port == null || port !in 1..65535) {
            say(State.BAD, getString(R.string.status_bad_port))
            return null
        }

        settings.setHostFor(selected, host)
        settings.setPortFor(selected, port)
        return "http://$host:$port"
    }

    private fun commitFields(game: Game) {
        settings.setHostFor(game, binding.hostField.text.toString().trim())
        binding.portField.text.toString().trim().toIntOrNull()
            ?.takeIf { it in 1..65535 }
            ?.let { settings.setPortFor(game, it) }
    }

    /*********
     * Actions
     *********/
    private fun test() {
        val url = readUrl() ?: return
        say(State.BUSY, getString(R.string.status_testing))

        Thread {
            val result = Probe.run(url)
            runOnUiThread {
                say(if (result.game == selected) State.OK else State.BAD, describe(result))
            }
        }.start()
    }

    private fun describe(result: ProbeResult): String = when {
        result.game == selected && result.inGame ->
            getString(R.string.status_ok_in_game, getString(selected.label), result.place.orEmpty())

        result.game == selected ->
            getString(R.string.status_ok_menu, getString(selected.label))

        result.game != null ->
            getString(R.string.status_wrong_game, getString(result.game.label), getString(selected.label))

        result.reachable ->
            getString(R.string.status_no_mod, result.detail.orEmpty())

        result.failure == Failure.REFUSED ->
            getString(R.string.status_refused, getString(selected.label))

        result.failure == Failure.UNKNOWN_HOST ->
            getString(R.string.status_unknown_host)

        result.failure == Failure.TIMEOUT ->
            getString(R.string.status_timeout)

        else ->
            getString(R.string.status_unreachable, result.detail.orEmpty())
    }

    private fun find(thenOpen: Boolean) {
        val port = binding.portField.text.toString().trim().toIntOrNull() ?: selected.defaultPort
        if (port !in 1..65535) {
            say(State.BAD, getString(R.string.status_bad_port))
            return
        }

        binding.findButton.isEnabled = false
        binding.openButton.isEnabled = false
        binding.foundGroup.removeAllViews()
        binding.foundCard.visibility = View.GONE
        say(State.BUSY, getString(R.string.status_scanning, 0))

        Thread {
            val results = Scanner.sweep(port) { progress ->
                val percent = (progress * 100).toInt()
                runOnUiThread { say(State.BUSY, getString(R.string.status_scanning, percent)) }
            }

            runOnUiThread {
                binding.findButton.isEnabled = true
                binding.openButton.isEnabled = true

                val match = results.singleOrNull { it.game != null }
                if (thenOpen && match != null) {
                    use(match, port)
                    open()
                } else {
                    showFound(results, port)
                }
            }
        }.start()
    }

    private fun showFound(results: List<Found>, port: Int) {
        if (results.isEmpty()) {
            say(State.BAD, getString(R.string.status_scan_none, port))
            return
        }

        say(State.OK, getString(R.string.status_scan_done, results.size))
        binding.foundCard.visibility = View.VISIBLE

        for (found in results) {
            val label = when {
                found.game != null && found.place != null ->
                    getString(R.string.found_in_game, found.host, getString(found.game.label), found.place)
                found.game != null ->
                    getString(R.string.found_menu, found.host, getString(found.game.label))
                else ->
                    getString(R.string.found_unknown, found.host)
            }

            val button = Button(this).apply {
                text = label
                isAllCaps = false
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(getColor(R.color.text))
                background = Appearance.panel(
                    this@HomeActivity, settings,
                    getColor(R.color.card_hi),
                    if (found.game != null) Appearance.accentOf(settings) else getColor(R.color.edge)
                )
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) }

                setOnClickListener {
                    use(found, port)
                    binding.foundCard.visibility = View.GONE
                }
            }

            binding.foundGroup.addView(button)
        }
    }

    /** Adopt a scan result: its address, its port, and the game it actually belongs to. */
    private fun use(found: Found, port: Int) {
        found.game?.let { game ->
            if (game != selected) {
                selected = game
                settings.lastGame = game
                applyGame(game)
                applyMode()
            }
        }
        binding.hostField.setText(found.host)
        binding.portField.setText(port.toString())
        settings.setHostFor(selected, found.host)
        settings.setPortFor(selected, port)
        say(State.OK, getString(R.string.status_picked, found.host))
    }

    private fun open() {
        val url = readUrl() ?: return

        // name the address, not just the game. In Simple mode the address is off screen entirely, so
        // when it opens the wrong PC there is otherwise nothing to tell you that is what happened.
        say(State.BUSY, getString(R.string.status_opening_at, getString(selected.label), url))

        val intent = Intent(this, ScreenActivity::class.java)
            .putExtra(ScreenActivity.EXTRA_URL, url)
            .putExtra(ScreenActivity.EXTRA_GAME, selected.id)
            .putExtra(ScreenActivity.EXTRA_RECONNECT, settings.autoReconnect)
            .putExtra(ScreenActivity.EXTRA_KEEP_AWAKE, settings.keepAwake)

        val options = targetDisplayId
            ?.let { ActivityOptions.makeBasic().setLaunchDisplayId(it).toBundle() }

        targetDisplayId?.let { if (settings.rememberDisplay) settings.setDisplayFor(selected, it) }
        startActivity(intent, options)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1", "[::1]")

        /** Marks the skin's status strip so a rebuild replaces it instead of stacking another. */
        const val STATUS_BAR_TAG = "console-status-bar"
    }
}
