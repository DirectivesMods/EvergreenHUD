package org.polyfrost.evergreenhud.client.script

internal sealed interface Node {
    data class Text(val text: String) : Node
    data class Format(val code: Char) : Node
    data class Hex(val argb: Int) : Node
    data object Newline : Node
    data object Column : Node
    data class Output(val expr: Expr, val line: Int) : Node
    data class If(val branches: List<Pair<Expr, List<Node>>>, val otherwise: List<Node>, val line: Int) : Node
    data class For(val name: String, val iterable: Expr, val body: List<Node>, val line: Int) : Node
    data class Set(val name: String, val expr: Expr, val line: Int) : Node
}

internal sealed interface Expr {
    data class Literal(val value: Value) : Expr
    data class Ident(val name: String) : Expr
    data class ListOf(val items: List<Expr>) : Expr
    data class Member(val target: Expr, val name: String) : Expr
    data class Index(val target: Expr, val index: Expr) : Expr
    data class Call(val callee: Expr, val args: List<Expr>) : Expr
    data class Unary(val op: String, val operand: Expr) : Expr
    data class Binary(val op: String, val left: Expr, val right: Expr) : Expr
    data class Ternary(val condition: Expr, val then: Expr, val otherwise: Expr) : Expr
}

private const val LOOP = "loop"

private val KEYWORDS = setOf("and", "or", "not", "in", "true", "false", "null", "if", "elif", "else", "end", "for", "set")

/** A template that has been parsed and checked, ready to run any number of times */
class CompiledScript internal constructor(
    internal val nodes: List<Node>,
    /** Every variable and function the script refers to */
    val names: Set<String>,
)

object ScriptParser {
    /**
     * Parses [source], checking every name against [variables] and [functions] so that typos are reported
     * straight away instead of silently printing nothing
     */
    fun compile(source: String, variables: Set<String>, functions: Map<String, ScriptFunction>): CompiledScript {
        val tokens = stripStandaloneLines(TemplateLexer(source).tokens())
        val nodes = BlockParser(tokens).parse()
        val checker = NameChecker(variables, functions)
        checker.check(nodes)
        return CompiledScript(nodes, checker.used)
    }
}

internal sealed interface Token {
    val line: Int

    data class Text(val text: String, override val line: Int) : Token
    data class Format(val code: Char, override val line: Int) : Token
    data class Hex(val argb: Int, override val line: Int) : Token
    data class Newline(override val line: Int) : Token
    data class Column(override val line: Int) : Token
    data class Tag(val kind: TagKind, val body: String, override val line: Int) : Token
}

internal enum class TagKind(val isBlock: Boolean) {
    OUTPUT(false), IF(true), ELIF(true), ELSE(true), END(true), FOR(true), SET(true), COMMENT(true)
}

private const val FORMAT_CODES = "0123456789abcdefklmnor"

internal fun isFormatCode(c: Char) = c.lowercaseChar() in FORMAT_CODES

internal fun isHexColorAt(text: String, index: Int): Boolean {
    if (index + 7 > text.length || text[index] != '#') return false
    for (i in index + 1 until index + 7) if (text[i].digitToIntOrNull(16) == null) return false
    return true
}

internal fun hexColorAt(text: String, index: Int): Int =
    (0xFF000000.toInt()) or text.substring(index + 1, index + 7).toInt(16)

private class TemplateLexer(private val source: String) {
    private val tokens = ArrayList<Token>()
    private val text = StringBuilder()
    private var line = 1
    private var i = 0

    fun tokens(): List<Token> {
        while (i < source.length) {
            val c = source[i]
            when {
                c == '\r' -> i++
                c == '\n' -> {
                    flush()
                    tokens.add(Token.Newline(line))
                    line++
                    i++
                }
                c == '\\' && i + 1 < source.length -> escape(source[i + 1])
                (c == '&' || c == '§') && i + 1 < source.length && isFormatCode(source[i + 1]) -> {
                    flush()
                    tokens.add(Token.Format(source[i + 1].lowercaseChar(), line))
                    i += 2
                }
                (c == '&' || c == '§') && isHexColorAt(source, i + 1) -> {
                    flush()
                    tokens.add(Token.Hex(hexColorAt(source, i + 1), line))
                    i += 8
                }
                c == '{' -> tag()
                else -> {
                    text.append(c)
                    i++
                }
            }
        }
        flush()
        return tokens
    }

