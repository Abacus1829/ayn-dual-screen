package com.abacus.dualscreen.macro

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shareable layout format.
 *
 * Worth testing for the same reason the DER encoder is: this is the one thing in the app that other
 * people's files flow into. Everything here is either exactly right or it is a crash on somebody
 * else's device holding a file we cannot see.
 *
 * [MacroAction] and [MacroScript] are plain Kotlin plus org.json, so they run on the desktop JVM.
 * [com.abacus.dualscreen.Macro] pulls in android.view.KeyEvent, so the layout half of the format is
 * exercised through the JSON it produces rather than through the data class.
 */
class LayoutPackageTest {

    // ── macro serialisation ─────────────────────────────────────────────────

    @Test
    fun `a macro survives a round trip`() {
        val original = MacroScript(
            id = "m1",
            name = "Open chest",
            actions = mutableListOf(
                MacroAction(MacroAction.Kind.KEY_DOWN, value = "SHIFT"),
                MacroAction(MacroAction.Kind.DELAY, number = 250),
                MacroAction(MacroAction.Kind.KEY_PRESS, value = "E"),
                MacroAction(MacroAction.Kind.KEY_UP, value = "SHIFT"),
                MacroAction(MacroAction.Kind.GAME, value = "use", number = 3, number2 = -1),
                MacroAction(MacroAction.Kind.TAP, number = 500, number2 = 750),
            ),
        )

        val copy = MacroScript.fromJson(JSONObject(original.toJson().toString()))

        assertEquals(original.id, copy.id)
        assertEquals(original.name, copy.name)
        assertEquals(original.actions.size, copy.actions.size)

        original.actions.zip(copy.actions).forEach { (a, b) ->
            assertEquals(a.kind, b.kind)
            assertEquals(a.value, b.value)
            assertEquals(a.number, b.number)
            assertEquals(a.number2, b.number2)
        }
    }

    @Test
    fun `an unknown step kind falls back rather than throwing`() {
        val json = JSONObject("""{"kind":"teleport","value":"X","number":1,"number2":2}""")
        val action = MacroAction.fromJson(json)

        // Falling back keeps the rest of the macro readable; the alternative is losing the lot
        // because one step came from a newer build.
        assertEquals(MacroAction.Kind.KEY_PRESS, action.kind)
        assertEquals("X", action.value)
    }

    @Test
    fun `a macro with unreadable steps keeps its name and drops the steps`() {
        val json = JSONObject("""{"id":"m2","name":"Half broken","actions":[1,2,3]}""")
        val script = MacroScript.fromJson(json)

        assertEquals("Half broken", script.name)
        assertEquals(0, script.actions.size)
    }

    @Test
    fun `a nameless macro is given one`() {
        val script = MacroScript.fromJson(JSONObject("""{"id":"m3","actions":[]}"""))
        assertTrue(script.name.isNotBlank())
    }

    // ── layout packages ─────────────────────────────────────────────────────

    /** A minimal package, built as JSON so the test does not need the Android Macro class. */
    private fun packageJson(
        format: Int = LayoutPackage.FORMAT,
        kind: String = LayoutPackage.KIND,
        buttons: String = """[{"id":"b1","label":"GG","kind":"script","payload":"m1","x":0.1,"y":0.2,"size":64}]""",
        scripts: String = """[{"id":"m1","name":"Wave","actions":[{"kind":"text","value":"hi"}]}]""",
    ) = """
        {
          "format": $format,
          "kind": "$kind",
          "layout": { "name": "My pad", "macros": $buttons },
          "scripts": $scripts
        }
    """.trimIndent()

    @Test
    fun `a valid package imports with its buttons and macros`() {
        val result = LayoutPackage.import(packageJson())
        assertTrue(result is LayoutImport.Ok)

        val ok = result as LayoutImport.Ok
        assertEquals("My pad", ok.layout.name)
        assertEquals(1, ok.layout.macros.size)
        assertEquals(1, ok.scripts.size)
        assertEquals("Wave", ok.scripts[0].name)
    }

    @Test
    fun `text that is not json is refused`() {
        val result = LayoutPackage.import("not json at all")
        assertEquals(LayoutError.NOT_JSON, (result as LayoutImport.Failed).reason)
    }

    @Test
    fun `a file from another feature is refused rather than half-read`() {
        // Shaped like the connection-profiles export, which lands in the same folder.
        val profiles = """{"format":1,"app":"AynDualScreen","profiles":[{"host":"10.0.0.5","port":27301}]}"""
        val result = LayoutPackage.import(profiles)

        assertEquals(LayoutError.NOT_A_LAYOUT, (result as LayoutImport.Failed).reason)
    }

    @Test
    fun `a newer format is refused`() {
        val result = LayoutPackage.import(packageJson(format = LayoutPackage.FORMAT + 1))
        assertEquals(LayoutError.TOO_NEW, (result as LayoutImport.Failed).reason)
    }

    @Test
    fun `an older format still imports`() {
        // The point of the version field: today's build must keep reading yesterday's files.
        val result = LayoutPackage.import(packageJson(format = 1))
        assertTrue(result is LayoutImport.Ok)
    }

    @Test
    fun `a layout with no buttons is refused`() {
        val result = LayoutPackage.import(packageJson(buttons = "[]"))
        assertEquals(LayoutError.NO_BUTTONS, (result as LayoutImport.Failed).reason)
    }

