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
import android.widget.ImageView
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
        binding.profilesButton.setOnClickListener {
            startActivity(Intent(this, ProfilesActivity::class.java))
        }
        binding.openButton.setOnClickListener {
            if (!advanced && binding.hostField.text.isBlank()) find(thenOpen = true) else open()
        }

        startUp(fresh = savedInstanceState == null)
    }

    /*********
     * Startup
     *********/
    private val updates by lazy { com.abacus.dualscreen.update.UpdateManager.get(this) }

    /** Set once the boot animation is out of the way, whether it played or not. */
    private var booted = false

    /** True only between onResume and onPause: a dialog cannot be shown outside that. */
    private var showable = false

    /** Re-checked whenever the update state moves, in case the check outlives the animation. */
    private val updateListener: (com.abacus.dualscreen.update.UpdateManager.State) -> Unit =
        { maybePrompt() }

    /**
     * What happens when the app opens.
     *
     * Two things at once, deliberately not waiting for each other:
     *
     * - the update check leaves for GitHub on a background thread, and takes as long as it takes;
     * - the abacus plays over the top of a home screen that is already building itself underneath.
     *
     * Whichever finishes second is the one that decides when the prompt appears. If the check is
     * still out when the animation ends, the home screen simply appears and the prompt arrives a
     * moment later — no spinner, no frozen frame, and nothing held up waiting for a network that
     * may not be there at all.
     */
    private fun startUp(fresh: Boolean) {
        updates.checkOnStartup()

        if (fresh && com.abacus.dualscreen.boot.Boot.due(settings)) {
            com.abacus.dualscreen.boot.Boot.play(this, binding.bootView, settings) { onBooted() }
        } else {
            onBooted()
        }
    }

    private fun onBooted() {
        booted = true
        maybePrompt()
    }

    /**
     * Offer the update, if there is one worth offering.
     *
     * Every rule about *whether* to interrupt lives in the manager; this only knows that the screen
     * is on and the animation has finished.
     */
    private fun maybePrompt() {
        if (!booted || !showable || isFinishing) return
        val update = updates.promptable() ?: return
        com.abacus.dualscreen.update.UpdatePrompt.show(this, update)
    }

    override fun onStart() {
        super.onStart()
        updates.observe(updateListener)
    }

    override fun onStop() {
        super.onStop()
        updates.forget(updateListener)
    }

    override fun onPause() {
        super.onPause()
        showable = false
    }

    /**
     * The hidden way in.
     *
     * Two sequences watched at once so it can be entered with a pad or with a keyboard; neither
     * consumes the press, so the d-pad keeps navigating this screen exactly as it did. Built only
     * when the feature is switched on — with it off there is no listener at all, which is what
     * "behaves as though it does not exist" has to mean.
     */
    private val secrets by lazy {
        val codes = com.abacus.dualscreen.codes.CodeSettings(this)
        if (!codes.enabled) emptyList()
        else listOf(
            // One watcher now rather than a pad one and a keyboard one: each step accepts every
            // code that button might arrive as, so a device that reports one button the pad way and
            // the other the keyboard way satisfies this instead of neither.
            com.abacus.dualscreen.codes.SecretSequence(
                steps = com.abacus.dualscreen.codes.SecretSequence.UNLOCK,
                onProgress = { at, total -> showProgress(at, total) },
            ) { toggleCodes() },
        )
    }

    /**
     * The gesture watcher, kept apart from the key ones.
     *
     * Its own instance because its steps are gestures rather than key codes, and its own progress
     * glyphs because the last two are taps rather than buttons -- showing "B A" while somebody is
     * being asked to tap would be a hint pointing the wrong way.
     */
    private val touchSecret by lazy {
        val codes = com.abacus.dualscreen.codes.CodeSettings(this)
        if (!codes.enabled) null
        else com.abacus.dualscreen.codes.SecretSequence(
            steps = com.abacus.dualscreen.codes.TouchCodes.UNLOCK,
            onProgress = { at, total ->
                showProgress(at, total, com.abacus.dualscreen.codes.TouchCodes.GLYPHS)
            },
        ) { toggleCodes() }
    }
    /**
     * The gesture path into the hidden sequence.
     *
     * Watched from dispatchTouchEvent, which sees every touch before any view does — and, as with
     * the key path, passes it straight on. Scrolling, tapping tiles and dragging all behave exactly
     * as they did; this only measures what happened afterwards.
     *
     * This exists because the keyboard cannot be relied upon: a desktop emulator's key-mapping layer
     * takes the arrow keys before the app sees them. A touch has nothing in between.
     */
    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                touchX = event.x
                touchY = event.y
                touchAt = System.currentTimeMillis()
            }

            android.view.MotionEvent.ACTION_UP -> {
                val dx = event.x - touchX
                val dy = event.y - touchY
                val far = dp(40).toFloat()

                val gesture = when {
                    kotlin.math.abs(dx) > kotlin.math.abs(dy) && kotlin.math.abs(dx) > far ->
                        if (dx > 0) com.abacus.dualscreen.codes.TouchCodes.SWIPE_RIGHT
                        else com.abacus.dualscreen.codes.TouchCodes.SWIPE_LEFT

                    kotlin.math.abs(dy) > far ->
                        if (dy > 0) com.abacus.dualscreen.codes.TouchCodes.SWIPE_DOWN
                        else com.abacus.dualscreen.codes.TouchCodes.SWIPE_UP

                    // A short, quick press is a tap. A long one is somebody holding something, and
                    // counting that as a tap would advance the sequence while they meant to do
                    // something else entirely.
                    System.currentTimeMillis() - touchAt < 400 ->
                        com.abacus.dualscreen.codes.TouchCodes.TAP

                    else -> 0
                }

                if (gesture != 0) touchSecret?.onKey(gesture)
            }
        }

        return super.dispatchTouchEvent(event)
    }

    private var touchX = 0f
    private var touchY = 0f
    private var touchAt = 0L


    /**
     * Every key, before anything else sees it.
     *
     * dispatchKeyEvent rather than onKeyDown, and that distinction is the feature working or not:
     * onKeyDown is only reached for keys the view hierarchy did **not** consume, and on this screen
     * the arrow keys are consumed to move focus between the dropdown, the address fields and the
     * buttons — so the sequence was being eaten before it ever arrived.
     *
     * The event is still passed on afterwards. This watches; it does not intercept.
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        // First press only. A held key repeats ACTION_DOWN many times a second, which would race
        // through the sequence and land nowhere — a hold has to count once, like a press.
        if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            secrets.forEach { it.onKey(event.keyCode) }
        }

        return super.dispatchKeyEvent(event)
    }

    /**
     * A real gamepad's d-pad, which is not keys at all.
     *
     * Plenty of pads — this handheld's included — report their d-pad as motion axes rather than as
     * DPAD key codes. Without this the sequence is enterable on a keyboard and quietly impossible on
     * the device it was designed for.
     *
     * **dispatchGenericMotionEvent, not onGenericMotionEvent**, and that is the same distinction
     * that broke the key path before it: `onGenericMotionEvent` is only reached for motion events
     * that no view consumed, and a scrolling list or a spinner on this screen will consume joystick
     * movement long before the activity hears about it. `dispatch` sees every event first and still
     * passes it on — this watches, it does not intercept.
     *
     * Both the hat axes and the left stick are read, because which of the two a d-pad reports is a
     * per-device decision and not one the app can influence. The edge detection in [feedAxis] means
     * a device that reports both does not count a push twice.
     */
    override fun dispatchGenericMotionEvent(event: android.view.MotionEvent): Boolean {
        val fromPad = event.source and android.view.InputDevice.SOURCE_CLASS_JOYSTICK != 0 ||
            event.source and android.view.InputDevice.SOURCE_DPAD == android.view.InputDevice.SOURCE_DPAD

        if (fromPad && event.action == android.view.MotionEvent.ACTION_MOVE) {
            feedAxis(
                pick(event, android.view.MotionEvent.AXIS_HAT_X, android.view.MotionEvent.AXIS_X),
                horizontal = true,
            )
            feedAxis(
                pick(event, android.view.MotionEvent.AXIS_HAT_Y, android.view.MotionEvent.AXIS_Y),
                horizontal = false,
            )
        }

        return super.dispatchGenericMotionEvent(event)
    }

    /** The hat if it is doing anything, otherwise the stick. */
    private fun pick(event: android.view.MotionEvent, hat: Int, stick: Int): Float {
        val fromHat = event.getAxisValue(hat)
        return if (kotlin.math.abs(fromHat) > 0.5f) fromHat else event.getAxisValue(stick)
    }

    /** Where each hat axis was last time, so one push is one press rather than one per frame. */
    private var hatX = 0
    private var hatY = 0

    private fun feedAxis(value: Float, horizontal: Boolean) {
        val now = when {
            value > 0.5f -> 1
            value < -0.5f -> -1
            else -> 0
        }

        val was = if (horizontal) hatX else hatY
        if (now == was) return

        if (horizontal) hatX = now else hatY = now
        if (now == 0) return

        val key = when {
            horizontal && now > 0 -> android.view.KeyEvent.KEYCODE_DPAD_RIGHT
            horizontal -> android.view.KeyEvent.KEYCODE_DPAD_LEFT
            now > 0 -> android.view.KeyEvent.KEYCODE_DPAD_DOWN
            else -> android.view.KeyEvent.KEYCODE_DPAD_UP
        }

        secrets.forEach { it.onKey(key) }
    }

    /**
     * Found it — or, if it was already found, put it away again.
     *
     * The same sequence both ways, so there is one thing to remember rather than two. Relocking
     * takes the tile away; the codes themselves and their per-game switches are untouched, so
     * finding it again restores what you had rather than a blank slate.
     */
    private fun toggleCodes() {
        val codes = com.abacus.dualscreen.codes.CodeSettings(this)
        if (!codes.enabled) return

        val nowUnlocked = !codes.unlocked
        codes.unlocked = nowUnlocked

        com.abacus.dualscreen.ui.Feedback.success(binding.root)
        removeTagged(binding.root, STEPS_TAG)
        celebrate(
            if (nowUnlocked) getString(R.string.codes_unlocked)
            else getString(R.string.codes_relocked)
        )
        buildTools()
    }

    /**
     * The step display, in the style of the arrow games.
     *
     * Deliberately silent for the first few presses: showing it on the very first UP would give the
     * secret away to anybody who nudged the stick. It appears once somebody is clearly entering
     * *something*, which is the point where a hint helps rather than spoils.
     *
     * Rebuilt per press rather than animated — ten small views is nothing, and rebuilding makes it
     * impossible for the lit state and the counter to disagree.
     */
    private fun showProgress(at: Int, total: Int, glyphs: List<String> = STEP_GLYPHS) {
        removeTagged(binding.root, STEPS_TAG)
        if (at < REVEAL_AFTER || at > total) return

        val row = LinearLayout(this).apply {
            tag = STEPS_TAG
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = Appearance.panel(
                this@HomeActivity, settings,
                getColor(R.color.card_hi), Appearance.accentOf(settings), 2,
            )
            alpha = 0f
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM,
            ).apply { bottomMargin = dp(28) }
        }

        glyphs.forEachIndexed { index, glyph ->
            row.addView(TextView(this).apply {
                text = glyph
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                setPadding(dp(7), 0, dp(7), 0)

                val done = index < at
                setTextColor(if (done) Appearance.accentOf(settings) else getColor(R.color.text_faint))
                alpha = if (done) 1f else 0.3f
                // The one just hit sits slightly larger, so the eye follows along the row.
                scaleX = if (index == at - 1) 1.3f else 1f
                scaleY = scaleX
            })
        }

        binding.root.addView(row)
        row.animate().alpha(1f).setDuration(90).start()

        lastStepAt = System.currentTimeMillis()

        // Clears itself if the attempt is abandoned, so half an entry does not sit there.
        binding.root.postDelayed({
            if (row.parent != null && System.currentTimeMillis() - lastStepAt >= 2_400) {
                binding.root.removeView(row)
            }
        }, 2_500)
    }

    private var lastStepAt = 0L

    /**
     * The celebration.
     *
     * A banner that rises, holds and fades, drawn in code so it costs no layout and inherits the
     * chosen accent. Original wording and original artwork — it names nothing but the feature.
     */
    private fun celebrate(message: String) {
        val banner = TextView(this).apply {
            text = message
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setPadding(dp(28), dp(18), dp(28), dp(18))
            background = Appearance.panel(
                this@HomeActivity, settings,
                Appearance.blend(getColor(R.color.card_hi), Appearance.accentOf(settings), 0.35f),
                Appearance.accentOf(settings), 2,
            )
            alpha = 0f
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            )
        }

        binding.root.addView(banner)
        banner.translationY = dp(24).toFloat()

        banner.animate().alpha(1f).translationY(0f).setDuration(260).withEndAction {
            banner.animate().alpha(0f).setStartDelay(1600).setDuration(420).withEndAction {
                binding.root.removeView(banner)
            }.start()
        }.start()
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

        // Only offered once there is something in it: an empty saved list is a worse first
        // impression than no button at all.
        binding.profilesButton.visibility =
            if (com.abacus.dualscreen.connect.ProfileStore(this).profiles.isEmpty()) View.GONE
            else View.VISIBLE

        if (settings.autoDetect) detect(announce = false)

        showable = true
        maybePrompt()
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

                // Manual selection wins when the user has said so. Detection still reports what it
                // found -- it simply does not move them off the game they chose.
                if (found != selected && settings.autoSwitchGame) {
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
        val themes = com.abacus.dualscreen.theme.ThemeStore(this)
        val skin = themes.byId(settings.consoleTheme)
        val skinned = skin.id != "default"
        val skinFont = if (skinned) themes.typeface(skin) else null

        binding.toolGrid.columnCount =
            if (skinned) skin.columns else settings.gridColumns.coerceIn(2, 5)

        if (skinned) {
            // The skin owns the whole surface, not just the tiles — a light skin on a black page would
            // look like a mistake, and the bubble skin needs its blue gradient.
            // A theme's own wallpaper wins over its colours — for most of these looks the
            // wallpaper IS the theme, and the palette exists to sit on top of it.
            val store = com.abacus.dualscreen.theme.ThemeStore(this)
            binding.root.background = store.background(skin)
                ?: com.abacus.dualscreen.theme.ConsoleSkin.backdrop(skin)

            binding.backgroundImage.visibility = View.GONE
        }

        applyWaves(skinned && settings.consoleTheme == com.abacus.dualscreen.theme.ConsoleTheme.WILD.id)

        // Hide the app's chrome FIRST, then add the status strip.
        //
        // The other order looked fine and wasn't: the sweep walks every child of the grid's parent,
        // and the strip is one of those children. Keeping it by tag should have spared it and did
        // not, so rather than debug a comparison, the strip is simply added after the sweep has
        // finished — nothing can hide something that is not there yet.
        applyChrome(skinned)
        if (skinned) {
            addStatusBar(skin)
            addTray(skin)
        }

        val accent = Appearance.accentOf(settings)
        val hidden = settings.hiddenTools

        // saved order first, then anything added by a later version that the saved list can't know about
        val ordered = settings.toolOrder.mapNotNull { Tool.byId(it) }
        // Game codes are the one hidden tool that can become visible: found, and switched on.
        val codesVisible = com.abacus.dualscreen.codes.CodeSettings(this).visible

        val tools = (ordered + Tool.entries)
            .distinct()
            .filter { it.id !in hidden && (!it.hidden || (it == Tool.GAME_CODES && codesVisible)) }

        if (tools.isEmpty()) {
            binding.toolGrid.visibility = View.GONE
            return
        }
        binding.toolGrid.visibility = View.VISIBLE

        for (tool in tools) {
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                // Height follows the skin. tileScale was being read by nothing on this screen, so
                // the silver skin's three chunky tiles and the tablet skin's five small ones came out identical
                // -- which is most of why the skins still looked alike.
                val pad = if (skinned) skin.tileScale else 1f
                setPadding(0, dp((16 * pad).toInt()), 0, dp((14 * pad).toInt()))
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
                // Glyphs scale with the skin too, so a chunky-tile skin gets a chunky icon.
                val base = if (settings.iconSet == "text") 13f else 22f
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (skinned) base * skin.tileScale * 1.15f else base)
                setTextColor(
                    when {
                        // A skin's glyph colour, not the app accent: dark glyphs on a light skin's white
                        // tiles, pale ones on a near-black field.
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

            // A theme's font, if it ships one. Applied to the glyph as well as the label because a
            // themed icon font is a real thing people do.
            skinFont?.let { glyph.typeface = it }

            val label = TextView(this).apply {
                // A live dot on the FTP tile while the server is up. Without it the only way to
                // know is to open the screen, and "did I leave that running?" is a question worth
                // answering from the home grid — especially for a thing that shares your files.
                text = when {
                    // A live dot on the FTP tile while the server is up. Without it the only way to
                    // know is to open the screen, and "did I leave that running?" is worth being
                    // able to answer from the home grid -- especially for a thing sharing files.
                    tool == Tool.FTP && FtpService.live != null -> "● " + getString(tool.label)

                    // Beta tools say so on the tile rather than in a screen you have to open first.
                    tool.beta -> getString(tool.label) + " " + getString(R.string.beta_suffix)

                    else -> getString(tool.label)
                }
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(getColor(R.color.text_dim))
                gravity = Gravity.CENTER
                setPadding(dp(2), dp(4), dp(2), 0)
            }

            /*
             * A theme's own icon for this tool, if it ships one.
             *
             * Looked up by the tool's id -- ftp.png, notes.png -- so a theme can replace as many or
             * as few as it likes: three icons and seven glyphs is a perfectly good theme, and the
             * ones it does not cover keep the built-in glyph rather than showing a gap.
             *
             * This is the half of "custom icons" that was missing. The loader existed; nothing on
             * the home screen called it.
             */
            val custom = if (skinned) themes.icon(skin, tool.id) else null

            /*
             * Three sources, in order of who should win:
             *   1. the theme's own PNG for this tool, if it ships one
             *   2. the tool's drawn vector, for the ones a font cannot be trusted with
             *   3. the glyph
             */
            val drawn = if (tool.icon != 0) androidx.core.content.ContextCompat.getDrawable(this, tool.icon) else null

            when {
                custom != null -> cell.addView(ImageView(this).apply {
                    setImageDrawable(custom)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    alpha = if (tool.available) 1f else 0.45f
                    layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
                })

                drawn != null -> cell.addView(ImageView(this).apply {
                    setImageDrawable(drawn)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    alpha = if (tool.available) 1f else 0.45f

                    // Tinted to whatever the glyphs are using, so a drawn icon and a typed one sit
                    // at the same weight on every skin.
                    setColorFilter(glyph.currentTextColor)
                    layoutParams = LinearLayout.LayoutParams(dp(26), dp(26))
                })

                else -> cell.addView(glyph)
            }

            cell.addView(label)
            binding.toolGrid.addView(cell)
        }

        // The empty slots behind the apps, for the skins that have them. Added after the tools so
        // they fill the rest of the row and one spare row below, the way the hardware does.
        if (skinned) {
            for (slot in com.abacus.dualscreen.theme.ConsoleSkin.slotFillers(this, skin, tools.size)) {
                binding.toolGrid.addView(slot, GridLayout.LayoutParams().apply {
                    width = 0
                    // Same height as a real tile, so the empty places line up with the filled ones
                    // instead of stepping down half way through a row.
                    height = dp(88)
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                })
            }
        }
    }

    /**
     * With a skin on, the home screen IS the console menu — everything else goes.
     *
     * Recolouring the app's own layout was never going to look like a console menu: the header, the connect
     * card, the address fields and the status row are all still there, and a console home menu has
     * none of that. So when a skin is chosen every sibling of the tool grid is hidden, leaving the
     * status strip and the tiles, which is what the hardware actually shows.
     *
     * Hidden by walking the children rather than by id, because several of those containers have no
     * id at all — and because a layout that grows another card later should not need this function
     * updated to keep working.
     *
     * Everything is restored when the skin is turned off: visibility is the only thing touched.
     */
    private fun applyChrome(skinned: Boolean) {
        val parent = binding.toolGrid.parent as? ViewGroup ?: return

        // The card holding the game picker stays, skin or no skin.
        //
        // The first version hid it along with everything else, which made the home screen look
        // right and took the app's actual purpose with it — there was no way to choose a game or
        // set an address any more. A themed home screen is still this app; it is not a costume
        // worth losing the connect controls over.
        val connectCard = generateSequence(binding.gameSpinner.parent) { (it as? View)?.parent }
            .filterIsInstance<View>()
            .firstOrNull { it.parent === parent }

        for (index in 0 until parent.childCount) {
            val child = parent.getChildAt(index)

            val keep = child === binding.toolGrid ||
                child === connectCard ||
                child.tag == STATUS_BAR_TAG ||
                child.tag == TRAY_TAG

            child.visibility = if (!skinned || keep) View.VISIBLE else View.GONE
        }

        /*
         * Repaint the connect card in the skin's colours.
         *
         * Left alone it keeps the app's dark card style, and a charcoal panel sitting on a light skin's
         * white field is the single most jarring thing on the themed home screen — it reads as a
         * bug, because it looks like one part of the app failed to notice the theme.
         */
        val skin = com.abacus.dualscreen.theme.ThemeStore(this).byId(settings.consoleTheme)
        if (skinned && connectCard != null) {
            connectCard.background = com.abacus.dualscreen.theme.ConsoleSkin.tileFace(this, skin)
            recolourText(connectCard, skin.tileLabel, skin.tileGlyph)

            // Again after the queue drains. The spinner rebuilds its selected view whenever the
            // selection is set, and applyMode() does exactly that later in the same pass -- which
            // threw away the colour set here and left the game name almost invisible on a white
            // card.
            connectCard.post { recolourText(connectCard, skin.tileLabel, skin.tileGlyph) }
        }

        // The scroller's own padding frames the app's cards; a console menu wants the grid to run
        // to the edges.
        (parent as? ViewGroup)?.setPadding(
            if (skinned) 0 else dp(16),
            if (skinned) 0 else dp(16),
            if (skinned) 0 else dp(16),
            if (skinned) 0 else dp(16),
        )
    }

    /**
     * Remove EVERY view carrying a tag, not just the first one.
     *
     * findViewWithTag returns one match. The tray adds two views under the same tag -- a hairline
     * and the strip itself -- so a single removal per rebuild left one behind, and buildTools()
     * runs on every resume: the bars stacked up and marched off the bottom of the screen.
     */
    /**
     * Put the drifting ribbons behind everything, or take them away.
     *
     * Added as the first child of the root so it sits under every card and every tile without any of
     * them needing to know it is there. Removed rather than hidden when another theme is chosen, so
     * a theme that does not use it costs nothing at all.
     */
    private fun applyWaves(wanted: Boolean) {
        removeTagged(binding.root, WAVE_TAG)
        if (!wanted) return

        binding.root.addView(
            com.abacus.dualscreen.theme.WaveView(this).apply { tag = WAVE_TAG },
            0,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    private fun removeTagged(parent: ViewGroup, tag: String) {
        while (true) {
            val found = parent.findViewWithTag<View>(tag) ?: return
            parent.removeView(found)
        }
    }

    /**
     * The strip of round shortcuts along the bottom, as the console-style skins have.
     *
     * Not decoration: on those machines the tray is where the things you actually use live, so it
     * carries real shortcuts — the second screen, the FTP server, and the theme picker — rather
     * than a row of dead icons pretending to be a console.
     *
     * Only drawn for skins that ask for it ([ConsoleTheme.trayBackground] non-zero), which is why
     * the clamshell and crossbar skins do not get one.
     */
    private fun addTray(skin: com.abacus.dualscreen.theme.ConsoleTheme) {
        val parent = binding.toolGrid.parent as? ViewGroup ?: return
        removeTagged(parent, TRAY_TAG)

        if (skin.trayBackground == 0) return

        val tray = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(skin.trayBackground)
            setPadding(dp(8), dp(10), dp(8), dp(10))
            tag = TRAY_TAG
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        val shortcuts = listOf(
            Tool.SECOND_SCREEN to "▣",
            Tool.FTP to "⇅",
            Tool.APPEARANCE to "◈",
        )

        for ((tool, glyph) in shortcuts) {
            tray.addView(TextView(this).apply {
                text = glyph
                gravity = Gravity.CENTER
                setTextColor(skin.trayIcon)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setPadding(dp(14), dp(4), dp(14), dp(4))
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    openTool(tool)
                }
            })
        }

        // A hairline above it, so the strip reads as an edge of the screen rather than as a band
        // of colour that happens to be sitting there.
        parent.addView(View(this).apply {
            setBackgroundColor(skin.tileBorder.takeIf { it != 0 } ?: skin.trayIcon)
            alpha = 0.4f
            tag = TRAY_TAG
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        })

        parent.addView(tray)
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
     * Walk a view tree and recolour its text to suit a skin.
     *
     * Labels take the strong colour, hints and captions the softer one — anything already dimmed by
     * the app's own styling stays relatively dimmer, so the hierarchy survives the repaint instead
     * of flattening into one colour.
     */
    private fun recolourText(view: View, strong: Int, soft: Int) {
        when (view) {
            /*
             * Buttons need their background replaced, not just their text.
             *
             * They carry the app's own accentFill drawable, so on a light skin a dark slab with
             * dark text sat in the middle of a white card — unreadable, and the most obviously
             * broken thing left on screen once the card itself was fixed.
             */
            is Button -> {
                val skin = com.abacus.dualscreen.theme.ThemeStore(this).byId(settings.consoleTheme)
                view.background = com.abacus.dualscreen.theme.ConsoleSkin.tileFace(this, skin)
                view.setTextColor(strong)
            }

            // A Spinner draws its selection through a child TextView, so recolouring the Spinner
            // itself did nothing and the game name came out almost invisible on a white card.
            is android.widget.Spinner ->
                for (i in 0 until view.childCount) recolourText(view.getChildAt(i), strong, soft)

            is TextView -> {
                val faint = view.alpha < 1f ||
                    view.textSize < 13 * resources.displayMetrics.scaledDensity
                view.setTextColor(if (faint) soft else strong)
            }

            is ViewGroup -> for (i in 0 until view.childCount) recolourText(view.getChildAt(i), strong, soft)
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

        removeTagged(parent, STATUS_BAR_TAG)

        val battery = runCatching {
            getSystemService(android.os.BatteryManager::class.java)
                ?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0
        }.getOrDefault(0)

        val connection = settings.hostFor(settings.lastGame).ifEmpty { getString(R.string.app_name) }

        val bar = com.abacus.dualscreen.theme.ConsoleSkin
            .buildStatusBar(this, skin, connection, battery)

        if (bar == null) {
            android.util.Log.w("AynSkin", "no status bar for ${skin.id} (statusBackground is 0)")
            return
        }

        bar.tag = STATUS_BAR_TAG

        // Explicit layout params. Added to a vertical LinearLayout without them, the bar takes the
        // parent's defaults and can end up measuring to nothing.
        bar.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )

        // Top of the screen, not above the grid. A console status strip belongs at the very top --
        // tucked between the connect card and the tiles it read as a stray line of text.
        parent.addView(bar, 0)

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
            // Two tiles, one screen, but each opens on the half it names.
            Tool.VOLUME -> startActivity(ControlsActivity.volume(this))
            Tool.BRIGHTNESS -> startActivity(ControlsActivity.brightness(this))
            Tool.APPEARANCE -> startActivity(Intent(this, AppearanceActivity::class.java))
            Tool.KEYBOARD -> startActivity(Intent(this, KeyboardActivity::class.java))
            Tool.MIRROR -> startActivity(Intent(this, MirrorActivity::class.java))
            Tool.MACROS -> startActivity(Intent(this, MacrosActivity::class.java))
            Tool.FTP -> startActivity(Intent(this, FtpActivity::class.java))
            Tool.STREAM -> startActivity(Intent(this, StreamActivity::class.java))
            Tool.PROFILES -> startActivity(Intent(this, ProfilesActivity::class.java))
            Tool.WIDGETS -> startActivity(Intent(this, WidgetsActivity::class.java))
            Tool.SETTINGS -> startActivity(Intent(this, SettingsActivity::class.java))
            Tool.GAME_CODES -> startActivity(Intent(this, GameCodesActivity::class.java))
            Tool.MACRO_BUILDER -> startActivity(Intent(this, MacroBuilderActivity::class.java))
            Tool.LAYOUTS -> startActivity(Intent(this, LayoutEditorActivity::class.java))
            Tool.SCRIBBLE -> startActivity(Intent(this, ScribbleActivity::class.java))
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

        addToggle(R.string.opt_auto_switch, R.string.opt_auto_switch_detail, settings.autoSwitchGame) {
            settings.autoSwitchGame = it
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

    /**
     * The status line, drawn by the one thing that knows how a status looks.
     *
     * This screen's own [State] is kept because it is what the connection logic speaks; it is
     * translated at the boundary rather than everywhere it is raised.
     */
    private fun say(state: State, message: String) = com.abacus.dualscreen.ui.Feedback.say(
        binding.statusText,
        binding.statusDot,
        when (state) {
            State.IDLE -> com.abacus.dualscreen.ui.Feedback.State.IDLE
            State.BUSY -> com.abacus.dualscreen.ui.Feedback.State.BUSY
            State.OK -> com.abacus.dualscreen.ui.Feedback.State.OK
            State.BAD -> com.abacus.dualscreen.ui.Feedback.State.BAD
        },
        message,
    )

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

        /** Same idea for the bottom shortcut strip. */
        const val TRAY_TAG = "console-tray"

        /** The hidden sequence's progress row. */
        const val STEPS_TAG = "secret-steps"

        /** How many correct presses before the row appears. Enough not to give the secret away. */
        const val REVEAL_AFTER = 4

        /** One glyph per step, in order: directions, then the two finishing buttons. */
        val STEP_GLYPHS = listOf("↑", "↑", "↓", "↓", "←", "→", "←", "→", "B", "A")

        /** The Wild theme's animated layer, so it is replaced rather than stacked. */
        const val WAVE_TAG = "console-waves"
    }
}
