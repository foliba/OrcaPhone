package com.orcaphone.ui.settings

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

/** Declarative description of one editable setting, so a single UI can edit all preset types. */
sealed class Setting<T>(val label: String, val help: String?) {
    class Number<T>(
        label: String, val unit: String, val get: (T) -> Double, val set: (T, Double) -> T,
        val min: Double, val max: Double, val decimals: Int = 2, help: String? = null,
    ) : Setting<T>(label, help)

    class Integer<T>(
        label: String, val unit: String, val get: (T) -> Int, val set: (T, Int) -> T,
        val min: Int, val max: Int, help: String? = null,
    ) : Setting<T>(label, help)

    class Toggle<T>(label: String, val get: (T) -> Boolean, val set: (T, Boolean) -> T, help: String? = null) :
        Setting<T>(label, help)

    class Choice<T, E>(
        label: String, val options: List<E>, val optionLabel: (E) -> String,
        val get: (T) -> E, val set: (T, E) -> T, help: String? = null,
    ) : Setting<T>(label, help)

    class Text<T>(
        label: String, val get: (T) -> String, val set: (T, String) -> T,
        val multiline: Boolean = true, val code: Boolean = true, help: String? = null,
    ) : Setting<T>(label, help)
}

class SettingGroup<T>(val title: String, val items: List<Setting<T>>)
class SettingPage<T>(val title: String, val groups: List<SettingGroup<T>>)

