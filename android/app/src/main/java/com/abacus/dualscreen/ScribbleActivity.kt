package com.abacus.dualscreen

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.abacus.dualscreen.databinding.ActivityScribbleBinding
import com.abacus.dualscreen.scribble.Scribble
import com.abacus.dualscreen.scribble.ScribbleNet
import com.abacus.dualscreen.scribble.ScribbleStore
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Draw something, type something, send it to everyone else on the Wi-Fi.
 *
 * No account, no server, no address to type: the app broadcasts its presence and picks up everybody
 * else doing the same, so two handhelds on one network find each other and start talking. Four rooms
 * exist so two conversations can happen without shouting over each other.
 *
 * It also works with nobody there. A message with no peers still lands in the room's log, which
 * makes this a drawing scrapbook when you are alone and a chat when you are not — and the log is
 * files on disk, so the doodles are PNGs you can pull off over FTP.
 */
class ScribbleActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScribbleBinding
    private lateinit var settings: Settings
    private lateinit var store: ScribbleStore
    private var net: ScribbleNet? = null

    private var room = "A"

    /** Redraws the peer line; the count changes on a background thread as devices come and go. */
    private val peerTick = object : Runnable {
        override fun run() {
            showPeers()
            binding.root.postDelayed(this, 2_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScribbleBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = Settings(this)
        store = ScribbleStore(this)
        room = settings.scribbleRoom

        binding.backButton.setOnClickListener { finish() }
        binding.nameButton.setOnClickListener { askName() }
        binding.sendButton.setOnClickListener { send() }

        buildRoomBar()
        buildPenBar()

        binding.doodle.penColor = settings.scribbleInk
        binding.doodle.onChanged = { showSendState() }

        showSendState()
        Appearance.apply(this, binding.root, settings, binding.backgroundImage)
    }

    override fun onResume() {
        super.onResume()
        showRoom()
        binding.root.post(peerTick)

        // Started here rather than in onCreate so the sockets are closed while the screen is away:
        // a chat nobody is looking at has no business holding a multicast lock and a listening port.
        net = ScribbleNet(
            context = this,
            name = settings.scribbleName.ifBlank { defaultName() },
            onMessage = { who, inRoom, text, image -> received(who, inRoom, text, image) },
            onPeersChanged = { runOnUiThread { showPeers() } },
        ).also { it.start() }

        showPeers()
    }

    override fun onPause() {
        super.onPause()
        binding.root.removeCallbacks(peerTick)
        net?.stop()
        net = null
    }

    // ── the bars ────────────────────────────────────────────────────────────

    private fun buildRoomBar() {
        binding.roomBar.removeAllViews()

        for (name in ScribbleStore.ROOMS) {
            binding.roomBar.addView(Button(this).apply {
                text = name
                isAllCaps = false
                tag = "room:$name"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(dp(14), dp(6), dp(14), dp(6))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(6) }

                setOnClickListener {
                    room = name
                    settings.scribbleRoom = name
                    showRoom()
                }
            })
        }
    }

    /** Ink colours and the two tools. Kept to one row so the pad below keeps its height. */
    private fun buildPenBar() {
        binding.penBar.removeAllViews()

        for (ink in INKS) {
            binding.penBar.addView(View(this).apply {
                background = Appearance.panel(
                    this@ScribbleActivity, settings, ink, getColor(R.color.edge)
                )
                layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(6) }
                contentDescription = getString(R.string.scribble_ink)

                setOnClickListener {
                    binding.doodle.penColor = ink
                    binding.doodle.erasing = false
                    settings.scribbleInk = ink
                    markPen()
                }
            })
        }

        binding.penBar.addView(smallButton(R.string.scribble_thin) {
            binding.doodle.penWidth = 3f
            binding.doodle.erasing = false
            markPen()
        })

        binding.penBar.addView(smallButton(R.string.scribble_thick) {
            binding.doodle.penWidth = 12f
            binding.doodle.erasing = false
            markPen()
        })

        binding.penBar.addView(smallButton(R.string.scribble_erase) {
            binding.doodle.erasing = true
            markPen()
        })

        binding.penBar.addView(smallButton(R.string.scribble_undo) { binding.doodle.undo() })
        binding.penBar.addView(smallButton(R.string.scribble_clear) { binding.doodle.clearAll() })
    }

    private fun smallButton(label: Int, onClick: () -> Unit) = Button(this).apply {
        text = getString(label)
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setTextColor(getColor(R.color.text_dim))
        setPadding(dp(8), dp(4), dp(8), dp(4))
        background = Appearance.panel(
            this@ScribbleActivity, settings, getColor(R.color.card), getColor(R.color.edge)
        )
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { marginEnd = dp(4) }
        setOnClickListener { onClick() }
    }

    /** The eraser is the only mode you can be stuck in, so it is the only one that needs marking. */
    private fun markPen() {
        binding.doodle.alpha = if (binding.doodle.erasing) 0.85f else 1f
    }

    // ── rooms and messages ──────────────────────────────────────────────────

    private fun showRoom() {
        for (name in ScribbleStore.ROOMS) {
            val button = binding.roomBar.findViewWithTag<Button>("room:$name") ?: continue
            val here = name == room

            button.background = Appearance.panel(
                this, settings,
                if (here) getColor(R.color.card_hi) else getColor(R.color.card),
                if (here) Appearance.accentOf(settings) else getColor(R.color.edge),
            )
            button.setTextColor(
                if (here) Appearance.accentOf(settings) else getColor(R.color.text_faint)
            )
        }

        buildMessages()
    }

    private fun buildMessages() {
        binding.messageList.removeAllViews()
        val messages = store.all(room)

        if (messages.isEmpty()) {
            binding.messageList.addView(TextView(this).apply {
                text = getString(R.string.scribble_empty)
                setTextColor(getColor(R.color.text_faint))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, dp(10), 0, dp(10))
            })
            return
        }

        for (message in messages) binding.messageList.addView(bubble(message))
        scrollToEnd()
    }

    /**
     * One message.
     *
     * Yours sit to the right and everyone else's to the left, which is the cheapest possible way to
     * make a shared log readable at a glance and costs one gravity flag.
     */
    private fun bubble(message: Scribble): View {
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (message.mine) Gravity.END else Gravity.START
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = Appearance.panel(
                this@ScribbleActivity, settings,
                if (message.mine) getColor(R.color.card_hi) else getColor(R.color.card),
                if (message.mine) Appearance.accentOf(settings) else getColor(R.color.edge),
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        card.addView(TextView(this).apply {
            text = "${message.who} · ${CLOCK.format(Date(message.at))}"
            setTextColor(getColor(R.color.text_faint))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        })

        store.image(room, message)?.let { bitmap ->
            card.addView(ImageView(this).apply {
                setImageBitmap(bitmap)
                adjustViewBounds = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(4) }
                contentDescription = getString(R.string.scribble_doodle)
            })
        }

        if (message.text.isNotBlank()) {
            card.addView(TextView(this).apply {
                text = message.text
                setTextColor(getColor(R.color.text))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(0, dp(3), 0, 0)
            })
        }

        holder.addView(card)
        return holder
    }

    private fun scrollToEnd() {
        binding.messageScroll.post { binding.messageScroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ── sending and receiving ───────────────────────────────────────────────

    private fun send() {
        val text = binding.messageField.text?.toString().orEmpty().trim()
        val doodle = binding.doodle.toBitmap()

        if (text.isEmpty() && doodle == null) return

        val who = settings.scribbleName.ifBlank { defaultName() }
        store.append(room, who, text, doodle, mine = true)
        net?.send(room, text, doodle?.let { png(it) })

        binding.messageField.setText("")
        binding.doodle.clearAll()
        buildMessages()
        showSendState()
    }

    /**
     * A message from somebody else.
     *
     * Arrives on a socket thread, so everything here hops to the UI thread first. A message for a
     * room you are not looking at is still written down — walking into room B and finding it empty
     * because you were in room A when it arrived would be a strange kind of loss.
     */
    private fun received(who: String, inRoom: String, text: String, image: ByteArray?) {
        val bitmap = image?.let {
            runCatching { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull()
        }

        val target = inRoom.ifBlank { "A" }
        store.append(target, who, text, bitmap, mine = false)

        runOnUiThread {
            if (target == room) buildMessages()
            else android.widget.Toast.makeText(
                this, getString(R.string.scribble_elsewhere, who, target),
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun png(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    private fun showSendState() {
        val hasText = !binding.messageField.text?.toString().isNullOrBlank()
        binding.sendButton.isEnabled = hasText || !binding.doodle.isEmpty
    }

    private fun showPeers() {
        val peers = net?.peers().orEmpty()

        binding.peersText.text = when {
            peers.isEmpty() -> getString(R.string.scribble_alone)
            peers.size == 1 -> getString(R.string.scribble_one_peer, peers[0].name)
            else -> getString(R.string.scribble_peers, peers.size)
        }
    }

    // ── who you are ─────────────────────────────────────────────────────────

    private fun askName() {
        val field = EditText(this).apply {
            setText(settings.scribbleName.ifBlank { defaultName() })
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.scribble_name)
            .setView(field)
            .setPositiveButton(R.string.notes_save) { _, _ ->
                val name = field.text.toString().trim().take(24)
                settings.scribbleName = name
                net?.name = name.ifBlank { defaultName() }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /**
     * A name before anybody has chosen one.
     *
     * The device model, because two handhelds sitting side by side is the case this feature exists
     * for, and "Thor" beside "Pixel 7" tells you which is which without either owner typing
     * anything.
     */
    private fun defaultName(): String = android.os.Build.MODEL?.takeIf { it.isNotBlank() } ?: "Player"

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        val CLOCK = SimpleDateFormat("HH:mm", Locale.getDefault())

        /** Ink colours. Bright ones, because they are drawn thin over a dark pad. */
        val INKS = intArrayOf(
            Color.WHITE,
            Color.parseColor("#6EC1FF"),
            Color.parseColor("#4BE08B"),
            Color.parseColor("#FFC24D"),
            Color.parseColor("#FF6B6B"),
            Color.parseColor("#D08BFF"),
        )
    }
}
