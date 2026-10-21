package org.polyfrost.evergreenhud.client.script

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

class ScriptFunction(
    val name: String,
    val minArgs: Int,
    val maxArgs: Int,
    /** Whether the function keeps something between runs with [Args.remember] */
    val stateful: Boolean = false,
    val call: (Args) -> Value,
) {
    val arity: String
        get() = when {
            minArgs == maxArgs -> "$minArgs argument${if (minArgs == 1) "" else "s"}"
            maxArgs == Int.MAX_VALUE -> "at least $minArgs arguments"
            else -> "$minArgs to $maxArgs arguments"
        }
}

/** What a script keeps between runs, so functions like every() and smooth() can see their earlier values */
class ScriptMemory(internal val clock: () -> Long = System::nanoTime) {
    private val slots = HashMap<Any, Any>()
    private val touched = HashSet<Any>()

    /** Whether smooth() is still moving towards a value, so the HUD should update every frame */
    var animating = false
        private set

    internal fun beginRun() {
        touched.clear()
        animating = false
    }

    /** Drops what belonged to calls the last run didn't reach, such as effects that ran out */
    internal fun endRun() {
        slots.keys.retainAll(touched)
    }

    internal fun <T : Any> remember(key: Any, init: () -> T): T {
        touched.add(key)
        @Suppress("UNCHECKED_CAST")
        return slots.getOrPut(key, init) as T
    }

    /** What [read] gave, only read again once [seconds] have passed since the last time */
    internal fun hold(key: Any, seconds: Double, read: () -> Value): Value {
        val held = remember(key) { Held() }
        val now = clock()
        if (held.value == null || now - held.since >= (seconds * NANOS_PER_SECOND).toLong()) {
            held.value = read()
            held.since = now
        }
        return held.value ?: Value.Null
    }

    internal fun animate() {
        animating = true
    }

    fun clear() {
        slots.clear()
        touched.clear()
        animating = false
    }
}

class Args(
    private val function: String,
    val values: List<Value>,
    private val lookup: (String) -> Value = { Value.Null },
    private val memory: ScriptMemory? = null,
    private val slot: Any? = null,
) {
    val size get() = values.size

    val nanoTime: Long get() = memory?.clock?.invoke() ?: System.nanoTime()

    /** The value of another name, such as `effects`, as seen by the script running this function */
    fun global(name: String): Value = lookup(name)

    /** State for this exact call in the script, made by [init] the first time and kept between runs */
    fun <T : Any> remember(init: () -> T): T =
        if (memory == null || slot == null) init() else memory.remember(slot, init)

    /** What [read] gave for this exact call, only read again once [seconds] have passed */
    fun hold(seconds: Double, read: () -> Value): Value =
        if (memory == null || slot == null) read() else memory.hold(slot, seconds, read)

    /** Asks for the HUD to keep updating every frame, while something is still moving */
    fun animate() {
        memory?.animate()
    }

    operator fun get(index: Int): Value = values.getOrElse(index) { Value.Null }

    fun number(index: Int): Double = this[index].asNumber("${function}()")

    fun number(index: Int, default: Double): Double = if (index < size) number(index) else default

    fun int(index: Int, default: Int = 0): Int =
        if (index < size) number(index).let { if (it.isNaN()) 0 else it.roundToInt() } else default

    fun text(index: Int, default: String = ""): String = if (index < size) this[index].asText() else default

    fun items(index: Int): List<Value> = this[index].asItems("${function}()")
}

private const val MAX_REPEAT = 1000
private const val NANOS_PER_SECOND = 1_000_000_000.0
private const val DEFAULT_SMOOTHING = 0.5

/** What every() is holding on to */
private class Held(var value: Value? = null, var since: Long = 0L)

/** Where smooth() is between the number it showed and the one it is heading to */
private class Motion(var from: Double, var to: Double = from, var current: Double = from, var started: Long = 0L)
private const val MAX_RANGE = 1000

private val ROMAN_VALUES = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
private val ROMAN_NUMERALS = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")

