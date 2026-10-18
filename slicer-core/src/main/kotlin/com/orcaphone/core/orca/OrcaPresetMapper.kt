package com.orcaphone.core.orca

import com.orcaphone.core.config.BrimType
import com.orcaphone.core.config.FilamentProfile
import com.orcaphone.core.config.GCodeFlavor
import com.orcaphone.core.config.InfillPattern
import com.orcaphone.core.config.PrinterProfile
import com.orcaphone.core.config.ProcessProfile
import com.orcaphone.core.config.SeamPosition
import com.orcaphone.core.config.SupportType
import com.orcaphone.core.config.SurfacePattern
import com.orcaphone.core.config.WallOrder
import com.orcaphone.core.gcode.NumFmt
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.roundToInt

/** OrcaSlicer preset kinds, named by the "type" value used in Orca Cloud sync content. */
enum class OrcaKind(val iot: String) {
    PRINTER("printer"), FILAMENT("filament"), PROCESS("print");

    companion object {
        fun fromIot(s: String?): OrcaKind? = entries.firstOrNull { it.iot == s }
    }
}

/**
 * Converts between OrcaSlicer presets (key -> value maps) and OrcaPhone profiles.
 *
 * Two value encodings exist:
 *  - "raw": as in OrcaSlicer's system profile JSON and [OrcaCatalog] (real newlines, single values)
 *  - "serialized": OrcaSlicer's ConfigOption::serialize() form, used in Orca Cloud sync content
 *    (C-style escapes for G-code, quoted string lists, comma-separated vectors)
 * Profiles are built from raw values; [decode] / [encode] convert at the sync boundary.
 */
object OrcaPresetMapper {

    private enum class Enc { VALUE, STRING, STRINGS }

    private class Field<T>(val key: String, val enc: Enc, val read: (T, String) -> T, val write: (T) -> String)

    // ------------------------------------------------------------------ value helpers

    /** First element of a comma separated vector ("500,200" -> 500). */
    private fun num(s: String): Double = s.substringBefore(',').trim().removeSuffix("%").toDouble()

    private fun int(s: String): Int = num(s).roundToInt()

    private fun bool(s: String): Boolean = s.substringBefore(',').trim().let { it == "1" || it.equals("true", true) }

    private fun pct(s: String): Double = num(s)

    /** Value or percentage of a reference (Orca's FloatOrPercent). 0 means "use the default". */
    private fun widthOr(s: String, ref: Double, fallback: Double): Double {
        val t = s.substringBefore(',').trim()
        val v = if (t.endsWith("%")) t.removeSuffix("%").toDouble() / 100.0 * ref else t.toDouble()
        return if (v <= 0) fallback else v
    }

    private fun f(v: Double): String = NumFmt.format(v, 4)
    private fun b(v: Boolean): String = if (v) "1" else "0"

    private fun isNil(s: String) = s.trim().let { it.isEmpty() || it == "nil" }

    /** Bed size from "0x0,256x0,256x256,0x256". */
    private fun area(s: String): Pair<Double, Double> {
        val pts = s.split(',').map { p -> p.trim().split('x').let { it[0].toDouble() to it[1].toDouble() } }
        return (pts.maxOf { it.first } - pts.minOf { it.first }) to (pts.maxOf { it.second } - pts.minOf { it.second })
    }

    // ------------------------------------------------------------------ printer

