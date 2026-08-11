package com.abacus.dualscreen

/**
 * What the on-screen keyboard is made of.
 *
 * Kept as plain data rather than the framework's XML keyboard format: that format is deprecated, can't
 * express the split the Thor needs, and gives no control over how a key is drawn. A row knows where its
 * own split falls, so the same definition serves both the split and the full layout.
 */
object KeyboardLayouts {

    /** Non-printing keys. Positive values only, so a key either types [output] or means one of these. */
    object Code {
        const val NONE = 0
        const val SHIFT = 1
        const val DELETE = 2
        const val ENTER = 3
        const val SPACE = 4
        const val SYMBOLS = 5
        const val LETTERS = 6
        const val HIDE = 7
        const val LEFT = 8
        const val RIGHT = 9
    }

    /**
     * One key. [weight] is its share of the row, so a space bar is just a wide key rather than a special
     * case in the layout code.
     */
    data class Key(
        val label: String,
        val output: String? = null,
        val code: Int = Code.NONE,
        val weight: Float = 1f
    )

    /** [splitAfter] is how many keys go on the left thumb's side when the split layout is on. */
    data class Row(val keys: List<Key>, val splitAfter: Int)

    private fun letters(text: String) = text.map { Key(it.toString(), it.toString()) }

    private val shift = Key("⇧", code = Code.SHIFT, weight = 1.5f)
    private val delete = Key("⌫", code = Code.DELETE, weight = 1.5f)
    private val enter = Key("⏎", code = Code.ENTER, weight = 2f)
    private val space = Key("", code = Code.SPACE, weight = 4f)
    private val hide = Key("⌄", code = Code.HIDE)
    private val left = Key("◀", code = Code.LEFT)
    private val right = Key("▶", code = Code.RIGHT)

    /** Lower case. Upper case is the same layout with the labels and output cased up. */
    val LETTERS: List<Row> = listOf(
        Row(letters("qwertyuiop"), 5),
        Row(letters("asdfghjkl"), 5),
        Row(listOf(shift) + letters("zxcvbnm") + listOf(delete), 5),
        Row(
            listOf(
                Key("?123", code = Code.SYMBOLS, weight = 1.6f),
                Key(",", ","),
                space,
                Key(".", "."),
                enter
            ),
            3
        ),
        Row(listOf(hide, left, right, Key("-", "-"), Key("_", "_"), Key("/", "/"), Key(":", ":")), 3)
    )

    val SYMBOLS: List<Row> = listOf(
        Row(letters("1234567890"), 5),
        Row(letters("!@#\$%^&*()"), 5),
        Row(
            listOf(Key("=\\<", code = Code.SHIFT, weight = 1.5f)) +
                letters("-_=+[]{}") +
                listOf(delete),
            5
        ),
        Row(
            listOf(
                Key("ABC", code = Code.LETTERS, weight = 1.6f),
                Key(",", ","),
                space,
                Key(".", "."),
                enter
            ),
            3
        ),
        Row(listOf(hide, left, right, Key("\\", "\\"), Key("|", "|"), Key("/", "/"), Key("~", "~")), 3)
    )

    /** The third layer, reached from the shift key while in symbols. */
    val MORE: List<Row> = listOf(
        Row(letters("~`|•√π÷×¶∆"), 5),
        Row(letters("£¢€¥^°={}"), 5),
        Row(
            listOf(Key("?123", code = Code.SHIFT, weight = 1.5f)) +
                letters("\\©®™%[]") +
                listOf(delete),
            4
        ),
        Row(
            listOf(
                Key("ABC", code = Code.LETTERS, weight = 1.6f),
                Key("<", "<"),
                space,
                Key(">", ">"),
                enter
            ),
            3
        ),
        Row(listOf(hide, left, right, Key(";", ";"), Key("\"", "\""), Key("'", "'"), Key("?", "?")), 3)
    )

    /** Upper-case view of the letter rows. Only printing keys change. */
    fun uppercase(rows: List<Row>): List<Row> = rows.map { row ->
        row.copy(keys = row.keys.map { key ->
            if (key.output != null && key.output.length == 1 && key.output[0].isLetter())
                key.copy(label = key.label.uppercase(), output = key.output.uppercase())
            else key
        })
    }
}