object ProcessSettings {
    val pages: List<SettingPage<ProcessProfile>> = listOf(
        SettingPage<ProcessProfile>(
            "Quality", listOf(
                SettingGroup<ProcessProfile>(
                    "Layer height", listOf(
                        Setting.Number("Layer height", "mm", { it.layerHeight }, { p, v -> p.copy(layerHeight = v) }, 0.04, 1.0, 2,
                            "Thinner layers give smoother surfaces but take longer."),
                        Setting.Number("First layer height", "mm", { it.initialLayerHeight }, { p, v -> p.copy(initialLayerHeight = v) }, 0.05, 1.0, 2,
                            "A thicker first layer improves bed adhesion."),
                    ),
                ),
                SettingGroup<ProcessProfile>(
                    "Line width", listOf(
                        Setting.Number("Default", "mm", { it.lineWidth }, { p, v -> p.copy(lineWidth = v) }, 0.1, 2.0, 2),
                        Setting.Number("Outer wall", "mm", { it.outerWallLineWidth }, { p, v -> p.copy(outerWallLineWidth = v) }, 0.1, 2.0, 2),
                        Setting.Number("First layer", "mm", { it.initialLayerLineWidth }, { p, v -> p.copy(initialLayerLineWidth = v) }, 0.1, 2.0, 2),
                    ),
                ),
                SettingGroup<ProcessProfile>(
                    "Seam", listOf(
                        Setting.Choice("Seam position", SeamPosition.entries, { it.label }, { it.seamPosition }, { p, v -> p.copy(seamPosition = v) },
                            "Where each outer wall loop starts and ends."),
                    ),
                ),
                SettingGroup<ProcessProfile>(
                    "Precision", listOf(
                        Setting.Number("Elephant foot compensation", "mm", { it.elephantFootCompensation }, { p, v -> p.copy(elephantFootCompensation = v) }, 0.0, 1.0, 2,
                            "Shrinks the first layer to counter squish."),
                    ),
                ),
                SettingGroup<ProcessProfile>(
                    "Ironing", listOf(
                        Setting.Toggle("Iron top surfaces", { it.ironing }, { p, v -> p.copy(ironing = v) }, "Smooths top surfaces with a second, nearly dry pass."),
                        Setting.Number("Ironing spacing", "mm", { it.ironingSpacing }, { p, v -> p.copy(ironingSpacing = v) }, 0.03, 1.0, 2),
                        Setting.Number("Ironing flow", "%", { it.ironingFlow }, { p, v -> p.copy(ironingFlow = v) }, 0.0, 100.0, 0),
                        Setting.Number("Ironing speed", "mm/s", { it.ironingSpeed }, { p, v -> p.copy(ironingSpeed = v) }, 1.0, 500.0, 0),
                    ),
                ),
            ),
        ),
        SettingPage<ProcessProfile>(
            "Strength", listOf(
                SettingGroup<ProcessProfile>(
                    "Walls", listOf(
                        Setting.Integer("Wall loops", "", { it.wallLoops }, { p, v -> p.copy(wallLoops = v) }, 1, 20, "Number of perimeters."),
                        Setting.Choice("Wall order", WallOrder.entries, { it.label }, { it.wallOrder }, { p, v -> p.copy(wallOrder = v) }),
                    ),
                ),
                SettingGroup<ProcessProfile>(
                    "Top/bottom shells", listOf(
                        Setting.Integer("Top shell layers", "", { it.topShellLayers }, { p, v -> p.copy(topShellLayers = v) }, 0, 50),
                        Setting.Choice("Top surface pattern", SurfacePattern.entries, { it.label }, { it.topSurfacePattern }, { p, v -> p.copy(topSurfacePattern = v) }),
                        Setting.Integer("Bottom shell layers", "", { it.bottomShellLayers }, { p, v -> p.copy(bottomShellLayers = v) }, 0, 50),
                        Setting.Choice("Bottom surface pattern", SurfacePattern.entries, { it.label }, { it.bottomSurfacePattern }, { p, v -> p.copy(bottomSurfacePattern = v) }),
                    ),
                ),
                SettingGroup<ProcessProfile>(
                    "Infill", listOf(
                        Setting.Number("Sparse infill density", "%", { it.sparseInfillDensity }, { p, v -> p.copy(sparseInfillDensity = v) }, 0.0, 100.0, 0),
                        Setting.Choice("Sparse infill pattern", InfillPattern.entries, { it.label }, { it.sparseInfillPattern }, { p, v -> p.copy(sparseInfillPattern = v) }),
                        Setting.Number("Sparse infill direction", "°", { it.sparseInfillDirection }, { p, v -> p.copy(sparseInfillDirection = v) }, 0.0, 360.0, 0),
                        Setting.Number("Solid infill direction", "°", { it.solidInfillDirection }, { p, v -> p.copy(solidInfillDirection = v) }, 0.0, 360.0, 0),
                        Setting.Number("Infill/wall overlap", "%", { it.infillWallOverlap }, { p, v -> p.copy(infillWallOverlap = v) }, 0.0, 100.0, 0),
                    ),
                ),
            ),
        ),
        SettingPage<ProcessProfile>(
            "Speed", listOf(
                SettingGroup<ProcessProfile>(
                    "First layer", listOf(
                        Setting.Number("First layer", "mm/s", { it.initialLayerSpeed }, { p, v -> p.copy(initialLayerSpeed = v) }, 1.0, 1000.0, 0),
                        Setting.Number("First layer infill", "mm/s", { it.initialLayerInfillSpeed }, { p, v -> p.copy(initialLayerInfillSpeed = v) }, 1.0, 1000.0, 0),
                    ),
                ),
                SettingGroup<ProcessProfile>(
                    "Other layers", listOf(
                        Setting.Number("Outer wall", "mm/s", { it.outerWallSpeed }, { p, v -> p.copy(outerWallSpeed = v) }, 1.0, 1000.0, 0),
                        Setting.Number("Inner wall", "mm/s", { it.innerWallSpeed }, { p, v -> p.copy(innerWallSpeed = v) }, 1.0, 1000.0, 0),
                        Setting.Number("Sparse infill", "mm/s", { it.sparseInfillSpeed }, { p, v -> p.copy(sparseInfillSpeed = v) }, 1.0, 1000.0, 0),
                        Setting.Number("Internal solid infill", "mm/s", { it.internalSolidInfillSpeed }, { p, v -> p.copy(internalSolidInfillSpeed = v) }, 1.0, 1000.0, 0),
                        Setting.Number("Top surface", "mm/s", { it.topSurfaceSpeed }, { p, v -> p.copy(topSurfaceSpeed = v) }, 1.0, 1000.0, 0),
                        Setting.Number("Support", "mm/s", { it.supportSpeed }, { p, v -> p.copy(supportSpeed = v) }, 1.0, 1000.0, 0),
                        Setting.Number("Support interface", "mm/s", { it.supportInterfaceSpeed }, { p, v -> p.copy(supportInterfaceSpeed = v) }, 1.0, 1000.0, 0),
                        Setting.Number("Travel", "mm/s", { it.travelSpeed }, { p, v -> p.copy(travelSpeed = v) }, 1.0, 2000.0, 0),
                    ),
                ),
                SettingGroup<ProcessProfile>(
                    "Acceleration", listOf(
                        Setting.Number("Normal printing", "mm/s²", { it.defaultAcceleration }, { p, v -> p.copy(defaultAcceleration = v) }, 100.0, 100000.0, 0),
                        Setting.Number("Outer wall", "mm/s²", { it.outerWallAcceleration }, { p, v -> p.copy(outerWallAcceleration = v) }, 100.0, 100000.0, 0),
                        Setting.Number("Top surface", "mm/s²", { it.topSurfaceAcceleration }, { p, v -> p.copy(topSurfaceAcceleration = v) }, 100.0, 100000.0, 0),
                        Setting.Number("First layer", "mm/s²", { it.initialLayerAcceleration }, { p, v -> p.copy(initialLayerAcceleration = v) }, 100.0, 100000.0, 0),
                        Setting.Number("Travel", "mm/s²", { it.travelAcceleration }, { p, v -> p.copy(travelAcceleration = v) }, 100.0, 100000.0, 0),
                    ),
                ),
            ),
        ),
        SettingPage<ProcessProfile>(
            "Support", listOf(
                SettingGroup<ProcessProfile>(
                    "Support", listOf(
                        Setting.Toggle("Enable support", { it.enableSupport }, { p, v -> p.copy(enableSupport = v) }),
                        Setting.Choice("Type", SupportType.entries, { it.label }, { it.supportType }, { p, v -> p.copy(supportType = v) },
                            "Organic trees (OrcaSlicer's default) use the least material, touch the model in few places and break off easily."),
                        Setting.Number("Threshold angle", "°", { it.supportThresholdAngle }, { p, v -> p.copy(supportThresholdAngle = v) }, 1.0, 89.0, 0,
                            "Surfaces flatter than this angle (from horizontal) get support."),
                        Setting.Toggle("On build plate only", { it.supportOnBuildPlateOnly }, { p, v -> p.copy(supportOnBuildPlateOnly = v) }),
                    ),
                ),
                SettingGroup<ProcessProfile>(
                    "Organic tree supports", listOf(
                        Setting.Number("Branch angle", "°", { it.organicBranchAngle }, { p, v -> p.copy(organicBranchAngle = v) }, 5.0, 60.0, 0,
                            "Maximum lean of branches avoiding the model."),
                        Setting.Number("Preferred branch angle", "°", { it.organicPreferredAngle }, { p, v -> p.copy(organicPreferredAngle = v) }, 5.0, 85.0, 0,
                            "Lean used to merge branches when nothing is in the way. Lower = more vertical and stable."),
                        Setting.Number("Tip diameter", "mm", { it.organicTipDiameter }, { p, v -> p.copy(organicTipDiameter = v) }, 0.1, 10.0, 2),
                        Setting.Number("Branch diameter", "mm", { it.organicBranchDiameter }, { p, v -> p.copy(organicBranchDiameter = v) }, 1.0, 10.0, 1),
                        Setting.Number("Branch diameter angle", "°", { it.organicDiameterAngle }, { p, v -> p.copy(organicDiameterAngle = v) }, 0.0, 15.0, 1,
                            "Branches thicken towards the bottom at this angle for stability."),
                        Setting.Number("Branch density", "%", { it.organicBranchDensity }, { p, v -> p.copy(organicBranchDensity = v) }, 5.0, 35.0, 0,
                            "How densely tips cover the overhang. Prefer interface layers over a high density."),
                    ),
                ),
                SettingGroup<ProcessProfile>(
                    "Tree supports (non-organic)", listOf(
                        Setting.Number("Branch angle", "°", { it.treeBranchAngle }, { p, v -> p.copy(treeBranchAngle = v) }, 5.0, 75.0, 0,
                            "How far branches may lean from vertical to avoid the model and join others."),
                        Setting.Number("Branch diameter", "mm", { it.treeBranchDiameter }, { p, v -> p.copy(treeBranchDiameter = v) }, 1.0, 20.0, 1,
                            "Merged trunks can grow to twice this."),
                    ),
                ),
                SettingGroup<ProcessProfile>(
                    "Advanced", listOf(
                        Setting.Number("Top Z distance", "mm", { it.supportTopZDistance }, { p, v -> p.copy(supportTopZDistance = v) }, 0.0, 2.0, 2),
                        Setting.Number("Support/object XY distance", "mm", { it.supportObjectXYDistance }, { p, v -> p.copy(supportObjectXYDistance = v) }, 0.0, 5.0, 2),
                        Setting.Number("Base pattern spacing", "mm", { it.supportBaseSpacing }, { p, v -> p.copy(supportBaseSpacing = v) }, 0.5, 10.0, 1),
                        Setting.Integer("Interface layers", "", { it.supportInterfaceLayers }, { p, v -> p.copy(supportInterfaceLayers = v) }, 0, 10),
                        Setting.Number("Interface spacing", "mm", { it.supportInterfaceSpacing }, { p, v -> p.copy(supportInterfaceSpacing = v) }, 0.0, 5.0, 2),
                    ),
                ),
            ),
        ),
        SettingPage(
            "Others", listOf(
                SettingGroup(
                    "Bed adhesion", listOf(
                        Setting.Integer("Skirt loops", "", { it.skirtLoops }, { p, v -> p.copy(skirtLoops = v) }, 0, 10),
                        Setting.Number("Skirt distance", "mm", { it.skirtDistance }, { p, v -> p.copy(skirtDistance = v) }, 0.0, 50.0, 1),
                        Setting.Integer("Skirt height", "layers", { it.skirtHeight }, { p, v -> p.copy(skirtHeight = v) }, 1, 100),
                        Setting.Choice("Brim type", BrimType.entries, { it.label }, { it.brimType }, { p, v -> p.copy(brimType = v) }),
                        Setting.Number("Brim width", "mm", { it.brimWidth }, { p, v -> p.copy(brimWidth = v) }, 0.0, 50.0, 1),
                        Setting.Number("Brim-object gap", "mm", { it.brimObjectGap }, { p, v -> p.copy(brimObjectGap = v) }, 0.0, 5.0, 2),
                    ),
                ),
                SettingGroup(
                    "Special mode", listOf(
                        Setting.Toggle("Spiral vase", { it.spiralMode }, { p, v -> p.copy(spiralMode = v) },
                            "One continuous outer wall with Z rising smoothly. No top layers or infill."),
                    ),
                ),
            ),
        ),
    )
}

