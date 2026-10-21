package org.polyfrost.evergreenhud.client.script

import java.util.IdentityHashMap

private const val MAX_LINES = 256
private const val MAX_CHARS = 16_384
private const val MAX_ITERATIONS = 10_000

const val ERROR_COLOR = 0xFFFF5555.toInt()

data class ScriptStyle(
    /** `null` keeps the HUD's own text color */
    val color: Int? = null,
    val chroma: Boolean = false,
    val chromaSpeed: Float = 1f,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikethrough: Boolean = false,
    val size: Float = 1f,
) {
    /** Unlike vanilla, a color keeps bold and the like, only `&r` clears them */
    fun withCode(code: Char): ScriptStyle = when (code) {
        'l' -> copy(bold = true)
        'o' -> copy(italic = true)
        'n' -> copy(underline = true)
        'm' -> copy(strikethrough = true)
        'k' -> this
        'r' -> DEFAULT
        else -> CODE_COLORS[code]?.let { copy(color = it, chroma = false) } ?: this
    }

    companion object {
        val DEFAULT = ScriptStyle()
    }
}

sealed interface ScriptSegment {
    data class Text(val text: String, val style: ScriptStyle) : ScriptSegment

    /** [lines] is how many lines tall the icon is, [size] is the text size it was written at */
    data class Icon(val source: IconSource, val lines: Float, val size: Float) : ScriptSegment

    data object Column : ScriptSegment
}

data class ScriptOutput(val lines: List<List<ScriptSegment>>) {
    val usesChroma: Boolean = lines.any { line -> line.any { it is ScriptSegment.Text && it.style.chroma } }

    companion object {
        val EMPTY = ScriptOutput(emptyList())

        fun error(message: String) =
            ScriptOutput(listOf(listOf(ScriptSegment.Text(message, ScriptStyle(color = ERROR_COLOR)))))
    }
}

/** The names and functions a script can use, on top of the built-in ones */
class ScriptLibrary(variables: Set<String>, functions: Map<String, ScriptFunction>) {
    val functions: Map<String, ScriptFunction> = CoreFunctions.ALL + functions
    val variables: Set<String> = CORE_VARIABLES.keys + variables

    init {
        val clashes = this.variables intersect this.functions.keys
        require(clashes.isEmpty()) { "Script names used as both variables and functions: $clashes" }
    }

    fun compile(source: String): CompiledScript = ScriptParser.compile(source, variables, functions)

    /**
     * Runs [script], asking [globals] for the value of each name the first time it is used
     *
     * Pass the same [memory] every time a script runs, so functions like every() and smooth() can pick up where they
     * left off
     */
    fun run(script: CompiledScript, memory: ScriptMemory = ScriptMemory(), globals: (String) -> Value?): ScriptOutput {
        val execution = Execution(globals, functions, memory)
        memory.beginRun()
        try {
            execution.exec(script.nodes)
        } catch (e: LimitException) {
            execution.out.error(e.message ?: "Script stopped")
        } finally {
            memory.endRun()
        }
        return execution.out.finish()
    }

    private companion object {
        val CORE_VARIABLES = mapOf<String, Value>("tab" to Value.Column)
    }