    private val printerFields: List<Field<PrinterProfile>> = listOf(
        Field("printable_area", Enc.VALUE, { p, v -> area(v).let { (w, d) -> p.copy(bedWidth = w, bedDepth = d) } },
            { p -> "0x0,${f(p.bedWidth)}x0,${f(p.bedWidth)}x${f(p.bedDepth)},0x${f(p.bedDepth)}" }),
        Field("printable_height", Enc.VALUE, { p, v -> p.copy(maxHeight = num(v)) }, { f(it.maxHeight) }),
        Field("nozzle_diameter", Enc.VALUE, { p, v -> p.copy(nozzleDiameter = num(v)) }, { f(it.nozzleDiameter) }),
        Field("gcode_flavor", Enc.VALUE, { p, v ->
            p.copy(flavor = when (v.trim()) {
                "klipper" -> GCodeFlavor.KLIPPER
                "reprapfirmware", "reprap" -> GCodeFlavor.REPRAP
                else -> GCodeFlavor.MARLIN
            })
        }, { when (it.flavor) { GCodeFlavor.KLIPPER -> "klipper"; GCodeFlavor.REPRAP -> "reprapfirmware"; GCodeFlavor.MARLIN -> "marlin2" } }),
        Field("machine_max_speed_x", Enc.VALUE, { p, v -> p.copy(maxSpeedXY = num(v)) }, { f(it.maxSpeedXY) }),
        Field("machine_max_speed_z", Enc.VALUE, { p, v -> p.copy(maxSpeedZ = num(v)) }, { f(it.maxSpeedZ) }),
        Field("machine_max_acceleration_x", Enc.VALUE, { p, v -> p.copy(maxAcceleration = num(v)) }, { f(it.maxAcceleration) }),
        Field("machine_max_acceleration_extruding", Enc.VALUE, { p, v -> p.copy(maxAcceleration = minOf(p.maxAcceleration, num(v))) },
            { f(it.maxAcceleration) }),
        Field("machine_max_jerk_x", Enc.VALUE, { p, v -> p.copy(squareCornerVelocity = num(v)) }, { f(it.squareCornerVelocity) }),
        Field("retraction_length", Enc.VALUE, { p, v -> p.copy(retractionLength = num(v)) }, { f(it.retractionLength) }),
        Field("retraction_speed", Enc.VALUE, { p, v -> p.copy(retractionSpeed = num(v)) }, { f(it.retractionSpeed) }),
        Field("deretraction_speed", Enc.VALUE, { p, v -> num(v).let { d -> p.copy(deretractionSpeed = if (d > 0) d else p.retractionSpeed) } },
            { f(it.deretractionSpeed) }),
        Field("retraction_minimum_travel", Enc.VALUE, { p, v -> p.copy(retractionMinTravel = num(v)) }, { f(it.retractionMinTravel) }),
        Field("z_hop", Enc.VALUE, { p, v -> p.copy(zHop = num(v)) }, { f(it.zHop) }),
        Field("use_firmware_retraction", Enc.VALUE, { p, v -> p.copy(firmwareRetraction = bool(v)) }, { b(it.firmwareRetraction) }),
        Field("min_layer_height", Enc.VALUE, { p, v -> p.copy(minLayerHeight = num(v)) }, { f(it.minLayerHeight) }),
        Field("max_layer_height", Enc.VALUE, { p, v -> p.copy(maxLayerHeight = num(v)) }, { f(it.maxLayerHeight) }),
        Field("machine_start_gcode", Enc.STRING, { p, v -> p.copy(startGcode = v, autoTemperatureCommands = true) }, { it.startGcode }),
        Field("machine_end_gcode", Enc.STRING, { p, v -> p.copy(endGcode = v) }, { it.endGcode }),
        Field("layer_change_gcode", Enc.STRING, { p, v -> p.copy(layerChangeGcode = v) }, { it.layerChangeGcode }),
    )

    // ------------------------------------------------------------------ filament