    private fun escape(next: Char) {
        when (next) {
            'n' -> {
                flush()
                tokens.add(Token.Newline(line))
            }
            't' -> {
                flush()
                tokens.add(Token.Column(line))
            }
            '{', '}', '\\', '&', '§' -> text.append(next)
            else -> text.append('\\').append(next)
        }
        i += 2
    }

    private fun flush() {
        if (text.isEmpty()) return
        tokens.add(Token.Text(text.toString(), line))
        text.setLength(0)
    }

    private fun tag() {
        flush()
        if (source.startsWith("{#", i)) {
            val end = source.indexOf("#}", i + 2)
            if (end < 0) throw ScriptException("This comment is never closed, end it with #}", line)
            line += source.substring(i, end).count { it == '\n' }
            tokens.add(Token.Tag(TagKind.COMMENT, "", line))
            i = end + 2
            return
        }

        val start = i + 1
        var depth = 0
        var quote: Char? = null
        var j = start
        while (j < source.length) {
            val c = source[j]
            if (c == '\n') break
            if (quote != null) {
                if (c == '\\') j++
                else if (c == quote) quote = null
            } else when (c) {
                '"', '\'' -> quote = c
                '{', '(', '[' -> depth++
                ')', ']' -> depth--
                '}' -> if (depth == 0) break else depth--
            }
            j++
        }
        if (j >= source.length || source[j] != '}') {
            throw ScriptException("Missing } to close this {. Write \\{ to show a { as text", line)
        }

        val body = source.substring(start, j).trim()
        tokens.add(classify(body))
        i = j + 1
    }

    private fun classify(body: String): Token.Tag {
        val word = body.takeWhile { it.isLetterOrDigit() || it == '_' || it == '/' }
        val rest = body.substring(word.length).trim()
        fun tag(kind: TagKind, content: String = rest) = Token.Tag(kind, content, line)
        return when (word) {
            "if" -> tag(TagKind.IF)
            "elif", "elseif" -> tag(TagKind.ELIF)
            "else" -> if (rest.startsWith("if") && (rest.length == 2 || !rest[2].isLetterOrDigit())) {
                tag(TagKind.ELIF, rest.substring(2).trim())
            } else if (rest.isEmpty()) tag(TagKind.ELSE) else throw ScriptException("Unexpected \"$rest\" after else", line)
            "end", "endif", "endfor", "/if", "/for" -> if (rest.isEmpty()) tag(TagKind.END) else throw ScriptException("Unexpected \"$rest\" after end", line)
            "for" -> tag(TagKind.FOR)
            "set" -> tag(TagKind.SET)
            else -> tag(TagKind.OUTPUT, body)
        }
    }
}

/**
 * Removes lines that hold nothing but block tags such as `{if ...}` or `{end}`, together with their line break,
 * so that blocks can sit on their own lines without leaving blank lines behind
 */
private fun stripStandaloneLines(tokens: List<Token>): List<Token> {
    val out = ArrayList<Token>(tokens.size)
    var lineStart = 0
    while (lineStart < tokens.size) {
        var lineEnd = lineStart
        while (lineEnd < tokens.size && tokens[lineEnd] !is Token.Newline) lineEnd++
        val line = tokens.subList(lineStart, lineEnd)

        val standalone = line.any { it is Token.Tag } && line.all {
            (it is Token.Tag && it.kind.isBlock) || (it is Token.Text && it.text.isBlank())
        }

        if (standalone) {
            out.addAll(line.filterIsInstance<Token.Tag>())
        } else {
            out.addAll(line)
            if (lineEnd < tokens.size) out.add(tokens[lineEnd])
        }
        lineStart = lineEnd + 1
    }
    return out
}

