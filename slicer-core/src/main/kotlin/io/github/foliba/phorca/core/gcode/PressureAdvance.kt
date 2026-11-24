package io.github.foliba.phorca.core.gcode

import io.github.foliba.phorca.core.config.GCodeFlavor
import kotlin.math.max

/** Pressure advance (Klipper), linear advance (Marlin) and pressure advance (RepRapFirmware) commands. */
object PressureAdvance {
    fun command(flavor: GCodeFlavor, value: Double): String {
        val v = NumFmt.format(max(0.0, value), 4)
        return when (flavor) {
            GCodeFlavor.KLIPPER -> "SET_PRESSURE_ADVANCE ADVANCE=$v"
            GCodeFlavor.MARLIN -> "M900 K$v"
            GCodeFlavor.REPRAP -> "M572 D0 S$v"
        }
    }
}

/**
 * Pressure advance calibration tower: the value rises with the height, [stepPerMm] per millimetre from [start]
 * at the bed, and stops at [end]. The best-looking corners show the value: start + height × stepPerMm.
 */
data class PaTower(val start: Double, val end: Double, val stepPerMm: Double) {
    init {
        require(stepPerMm > 0 && end > start && start >= 0) { "Pressure advance tower needs 0 ≤ start < end and a step > 0" }
    }

    /** Height that covers start…end, between 5 mm and [maxHeight]. */
    fun height(maxHeight: Double): Double = ((end - start) / stepPerMm).coerceIn(5.0, maxHeight)

    fun valueAt(z: Double): Double = (start + z * stepPerMm).coerceAtMost(end)

    companion object {
        /** Typical ranges, as in OrcaSlicer's calibration: direct drive and Bowden extruders. */
        val DIRECT_DRIVE = PaTower(0.0, 0.1, 0.002)
        val BOWDEN = PaTower(0.0, 1.0, 0.02)
        /** Footprint of the tower; the corners show the effect best. */
        const val SIZE = 40.0
    }
}