    private val filamentFields: List<Field<FilamentProfile>> = listOf(
        Field("filament_type", Enc.STRINGS, { p, v -> p.copy(type = v) }, { it.type }),
        Field("filament_colour", Enc.STRINGS, { p, v -> if (v.length == 7 && v.startsWith("#")) p.copy(colorHex = v) else p }, { it.colorHex }),
        Field("filament_diameter", Enc.VALUE, { p, v -> p.copy(diameter = num(v)) }, { f(it.diameter) }),
        Field("filament_density", Enc.VALUE, { p, v -> p.copy(density = num(v)) }, { f(it.density) }),
        Field("filament_cost", Enc.VALUE, { p, v -> p.copy(costPerKg = num(v)) }, { f(it.costPerKg) }),
        Field("filament_flow_ratio", Enc.VALUE, { p, v -> p.copy(flowRatio = num(v)) }, { f(it.flowRatio) }),
        Field("nozzle_temperature", Enc.VALUE, { p, v -> p.copy(nozzleTemp = int(v)) }, { it.nozzleTemp.toString() }),
        Field("nozzle_temperature_initial_layer", Enc.VALUE, { p, v -> p.copy(nozzleTempInitial = int(v)) }, { it.nozzleTempInitial.toString() }),
        Field("hot_plate_temp", Enc.VALUE, { p, v -> p.copy(bedTemp = int(v)) }, { it.bedTemp.toString() }),
        Field("hot_plate_temp_initial_layer", Enc.VALUE, { p, v -> p.copy(bedTempInitial = int(v)) }, { it.bedTempInitial.toString() }),
        Field("filament_max_volumetric_speed", Enc.VALUE, { p, v -> num(v).let { s -> if (s > 0) p.copy(maxVolumetricSpeed = s) else p } },
            { f(it.maxVolumetricSpeed) }),
        Field("fan_min_speed", Enc.VALUE, { p, v -> p.copy(fanMinSpeed = int(v)) }, { it.fanMinSpeed.toString() }),
        Field("fan_max_speed", Enc.VALUE, { p, v -> p.copy(fanMaxSpeed = int(v)) }, { it.fanMaxSpeed.toString() }),
        Field("close_fan_the_first_x_layers", Enc.VALUE, { p, v -> p.copy(closeFanFirstLayers = int(v)) }, { it.closeFanFirstLayers.toString() }),
        Field("fan_cooling_layer_time", Enc.VALUE, { p, v -> p.copy(fanCoolingLayerTime = num(v)) }, { f(it.fanCoolingLayerTime) }),
        Field("slow_down_layer_time", Enc.VALUE, { p, v -> p.copy(slowDownLayerTime = num(v)) }, { f(it.slowDownLayerTime) }),
        Field("slow_down_min_speed", Enc.VALUE, { p, v -> p.copy(slowDownMinSpeed = num(v)) }, { f(it.slowDownMinSpeed) }),
        Field("filament_retraction_length", Enc.VALUE, { p, v -> if (isNil(v)) p.copy(retractionLengthOverride = 0.0) else p.copy(retractionLengthOverride = num(v)) },
            { if (it.retractionLengthOverride > 0) f(it.retractionLengthOverride) else "nil" }),
    )

    // ------------------------------------------------------------------ process