/** Vanilla's sixteen chat colors, by their formatting code */
val CODE_COLORS: Map<Char, Int> = mapOf(
    '0' to 0x000000, '1' to 0x0000AA, '2' to 0x00AA00, '3' to 0x00AAAA,
    '4' to 0xAA0000, '5' to 0xAA00AA, '6' to 0xFFAA00, '7' to 0xAAAAAA,
    '8' to 0x555555, '9' to 0x5555FF, 'a' to 0x55FF55, 'b' to 0x55FFFF,
    'c' to 0xFF5555, 'd' to 0xFF55FF, 'e' to 0xFFFF55, 'f' to 0xFFFFFF,
).mapValues { (_, rgb) -> rgb or 0xFF000000.toInt() }

private val NAMED_COLORS: Map<String, Char> = mapOf(
    "black" to '0', "dark_blue" to '1', "dark_green" to '2', "dark_aqua" to '3',
    "dark_red" to '4', "dark_purple" to '5', "gold" to '6', "gray" to '7',
    "dark_gray" to '8', "blue" to '9', "green" to 'a', "aqua" to 'b',
    "red" to 'c', "light_purple" to 'd', "yellow" to 'e', "white" to 'f',
    "grey" to '7', "dark_grey" to '8', "purple" to '5', "pink" to 'd', "magenta" to 'd',
    "orange" to '6', "cyan" to 'b', "lime" to 'a',
)

private val formatters = ConcurrentHashMap<String, DateTimeFormatter>()

fun parseColor(value: Value): Value.Color = when (value) {
    is Value.Color -> value
    is Value.Num -> Value.Color(value.value.toInt() or 0xFF000000.toInt())
    is Value.Obj -> parseColor(value.display)
    else -> {
        val text = value.asText().trim()
        val key = text.lowercase().replace(' ', '_')
        val code = when {
            text.length == 2 && (text[0] == '&' || text[0] == '§') -> text[1].lowercaseChar()
            text.length == 1 -> text[0].lowercaseChar()
            else -> NAMED_COLORS[key]
        }
        code?.let(CODE_COLORS::get)?.let { Value.Color(it) } ?: parseHex(text) ?: fail("\"$text\" isn't a color. Try \"red\", \"&c\" or \"#FF5555\"")
    }
}

private fun parseHex(text: String): Value.Color? {
    val hex = text.removePrefix("&").removePrefix("#")
    if (hex.isEmpty() || hex.any { it.digitToIntOrNull(16) == null }) return null
    return when (hex.length) {
        3 -> Value.Color(0xFF000000.toInt() or hex.map { "$it$it" }.joinToString("").toInt(16))
        6 -> Value.Color(0xFF000000.toInt() or hex.toInt(16))
        8 -> Value.Color(hex.toLong(16).toInt())
        else -> null
    }
}

private fun channel(value: Double) = value.roundToInt().coerceIn(0, 255)

private fun rgb(r: Double, g: Double, b: Double, a: Double = 255.0) =
    Value.Color((channel(a) shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b))

private fun blend(from: Value.Color, to: Value.Color, t: Double): Value.Color {
    val f = t.coerceIn(0.0, 1.0)
    fun mix(shift: Int): Int {
        val a = (from.argb ushr shift) and 0xFF
        val b = (to.argb ushr shift) and 0xFF
        return (a + (b - a) * f).roundToInt() shl shift
    }
    return Value.Color(mix(24) or mix(16) or mix(8) or mix(0))
}

private fun hsv(hue: Double, saturation: Double, brightness: Double): Value.Color {
    fun unit(v: Double) = (if (v > 1.0) v / 100.0 else v).coerceIn(0.0, 1.0)
    val s = unit(saturation)
    val v = unit(brightness)
    val h = ((hue % 360.0) + 360.0) % 360.0 / 60.0
    val c = v * s
    val x = c * (1 - abs(h % 2 - 1))
    val m = v - c
    val (r, g, b) = when (h.toInt()) {
        0 -> Triple(c, x, 0.0)
        1 -> Triple(x, c, 0.0)
        2 -> Triple(0.0, c, x)
        3 -> Triple(0.0, x, c)
        4 -> Triple(x, 0.0, c)
        else -> Triple(c, 0.0, x)
    }
    return rgb((r + m) * 255, (g + m) * 255, (b + m) * 255)
}

