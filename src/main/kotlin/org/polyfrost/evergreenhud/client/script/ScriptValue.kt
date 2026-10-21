package org.polyfrost.evergreenhud.client.script

import java.math.BigDecimal
import java.math.RoundingMode

/** Something a script can draw inline, such as a player head or an item */
interface IconSource

/** Every value a script expression can produce */
sealed interface Value {
    data object Null : Value

    data class Bool(val value: Boolean) : Value

    /**
     * [decimals] is how many decimal places to show at most, or -1 to use the default
     *
     * [fixed] always shows all of them, keeping trailing zeros like 72.00
     */
    data class Num(val value: Double, val decimals: Int = -1, val fixed: Boolean = false) : Value

    data class Text(val value: String) : Value

    data class Color(val argb: Int, val chroma: Boolean = false, val chromaSpeed: Float = 1f) : Value

    /** Changes the size of whatever is written after it */
    data class Size(val scale: Float) : Value

    /** [size] is how many lines tall the icon is */
    data class Icon(val source: IconSource, val size: Float = 1f) : Value

    data class Items(val items: List<Value>) : Value

    /** [display] is what gets written when the object itself is output */
    data class Obj(val type: String, val fields: Map<String, Value>, val display: Value = Null) : Value

    /** Starts a new column, lining it up with the same column on the other lines */
    data object Column : Value

    companion object {
        val TRUE = Bool(true)
        val FALSE = Bool(false)
        val EMPTY = Text("")

        fun of(value: Boolean): Value = if (value) TRUE else FALSE
        fun of(value: Number): Value = Num(value.toDouble())
        fun of(value: String?): Value = if (value == null) Null else Text(value)
    }
}

class ScriptException(message: String, val line: Int = 0) : RuntimeException(message)

internal fun fail(message: String): Nothing = throw ScriptException(message)

val Value.typeName: String
    get() = when (this) {
        Value.Null -> "nothing"
        is Value.Bool -> "true/false"
        is Value.Num -> "number"
        is Value.Text -> "text"
        is Value.Color -> "color"
        is Value.Size -> "size"
        is Value.Icon -> "icon"
        is Value.Items -> "list"
        is Value.Obj -> type
        Value.Column -> "column"
    }

val Value.truthy: Boolean
    get() = when (this) {
        Value.Null -> false
        is Value.Bool -> value
        is Value.Num -> value != 0.0 && !value.isNaN()
        is Value.Text -> value.isNotEmpty()
        is Value.Items -> items.isNotEmpty()
        else -> true
    }

/** The plain text of a value, ignoring colors, sizes and icons */
fun Value.asText(): String = when (this) {
    Value.Null -> ""
    is Value.Bool -> value.toString()
    is Value.Num -> if (fixed && decimals >= 0) formatFixed(value, decimals) else formatNumber(value, decimals)
    is Value.Text -> value
    is Value.Items -> items.joinToString(", ") { it.asText() }
    is Value.Obj -> display.asText()
    else -> ""
}

fun Value.asNumber(what: String = "value"): Double = when (this) {
    is Value.Num -> value
    is Value.Bool -> if (value) 1.0 else 0.0
    is Value.Text -> value.trim().toDoubleOrNull() ?: fail("Expected a number for $what, got \"$value\"")
    is Value.Obj -> display.takeIf { it !is Value.Null }?.asNumber(what) ?: fail("Expected a number for $what, got $typeName")
    else -> fail("Expected a number for $what, got $typeName")
}

fun Value.asItems(what: String = "value"): List<Value> = when (this) {
    is Value.Items -> items
    Value.Null -> emptyList()
    else -> fail("Expected a list for $what, got $typeName")
}

fun formatNumber(value: Double, decimals: Int = -1): String {
    if (value.isNaN()) return "?"
    if (value.isInfinite()) return if (value > 0) "∞" else "-∞"
    val places = if (decimals >= 0) decimals else DEFAULT_DECIMALS
    return BigDecimal.valueOf(value).setScale(places, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
}

fun formatFixed(value: Double, decimals: Int): String {
    if (value.isNaN() || value.isInfinite()) return formatNumber(value)
    return BigDecimal.valueOf(value).setScale(decimals.coerceIn(0, MAX_DECIMALS), RoundingMode.HALF_UP).toPlainString()
}

fun valuesEqual(a: Value, b: Value): Boolean = when {
    a is Value.Num && b is Value.Num -> a.value == b.value
    a is Value.Obj && b !is Value.Obj -> valuesEqual(a.display, b)
    b is Value.Obj && a !is Value.Obj -> valuesEqual(a, b.display)
    a is Value.Items && b is Value.Items -> a.items.size == b.items.size && a.items.indices.all { valuesEqual(a.items[it], b.items[it]) }
    else -> a == b
}

fun compareValues(a: Value, b: Value): Int {
    val left = if (a is Value.Obj) a.display else a
    val right = if (b is Value.Obj) b.display else b
    return when {
        left is Value.Text && right is Value.Text -> left.value.compareTo(right.value, ignoreCase = true)
        left is Value.Num || right is Value.Num || left is Value.Bool || right is Value.Bool ->
            left.asNumber().compareTo(right.asNumber())
        else -> fail("Can't compare ${a.typeName} with ${b.typeName}")
    }
}

private const val DEFAULT_DECIMALS = 2
/** A double holds about 16 significant digits, so more decimals than this would only be noise */
internal const val MAX_DECIMALS = 16