    private fun processFields(nozzle: Double): List<Field<ProcessProfile>> = listOf(
        Field("layer_height", Enc.VALUE, { p, v -> p.copy(layerHeight = num(v)) }, { f(it.layerHeight) }),
        Field("initial_layer_print_height", Enc.VALUE, { p, v -> p.copy(initialLayerHeight = num(v)) }, { f(it.initialLayerHeight) }),
        Field("line_width", Enc.VALUE, { p, v -> p.copy(lineWidth = widthOr(v, nozzle, nozzle * 1.05)) }, { f(it.lineWidth) }),
        Field("outer_wall_line_width", Enc.VALUE, { p, v -> p.copy(outerWallLineWidth = widthOr(v, nozzle, p.lineWidth)) }, { f(it.outerWallLineWidth) }),
        Field("initial_layer_line_width", Enc.VALUE, { p, v -> p.copy(initialLayerLineWidth = widthOr(v, nozzle, p.lineWidth)) }, { f(it.initialLayerLineWidth) }),
        Field("seam_position", Enc.VALUE, { p, v ->
            p.copy(seamPosition = when (v.trim()) { "back" -> SeamPosition.BACK; "nearest" -> SeamPosition.NEAREST; "random" -> SeamPosition.RANDOM; else -> SeamPosition.ALIGNED })
        }, { it.seamPosition.name.lowercase() }),
        Field("elefant_foot_compensation", Enc.VALUE, { p, v -> p.copy(elephantFootCompensation = num(v)) }, { f(it.elephantFootCompensation) }),
        Field("ironing_type", Enc.VALUE, { p, v -> p.copy(ironing = v.trim() != "no ironing") }, { if (it.ironing) "top" else "no ironing" }),
        Field("ironing_spacing", Enc.VALUE, { p, v -> p.copy(ironingSpacing = num(v)) }, { f(it.ironingSpacing) }),
        Field("ironing_flow", Enc.VALUE, { p, v -> p.copy(ironingFlow = pct(v)) }, { f(it.ironingFlow) + "%" }),
        Field("ironing_speed", Enc.VALUE, { p, v -> p.copy(ironingSpeed = num(v)) }, { f(it.ironingSpeed) }),
        Field("wall_loops", Enc.VALUE, { p, v -> p.copy(wallLoops = int(v).coerceAtLeast(1)) }, { it.wallLoops.toString() }),
        Field("wall_sequence", Enc.VALUE, { p, v -> p.copy(wallOrder = if (v.trim() == "outer wall/inner wall") WallOrder.OUTER_INNER else WallOrder.INNER_OUTER) },
            { if (it.wallOrder == WallOrder.OUTER_INNER) "outer wall/inner wall" else "inner wall/outer wall" }),
        Field("top_shell_layers", Enc.VALUE, { p, v -> p.copy(topShellLayers = int(v)) }, { it.topShellLayers.toString() }),
        Field("bottom_shell_layers", Enc.VALUE, { p, v -> p.copy(bottomShellLayers = int(v)) }, { it.bottomShellLayers.toString() }),
        Field("sparse_infill_density", Enc.VALUE, { p, v -> p.copy(sparseInfillDensity = pct(v)) }, { f(it.sparseInfillDensity) + "%" }),
        Field("sparse_infill_pattern", Enc.VALUE, { p, v -> p.copy(sparseInfillPattern = infill(v.trim())) }, { infillName(it.sparseInfillPattern) }),
        Field("top_surface_pattern", Enc.VALUE, { p, v -> p.copy(topSurfacePattern = surface(v.trim())) }, { surfaceName(it.topSurfacePattern) }),
        Field("bottom_surface_pattern", Enc.VALUE, { p, v -> p.copy(bottomSurfacePattern = surface(v.trim())) }, { surfaceName(it.bottomSurfacePattern) }),
        Field("infill_wall_overlap", Enc.VALUE, { p, v -> p.copy(infillWallOverlap = pct(v)) }, { f(it.infillWallOverlap) + "%" }),
        Field("infill_direction", Enc.VALUE, { p, v -> p.copy(sparseInfillDirection = num(v)) }, { f(it.sparseInfillDirection) }),
        Field("solid_infill_direction", Enc.VALUE, { p, v -> p.copy(solidInfillDirection = num(v)) }, { f(it.solidInfillDirection) }),
        Field("initial_layer_speed", Enc.VALUE, { p, v -> p.copy(initialLayerSpeed = num(v)) }, { f(it.initialLayerSpeed) }),
        Field("initial_layer_infill_speed", Enc.VALUE, { p, v -> p.copy(initialLayerInfillSpeed = num(v)) }, { f(it.initialLayerInfillSpeed) }),
        Field("outer_wall_speed", Enc.VALUE, { p, v -> p.copy(outerWallSpeed = num(v)) }, { f(it.outerWallSpeed) }),
        Field("inner_wall_speed", Enc.VALUE, { p, v -> p.copy(innerWallSpeed = num(v)) }, { f(it.innerWallSpeed) }),
        Field("sparse_infill_speed", Enc.VALUE, { p, v -> p.copy(sparseInfillSpeed = num(v)) }, { f(it.sparseInfillSpeed) }),
        Field("internal_solid_infill_speed", Enc.VALUE, { p, v -> p.copy(internalSolidInfillSpeed = num(v)) }, { f(it.internalSolidInfillSpeed) }),
        Field("top_surface_speed", Enc.VALUE, { p, v -> p.copy(topSurfaceSpeed = num(v)) }, { f(it.topSurfaceSpeed) }),
        Field("support_speed", Enc.VALUE, { p, v -> p.copy(supportSpeed = num(v)) }, { f(it.supportSpeed) }),
        Field("support_interface_speed", Enc.VALUE, { p, v -> p.copy(supportInterfaceSpeed = num(v)) }, { f(it.supportInterfaceSpeed) }),
        Field("travel_speed", Enc.VALUE, { p, v -> p.copy(travelSpeed = num(v)) }, { f(it.travelSpeed) }),
        Field("default_acceleration", Enc.VALUE, { p, v -> p.copy(defaultAcceleration = num(v)) }, { f(it.defaultAcceleration) }),
        Field("outer_wall_acceleration", Enc.VALUE, { p, v -> p.copy(outerWallAcceleration = num(v)) }, { f(it.outerWallAcceleration) }),
        Field("top_surface_acceleration", Enc.VALUE, { p, v -> p.copy(topSurfaceAcceleration = num(v)) }, { f(it.topSurfaceAcceleration) }),
        Field("initial_layer_acceleration", Enc.VALUE, { p, v -> p.copy(initialLayerAcceleration = num(v)) }, { f(it.initialLayerAcceleration) }),
        Field("travel_acceleration", Enc.VALUE, { p, v -> p.copy(travelAcceleration = num(v)) }, { f(it.travelAcceleration) }),
        Field("enable_support", Enc.VALUE, { p, v -> p.copy(enableSupport = bool(v)) }, { b(it.enableSupport) }),
        Field("support_type", Enc.VALUE, { p, v -> p.copy(supportType = if (v.trim().startsWith("tree")) SupportType.TREE else SupportType.NORMAL) },
            { if (it.supportType == SupportType.TREE) "tree(auto)" else "normal(auto)" }),
        Field("tree_support_branch_angle", Enc.VALUE, { p, v -> p.copy(treeBranchAngle = num(v)) }, { f(it.treeBranchAngle) }),
        Field("tree_support_branch_diameter", Enc.VALUE, { p, v -> p.copy(treeBranchDiameter = num(v)) }, { f(it.treeBranchDiameter) }),
        Field("support_threshold_angle", Enc.VALUE, { p, v -> num(v).let { a -> if (a > 0) p.copy(supportThresholdAngle = a) else p } },
            { f(it.supportThresholdAngle) }),
        Field("support_on_build_plate_only", Enc.VALUE, { p, v -> p.copy(supportOnBuildPlateOnly = bool(v)) }, { b(it.supportOnBuildPlateOnly) }),
        Field("support_top_z_distance", Enc.VALUE, { p, v -> p.copy(supportTopZDistance = num(v)) }, { f(it.supportTopZDistance) }),
        Field("support_object_xy_distance", Enc.VALUE, { p, v -> p.copy(supportObjectXYDistance = num(v)) }, { f(it.supportObjectXYDistance) }),
        Field("support_base_pattern_spacing", Enc.VALUE, { p, v -> p.copy(supportBaseSpacing = num(v)) }, { f(it.supportBaseSpacing) }),
        Field("support_interface_top_layers", Enc.VALUE, { p, v -> p.copy(supportInterfaceLayers = int(v)) }, { it.supportInterfaceLayers.toString() }),
        Field("support_interface_spacing", Enc.VALUE, { p, v -> p.copy(supportInterfaceSpacing = num(v)) }, { f(it.supportInterfaceSpacing) }),
        Field("skirt_loops", Enc.VALUE, { p, v -> p.copy(skirtLoops = int(v)) }, { it.skirtLoops.toString() }),
        Field("skirt_distance", Enc.VALUE, { p, v -> p.copy(skirtDistance = num(v)) }, { f(it.skirtDistance) }),
        Field("skirt_height", Enc.VALUE, { p, v -> p.copy(skirtHeight = int(v).coerceAtLeast(1)) }, { it.skirtHeight.toString() }),
        Field("brim_type", Enc.VALUE, { p, v ->
            p.copy(brimType = when (v.trim()) { "outer_only", "outer_and_inner", "brim_ears" -> BrimType.OUTER; else -> BrimType.NONE })
        }, { if (it.brimType == BrimType.OUTER) "outer_only" else "no_brim" }),
        Field("brim_width", Enc.VALUE, { p, v -> p.copy(brimWidth = num(v)) }, { f(it.brimWidth) }),
        Field("brim_object_gap", Enc.VALUE, { p, v -> p.copy(brimObjectGap = num(v)) }, { f(it.brimObjectGap) }),
        Field("spiral_mode", Enc.VALUE, { p, v -> p.copy(spiralMode = bool(v)) }, { b(it.spiralMode) }),
    )