fun roman(value: Int): String {
    if (value < 1 || value > 3999) return value.toString()
    val builder = StringBuilder()
    var left = value
    for (i in ROMAN_VALUES.indices) {
        while (left >= ROMAN_VALUES[i]) {
            builder.append(ROMAN_NUMERALS[i])
            left -= ROMAN_VALUES[i]
        }
    }
    return builder.toString()
}

fun formatDuration(seconds: Double): String {
    if (seconds.isInfinite()) return "**:**"
    val total = seconds.coerceAtLeast(0.0).toLong()
    val hours = total / 3600
    val minutes = total / 60 % 60
    val secs = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, secs) else "%d:%02d".format(minutes, secs)
}

/** Removes `&` and `§` formatting codes */
fun stripCodes(text: String): String {
    val out = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if ((c == '&' || c == '§') && i + 1 < text.length) {
            if (isFormatCode(text[i + 1])) {
                i += 2
                continue
            }
            if (isHexColorAt(text, i + 1)) {
                i += 8
                continue
            }
        }
        out.append(c)
        i++
    }
    return out.toString()
}

fun member(target: Value, name: String): Value = when (target) {
    Value.Null -> Value.Null
    is Value.Obj -> target.fields[name] ?: fail("${target.type} has no \"$name\"${suggestion(name, target.fields.keys)}")
    is Value.Items -> when (name) {
        "length", "size", "count" -> Value.Num(target.items.size.toDouble())
        "first" -> target.items.firstOrNull() ?: Value.Null
        "last" -> target.items.lastOrNull() ?: Value.Null
        else -> fail("Lists have no \"$name\". Try length, first or last")
    }
    is Value.Text -> when (name) {
        "length" -> Value.Num(target.value.length.toDouble())
        else -> fail("Text has no \"$name\"")
    }
    else -> fail("${target.typeName.replaceFirstChar { it.uppercase() }} has no \"$name\"")
}

fun contains(haystack: Value, needle: Value): Boolean = when (haystack) {
    is Value.Items -> haystack.items.any { valuesEqual(it, needle) }
    is Value.Obj -> haystack.fields.containsKey(needle.asText())
    Value.Null -> false
    else -> haystack.asText().contains(needle.asText(), ignoreCase = true)
}

private fun numbersOf(args: Args): List<Double> =
    if (args.size == 1 && args[0] is Value.Items) args.items(0).map { it.asNumber() } else args.values.map { it.asNumber() }

private fun field(value: Value, name: String?): Value = if (name == null) value else member(value, name)

