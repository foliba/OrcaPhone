package io.github.foliba.phorca.core.gcode

import kotlin.math.abs
import kotlin.math.floor

/**
 * Replaces OrcaSlicer-style placeholders in custom G-code:
 * - `[name]`, `[name[0]]`, `{name}`, `{name[0]}`: the value (vector values are comma separated, the index picks one)
 * - `{expression}` with numbers, variables, `+ - * / %`, parentheses, `min(…)` and `max(…)`, e.g.
 *   `{layer_num + 1}` or `{min(max_layer_z + 50, printable_height)}`
 *
 * Anything else (`{if …}` conditions, unknown variables, assignments) is left untouched; [unresolved] finds it.
 *
 * Hand-written scanner rather than a regex: Android's ICU regex engine rejects patterns with an
 * unescaped closing `}` or `]` that desktop Java accepts.
 */
object Placeholders {

    fun apply(template: String, values: Map<String, String>): String {
        if (template.isEmpty()) return template
        val out = StringBuilder(template.length + 32)
        var i = 0
        val n = template.length
        while (i < n) {
            val c = template[i]
            if (c != '[' && c != '{') { out.append(c); i++; continue }
            if (c == '{') {
                val end = template.indexOf('}', i + 1)
                // a nested '{' means a block we don't handle ({if …}…{endif} keeps its own braces)
                if (end > i && template.indexOf('{', i + 1).let { it < 0 || it > end }) {
                    val v = Expr(template.substring(i + 1, end), values).evaluate()
                    if (v != null) { out.append(v); i = end + 1; continue }
                }
                out.append(c); i++; continue
            }
            // [name] or [name[0]]
            var j = i + 1
            if (j < n && (template[j].isLetter() || template[j] == '_')) {
                while (j < n && (template[j].isLetterOrDigit() || template[j] == '_')) j++
                val key = template.substring(i + 1, j)
                var index = -1
                if (j < n && template[j] == '[') {
                    var k = j + 1
                    while (k < n && template[k].isDigit()) k++
                    if (k > j + 1 && k < n && template[k] == ']') { index = template.substring(j + 1, k).toInt(); j = k + 1 }
                }
                if (j < n && template[j] == ']') {
                    val v = lookup(values, key, index)
                    if (v != null) { out.append(v); i = j + 1; continue }
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    /** `{…}` placeholders left in [gcode] after [apply]: what Phorca could not evaluate (e.g. `{if …}`). */
    fun unresolved(gcode: String): List<String> {
        val found = ArrayList<String>()
        var i = gcode.indexOf('{')
        while (i >= 0) {
            val end = gcode.indexOf('}', i + 1)
            if (end < 0) break
            found.add(gcode.substring(i, end + 1).replace('\n', ' ').take(60))
            i = gcode.indexOf('{', end + 1)
        }
        return found
    }

    /** True if `{body}` is something [apply] can evaluate given the variables (any names are assumed known). */
    fun isSupported(body: String): Boolean = Expr(body, null).evaluate() != null

    private fun lookup(values: Map<String, String>, key: String, index: Int): String? {
        val v = values[key] ?: return null
        if (index < 0 || ',' !in v) return v
        return v.split(',').getOrNull(index)?.trim()
    }

    private fun format(x: Double): String =
        if (x == floor(x) && abs(x) < 1e15) x.toLong().toString() else NumFmt.format(x, 4)

    /**
     * Recursive-descent evaluator. With [values] == null every variable counts as 1 (used to check the syntax).
     * Returns null for anything it does not understand.
     */
    private class Expr(private val s: String, private val values: Map<String, String>?) {
        private var p = 0
        private var text: String? = null // a plain non-numeric variable, e.g. {filament_type}

        fun evaluate(): String? = try {
            skip()
            if (p >= s.length) null else {
                val v = sum()
                skip()
                when {
                    p != s.length -> null
                    text != null -> text
                    else -> format(v)
                }
            }
        } catch (e: IllegalStateException) {
            null
        } catch (e: NumberFormatException) {
            null
        }

        private fun skip() { while (p < s.length && s[p].isWhitespace()) p++ }
        private fun fail(): Nothing = throw IllegalStateException()

        private fun sum(): Double {
            var v = product()
            while (true) {
                skip()
                when (s.getOrNull(p)) {
                    '+' -> { p++; v += product() }
                    '-' -> { p++; v -= product() }
                    else -> return v
                }
            }
        }

        private fun product(): Double {
            var v = unary()
            while (true) {
                skip()
                when (s.getOrNull(p)) {
                    '*' -> { p++; v *= unary() }
                    '/' -> { p++; val d = unary(); if (d == 0.0) fail(); v /= d }
                    '%' -> { p++; val d = unary(); if (d == 0.0) fail(); v %= d }
                    else -> return v
                }
            }
        }

        private fun unary(): Double {
            skip()
            return when (s.getOrNull(p)) {
                '-' -> { p++; -unary() }
                '+' -> { p++; unary() }
                else -> atom()
            }
        }

        private fun atom(): Double {
            skip()
            val c = s.getOrNull(p) ?: fail()
            if (c == '(') {
                p++; val v = sum(); skip()
                if (s.getOrNull(p) != ')') fail()
                p++; return v
            }
            if (c.isDigit() || c == '.') {
                val start = p
                while (p < s.length && (s[p].isDigit() || s[p] == '.')) p++
                return s.substring(start, p).toDouble()
            }
            if (!(c.isLetter() || c == '_')) fail()
            val start = p
            while (p < s.length && (s[p].isLetterOrDigit() || s[p] == '_')) p++
            val name = s.substring(start, p)
            skip()
            if (name == "min" || name == "max") {
                if (s.getOrNull(p) != '(') fail()
                p++
                val a = sum(); skip()
                if (s.getOrNull(p) != ',') fail()
                p++
                val b = sum(); skip()
                if (s.getOrNull(p) != ')') fail()
                p++
                return if (name == "min") minOf(a, b) else maxOf(a, b)
            }
            if (name in KEYWORDS) fail()
            var index = -1
            if (s.getOrNull(p) == '[') {
                val close = s.indexOf(']', p)
                if (close < 0) fail()
                index = s.substring(p + 1, close).trim().toIntOrNull() ?: fail()
                p = close + 1
            }
            if (values == null) return 1.0
            val raw = lookup(values, name, index) ?: fail()
            raw.trim().toDoubleOrNull()?.let { return it }
            // a text value is fine on its own ({filament_type}), not inside arithmetic
            if (start == s.indexOfFirst { !it.isWhitespace() } && s.substring(p).isBlank()) { text = raw; return 0.0 }
            fail()
        }

        companion object {
            val KEYWORDS = setOf("if", "elsif", "else", "endif", "local", "global", "and", "or", "not", "true", "false")
        }
    }
}