    private fun infill(v: String): InfillPattern = when (v) {
        "gyroid", "3dhoneycomb" -> InfillPattern.GYROID
        "cubic", "adaptivecubic", "supportcubic" -> InfillPattern.CUBIC
        "triangles", "tri-hexagon", "honeycomb" -> InfillPattern.TRIANGLES
        "concentric" -> InfillPattern.CONCENTRIC
        "rectilinear", "zig-zag", "line", "alignedrectilinear", "monotonic", "monotonicline" -> InfillPattern.RECTILINEAR
        else -> InfillPattern.GRID // grid, crosshatch, lightning, ...
    }

    private fun infillName(p: InfillPattern) = when (p) {
        InfillPattern.GRID -> "grid"
        InfillPattern.RECTILINEAR -> "rectilinear"
        InfillPattern.TRIANGLES -> "triangles"
        InfillPattern.CUBIC -> "cubic"
        InfillPattern.GYROID -> "gyroid"
        InfillPattern.CONCENTRIC -> "concentric"
    }

    private fun surface(v: String): SurfacePattern = when (v) {
        "concentric" -> SurfacePattern.CONCENTRIC
        "rectilinear", "zig-zag", "alignedrectilinear" -> SurfacePattern.RECTILINEAR
        else -> SurfacePattern.MONOTONIC
    }

