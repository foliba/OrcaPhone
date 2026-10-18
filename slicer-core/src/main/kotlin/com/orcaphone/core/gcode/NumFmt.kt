package com.orcaphone.core.gcode

import kotlin.math.abs

/** Locale-independent fixed-point formatting with trailing zeros trimmed (G-code needs '.' decimals). */
object NumFmt {
    private val POW10 = longArrayOf(1, 10, 100, 1000, 10000, 100000, 1000000)

    fun append(sb: StringBuilder, v: Double, decimals: Int) {
        val x = if (v.isNaN() || v.isInfinite()) 0.0 else v
        val pow = POW10[decimals]
        val scaled = Math.round(abs(x) * pow)
        if (scaled == 0L) { sb.append('0'); return }
        if (x < 0) sb.append('-')
        sb.append(scaled / pow)
        var fp = scaled % pow
        if (fp != 0L) {
            sb.append('.')
            var div = pow / 10
            while (fp != 0L && div > 0) {
                val d = fp / div
                sb.append(('0'.code + d.toInt()).toChar())
                fp %= div
                div /= 10
            }
        }
    }

    fun format(v: Double, decimals: Int): String = StringBuilder().also { append(it, v, decimals) }.toString()

    fun duration(seconds: Double): String {
        val s = seconds.toLong().coerceAtLeast(0)
        val d = s / 86400; val h = (s % 86400) / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return buildString {
            if (d > 0) append(d).append("d ")
            if (d > 0 || h > 0) append(h).append("h ")
            if (d > 0 || h > 0 || m > 0) append(m).append("m ")
            append(sec).append('s')
        }
    }
}