    private class Execution(
        private val globals: (String) -> Value?,
        private val functions: Map<String, ScriptFunction>,
        private val memory: ScriptMemory,
    ) {
        val out = OutputBuilder()
        private val locals = HashMap<String, Value>()
        private val cache = HashMap<String, Value>()
        private val visits = IdentityHashMap<Expr.Call, Int>()
        private var iterations = 0

        fun exec(nodes: List<Node>) {
            for (node in nodes) exec(node)
        }

        private fun exec(node: Node) {
            when (node) {
                is Node.Text -> out.text(node.text)
                is Node.Format -> out.code(node.code)
                is Node.Hex -> out.color(node.argb, chroma = false, speed = 1f)
                Node.Newline -> out.newLine()
                Node.Column -> out.column()
                is Node.Output -> out.tagged { emit(eval(node.expr)) }
                is Node.Set -> out.tagged { locals[node.name] = eval(node.expr) }
                is Node.If -> {
                    var body: List<Node>? = null
                    out.tagged { body = node.branches.firstOrNull { eval(it.first).truthy }?.second ?: node.otherwise }
                    body?.let(::exec)
                    out.markTagged()
                }
                is Node.For -> {
                    var items: List<Value>? = null
                    out.tagged { items = eval(node.iterable).asItems("{for}") }
                    items?.let { loop(node, it) }
                    out.markTagged()
                }
            }
        }

        private fun loop(node: Node.For, items: List<Value>) {
            val previous = locals[node.name]
            val previousLoop = locals[LOOP_NAME]
            for ((index, item) in items.withIndex()) {
                if (++iterations > MAX_ITERATIONS) throw LimitException("Loops ran more than $MAX_ITERATIONS times")
                locals[node.name] = item
                locals[LOOP_NAME] = Value.Obj(
                    "loop",
                    mapOf(
                        "index" to Value.Num(index + 1.0),
                        "index0" to Value.Num(index.toDouble()),
                        "first" to Value.of(index == 0),
                        "last" to Value.of(index == items.lastIndex),
                        "length" to Value.Num(items.size.toDouble()),
                    ),
                    Value.Num(index + 1.0),
                )
                exec(node.body)
            }
            restore(node.name, previous)
            restore(LOOP_NAME, previousLoop)
        }

        private fun restore(name: String, value: Value?) {
            if (value == null) locals.remove(name) else locals[name] = value
        }

        private fun emit(value: Value) {
            when (value) {
                Value.Null -> {}
                is Value.Text -> out.formatted(value.value)
                is Value.Num, is Value.Bool -> out.text(value.asText())
                is Value.Color -> out.color(value.argb, value.chroma, value.chromaSpeed)
                is Value.Size -> out.size(value.scale)
                is Value.Icon -> out.icon(value)
                Value.Column -> out.column()
                is Value.Obj -> emit(value.display)
                is Value.Items -> value.items.forEachIndexed { i, item ->
                    if (i > 0) out.text(", ")
                    emit(item)
                }
            }
        }

        private fun lookup(name: String): Value =
            locals[name] ?: cache.getOrPut(name) { CORE_VARIABLES[name] ?: globals(name) ?: fail("Unknown name \"$name\"") }

        private fun eval(expr: Expr): Value = when (expr) {
            is Expr.Literal -> expr.value
            is Expr.Ident -> lookup(expr.name)
            is Expr.ListOf -> Value.Items(expr.items.map(::eval))
            is Expr.Member -> member(eval(expr.target), expr.name)
            is Expr.Index -> index(eval(expr.target), eval(expr.index))
            is Expr.Call -> call(expr)
            is Expr.Unary -> when (expr.op) {
                "-" -> eval(expr.operand).let { if (it is Value.Num) it.copy(value = -it.value) else Value.Num(-it.asNumber("-")) }
                else -> Value.of(!eval(expr.operand).truthy)
            }
            is Expr.Ternary -> if (eval(expr.condition).truthy) eval(expr.then) else eval(expr.otherwise)
            is Expr.Binary -> binary(expr)
        }

        private fun index(target: Value, index: Value): Value = when (target) {
            Value.Null -> Value.Null
            is Value.Items -> target.items.getOrNull(position(index, target.items.size)) ?: Value.Null
            is Value.Text -> target.value.getOrNull(position(index, target.value.length))?.let { Value.Text(it.toString()) } ?: Value.Null
            is Value.Obj -> member(target, index.asText())
            else -> fail("Can't use [] on ${target.typeName}")
        }

        private fun position(index: Value, size: Int): Int {
            val i = index.asNumber("[]").toInt()
            return if (i < 0) size + i else i
        }

        private fun call(expr: Expr.Call): Value {
            val callee = expr.callee
            if (callee is Expr.Ident && callee.name !in locals) {
                functions[callee.name]?.let { function ->
                    val slot = if (function.stateful) CallSlot(expr, visits.merge(expr, 1, Int::plus)!! - 1) else null
                    return function.call(Args(callee.name, expr.args.map(::eval), ::lookup, memory, slot))
                }
            }
            return when (val target = eval(callee)) {
                is Value.Icon -> {
                    if (expr.args.size != 1) fail("Icons take one size, like head(2)")
                    Value.Icon(target.source, eval(expr.args[0]).asNumber("icon size").toFloat().coerceIn(MIN_SIZE, MAX_SIZE))
                }
                Value.Null -> Value.Null
                else -> fail("${target.typeName.replaceFirstChar { it.uppercase() }} can't be called like a function")
            }
        }

        private fun binary(expr: Expr.Binary): Value {
            when (expr.op) {
                "and" -> return eval(expr.left).let { if (!it.truthy) it else eval(expr.right) }
                "or" -> return eval(expr.left).let { if (it.truthy) it else eval(expr.right) }
            }
            val left = eval(expr.left)
            val right = eval(expr.right)
            return when (expr.op) {
                "==" -> Value.of(valuesEqual(left, right))
                "!=" -> Value.of(!valuesEqual(left, right))
                "<" -> Value.of(compareValues(left, right) < 0)
                "<=" -> Value.of(compareValues(left, right) <= 0)
                ">" -> Value.of(compareValues(left, right) > 0)
                ">=" -> Value.of(compareValues(left, right) >= 0)
                "in" -> Value.of(contains(right, left))
                "not in" -> Value.of(!contains(right, left))
                "+" -> add(left, right)
                "-" -> number(left.asNumber("-") - right.asNumber("-"), left, right)
                "*" -> Value.Num(left.asNumber("*") * right.asNumber("*"))
                "/" -> {
                    val divisor = right.asNumber("/")
                    if (divisor == 0.0) fail("Can't divide by zero")
                    Value.Num(left.asNumber("/") / divisor)
                }
                "%" -> {
                    val divisor = right.asNumber("%")
                    if (divisor == 0.0) fail("Can't divide by zero")
                    Value.Num(left.asNumber("%").mod(divisor))
                }
                else -> fail("Unknown operator ${expr.op}")
            }
        }

        private fun add(left: Value, right: Value): Value = when {
            left == Value.Null -> right
            right == Value.Null -> left
            left is Value.Items && right is Value.Items -> Value.Items(left.items + right.items)
            left is Value.Text || right is Value.Text -> Value.Text(left.asText() + right.asText())
            left is Value.Obj && left.display is Value.Text || right is Value.Obj && right.display is Value.Text ->
                Value.Text(left.asText() + right.asText())
            else -> number(left.asNumber("+") + right.asNumber("+"), left, right)
        }

        /** A sum or difference, shown as precisely as the more precise of the two numbers */
        private fun number(value: Double, left: Value, right: Value): Value.Num {
            val a = left as? Value.Num
            val b = right as? Value.Num
            return Value.Num(value, maxOf(a?.decimals ?: -1, b?.decimals ?: -1), a?.fixed == true || b?.fixed == true)
        }
    }
}