    private fun surfaceName(p: SurfacePattern) = when (p) {
        SurfacePattern.MONOTONIC -> "monotonic"
        SurfacePattern.RECTILINEAR -> "rectilinear"
        SurfacePattern.CONCENTRIC -> "concentric"
    }

    /** All OrcaSlicer keys OrcaPhone understands, per kind. */
    fun mappedKeys(kind: OrcaKind): List<String> = when (kind) {
        OrcaKind.PRINTER -> printerFields.map { it.key }
        OrcaKind.FILAMENT -> filamentFields.map { it.key }
        OrcaKind.PROCESS -> processFields(0.4).map { it.key }
    }

    private fun <T> apply(fields: List<Field<T>>, base: T, raw: Map<String, String>): T =
        fields.fold(base) { acc, fld ->
            val v = raw[fld.key] ?: return@fold acc
            if (fld.enc == Enc.VALUE && isNil(v)) return@fold acc
            runCatching { fld.read(acc, v) }.getOrDefault(acc)
        }

    // ------------------------------------------------------------------ raw values -> profiles

    fun toPrinter(raw: Map<String, String>, id: String, name: String): PrinterProfile {
        val p = apply(printerFields, PrinterProfile(id = id, name = name, vendor = raw["vendor"] ?: "Orca"), raw)
        val notes = if (usesUnsupportedScripting(p.startGcode) || usesUnsupportedScripting(p.endGcode))
            "This printer's G-code uses OrcaSlicer scripting ({if …}, expressions) that OrcaPhone cannot evaluate. Review the start/end G-code before printing."
        else ""
        return p.copy(notes = notes)
    }