private val CLOSERS = setOf(TagKind.ELIF, TagKind.ELSE, TagKind.END)

private class BlockParser(private val tokens: List<Token>) {
    private var i = 0

    fun parse(): List<Node> {
        val nodes = block()
        if (i < tokens.size) {
            val tag = tokens[i] as Token.Tag
            val what = when (tag.kind) {
                TagKind.END -> "{end} without a matching {if} or {for}"
                TagKind.ELSE -> "{else} without a matching {if}"
                else -> "{elif} without a matching {if}"
            }
            throw ScriptException(what, tag.line)
        }
        return nodes
    }

    private fun block(): List<Node> {
        val nodes = ArrayList<Node>()
        while (i < tokens.size) {
            val token = tokens[i]
            if (token is Token.Tag && token.kind in CLOSERS) break
            i++
            when (token) {
                is Token.Text -> {
                    val last = nodes.lastOrNull()
                    if (last is Node.Text) nodes[nodes.lastIndex] = Node.Text(last.text + token.text)
                    else nodes.add(Node.Text(token.text))
                }
                is Token.Format -> nodes.add(Node.Format(token.code))
                is Token.Hex -> nodes.add(Node.Hex(token.argb))
                is Token.Newline -> nodes.add(Node.Newline)
                is Token.Column -> nodes.add(Node.Column)
                is Token.Tag -> tag(token)?.let(nodes::add)
            }
        }
        return nodes
    }

    private fun tag(tag: Token.Tag): Node? = when (tag.kind) {
        TagKind.COMMENT -> null
        TagKind.OUTPUT -> {
            if (tag.body.isEmpty()) throw ScriptException("Empty {}. Write \\{ to show a { as text", tag.line)
            Node.Output(expression(tag.body, tag.line), tag.line)
        }
        TagKind.IF -> ifBlock(tag)
        TagKind.FOR -> forBlock(tag)
        TagKind.SET -> setTag(tag)
        else -> error("unreachable")
    }

    private fun ifBlock(open: Token.Tag): Node {
        val branches = ArrayList<Pair<Expr, List<Node>>>()
        var condition = condition(open)
        var otherwise = emptyList<Node>()
        while (true) {
            val body = block()
            val close = tokens.getOrNull(i) as? Token.Tag ?: throw ScriptException("This {if} is missing its {end}", open.line)
            i++
            when (close.kind) {
                TagKind.ELIF -> {
                    branches.add(condition to body)
                    condition = condition(close)
                }
                TagKind.ELSE -> {
                    branches.add(condition to body)
                    otherwise = block()
                    val end = tokens.getOrNull(i) as? Token.Tag
                    if (end?.kind != TagKind.END) {
                        throw ScriptException(if (end == null) "This {if} is missing its {end}" else "Only {end} can follow {else}", end?.line ?: open.line)
                    }
                    i++
                    return Node.If(branches, otherwise, open.line)
                }
                else -> {
                    branches.add(condition to body)
                    return Node.If(branches, otherwise, open.line)
                }
            }
        }
    }

    private fun condition(tag: Token.Tag): Expr {
        if (tag.body.isEmpty()) throw ScriptException("{${tag.kind.name.lowercase()}} needs a condition, like {if sprinting}", tag.line)
        return expression(tag.body, tag.line)
    }

    private fun forBlock(open: Token.Tag): Node {
        val match = Regex("""^([A-Za-z_]\w*)\s+in\s+(.+)$""").find(open.body)
            ?: throw ScriptException("Write loops like {for effect in effects}", open.line)
        val name = checkName(match.groupValues[1], open.line)
        val iterable = expression(match.groupValues[2], open.line)
        val body = block()
        val close = tokens.getOrNull(i) as? Token.Tag ?: throw ScriptException("This {for} is missing its {end}", open.line)
        if (close.kind != TagKind.END) throw ScriptException("{${close.kind.name.lowercase()}} without a matching {if}", close.line)
        i++
        return Node.For(name, iterable, body, open.line)
    }