object CoreFunctions {
    val ALL: Map<String, ScriptFunction> = buildMap {
        fun def(name: String, min: Int, max: Int = min, stateful: Boolean = false, call: (Args) -> Value) {
            put(name, ScriptFunction(name, min, max, stateful, call))
        }

        def("round", 1, 2) { a ->
            val places = a.int(1).coerceIn(-MAX_DECIMALS, MAX_DECIMALS)
            val n = a.number(0)
            if (n.isNaN() || n.isInfinite()) Value.Num(n)
            else Value.Num(BigDecimal.valueOf(n).setScale(places, RoundingMode.HALF_UP).toDouble(), places.coerceAtLeast(0))
        }
        def("floor", 1) { a -> Value.Num(floor(a.number(0))) }
        def("ceil", 1) { a -> Value.Num(ceil(a.number(0))) }
        def("abs", 1) { a -> (a[0] as? Value.Num)?.let { it.copy(value = abs(it.value)) } ?: Value.Num(abs(a.number(0))) }
        def("sqrt", 1) { a -> Value.Num(sqrt(a.number(0))) }
        def("pow", 2) { a -> Value.Num(a.number(0).pow(a.number(1))) }
        def("min", 1, Int.MAX_VALUE) { a -> numbersOf(a).minOrNull()?.let { Value.Num(it) } ?: Value.Null }
        def("max", 1, Int.MAX_VALUE) { a -> numbersOf(a).maxOrNull()?.let { Value.Num(it) } ?: Value.Null }
        def("clamp", 3) { a -> Value.Num(a.number(0).coerceIn(minOf(a.number(1), a.number(2)), maxOf(a.number(1), a.number(2)))) }
        def("fixed", 1, 2) { a -> Value.Text(formatFixed(a.number(0), a.int(1))) }
        def("exact", 1) { a -> Value.Num(a.number(0), MAX_DECIMALS) }
        def("signed", 1) { a ->
            val n = a[0] as? Value.Num ?: Value.Num(a.number(0))
            Value.Text(if (n.value > 0) "+" + n.asText() else n.asText())
        }
        def("number", 1) { a -> a[0].asText().trim().toDoubleOrNull()?.let { Value.Num(it) } ?: Value.Null }
        def("text", 1) { a -> Value.Text(a[0].asText()) }
        def("roman", 1) { a -> Value.Text(roman(a.int(0))) }
        def("duration", 1) { a -> Value.Text(formatDuration(a.number(0))) }

        def("len", 1) { a ->
            when (val v = a[0]) {
                is Value.Items -> Value.Num(v.items.size.toDouble())
                else -> Value.Num(v.asText().length.toDouble())
            }
        }
        def("upper", 1) { a -> Value.Text(a.text(0).uppercase()) }
        def("lower", 1) { a -> Value.Text(a.text(0).lowercase()) }
        def("title", 1) { a ->
            Value.Text(a.text(0).split(' ').joinToString(" ") { word -> word.lowercase().replaceFirstChar { it.uppercase() } })
        }
        def("trim", 1) { a -> Value.Text(a.text(0).trim()) }
        def("plain", 1) { a -> Value.Text(stripCodes(a.text(0))) }
        def("replace", 3) { a -> Value.Text(a.text(0).replace(a.text(1), a.text(2))) }
        def("truncate", 2, 3) { a ->
            val text = a.text(0)
            val length = a.int(1).coerceAtLeast(0)
            Value.Text(if (text.length <= length) text else text.take(length) + a.text(2, "..."))
        }
        def("pad_left", 2, 3) { a -> Value.Text(a.text(0).padStart(a.int(1).coerceIn(0, MAX_REPEAT), a.text(2, " ").firstOrNull() ?: ' ')) }
        def("pad_right", 2, 3) { a -> Value.Text(a.text(0).padEnd(a.int(1).coerceIn(0, MAX_REPEAT), a.text(2, " ").firstOrNull() ?: ' ')) }
        def("repeat", 2) { a -> Value.Text(a.text(0).repeat(a.int(1).coerceIn(0, MAX_REPEAT))) }
        def("contains", 2) { a -> Value.of(contains(a[0], a[1])) }

        def("join", 1, 2) { a -> Value.Text(a.items(0).joinToString(a.text(1, ", ")) { it.asText() }) }
        def("first", 1) { a -> a.items(0).firstOrNull() ?: Value.Null }
        def("last", 1) { a -> a.items(0).lastOrNull() ?: Value.Null }
        def("reverse", 1) { a -> Value.Items(a.items(0).asReversed()) }
        def("take", 2) { a -> Value.Items(a.items(0).take(a.int(1).coerceAtLeast(0))) }
        def("sort", 1, 2) { a ->
            val key = a.text(1).takeIf { a.size > 1 }
            Value.Items(a.items(0).sortedWith { x, y -> compareValues(field(x, key), field(y, key)) })
        }
        def("where", 2, 3) { a ->
            val key = a.text(1)
            Value.Items(a.items(0).filter { item ->
                val v = member(item, key)
                if (a.size > 2) valuesEqual(v, a[2]) else v.truthy
            })
        }
        def("range", 1, 2) { a ->
            val start = if (a.size > 1) a.int(0) else 1
            val end = if (a.size > 1) a.int(1) else a.int(0)
            val step = if (end >= start) 1 else -1
            val count = abs(end - start) + 1
            if (count > MAX_RANGE) fail("range() can make at most $MAX_RANGE numbers")
            Value.Items(List(count) { Value.Num((start + it * step).toDouble()) })
        }
        def("switch", 3, Int.MAX_VALUE) { a ->
            val value = a[0]
            var i = 1
            while (i + 1 < a.size) {
                if (valuesEqual(value, a[i])) return@def a[i + 1]
                i += 2
            }
            if (i < a.size) a[i] else Value.Null
        }

        def("color", 1, 4) { a ->
            if (a.size >= 3) rgb(a.number(0), a.number(1), a.number(2), a.number(3, 255.0)) else parseColor(a[0])
        }
        def("rgb", 3, 4) { a -> rgb(a.number(0), a.number(1), a.number(2), a.number(3, 255.0)) }
        def("hsv", 3) { a -> hsv(a.number(0), a.number(1), a.number(2)) }
        def("blend", 3) { a -> blend(parseColor(a[0]), parseColor(a[1]), a.number(2)) }
        def("gradient", 5, Int.MAX_VALUE) { a ->
            val min = a.number(1)
            val max = a.number(2)
            val colors = a.values.drop(3).map(::parseColor)
            val t = if (max == min) 1.0 else ((a.number(0) - min) / (max - min)).coerceIn(0.0, 1.0)
            val scaled = t * (colors.size - 1)
            val index = floor(scaled).toInt().coerceAtMost(colors.size - 2)
            blend(colors[index], colors[index + 1], scaled - index)
        }
        def("rainbow", 0, 1) { a -> Value.Color(0xFFFF5555.toInt(), chroma = true, chromaSpeed = a.number(0, 1.0).toFloat()) }
        def("size", 1) { a -> Value.Size(a.number(0).toFloat().coerceIn(MIN_SIZE, MAX_SIZE)) }
        put("scale", getValue("size"))
        def("bar", 1) { a ->
            when (val icon = a[0]) {
                is Value.Icon -> icon.copy(bar = true)
                Value.Null -> Value.Null
                else -> fail("bar goes on an item's icon, like helmet.icon | bar")
            }
        }

        def("every", 2, stateful = true) { a -> a.hold(a.number(1)) { a[0] } }
        def("smooth", 1, 2, stateful = true) { a ->
            val target = a[0].let { if (it is Value.Obj) it.display else it } as? Value.Num ?: return@def a[0]
            if (target.value.isNaN() || target.value.isInfinite()) return@def target
            val motion = a.remember { Motion(target.value) }
            val now = a.nanoTime
            if (target.value != motion.to) {
                motion.from = motion.current
                motion.to = target.value
                motion.started = now
            }
            val duration = a.number(1, DEFAULT_SMOOTHING).coerceAtLeast(0.0) * NANOS_PER_SECOND
            val t = if (duration <= 0.0 || motion.from == motion.to) 1.0 else ((now - motion.started) / duration).coerceIn(0.0, 1.0)
            motion.current = motion.from + (motion.to - motion.from) * (1.0 - (1.0 - t) * (1.0 - t))
            if (t < 1.0) a.animate()
            val places = when {
                target.decimals >= 0 -> target.decimals
                target.value == floor(target.value) -> 0
                else -> -1
            }
            Value.Num(motion.current, places, target.fixed)
        }

        def("now", 0, 1) { a ->
            val pattern = a.text(0, "HH:mm")
            val formatter = formatters[pattern] ?: try {
                DateTimeFormatter.ofPattern(pattern).also { formatters[pattern] = it }
            } catch (_: IllegalArgumentException) {
                fail("\"$pattern\" isn't a valid time pattern. Try \"HH:mm:ss\"")
            }
            Value.Text(LocalDateTime.now().format(formatter))
        }
    }
}

const val MIN_SIZE = 0.25f
const val MAX_SIZE = 8f