    fun toFilament(raw: Map<String, String>, id: String, name: String): FilamentProfile {
        var f = apply(filamentFields, FilamentProfile(id = id, name = name), raw)
        // Printers without a "hot plate" setting fall back to the textured plate temperature.
        if (raw["hot_plate_temp"] == null) raw["textured_plate_temp"]?.let { v -> runCatching { f = f.copy(bedTemp = int(v)) } }
        if (raw["hot_plate_temp_initial_layer"] == null) raw["textured_plate_temp_initial_layer"]?.let { v -> runCatching { f = f.copy(bedTempInitial = int(v)) } }
        return f
    }

    fun toProcess(raw: Map<String, String>, id: String, name: String, nozzle: Double = 0.4): ProcessProfile =
        apply(processFields(nozzle), ProcessProfile(id = id, name = name), raw)

    /** True when G-code contains Orca's scripting language beyond simple placeholders. */
    fun usesUnsupportedScripting(gcode: String): Boolean {
        var i = gcode.indexOf('{')
        while (i >= 0) {
            val end = gcode.indexOf('}', i + 1)
            if (end < 0) return false
            val body = gcode.substring(i + 1, end).trim()
            if (body.startsWith("if") || body.startsWith("elsif") || body.startsWith("else") || body.startsWith("endif") ||
                body.any { it in "+*/<>=?(" || it == '-' && body.indexOf('-') > 0 }
            ) return true
            i = gcode.indexOf('{', end + 1)
        }
        return false
    }

    // ------------------------------------------------------------------ cloud content

    /** Decodes serialized sync content into raw values. */
    fun decode(kind: OrcaKind, content: Map<String, String>): Map<String, String> {
        val enc = encodings(kind)
        return content.mapValues { (k, v) ->
            when (enc[k]) {
                Enc.STRING -> unescape(v)
                Enc.STRINGS -> firstString(v)
                else -> v
            }
        }
    }

    /** Resolves cloud content against its OrcaSlicer system parent ("inherits"). */
    fun resolve(catalog: OrcaCatalog?, kind: OrcaKind, content: Map<String, String>): Map<String, String> {
        val parentName = content["inherits"]?.let { firstString(it) }?.takeIf { it.isNotBlank() }
        val parent = parentName?.let { catalog?.get(kind, it) } ?: emptyMap()
        return parent + decode(kind, content)
    }

    /**
     * Preset kind of a synced profile: its "type" (also the folder-style aliases), else the kind of the
     * system preset it inherits from, else typical keys. Like OrcaSlicer, an untyped profile is a process.
     */
    fun kindOf(content: Map<String, String>, catalog: OrcaCatalog? = null): OrcaKind {
        when (content["type"]?.let { firstString(it) }?.trim()?.lowercase()) {
            "printer", "machine", "printer_settings" -> return OrcaKind.PRINTER
            "filament", "filament_settings" -> return OrcaKind.FILAMENT
            "print", "process", "print_settings" -> return OrcaKind.PROCESS
        }
        val parent = content["inherits"]?.let { firstString(it) }?.takeIf { it.isNotBlank() }
        if (parent != null && catalog != null) {
            for (k in OrcaKind.entries) if (catalog.get(k, parent) != null) return k
        }
        return when {
            content.keys.any { it == "printable_area" || it == "machine_start_gcode" || it == "printer_model" || it.startsWith("machine_") } -> OrcaKind.PRINTER
            content.keys.any { it.startsWith("filament_") || it == "nozzle_temperature" || it.endsWith("_plate_temp") } -> OrcaKind.FILAMENT
            else -> OrcaKind.PROCESS
        }
    }

