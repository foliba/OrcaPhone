package com.orcaphone.core.gcode

/**
 * Replaces OrcaSlicer-style placeholders `[name]`, `[name[0]]`, `{name}` and `{name[0]}` with values.
 * Unknown placeholders and expressions are left untouched.
 */
object Placeholders {
    private val SQUARE = Regex("\\[([A-Za-z_][A-Za-z0-9_]*)(\\[\\d+])?]")
    private val CURLY = Regex("\\{([A-Za-z_][A-Za-z0-9_]*)(\\[\\d+])?}")

    fun apply(template: String, values: Map<String, String>): String {
        if (template.isEmpty()) return template
        val f: (MatchResult) -> CharSequence = { m -> values[m.groupValues[1]] ?: m.value }
        return CURLY.replace(SQUARE.replace(template, f), f)
    }
}