    private fun setTag(tag: Token.Tag): Node {
        val match = Regex("""^([A-Za-z_]\w*)\s*=(?!=)\s*(.+)$""").find(tag.body)
            ?: throw ScriptException("Write variables like {set name = value}", tag.line)
        return Node.Set(checkName(match.groupValues[1], tag.line), expression(match.groupValues[2], tag.line), tag.line)
    }

    private fun checkName(name: String, line: Int): String {
        if (name in KEYWORDS || name == LOOP) throw ScriptException("\"$name\" is reserved and can't be used as a name", line)
        return name
    }

    private fun expression(source: String, line: Int): Expr = try {
        ExpressionParser(source).parse()
    } catch (e: ScriptException) {
        throw ScriptException(e.message ?: "Invalid expression", line)
    }
}

private enum class Kind { NUMBER, STRING, NAME, OP, END }

private data class Lexeme(val kind: Kind, val text: String, val number: Double = 0.0)

private val OPERATORS = listOf("==", "!=", "<=", ">=", "&&", "||", "<", ">", "+", "-", "*", "/", "%", "!", "?", ":", "|", ".", ",", "(", ")", "[", "]")

private fun lex(source: String): List<Lexeme> {
    val out = ArrayList<Lexeme>()
    var i = 0
    while (i < source.length) {
        val c = source[i]
        when {
            c.isWhitespace() -> i++
            c.isDigit() -> {
                val start = i
                while (i < source.length && source[i].isDigit()) i++
                if (i + 1 < source.length && source[i] == '.' && source[i + 1].isDigit()) {
                    i++
                    while (i < source.length && source[i].isDigit()) i++
                }
                val text = source.substring(start, i)
                out.add(Lexeme(Kind.NUMBER, text, text.toDouble()))
            }
            c.isLetter() || c == '_' -> {
                val start = i
                while (i < source.length && (source[i].isLetterOrDigit() || source[i] == '_')) i++
                out.add(Lexeme(Kind.NAME, source.substring(start, i)))
            }
            c == '"' || c == '\'' -> {
                val sb = StringBuilder()
                i++
                while (true) {
                    if (i >= source.length) fail("This text is missing its closing $c")
                    val ch = source[i]
                    if (ch == c) break
                    if (ch == '\\' && i + 1 < source.length) {
                        i++
                        sb.append(
                            when (val e = source[i]) {
                                'n' -> '\n'
                                't' -> '\t'
                                else -> e
                            }
                        )
                    } else sb.append(ch)
                    i++
                }
                i++
                out.add(Lexeme(Kind.STRING, sb.toString()))
            }
            else -> {
                val op = OPERATORS.firstOrNull { source.startsWith(it, i) } ?: fail("Unexpected \"$c\"")
                out.add(Lexeme(Kind.OP, op))
                i += op.length
            }
        }
    }
    out.add(Lexeme(Kind.END, ""))
    return out
}

private class ExpressionParser(source: String) {
    private val tokens = lex(source)
    private var i = 0

    private val peek get() = tokens[i]

    private fun isOp(op: String) = peek.kind == Kind.OP && peek.text == op
    private fun isWord(word: String) = peek.kind == Kind.NAME && peek.text == word

    private fun accept(op: String): Boolean {
        if (!isOp(op)) return false
        i++
        return true
    }

    private fun acceptWord(word: String): Boolean {
        if (!isWord(word)) return false
        i++
        return true
    }

    private fun expect(op: String) {
        if (!accept(op)) fail("Expected \"$op\" but found ${describe(peek)}")
    }

    private fun describe(token: Lexeme) = if (token.kind == Kind.END) "the end" else "\"${token.text}\""

    fun parse(): Expr {
        val expr = pipe()
        if (peek.kind != Kind.END) fail("Unexpected ${describe(peek)}")
        return expr
    }