    /**
     * Builds the sync content to upload. Starts from the previous cloud content and only writes the
     * settings that changed compared to [imported] (what the previous content meant to OrcaPhone), so
     * values OrcaPhone cannot represent exactly are left untouched. With no previous version every
     * mapped setting is written.
     */
    fun <T : Any> buildContent(kind: OrcaKind, name: String, current: T, imported: T?, previous: Map<String, String>): Map<String, String> {
        @Suppress("UNCHECKED_CAST")
        val fields = when (kind) {
            OrcaKind.PRINTER -> printerFields
            OrcaKind.FILAMENT -> filamentFields
            OrcaKind.PROCESS -> processFields(0.4)
        } as List<Field<T>>
        val out = LinkedHashMap(previous)
        out.remove("updated_time")
        for (fld in fields) {
            val now = fld.write(current)
            if (imported != null && now == fld.write(imported)) continue
            out[fld.key] = encode(fld.enc, now)
        }
        out["name"] = name
        out["type"] = kind.iot
        if (previous.isEmpty()) out["from"] = "User"
        return out
    }

    private fun encodings(kind: OrcaKind): Map<String, Enc> = when (kind) {
        OrcaKind.PRINTER -> printerFields.associate { it.key to it.enc }
        OrcaKind.FILAMENT -> filamentFields.associate { it.key to it.enc }
        OrcaKind.PROCESS -> processFields(0.4).associate { it.key to it.enc }
    } + mapOf("inherits" to Enc.STRING, "name" to Enc.STRING)

    private fun encode(enc: Enc, raw: String): String = when (enc) {
        Enc.VALUE -> raw
        Enc.STRING -> escape(raw)
        Enc.STRINGS -> if (raw.isEmpty() || raw.any { it == ' ' || it == ';' || it == '\t' || it == '\\' || it == '"' || it == '\r' || it == '\n' })
            "\"" + escape(raw).replace("\"", "\\\"") + "\"" else raw
    }

    /** OrcaSlicer escape_string_cstyle. */
    fun escape(s: String): String = buildString(s.length + 16) {
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(c)
        }
    }

    /** OrcaSlicer unescape_string_cstyle. */
    fun unescape(s: String): String {
        if (!s.contains('\\')) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    else -> sb.append(n)
                }
                i += 2
            } else {
                sb.append(c); i++
            }
        }
        return sb.toString()
    }

    /** First element of an escape_strings_cstyle list: `PLA`, `"Bambu Lab"`, `"a";"b"`. */
    fun firstString(s: String): String {
        val t = s.trim()
        if (!t.startsWith("\"")) return t.substringBefore(';')
        val sb = StringBuilder()
        var i = 1
        while (i < t.length) {
            val c = t[i]
            if (c == '\\' && i + 1 < t.length) {
                when (val n = t[i + 1]) { 'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t'); else -> sb.append(n) }
                i += 2; continue
            }
            if (c == '"') break
            sb.append(c); i++
        }
        return sb.toString()
    }

    /**
     * Cloud setting id, identical to OrcaSlicer's generate_uuid_for_setting_id:
     * UUID v5 (SHA-1) of "user_id/name" in a fixed namespace.
     */
    fun settingId(name: String, userId: String): String {
        val ns = UUID.fromString("f47ac10b-58cc-4372-a567-0e02b2c3d479")
        val nsBytes = java.nio.ByteBuffer.allocate(16).putLong(ns.mostSignificantBits).putLong(ns.leastSignificantBits).array()
        val input = (if (userId.isEmpty()) name else "$userId/$name").toByteArray(Charsets.UTF_8)
        val hash = MessageDigest.getInstance("SHA-1").apply { update(nsBytes); update(input) }.digest()
        hash[6] = ((hash[6].toInt() and 0x0f) or 0x50).toByte()
        hash[8] = ((hash[8].toInt() and 0x3f) or 0x80).toByte()
        val bb = java.nio.ByteBuffer.wrap(hash, 0, 16)
        return UUID(bb.long, bb.long).toString()
    }
}
