package org.polyfrost.evergreenhud.test

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.polyfrost.evergreenhud.client.script.IconSource
import org.polyfrost.evergreenhud.client.script.ScriptException
import org.polyfrost.evergreenhud.client.script.ScriptFunction
import org.polyfrost.evergreenhud.client.script.ScriptLibrary
import org.polyfrost.evergreenhud.client.script.ScriptMemory
import org.polyfrost.evergreenhud.client.script.ScriptOutput
import org.polyfrost.evergreenhud.client.script.ScriptSegment
import org.polyfrost.evergreenhud.client.script.ScriptStyle
import org.polyfrost.evergreenhud.client.script.Value

class ScriptEngineTest {
    private data class TestIcon(val name: String) : IconSource

    private fun effect(name: String, level: Int, seconds: Double, harmful: Boolean = false) = Value.Obj(
        "effect",
        mapOf(
            "name" to Value.Text(name),
            "id" to Value.Text(name.lowercase()),
            "level" to Value.Num(level.toDouble()),
            "duration" to Value.Num(seconds),
            "harmful" to Value.of(harmful),
            "color" to Value.Color(0xFF7CAFC6.toInt()),
            "icon" to Value.Icon(TestIcon(name)),
        ),
        Value.Text(name),
    )

    private val globals = mapOf(
        "x" to Value.Num(-38.4712),
        "y" to Value.Num(72.0),
        "z" to Value.Num(0.5),
        "health" to Value.Num(15.0),
        "max_health" to Value.Num(20.0),
        "sprinting" to Value.TRUE,
        "name" to Value.Text("Steve"),
        "server" to Value.Text(""),
        "nothing" to Value.Null,
        "head" to Value.Icon(TestIcon("head")),
        "effects" to Value.Items(listOf(effect("Speed", 2, 90.0), effect("Poison", 1, 5.0, harmful = true))),
        "no_effects" to Value.Items(emptyList()),
    )

    private val library = ScriptLibrary(
        globals.keys,
        mapOf("double" to ScriptFunction("double", 1, 1) { Value.Num(it.number(0) * 2) }),
    )

    private fun run(source: String): ScriptOutput = library.run(library.compile(source)) { globals[it] }

    private fun ScriptOutput.text(): String = lines.joinToString("\n") { line ->
        line.joinToString("") {
            when (it) {
                is ScriptSegment.Text -> it.text
                is ScriptSegment.Icon -> "<${(it.source as TestIcon).name}${if (it.lines != 1f) "x${it.lines.toInt()}" else ""}>"
                ScriptSegment.Column -> "|"
            }
        }
    }

    private fun render(source: String) = run(source).text()

    private fun compileError(source: String): String =
        assertThrows<ScriptException> { library.compile(source) }.message!!

    @Test
    fun plainText() {
        assertEquals("Hello world", render("Hello world"))
        assertEquals("a\nb", render("a\nb"))
    }

    @Test
    fun formattingCodes() {
        val line = run("&l&0[X] &r&f{x | round}").lines.single()
        val label = line[0] as ScriptSegment.Text
        val value = line[1] as ScriptSegment.Text
        assertEquals("[X] ", label.text)
        assertTrue(label.style.bold, "a color code should not clear bold")
        assertEquals(0xFF000000.toInt(), label.style.color)
        assertEquals("-38", value.text)
        assertFalse(value.style.bold)
        assertEquals(0xFFFFFFFF.toInt(), value.style.color)
    }

    @Test
    fun sectionSignAndHexColors() {
        val line = run("§cRed &#12AB34Hex").lines.single()
        assertEquals(0xFFFF5555.toInt(), (line[0] as ScriptSegment.Text).style.color)
        assertEquals(0xFF12AB34.toInt(), (line[1] as ScriptSegment.Text).style.color)
    }

    @Test
    fun styleResetsEachLine() {
        val lines = run("&cred\nplain").lines
        assertEquals(ScriptStyle.DEFAULT, (lines[1][0] as ScriptSegment.Text).style)
    }

    @Test
    fun escapes() {
        assertEquals("{x} &a \\ Tom & Jerry", render("\\{x\\} \\&a \\\\ Tom & Jerry"))
        assertEquals("a|b", render("a\\tb"))
    }

    @Test
    fun codesInsideValuesAreApplied() {
        val line = run("{sprinting ? \"&aRunning\" : \"&7Walking\"}").lines.single()
        val segment = line.single() as ScriptSegment.Text
        assertEquals("Running", segment.text)
        assertEquals(0xFF55FF55.toInt(), segment.style.color)
    }

    @Test
    fun numberFormatting() {
        assertEquals("-38.47 72 0.5", render("{x} {y} {z}"))
        assertEquals("-38.5 -38.47 -38.471 -38.47", render("{round(x, 1)} {round(x, 2)} {x | round(3)} {fixed(x, 2)}"))
        assertEquals("72.00 +72 -38", render("{fixed(y, 2)} {signed(y)} {floor(x) + 1}"))
        assertEquals("0", render("{round(-0.001, 2)}"))
        assertEquals("0.3333333333333333 0.333333333333333 -38.4712", render("{exact(1 / 3)} {fixed(1 / 3, 15)} {x | exact}"))
        assertEquals("-39", render("{floor(x)}"))
    }

