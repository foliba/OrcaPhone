package com.orcaphone.core.gcode

import com.orcaphone.core.config.FilamentProfile
import com.orcaphone.core.config.GCodeFlavor
import com.orcaphone.core.config.PrinterProfile
import com.orcaphone.core.config.ProcessProfile
import com.orcaphone.core.slice.CancelToken
import com.orcaphone.core.slice.Feature
import com.orcaphone.core.slice.LayerToolpaths
import com.orcaphone.core.slice.ToolPath
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.prep.PreparedGeometry
import org.locationtech.jts.geom.prep.PreparedGeometryFactory
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.EnumMap
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

class PrintStats(
    val timeSeconds: Double,
    val filamentMm: Double,
    val filamentGrams: Double,
    val filamentCm3: Double,
    val cost: Double,
    val layerCount: Int,
    val maxZ: Double,
    val timeByFeature: Map<Feature, Double>,
    val layerTimes: DoubleArray,
)

/**
 * Turns ordered tool paths into G-code: travel/retraction handling, extrusion amounts,
 * cooling (fan + minimum layer time), and a look-ahead time estimate.
 */
class GCodeGenerator(
    private val printer: PrinterProfile,
    private val filament: FilamentProfile,
    private val p: ProcessProfile,
    private val cancel: CancelToken = CancelToken(),
) {
    private companion object {
        const val TRAVEL = 0
        const val EXTRUDE = 1
        const val RETRACT = 2
        const val ZMOVE = 3
        const val M73_MARK = ";@M73 "
        const val VERSION = "0.2.2"
    }

    private sealed class Cmd
    private class Text(val s: String) : Cmd()
    private class Move(
        val kind: Int, val x: Double, val y: Double, val z: Double, val e: Double,
        var feed: Double, val accel: Double, val feature: Feature?,
    ) : Cmd() {
        var time = 0.0
        var startX = 0.0
        var startY = 0.0
        var startZ = 0.0
    }

    private val filamentArea = PI * filament.diameter * filament.diameter / 4
    private val retractLen = if (filament.retractionLengthOverride > 0) filament.retractionLengthOverride else printer.retractionLength
    private val gf = GeometryFactory()

    // machine state while building
    private var x = 0.0
    private var y = 0.0
    private var z = 0.0
    private var retracted = false
    private var curFeature: Feature? = null
    private var curWidth = -1.0
    private var curAccel = -1.0
    private var lastFanPercent = -1

    // writer state
    private var lastF = -1.0
    private var outX = Double.NaN
    private var outY = Double.NaN
    private var outZ = Double.NaN

    private var filamentUsed = 0.0
    private val featureTime = EnumMap<Feature, Double>(Feature::class.java)

    fun generate(layers: List<LayerToolpaths>, outFile: File, progress: (Float) -> Unit): PrintStats {
        val body = File(outFile.parentFile, outFile.name + ".body")
        val layerTimes = DoubleArray(layers.size)
        val maxZ = layers.lastOrNull()?.z ?: 0.0
        val values = placeholderValues(layers.size, maxZ)

        BufferedWriter(FileWriter(body), 1 shl 16).use { w ->
            val sb = StringBuilder(1 shl 16)
            // --- start
            sb.append("; EXECUTABLE_BLOCK_START\n")
            if (printer.emitM73) sb.append(M73_MARK).append("-1\n")
            val start = Placeholders.apply(printer.startGcode, values)
            if (printer.autoTemperatureCommands) {
                if (!start.containsCommand("M190") && !start.containsCommand("M140")) sb.append("M190 S").append(filament.bedTempInitial).append(" ; wait for bed\n")
                if (!start.containsCommand("M109") && !start.containsCommand("M104")) sb.append("M109 S").append(filament.nozzleTempInitial).append(" ; wait for nozzle\n")
            }
            sb.append(start).append('\n')
            sb.append("G90\nM83 ; relative extrusion\n")
            if (printer.flavor == GCodeFlavor.KLIPPER) sb.append("SET_PRINT_STATS_INFO TOTAL_LAYER=").append(layers.size).append('\n')
            if (filament.closeFanFirstLayers > 0) { sb.append("M107\n"); lastFanPercent = 0 }
            w.write(sb.toString()); sb.setLength(0)
            x = 0.0; y = 0.0; z = 0.0

            // --- layers
            for ((li, layer) in layers.withIndex()) {
                cancel.check()
                val sx = x; val sy = y; val sz = z
                val cmds = buildLayer(layer, li, values)
                var t = plan(cmds, sx, sy, sz)
                val baseTime = t
                // minimum layer time: slow extrusion down
                if (t > 0 && t < filament.slowDownLayerTime) {
                    val factor = t / filament.slowDownLayerTime
                    for (c in cmds) if (c is Move && c.kind == EXTRUDE) {
                        c.feed = max(min(c.feed, filament.slowDownMinSpeed), c.feed * factor)
                    }
                    t = plan(cmds, sx, sy, sz)
                }
                // fan
                val fan = when {
                    li < filament.closeFanFirstLayers -> 0
                    baseTime < filament.slowDownLayerTime -> filament.fanMaxSpeed
                    baseTime < filament.fanCoolingLayerTime && filament.fanCoolingLayerTime > filament.slowDownLayerTime -> {
                        val f = (baseTime - filament.slowDownLayerTime) / (filament.fanCoolingLayerTime - filament.slowDownLayerTime)
                        (filament.fanMaxSpeed + (filament.fanMinSpeed - filament.fanMaxSpeed) * f).roundToInt()
                    }
                    else -> filament.fanMinSpeed
                }
                layerTimes[li] = t
                for (c in cmds) if (c is Move) {
                    val f = if (c.kind == EXTRUDE) c.feature ?: Feature.TRAVEL else Feature.TRAVEL
                    featureTime[f] = (featureTime[f] ?: 0.0) + c.time
                }
                writeLayer(sb, cmds, fan)
                w.write(sb.toString()); sb.setLength(0)
                if (li % 4 == 0) progress(0.9f * li / layers.size)
            }

            // --- end
            if (!retracted) emitRetractText(sb)
            if (printer.flavor == GCodeFlavor.KLIPPER) sb.append("SET_PRINT_STATS_INFO CURRENT_LAYER=").append(layers.size).append('\n')
            sb.append(Placeholders.apply(printer.endGcode, values)).append('\n')
            if (printer.emitM73) sb.append("M73 P100 R0\n")
            sb.append("; EXECUTABLE_BLOCK_END\n")
            w.write(sb.toString())
        }

        val total = layerTimes.sum()
        val cm3 = filamentUsed * filamentArea / 1000.0
        val grams = cm3 * filament.density
        val stats = PrintStats(
            timeSeconds = total, filamentMm = filamentUsed, filamentGrams = grams, filamentCm3 = cm3,
            cost = grams / 1000.0 * filament.costPerKg, layerCount = layers.size, maxZ = maxZ,
            timeByFeature = featureTime.toMap(), layerTimes = layerTimes,
        )

        // --- assemble: header + body with M73 placeholders resolved
        val elapsedAtLayer = DoubleArray(layers.size + 1)
        for (i in layers.indices) elapsedAtLayer[i + 1] = elapsedAtLayer[i] + layerTimes[i]
        BufferedWriter(FileWriter(outFile), 1 shl 16).use { w ->
            w.write(header(stats))
            BufferedReader(FileReader(body), 1 shl 16).use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    if (line.startsWith(M73_MARK)) {
                        val idx = line.substring(M73_MARK.length).trim().toInt()
                        val elapsed = if (idx < 0) 0.0 else elapsedAtLayer[idx]
                        val pct = if (total > 0) (elapsed / total * 100).toInt() else 0
                        val rem = ((total - elapsed) / 60).roundToInt()
                        w.write("M73 P$pct R$rem\n")
                    } else {
                        w.write(line); w.write("\n")
                    }
                }
            }
            w.write(footer(stats))
        }
        body.delete()
        progress(1f)
        return stats
    }

    private fun String.containsCommand(cmd: String) = lineSequence().any { it.trim().uppercase().startsWith(cmd) }

    private fun placeholderValues(layerCount: Int, maxZ: Double): MutableMap<String, String> {
        fun n(v: Double) = NumFmt.format(v, 3)
        return mutableMapOf(
            "nozzle_temperature_initial_layer" to filament.nozzleTempInitial.toString(),
            "first_layer_temperature" to filament.nozzleTempInitial.toString(),
            "nozzle_temperature" to filament.nozzleTemp.toString(),
            "temperature" to filament.nozzleTemp.toString(),
            "bed_temperature_initial_layer_single" to filament.bedTempInitial.toString(),
            "bed_temperature_initial_layer" to filament.bedTempInitial.toString(),
            "first_layer_bed_temperature" to filament.bedTempInitial.toString(),
            "bed_temperature" to filament.bedTemp.toString(),
            "hot_plate_temp" to filament.bedTemp.toString(),
            "layer_height" to n(p.layerHeight),
            "initial_layer_print_height" to n(p.initialLayerHeight),
            "first_layer_height" to n(p.initialLayerHeight),
            "nozzle_diameter" to n(printer.nozzleDiameter),
            "filament_diameter" to n(filament.diameter),
            "filament_type" to filament.type,
            "total_layer_count" to layerCount.toString(),
            "max_layer_z" to n(maxZ),
            "max_print_height" to n(printer.maxHeight),
            "printable_height" to n(printer.maxHeight),
            "max_layer_z_plus_10" to n(min(maxZ + 10, printer.maxHeight)),
            "bed_width" to n(printer.bedWidth),
            "bed_depth" to n(printer.bedDepth),
            "travel_speed" to n(p.travelSpeed),
            "layer_num" to "0",
            "layer_z" to "0",
        )
    }

    // ------------------------------------------------------------------ building

    private fun eRate(width: Double, height: Double, flow: Double): Double {
        val area = if (width > height) height * (width - height) + PI * height * height / 4 else PI * width * width / 4
        return area / filamentArea * filament.flowRatio * flow
    }

    private fun volumetricLimit(width: Double, height: Double): Double {
        val area = if (width > height) height * (width - height) + PI * height * height / 4 else PI * width * width / 4
        return if (filament.maxVolumetricSpeed > 0) filament.maxVolumetricSpeed / area else Double.MAX_VALUE
    }

    private fun accelFor(feature: Feature?, layer: Int): Double {
        val a = when {
            layer == 0 -> p.initialLayerAcceleration
            feature == null -> p.travelAcceleration
            feature == Feature.OUTER_WALL -> p.outerWallAcceleration
            feature == Feature.TOP_SURFACE || feature == Feature.IRONING -> p.topSurfaceAcceleration
            else -> p.defaultAcceleration
        }
        return min(a, printer.maxAcceleration).coerceAtLeast(100.0)
    }

    private fun buildLayer(layer: LayerToolpaths, li: Int, values: MutableMap<String, String>): ArrayList<Cmd> {
        val cmds = ArrayList<Cmd>(layer.paths.sumOf { it.pointCount } + 64)
        val h = layer.height
        val spiralLayer = layer.paths.any { it.spiral }
        cmds.add(Text(buildString {
            append(";LAYER_CHANGE\n;Z:"); NumFmt.append(this, layer.z, 3)
            append("\n;HEIGHT:"); NumFmt.append(this, h, 3)
        }))
        if (printer.emitM73) cmds.add(Text(M73_MARK + li))
        if (printer.flavor == GCodeFlavor.KLIPPER) cmds.add(Text("SET_PRINT_STATS_INFO CURRENT_LAYER=${li + 1}"))
        if (printer.layerChangeGcode.isNotBlank()) {
            values["layer_num"] = li.toString()
            values["layer_z"] = NumFmt.format(layer.z, 3)
            cmds.add(Text(Placeholders.apply(printer.layerChangeGcode, values)))
        }
        if (li == 1) {
            if (filament.nozzleTemp != filament.nozzleTempInitial) cmds.add(Text("M104 S${filament.nozzleTemp}"))
            if (filament.bedTemp != filament.bedTempInitial) cmds.add(Text("M140 S${filament.bedTemp}"))
        }
        cmds.add(Text("@FAN"))

        val travelArea: PreparedGeometry? = layer.travelArea?.let {
            if (it.isEmpty) null else PreparedGeometryFactory.prepare(it)
        }

        if (!spiralLayer) {
            if (li > 0 && !retracted) retract(cmds)
            val travelAccel = accelFor(null, li)
            cmds.add(Move(ZMOVE, x, y, layer.z, 0.0, printer.maxSpeedZ, travelAccel, null))
            z = layer.z
        }

        for (path in layer.paths) {
            travelTo(cmds, path.startX, path.startY, layer, li, travelArea, spiralLayer)
            if (path.feature != curFeature) {
                cmds.add(Text(";TYPE:" + path.feature.gcodeName))
                curFeature = path.feature
            }
            val accel = accelFor(path.feature, li)
            if (printer.emitAcceleration && accel != curAccel) {
                cmds.add(Text("M204 S" + accel.roundToInt()))
                curAccel = accel
            }
            if (abs(path.width - curWidth) > 1e-4) {
                cmds.add(Text(";WIDTH:" + NumFmt.format(path.width, 3)))
                curWidth = path.width
            }
            if (retracted) unretract(cmds)
            val rate = eRate(path.width, h, path.flow)
            val feed = minOf(path.speed, volumetricLimit(path.width, h), printer.maxSpeedXY)
            val n = path.pointCount
            val pts = path.points
            val total = if (path.spiral) path.length() else 0.0
            val z0 = layer.z - h
            var acc = 0.0
            val last = if (path.closed) n else n - 1
            for (k in 1..last) {
                val idx = k % n
                val nx = pts[idx * 2]; val ny = pts[idx * 2 + 1]
                val dx = nx - x; val dy = ny - y
                val len = sqrt(dx * dx + dy * dy)
                if (len < 1e-4) continue
                acc += len
                val nz = if (path.spiral && total > 0) z0 + h * (acc / total) else z
                val e = len * rate
                cmds.add(Move(EXTRUDE, nx, ny, nz, e, feed, accel, path.feature))
                filamentUsed += e
                x = nx; y = ny; z = nz
            }
            if (path.spiral) z = layer.z
        }
        return cmds
    }

    private fun travelTo(
        cmds: MutableList<Cmd>, tx: Double, ty: Double, layer: LayerToolpaths, li: Int,
        area: PreparedGeometry?, spiral: Boolean,
    ) {
        val dx = tx - x; val dy = ty - y
        val dist = sqrt(dx * dx + dy * dy)
        if (dist < 1e-4) return
        val accel = accelFor(null, li)
        val needRetract = !spiral && dist > printer.retractionMinTravel && !retracted &&
            (area == null || !area.covers(gf.createLineString(arrayOf(Coordinate(x, y), Coordinate(tx, ty)))))
        if (needRetract) retract(cmds)
        val hop = retracted && printer.zHop > 0 && !spiral
        if (hop) cmds.add(Move(ZMOVE, x, y, z + printer.zHop, 0.0, printer.maxSpeedZ, accel, null))
        if (printer.emitAcceleration && accel != curAccel) {
            cmds.add(Text("M204 S" + accel.roundToInt()))
            curAccel = accel
        }
        cmds.add(Move(TRAVEL, tx, ty, if (hop) z + printer.zHop else z, 0.0, min(p.travelSpeed, printer.maxSpeedXY), accel, null))
        if (hop) cmds.add(Move(ZMOVE, tx, ty, z, 0.0, printer.maxSpeedZ, accel, null))
        x = tx; y = ty
    }

    private fun retract(cmds: MutableList<Cmd>) {
        if (retracted) return
        if (printer.firmwareRetraction) cmds.add(Text("G10"))
        else if (retractLen > 0) cmds.add(Move(RETRACT, x, y, z, -retractLen, printer.retractionSpeed, 0.0, null))
        retracted = true
    }

    private fun unretract(cmds: MutableList<Cmd>) {
        if (!retracted) return
        if (printer.firmwareRetraction) cmds.add(Text("G11"))
        else if (retractLen > 0) cmds.add(Move(RETRACT, x, y, z, retractLen, printer.deretractionSpeed, 0.0, null))
        retracted = false
    }

    private fun emitRetractText(sb: StringBuilder) {
        if (printer.firmwareRetraction) sb.append("G10\n")
        else if (retractLen > 0) {
            sb.append("G1 E-"); NumFmt.append(sb, retractLen, 3)
            sb.append(" F"); NumFmt.append(sb, printer.retractionSpeed * 60, 0); sb.append('\n')
        }
        retracted = true
    }

    // ------------------------------------------------------------------ planner

    /** Look-ahead trapezoid planner (Klipper-style junction deviation). Returns layer time in seconds. */
    private fun plan(cmds: List<Cmd>, sx: Double, sy: Double, sz: Double): Double {
        var total = 0.0
        val block = ArrayList<Move>()
        var px = sx; var py = sy; var pz = sz
        fun flush() {
            if (block.isNotEmpty()) total += planBlock(block)
            block.clear()
        }
        for (c in cmds) {
            if (c !is Move) continue
            if (c.kind == RETRACT) {
                flush()
                c.time = abs(c.e) / max(1.0, c.feed)
                total += c.time
                continue
            }
            block.add(c)
            c.startX = px; c.startY = py; c.startZ = pz
            px = c.x; py = c.y; pz = c.z
        }
        flush()
        return total
    }


    private fun planBlock(moves: List<Move>): Double {
        val k = moves.size
        val d = DoubleArray(k); val ux = DoubleArray(k); val uy = DoubleArray(k); val uz = DoubleArray(k)
        val vmax = DoubleArray(k); val acc = DoubleArray(k)
        for (i in 0 until k) {
            val m = moves[i]
            val dx = m.x - m.startX; val dy = m.y - m.startY; val dz = m.z - m.startZ
            val len = sqrt(dx * dx + dy * dy + dz * dz)
            d[i] = len
            if (len > 0) { ux[i] = dx / len; uy[i] = dy / len; uz[i] = dz / len }
            vmax[i] = max(0.1, m.feed)
            acc[i] = if (m.kind == ZMOVE) 500.0 else max(100.0, m.accel)
        }
        val scv = printer.squareCornerVelocity
        val jl = DoubleArray(k)
        for (i in 1 until k) {
            val cosT = -(ux[i - 1] * ux[i] + uy[i - 1] * uy[i] + uz[i - 1] * uz[i])
            jl[i] = when {
                d[i] == 0.0 || d[i - 1] == 0.0 -> 0.0
                cosT > 0.999999 -> 0.0
                cosT < -0.999999 -> Double.MAX_VALUE
                else -> {
                    val sinD2 = sqrt(0.5 * (1 - cosT))
                    val rJd = sinD2 / (1 - sinD2)
                    val tanD2 = sinD2 / sqrt(0.5 * (1 + cosT))
                    val a = min(acc[i], acc[i - 1])
                    val jd = scv * scv * (sqrt(2.0) - 1) / a
                    val v2 = minOf(rJd * jd * a, 0.5 * d[i] * tanD2 * acc[i], 0.5 * d[i - 1] * tanD2 * acc[i - 1])
                    sqrt(v2)
                }
            }
        }
        val vs = DoubleArray(k); val ve = DoubleArray(k)
        var next = 0.0
        for (i in k - 1 downTo 0) {
            ve[i] = min(next, vmax[i])
            vs[i] = minOf(jl[i], vmax[i], sqrt(ve[i] * ve[i] + 2 * acc[i] * d[i]))
            next = vs[i]
        }
        var prev = 0.0
        var total = 0.0
        for (i in 0 until k) {
            vs[i] = min(vs[i], prev)
            ve[i] = min(ve[i], sqrt(vs[i] * vs[i] + 2 * acc[i] * d[i]))
            prev = ve[i]
            val t = trapezoid(d[i], vs[i], ve[i], vmax[i], acc[i])
            moves[i].time = t
            total += t
        }
        return total
    }

    private fun trapezoid(d: Double, v0: Double, v1: Double, vmax: Double, a: Double): Double {
        if (d <= 0) return 0.0
        val da = (vmax * vmax - v0 * v0) / (2 * a)
        val dd = (vmax * vmax - v1 * v1) / (2 * a)
        return if (da + dd <= d) {
            (vmax - v0) / a + (vmax - v1) / a + (d - da - dd) / vmax
        } else {
            val vp = sqrt(max(0.0, (2 * a * d + v0 * v0 + v1 * v1) / 2))
            max(0.0, (vp - v0) / a) + max(0.0, (vp - v1) / a)
        }
    }

    // ------------------------------------------------------------------ writing

    private fun writeLayer(sb: StringBuilder, cmds: List<Cmd>, fanPercent: Int) {
        for (c in cmds) {
            when (c) {
                is Text -> {
                    if (c.s == "@FAN") {
                        if (fanPercent != lastFanPercent) {
                            if (fanPercent <= 0) sb.append("M107\n")
                            else sb.append("M106 S").append((fanPercent.coerceIn(0, 100) * 255 / 100.0).roundToInt()).append('\n')
                            lastFanPercent = fanPercent
                        }
                    } else sb.append(c.s).append('\n')
                }
                is Move -> writeMove(sb, c)
            }
        }
    }

    private fun writeMove(sb: StringBuilder, m: Move) {
        val f = m.feed * 60
        when (m.kind) {
            RETRACT -> {
                sb.append("G1 E"); NumFmt.append(sb, m.e, 5)
                sb.append(" F"); NumFmt.append(sb, f, 0); lastF = f
            }
            ZMOVE -> {
                if (m.z == outZ) return
                sb.append("G1 Z"); NumFmt.append(sb, m.z, 3); outZ = m.z
                sb.append(" F"); NumFmt.append(sb, f, 0); lastF = f
            }
            else -> {
                sb.append("G1")
                if (m.x != outX) { sb.append(" X"); NumFmt.append(sb, m.x, 3); outX = m.x }
                if (m.y != outY) { sb.append(" Y"); NumFmt.append(sb, m.y, 3); outY = m.y }
                if (m.z != outZ) { sb.append(" Z"); NumFmt.append(sb, m.z, 3); outZ = m.z }
                if (m.kind == EXTRUDE) { sb.append(" E"); NumFmt.append(sb, m.e, 5) }
                if (abs(f - lastF) > 0.5) { sb.append(" F"); NumFmt.append(sb, f, 0); lastF = f }
            }
        }
        sb.append('\n')
    }

    private fun header(s: PrintStats): String = buildString {
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        append("; generated by OrcaPhone ").append(VERSION).append(" on ").append(date).append('\n')
        append(";\n; HEADER_BLOCK_START\n")
        append("; printer = ").append(printer.name).append('\n')
        append("; filament = ").append(filament.name).append('\n')
        append("; process = ").append(p.name).append('\n')
        append("; estimated printing time (normal mode) = ").append(NumFmt.duration(s.timeSeconds)).append('\n')
        append("; total layer number: ").append(s.layerCount).append('\n')
        append("; filament used [mm] = "); NumFmt.append(this, s.filamentMm, 2); append('\n')
        append("; filament used [g] = "); NumFmt.append(this, s.filamentGrams, 2); append('\n')
        append("; max_z_height: "); NumFmt.append(this, s.maxZ, 2); append('\n')
        append("; HEADER_BLOCK_END\n\n")
    }

    private fun footer(s: PrintStats): String = buildString {
        append("\n; filament used [mm] = "); NumFmt.append(this, s.filamentMm, 2)
        append("\n; filament used [cm3] = "); NumFmt.append(this, s.filamentCm3, 2)
        append("\n; filament used [g] = "); NumFmt.append(this, s.filamentGrams, 2)
        append("\n; filament cost = "); NumFmt.append(this, s.cost, 2)
        append("\n; total layers count = ").append(s.layerCount)
        append("\n; estimated printing time (normal mode) = ").append(NumFmt.duration(s.timeSeconds))
        append("\n; layer_height = "); NumFmt.append(this, p.layerHeight, 3)
        append("\n; first_layer_height = "); NumFmt.append(this, p.initialLayerHeight, 3)
        append("\n; nozzle_diameter = "); NumFmt.append(this, printer.nozzleDiameter, 2)
        append("\n; filament_type = ").append(filament.type)
        append("\n; filament_diameter = "); NumFmt.append(this, filament.diameter, 2)
        append("\n; temperature = ").append(filament.nozzleTemp)
        append("\n; bed_temperature = ").append(filament.bedTemp)
        append("\n; sparse_infill_density = ").append(p.sparseInfillDensity.roundToInt()).append('%')
        append("\n; wall_loops = ").append(p.wallLoops)
        append("\n; enable_support = ").append(if (p.enableSupport) 1 else 0)
        append('\n')
    }
}
