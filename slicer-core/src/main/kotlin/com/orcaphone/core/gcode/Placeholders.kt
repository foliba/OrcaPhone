package com.orcaphone.core.gcode

/**
 * Replaces OrcaSlicer-style placeholders `[name]`, `[name[0]]`, `{name}` and `{name[0]}` with values.
 * Unknown placeholders and expressions are left untouched.
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
            val close = if (c == '[') ']' else '}'
            var j = i + 1
            if (j < n && (template[j].isLetter() || template[j] == '_')) {
                while (j < n && (template[j].isLetterOrDigit() || template[j] == '_')) j++
                val key = template.substring(i + 1, j)
                // optional vector index like [0]
                if (j < n && template[j] == '[') {
                    var k = j + 1
                    while (k < n && template[k].isDigit()) k++
                    if (k > j + 1 && k < n && template[k] == ']') j = k + 1
                }
                if (j < n && template[j] == close) {
                    val v = values[key]
                    if (v != null) {
                        out.append(v)
                        i = j + 1
                        continue
                    }
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }
}