    @Test
    fun fixedDecimalsKeepTrailingZeros() {
        val fixed = ScriptLibrary(setOf("y", "speed"), emptyMap())
        val globals = mapOf("y" to Value.Num(72.0, 2, fixed = true), "speed" to Value.Num(5.6, 2, fixed = true))
        fun render(source: String) = fixed.run(fixed.compile(source)) { globals[it] }.text()
        assertEquals("72.00 5.60 73.00 -72.00", render("{y} {speed} {y + 1} {-y}"))
        assertEquals("72 5.6", render("{round(y, 1)} {speed | round(1)}"))
        assertEquals("72.00", render("{y | smooth}"))
    }

    @Test
    fun arithmeticAndLogic() {
        assertEquals("7 1 2.5 1", render("{1 + 2 * 3} {7 % 3} {5 / 2} {-7 % 4 == 1 ? 1 : 0}"))
        assertEquals("75", render("{health / max_health * 100}"))
        assertEquals("Steve fallback", render("{name or \"x\"} {server or \"fallback\"}"))
        assertEquals("true false", render("{health > 10 and sprinting} {not sprinting}"))
        assertEquals("HP: 15", render("{\"HP: \" + health}"))
        assertEquals("yes no", render("{\"eve\" in name ? \"yes\" : \"no\"} {\"x\" in [\"a\", \"b\"] ? \"yes\" : \"no\"}"))
    }

    @Test
    fun pipesAndFunctions() {
        assertEquals("STEVE", render("{name | upper}"))
        assertEquals("30", render("{health | double}"))
        assertEquals("Ste...", render("{name | truncate(3)}"))
        assertEquals("007", render("{pad_left(7, 3, \"0\")}"))
        assertEquals("IV 1:30 1:01:01", render("{roman(4)} {duration(90)} {duration(3661)}"))
        assertEquals("b", render("{switch(2, 1, \"a\", 2, \"b\", \"c\")}"))
        assertEquals("c", render("{switch(9, 1, \"a\", 2, \"b\", \"c\")}"))
        assertEquals("1, 2, 3", render("{join(range(3))}"))
        assertEquals("Poison Speed", render("{join(sort(effects, \"duration\"), \" \")}"))
        assertEquals("Poison", render("{join(where(effects, \"harmful\"))}"))
        assertEquals("|||||", render("{repeat(\"|\", 5)}"))
    }

    @Test
    fun ifBlocksOnTheirOwnLines() {
        val source = """
            top
            {if health < 10}
            low
            {elif health < 18}
            medium
            {else}
            high
            {end}
            bottom
        """.trimIndent()
        assertEquals("top\nmedium\nbottom", render(source))
        assertEquals("a", render("{if sprinting}a{else}b{end}"))
        assertEquals("b", render("{if not sprinting}a{else if true}b{end}"))
    }

    @Test
    fun loops() {
        val source = """
            {for e in effects}
            {e.name} {e.level | roman}
            {e.duration | duration}
            {end}
        """.trimIndent()
        assertEquals("Speed II\n1:30\nPoison I\n0:05", render(source))
        assertEquals("Speed, Poison", render("{for e in effects}{e}{if not loop.last}, {end}{end}"))
        assertEquals("1:Speed 2:Poison", render("{for e in effects}{loop.index}:{e.name}{if not loop.last} {end}{end}"))
    }

    @Test
    fun loopsCanSkipItems() {
        val source = """
            {for e in effects}
            {if e.id not in ["poison", "night_vision"]}
            {e.name} {e.level}
            {end}
            {end}
        """.trimIndent()
        assertEquals("Speed 2", render(source))
    }

    @Test
    fun blankLinesInLoopsAreKept() {
        val source = """
            {for e in effects}
            {e.name}

            {end}
        """.trimIndent()
        assertEquals("Speed\n\nPoison", render(source))
    }

    @Test
    fun emptyDynamicLinesAreHidden() {
        assertEquals("a\nb", render("a\n{server}\n{if false}x{end}\nb"))
        assertEquals("", render("{for e in no_effects}\n{e.name}\n{end}"))
        assertEquals("a\n\nb", render("a\n\nb"))
    }

    @Test
    fun setVariables() {
        assertEquals("30", render("{set total = health * 2}\n{total}"))
        val source = """
            {set total = 0}
            {for e in effects}
            {set total = total + e.duration}
            {end}
            {total}
        """.trimIndent()
        assertEquals("95", render(source))
    }

    @Test
    fun icons() {
        assertEquals("<head> Steve <headx2>", render("{head} {name} {head(2)}"))
        assertEquals("<Speed> Speed\n<Poison> Poison", render("{for e in effects}\n{e.icon} {e.name}\n{end}"))
        val icon = run("{size(2)}{head}").lines.single().single() as ScriptSegment.Icon
        assertEquals(2f, icon.size)
        val barred = run("{head(2) | bar}").lines.single().single() as ScriptSegment.Icon
        assertEquals(2f, barred.lines)
        assertEquals(true, barred.bar)
        assertEquals(false, icon.bar)
    }