object FilamentSettings {
    val pages: List<SettingPage<FilamentProfile>> = listOf(
        SettingPage<FilamentProfile>(
            "Filament", listOf(
                SettingGroup<FilamentProfile>(
                    "Basic", listOf(
                        Setting.Text("Type", { it.type }, { p, v -> p.copy(type = v.trim().uppercase()) }, multiline = false, code = false),
                        Setting.Text("Colour (hex)", { it.colorHex }, { p, v -> p.copy(colorHex = v.trim()) }, multiline = false, code = true),
                        Setting.Number("Diameter", "mm", { it.diameter }, { p, v -> p.copy(diameter = v) }, 1.0, 3.5, 2),
                        Setting.Number("Density", "g/cm³", { it.density }, { p, v -> p.copy(density = v) }, 0.5, 5.0, 2),
                        Setting.Number("Cost", "/kg", { it.costPerKg }, { p, v -> p.copy(costPerKg = v) }, 0.0, 1000.0, 2),
                        Setting.Number("Flow ratio", "", { it.flowRatio }, { p, v -> p.copy(flowRatio = v) }, 0.5, 1.5, 3),
                        Setting.Number("Max volumetric speed", "mm³/s", { it.maxVolumetricSpeed }, { p, v -> p.copy(maxVolumetricSpeed = v) }, 0.5, 100.0, 1,
                            "Caps print speed so the hotend can melt enough plastic."),
                    ),
                ),
                SettingGroup<FilamentProfile>(
                    "Temperature", listOf(
                        Setting.Integer("Nozzle (first layer)", "°C", { it.nozzleTempInitial }, { p, v -> p.copy(nozzleTempInitial = v) }, 0, 500),
                        Setting.Integer("Nozzle", "°C", { it.nozzleTemp }, { p, v -> p.copy(nozzleTemp = v) }, 0, 500),
                        Setting.Integer("Bed (first layer)", "°C", { it.bedTempInitial }, { p, v -> p.copy(bedTempInitial = v) }, 0, 150),
                        Setting.Integer("Bed", "°C", { it.bedTemp }, { p, v -> p.copy(bedTemp = v) }, 0, 150),
                    ),
                ),
                SettingGroup<FilamentProfile>(
                    "Retraction", listOf(
                        Setting.Number("Retraction length override", "mm", { it.retractionLengthOverride }, { p, v -> p.copy(retractionLengthOverride = v) }, 0.0, 20.0, 2,
                            "0 uses the printer's retraction length."),
                    ),
                ),
            ),
        ),
        SettingPage<FilamentProfile>(
            "Cooling", listOf(
                SettingGroup<FilamentProfile>(
                    "Part cooling fan", listOf(
                        Setting.Integer("Min fan speed", "%", { it.fanMinSpeed }, { p, v -> p.copy(fanMinSpeed = v) }, 0, 100),
                        Setting.Integer("Max fan speed", "%", { it.fanMaxSpeed }, { p, v -> p.copy(fanMaxSpeed = v) }, 0, 100),
                        Setting.Integer("No cooling for the first", "layers", { it.closeFanFirstLayers }, { p, v -> p.copy(closeFanFirstLayers = v) }, 0, 100),
                        Setting.Number("Full fan below layer time", "s", { it.fanCoolingLayerTime }, { p, v -> p.copy(fanCoolingLayerTime = v) }, 0.0, 1000.0, 0,
                            "Fan ramps from min to max as layers get faster than this."),
                    ),
                ),
                SettingGroup<FilamentProfile>(
                    "Slow down", listOf(
                        Setting.Number("Minimum layer time", "s", { it.slowDownLayerTime }, { p, v -> p.copy(slowDownLayerTime = v) }, 0.0, 1000.0, 0),
                        Setting.Number("Minimum print speed", "mm/s", { it.slowDownMinSpeed }, { p, v -> p.copy(slowDownMinSpeed = v) }, 1.0, 200.0, 0),
                    ),
                ),
            ),
        ),
    )
}