private const val LOOP_NAME = "loop"

/** One call in the script, and which time this run has reached it, since a call in a loop is reached once per item */
private class CallSlot(val call: Expr.Call, val visit: Int) {
    override fun equals(other: Any?): Boolean = other is CallSlot && other.call === call && other.visit == visit

    override fun hashCode(): Int = System.identityHashCode(call) * 31 + visit
}

private class LimitException(message: String) : RuntimeException(message)

private class OutputBuilder {
    private class Line(var tagged: Boolean) {
        val segments = ArrayList<ScriptSegment>()
    }

    private val lines = ArrayList<Line>()
    private var line = Line(false)
    private var style = ScriptStyle.DEFAULT
    private val pending = StringBuilder()
    private var pendingStyle = style
    private var chars = 0
    private var inTag = false

    fun markTagged() {
        line.tagged = true
    }

    /** Runs [block] as part of a tag, showing any error it throws in place of its output */
    inline fun tagged(block: () -> Unit) {
        markTagged()
        val wasInTag = inTag
        inTag = true
        try {
            block()
        } catch (e: LimitException) {
            throw e
        } catch (e: ScriptException) {
            error(e.message ?: "Error")
        } catch (e: RuntimeException) {
            error(e.message ?: e.javaClass.simpleName)
        } finally {
            inTag = wasInTag
        }
    }

    fun text(text: String) {
        var start = 0
        while (true) {
            val newline = text.indexOf('\n', start)
            val end = if (newline < 0) text.length else newline
            if (end > start) append(text.substring(start, end))
            if (newline < 0) return
            newLine()
            start = newline + 1
        }
    }

    /** Writes [text], applying the `&` and `§` codes inside it */
    fun formatted(text: String) {
        var start = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if ((c == '&' || c == '§') && i + 1 < text.length) {
                val next = text[i + 1]
                if (isFormatCode(next)) {
                    this.text(text.substring(start, i))
                    code(next.lowercaseChar())
                    i += 2
                    start = i
                    continue
                }
                if (isHexColorAt(text, i + 1)) {
                    this.text(text.substring(start, i))
                    color(hexColorAt(text, i + 1), chroma = false, speed = 1f)
                    i += 8
                    start = i
                    continue
                }
            }
            i++
        }
        this.text(text.substring(start))
    }

    private fun append(text: String) {
        chars += text.length
        if (chars > MAX_CHARS) throw LimitException("Output is longer than $MAX_CHARS characters")
        if (pending.isNotEmpty() && pendingStyle != style) flush()
        pending.append(text)
        pendingStyle = style
    }

    private fun flush() {
        if (pending.isEmpty()) return
        line.segments.add(ScriptSegment.Text(pending.toString(), pendingStyle))
        pending.setLength(0)
    }

    fun code(code: Char) {
        style = style.withCode(code)
    }

    fun color(argb: Int, chroma: Boolean, speed: Float) {
        style = style.copy(color = argb, chroma = chroma, chromaSpeed = speed)
    }

    fun size(scale: Float) {
        style = style.copy(size = scale)
    }

    fun icon(icon: Value.Icon) {
        flush()
        line.segments.add(ScriptSegment.Icon(icon.source, icon.size, style.size))
    }

    fun column() {
        flush()
        line.segments.add(ScriptSegment.Column)
    }

    fun error(message: String) {
        flush()
        line.segments.add(ScriptSegment.Text("[$message]", ScriptStyle(color = ERROR_COLOR)))
    }

    fun newLine() {
        flush()
        lines.add(line)
        if (lines.size >= MAX_LINES) throw LimitException("Output has more than $MAX_LINES lines")
        line = Line(inTag)
        style = ScriptStyle.DEFAULT
    }

    fun finish(): ScriptOutput {
        flush()
        lines.add(line)
        val kept = lines.filterNot { it.tagged && !it.isVisible() }.toMutableList()
        while (kept.isNotEmpty() && !kept.last().isVisible()) kept.removeAt(kept.lastIndex)
        return ScriptOutput(kept.map { it.segments })
    }

    private fun Line.isVisible() = segments.any {
        it is ScriptSegment.Icon || (it is ScriptSegment.Text && it.text.isNotBlank())
    }
}