    @Test
    fun columns() {
        assertEquals("a|b\nc|d", render("a{tab}b\nc\\td"))
    }

    @Test
    fun colors() {
        val segment = { source: String -> run(source).lines.single().single() as ScriptSegment.Text }
        assertEquals(0xFFFF5555.toInt(), segment("{color(\"red\")}x").style.color)
        assertEquals(0xFFFF5555.toInt(), segment("{color(\"&c\")}x").style.color)
        assertEquals(0xFF7CAFC6.toInt(), segment("{effects[0].color}x").style.color)
        assertEquals(0xFFFFFF00.toInt(), segment("{gradient(10, 0, 20, \"#FF0000\", \"#FFFF00\", \"#00FF00\")}x").style.color)
        assertTrue(segment("{rainbow()}x").style.chroma)
        assertTrue(run("{rainbow()}x").usesChroma)
        assertEquals(2f, segment("{size(2)}x").style.size)
    }

    @Test
    fun nullSafety() {
        assertEquals("[]", "[" + render("{nothing.name}{nothing[0]}") + "]")
        assertEquals("Poison", render("{effects[-1].name}"))
        assertEquals("", render("{effects[5].name}"))
    }

    @Test
    fun runtimeErrorsAreShownInline() {
        assertEquals("a [Can't divide by zero] b", render("a {1 / 0} b"))
        assertEquals("[effect has no \"nope\"]", render("{effects[0].nope}"))
        assertEquals("[effect has no \"nmae\", did you mean \"name\"?]", render("{effects[0].nmae}"))
        assertTrue(render("{join(range(5000))}").contains("at most"))
    }

    @Test
    fun compileErrors() {
        assertTrue(compileError("{helth}").contains("did you mean \"health\""))
        assertTrue(compileError("{if sprinting}a").contains("missing its {end}"))
        assertTrue(compileError("{end}").contains("without a matching"))
        assertTrue(compileError("{x").contains("Missing }"))
        assertTrue(compileError("{round()}").contains("takes 1 to 2 arguments"))
        assertTrue(compileError("{upper}").contains("is a function"))
        assertTrue(compileError("{loop.index}").contains("only exists inside"))
        assertTrue(compileError("{1 +}").contains("ended too early"))
        assertEquals(3, assertThrows<ScriptException> { library.compile("a\nb\n{nope}") }.line)
    }

    private class Clocked(source: String) {
        var now = 0L
        var numbers = listOf(0.0)
        val memory = ScriptMemory { now }
        private val library = ScriptLibrary(setOf("value", "values"), emptyMap())
        private val script = library.compile(source)

        fun render(): String = library.run(script, memory) { name ->
            when (name) {
                "value" -> Value.Num(numbers[0])
                "values" -> Value.Items(numbers.map { Value.Num(it) })
                else -> null
            }
        }.lines.joinToString("\n") { line -> line.joinToString("") { (it as ScriptSegment.Text).text } }
    }

    @Test
    fun everyHoldsValues() {
        val clock = Clocked("{value | every(1)}")
        clock.numbers = listOf(100.0)
        assertEquals("100", clock.render())
        clock.numbers = listOf(150.0)
        clock.now = 500_000_000L
        assertEquals("100", clock.render())
        clock.now = 1_000_000_000L
        assertEquals("150", clock.render())
    }

    @Test
    fun smoothCountsTowardsNewNumbers() {
        val clock = Clocked("{value | smooth}")
        clock.numbers = listOf(204.0)
        assertEquals("204", clock.render())
        assertFalse(clock.memory.animating)

        clock.numbers = listOf(250.0)
        assertEquals("204", clock.render())
        assertTrue(clock.memory.animating, "a jump should start an animation")
        clock.now = 250_000_000L
        assertEquals("239", clock.render())
        clock.now = 500_000_000L
        assertEquals("250", clock.render())
        assertFalse(clock.memory.animating)
    }

    @Test
    fun smoothKeepsEachLoopItemApart() {
        val clock = Clocked("{for v in values}{v | smooth(1)}{if not loop.last} {end}{end}")
        clock.numbers = listOf(10.0, 100.0)
        assertEquals("10 100", clock.render())
        clock.numbers = listOf(20.0, 100.0)
        clock.render()
        clock.now = 1_000_000_000L
        assertEquals("20 100", clock.render())
    }

    @Test
    fun withoutMemoryEveryAndSmoothPassValuesThrough() {
        assertEquals("15 15", render("{health | every(5)} {health | smooth}"))
    }

    @Test
    fun commentsAreIgnored() {
        assertEquals("a\nb", render("a\n{# a comment #}\nb"))
        assertEquals("ab", render("a{# inline #}b"))
    }

    @Test
    fun outputLimits() {
        val source = "{for a in range(1000)}{for b in range(1000)}x{end}{end}"
        assertTrue(render(source).contains("["))
    }
}