object PrinterSettings {
    val pages: List<SettingPage<PrinterProfile>> = listOf(
        SettingPage<PrinterProfile>(
            "Machine", listOf(
                SettingGroup<PrinterProfile>(
                    "Build volume", listOf(
                        Setting.Number("Bed width (X)", "mm", { it.bedWidth }, { p, v -> p.copy(bedWidth = v) }, 10.0, 2000.0, 1),
                        Setting.Number("Bed depth (Y)", "mm", { it.bedDepth }, { p, v -> p.copy(bedDepth = v) }, 10.0, 2000.0, 1),
                        Setting.Number("Max height (Z)", "mm", { it.maxHeight }, { p, v -> p.copy(maxHeight = v) }, 10.0, 2000.0, 1),
                    ),
                ),
                SettingGroup<PrinterProfile>(
                    "Firmware", listOf(
                        Setting.Choice("G-code flavor", GCodeFlavor.entries, { it.label }, { it.flavor }, { p, v -> p.copy(flavor = v) }),
                        Setting.Toggle("Emit M73 progress", { it.emitM73 }, { p, v -> p.copy(emitM73 = v) }, "Shows remaining time on Prusa and Marlin displays."),
                        Setting.Toggle("Emit acceleration (M204)", { it.emitAcceleration }, { p, v -> p.copy(emitAcceleration = v) }),
                        Setting.Toggle("Add heating commands", { it.autoTemperatureCommands }, { p, v -> p.copy(autoTemperatureCommands = v) },
                            "Insert M190/M109 before the start G-code when it does not heat by itself."),
                    ),
                ),
                SettingGroup<PrinterProfile>(
                    "Motion limits", listOf(
                        Setting.Number("Max speed XY", "mm/s", { it.maxSpeedXY }, { p, v -> p.copy(maxSpeedXY = v) }, 10.0, 2000.0, 0),
                        Setting.Number("Max speed Z", "mm/s", { it.maxSpeedZ }, { p, v -> p.copy(maxSpeedZ = v) }, 1.0, 200.0, 0),
                        Setting.Number("Max acceleration", "mm/s²", { it.maxAcceleration }, { p, v -> p.copy(maxAcceleration = v) }, 100.0, 100000.0, 0),
                        Setting.Number("Square corner velocity / jerk", "mm/s", { it.squareCornerVelocity }, { p, v -> p.copy(squareCornerVelocity = v) }, 1.0, 50.0, 1),
                    ),
                ),
            ),
        ),
        SettingPage<PrinterProfile>(
            "Extruder", listOf(
                SettingGroup<PrinterProfile>(
                    "Nozzle", listOf(
                        Setting.Number("Nozzle diameter", "mm", { it.nozzleDiameter }, { p, v -> p.copy(nozzleDiameter = v) }, 0.1, 2.0, 2),
                        Setting.Number("Min layer height", "mm", { it.minLayerHeight }, { p, v -> p.copy(minLayerHeight = v) }, 0.01, 2.0, 2),
                        Setting.Number("Max layer height", "mm", { it.maxLayerHeight }, { p, v -> p.copy(maxLayerHeight = v) }, 0.01, 2.0, 2),
                    ),
                ),
                SettingGroup<PrinterProfile>(
                    "Retraction", listOf(
                        Setting.Number("Length", "mm", { it.retractionLength }, { p, v -> p.copy(retractionLength = v) }, 0.0, 20.0, 2),
                        Setting.Number("Retraction speed", "mm/s", { it.retractionSpeed }, { p, v -> p.copy(retractionSpeed = v) }, 1.0, 200.0, 0),
                        Setting.Number("Deretraction speed", "mm/s", { it.deretractionSpeed }, { p, v -> p.copy(deretractionSpeed = v) }, 1.0, 200.0, 0),
                        Setting.Number("Minimum travel", "mm", { it.retractionMinTravel }, { p, v -> p.copy(retractionMinTravel = v) }, 0.0, 50.0, 1),
                        Setting.Number("Z hop", "mm", { it.zHop }, { p, v -> p.copy(zHop = v) }, 0.0, 5.0, 2),
                        Setting.Toggle("Firmware retraction (G10/G11)", { it.firmwareRetraction }, { p, v -> p.copy(firmwareRetraction = v) }),
                    ),
                ),
            ),
        ),
        SettingPage<PrinterProfile>(
            "G-code", listOf(
                SettingGroup<PrinterProfile>(
                    "Custom G-code", listOf(
                        Setting.Text("Start G-code", { it.startGcode }, { p, v -> p.copy(startGcode = v) },
                            help = "Placeholders: [nozzle_temperature_initial_layer], [bed_temperature_initial_layer_single], [layer_height] …"),
                        Setting.Text("End G-code", { it.endGcode }, { p, v -> p.copy(endGcode = v) }),
                        Setting.Text("Layer change G-code", { it.layerChangeGcode }, { p, v -> p.copy(layerChangeGcode = v) },
                            help = "Runs after each layer change. Placeholders: [layer_num], [layer_z]"),
                    ),
                ),
            ),
        ),
    )
}