    private fun pipe(): Expr {
        var expr = ternary()
        while (accept("|")) {
            val name = peek.takeIf { it.kind == Kind.NAME }?.text ?: fail("Expected a function name after |")
            i++
            val args = if (accept("(")) arguments() else emptyList()
            expr = Expr.Call(Expr.Ident(name), listOf(expr) + args)
        }
        return expr
    }

    private fun ternary(): Expr {
        val condition = or()
        if (!accept("?")) return condition
        val then = ternary()
        expect(":")
        return Expr.Ternary(condition, then, ternary())
    }

    private fun or(): Expr {
        var left = and()
        while (acceptWord("or") || accept("||")) left = Expr.Binary("or", left, and())
        return left
    }

    private fun and(): Expr {
        var left = not()
        while (acceptWord("and") || accept("&&")) left = Expr.Binary("and", left, not())
        return left
    }

    private fun not(): Expr =
        if (acceptWord("not") || accept("!")) Expr.Unary("not", not()) else comparison()

    private fun comparison(): Expr {
        var left = additive()
        while (true) {
            val op = when {
                peek.kind == Kind.OP && peek.text in setOf("==", "!=", "<", "<=", ">", ">=") -> peek.text.also { i++ }
                acceptWord("in") -> "in"
                isWord("not") && tokens[i + 1].let { it.kind == Kind.NAME && it.text == "in" } -> {
                    i += 2
                    "not in"
                }
                else -> return left
            }
            left = Expr.Binary(op, left, additive())
        }
    }

    private fun additive(): Expr {
        var left = multiplicative()
        while (true) {
            val op = if (accept("+")) "+" else if (accept("-")) "-" else return left
            left = Expr.Binary(op, left, multiplicative())
        }
    }

    private fun multiplicative(): Expr {
        var left = unary()
        while (true) {
            val op = if (accept("*")) "*" else if (accept("/")) "/" else if (accept("%")) "%" else return left
            left = Expr.Binary(op, left, unary())
        }
    }

    private fun unary(): Expr = when {
        accept("-") -> Expr.Unary("-", unary())
        accept("+") -> unary()
        else -> postfix()
    }

    private fun postfix(): Expr {
        var expr = primary()
        while (true) {
            expr = when {
                accept(".") -> Expr.Member(expr, peek.takeIf { it.kind == Kind.NAME }?.text?.also { i++ } ?: fail("Expected a name after ."))
                accept("[") -> Expr.Index(expr, pipe()).also { expect("]") }
                accept("(") -> Expr.Call(expr, arguments())
                else -> return expr
            }
        }
    }

    private fun arguments(): List<Expr> {
        val args = ArrayList<Expr>()
        if (accept(")")) return args
        do args.add(pipe()) while (accept(","))
        expect(")")
        return args
    }

    private fun primary(): Expr {
        val token = peek
        i++
        return when (token.kind) {
            Kind.NUMBER -> Expr.Literal(Value.Num(token.number))
            Kind.STRING -> Expr.Literal(Value.Text(token.text))
            Kind.NAME -> when (token.text) {
                "true" -> Expr.Literal(Value.TRUE)
                "false" -> Expr.Literal(Value.FALSE)
                "null" -> Expr.Literal(Value.Null)
                in KEYWORDS -> fail("\"${token.text}\" can't be used here")
                else -> Expr.Ident(token.text)
            }
            Kind.OP -> when (token.text) {
                "(" -> pipe().also { expect(")") }
                "[" -> {
                    val items = ArrayList<Expr>()
                    if (!accept("]")) {
                        do items.add(pipe()) while (accept(","))
                        expect("]")
                    }
                    Expr.ListOf(items)
                }
                else -> fail("Unexpected \"${token.text}\"")
            }
            Kind.END -> fail("Expression ended too early")
        }
    }
}

private class NameChecker(private val variables: Set<String>, private val functions: Map<String, ScriptFunction>) {
    private val locals = HashSet<String>()
    val used = HashSet<String>()

    fun check(nodes: List<Node>) {
        collectLocals(nodes)
        visit(nodes)
    }