    @Test
    fun `buttons from a differently shaped screen are clamped onto this one`() {
        val offscreen =
            """[{"id":"b1","label":"X","kind":"key","payload":"ENTER","x":4.5,"y":-2.0,"size":900}]"""
        val result = LayoutPackage.import(packageJson(buttons = offscreen))

        val button = (result as LayoutImport.Ok).layout.macros[0]
        assertTrue("x was $button.x", button.x in 0f..0.98f)
        assertTrue("y was $button.y", button.y in 0f..0.98f)
        assertTrue("size was ${button.size}", button.size in 44..104)
    }

    @Test
    fun `a package carries only the macros its buttons use`() {
        val used = MacroScript("m1", "Used", mutableListOf(MacroAction(MacroAction.Kind.TEXT, "hi")))
        val unused = MacroScript("m2", "Unused", mutableListOf())

        val layout = com.abacus.dualscreen.MacroProfile.fromJson(
            JSONObject("""{"name":"Pad","macros":[{"id":"b1","label":"U","kind":"script","payload":"m1","x":0.1,"y":0.1,"size":64}]}""")
        )

        val json = JSONObject(LayoutPackage.export(layout, listOf(used, unused)))
        val scripts = json.getJSONArray("scripts")

        assertEquals(1, scripts.length())
        assertEquals("m1", scripts.getJSONObject(0).getString("id"))
    }

    @Test
    fun `an exported layout imports back unchanged`() {
        val original = LayoutPackage.import(packageJson()) as LayoutImport.Ok
        val again = LayoutPackage.import(
            LayoutPackage.export(original.layout, original.scripts)
        )

        val second = again as LayoutImport.Ok
        assertEquals(original.layout.name, second.layout.name)
        assertEquals(original.layout.macros.size, second.layout.macros.size)
        assertEquals(original.scripts.size, second.scripts.size)
    }
}

/**
 * Remote control profiles: the parts that go through JSON.
 *
 * Backward compatibility is the point of most of these. Layouts saved by every earlier build are
 * still out there, in preferences and in exported files, and they must keep loading unchanged.
 */
class ControlProfileTest {

    @Test
    fun `a button saved before gestures existed still loads`() {
        val old = JSONObject(
            """{"id":"b1","label":"GG","kind":"text","payload":"hi","x":0.1,"y":0.2,"size":64}"""
        )
        val macro = com.abacus.dualscreen.Macro.fromJson(old)

        assertEquals("GG", macro.label)
        assertTrue(macro.bindings.isEmpty())
        assertEquals(false, macro.toggle)
    }

    @Test
    fun `a button with no gestures serialises to what it always did`() {
        val macro = com.abacus.dualscreen.Macro(
            id = "b1", label = "GG", kind = com.abacus.dualscreen.Macro.Kind.TEXT,
            payload = "hi", x = 0.1f, y = 0.2f, size = 64,
        )

        val json = macro.toJson()
        assertTrue("bindings should be absent", !json.has("bindings"))
        assertTrue("toggle should be absent", !json.has("toggle"))
    }

    @Test
    fun `gestures and toggle survive a round trip`() {
        val macro = com.abacus.dualscreen.Macro(
            id = "b1", label = "Run", kind = com.abacus.dualscreen.Macro.Kind.KEY,
            payload = "SHIFT", x = 0.1f, y = 0.2f, size = 64,
            bindings = mutableMapOf(
                com.abacus.dualscreen.Macro.Trigger.LONG_PRESS.id to "m1",
                com.abacus.dualscreen.Macro.Trigger.DOUBLE_TAP.id to "m2",
            ),
            toggle = true,
        )

        val copy = com.abacus.dualscreen.Macro.fromJson(JSONObject(macro.toJson().toString()))

        assertEquals("m1", copy.bindings[com.abacus.dualscreen.Macro.Trigger.LONG_PRESS.id])
        assertEquals("m2", copy.bindings[com.abacus.dualscreen.Macro.Trigger.DOUBLE_TAP.id])
        assertEquals(true, copy.toggle)
    }

    @Test
    fun `a binding for a trigger this build does not know is ignored, not fatal`() {
        // What an older build sees when a newer one adds a swipe. It must load the button.
        val json = JSONObject(
            """{"id":"b1","label":"X","kind":"key","payload":"ENTER","x":0.1,"y":0.1,"size":64,
                "bindings":{"swipeup":"m9","long":"m1"}}"""
        )
        val macro = com.abacus.dualscreen.Macro.fromJson(json)

        assertEquals("m1", macro.bindings["long"])
        assertEquals("m9", macro.bindings["swipeup"])
        assertEquals(null, com.abacus.dualscreen.Macro.Trigger.byId("swipeup"))
    }

    @Test
    fun `a layout keeps which game it is for`() {
        val json = JSONObject(
            """{"name":"Farm pad","gameId":"stardew","macros":[
                {"id":"b1","label":"X","kind":"key","payload":"ENTER","x":0.1,"y":0.1,"size":64}]}"""
        )
        val layout = com.abacus.dualscreen.MacroProfile.fromJson(json)

        assertEquals("stardew", layout.gameId)
        assertEquals("stardew", com.abacus.dualscreen.MacroProfile.fromJson(
            JSONObject(layout.toJson().toString())
        ).gameId)
    }

    @Test
    fun `a layout saved before per-game assignment is a general one`() {
        val json = JSONObject("""{"name":"Old pad","macros":[]}""")
        assertEquals("", com.abacus.dualscreen.MacroProfile.fromJson(json).gameId)
    }
}