    private fun collectLocals(nodes: List<Node>) {
        for (node in nodes) when (node) {
            is Node.Set -> {
                if (node.name in functions) throw ScriptException("\"${node.name}\" is a function and can't be used as a variable name", node.line)
                locals.add(node.name)
            }
            is Node.For -> {
                if (node.name in functions) throw ScriptException("\"${node.name}\" is a function and can't be used as a variable name", node.line)
                locals.add(node.name)
                collectLocals(node.body)
            }
            is Node.If -> {
                node.branches.forEach { collectLocals(it.second) }
                collectLocals(node.otherwise)
            }
            else -> {}
        }
    }

    private fun visit(nodes: List<Node>, inLoop: Boolean = false) {
        for (node in nodes) when (node) {
            is Node.Output -> expr(node.expr, node.line, inLoop)
            is Node.Set -> expr(node.expr, node.line, inLoop)
            is Node.For -> {
                expr(node.iterable, node.line, inLoop)
                visit(node.body, inLoop = true)
            }
            is Node.If -> {
                for ((condition, body) in node.branches) {
                    expr(condition, node.line, inLoop)
                    visit(body, inLoop)
                }
                visit(node.otherwise, inLoop)
            }
            else -> {}
        }
    }

    private fun expr(expr: Expr, line: Int, inLoop: Boolean) {
        when (expr) {
            is Expr.Ident -> {
                if (expr.name == LOOP) {
                    if (!inLoop) throw ScriptException("\"loop\" only exists inside {for}", line)
                    return
                }
                used.add(expr.name)
                if (expr.name in variables || expr.name in locals) return
                if (expr.name in functions) throw ScriptException("\"${expr.name}\" is a function, call it like ${expr.name}(...)", line)
                throw ScriptException("Unknown name \"${expr.name}\"${suggest(expr.name)}", line)
            }
            is Expr.Call -> {
                val callee = expr.callee
                if (callee is Expr.Ident && callee.name !in locals && callee.name !in variables) {
                    used.add(callee.name)
                    val function = functions[callee.name]
                        ?: throw ScriptException("Unknown function \"${callee.name}\"${suggest(callee.name)}", line)
                    val count = expr.args.size
                    if (count < function.minArgs || count > function.maxArgs) {
                        throw ScriptException("${callee.name}() takes ${function.arity}, but got $count", line)
                    }
                } else expr(callee, line, inLoop)
                expr.args.forEach { expr(it, line, inLoop) }
            }
            is Expr.ListOf -> expr.items.forEach { expr(it, line, inLoop) }
            is Expr.Member -> expr(expr.target, line, inLoop)
            is Expr.Index -> {
                expr(expr.target, line, inLoop)
                expr(expr.index, line, inLoop)
            }
            is Expr.Unary -> expr(expr.operand, line, inLoop)
            is Expr.Binary -> {
                expr(expr.left, line, inLoop)
                expr(expr.right, line, inLoop)
            }
            is Expr.Ternary -> {
                expr(expr.condition, line, inLoop)
                expr(expr.then, line, inLoop)
                expr(expr.otherwise, line, inLoop)
            }
            is Expr.Literal -> {}
        }
    }

    private fun suggest(name: String): String = suggestion(name, variables + locals + functions.keys)
}

/** `, did you mean "x"?` for the candidate closest to [name], or nothing when none are close */
internal fun suggestion(name: String, candidates: Collection<String>): String {
    val best = candidates.minByOrNull { distance(name.lowercase(), it.lowercase()) } ?: return ""
    return if (distance(name.lowercase(), best.lowercase()) <= maxOf(1, name.length / 3)) ", did you mean \"$best\"?" else ""
}

/** Edits needed to turn [a] into [b], counting two swapped letters as one */
private fun distance(a: String, b: String): Int {
    val d = Array(a.length + 1) { i -> IntArray(b.length + 1) { j -> if (i == 0) j else if (j == 0) i else 0 } }
    for (i in 1..a.length) {
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
        }
    }
    return d[a.length][b.length]
}
