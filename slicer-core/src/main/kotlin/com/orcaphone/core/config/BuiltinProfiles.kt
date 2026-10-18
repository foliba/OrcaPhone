package com.orcaphone.core.config

/**
 * Built-in presets. Machine dimensions and limits follow the OrcaSlicer system profiles;
 * start/end G-code is simplified to plain commands that need no slicer-side scripting.
 */
object BuiltinProfiles {

    private val MARLIN_START = """
        G90 ; absolute coordinates
        M83 ; relative extrusion
        M140 S[bed_temperature_initial_layer_single] ; heat bed
        M104 S150 ; pre-heat nozzle without oozing
        G28 ; home all axes
        M190 S[bed_temperature_initial_layer_single] ; wait for bed
        M109 S[nozzle_temperature_initial_layer] ; wait for nozzle
        G92 E0
        G1 Z2.0 F3000
        G1 X2 Y20 Z0.3 F5000.0 ; move to purge position
        G1 X2 Y145.0 Z0.3 F1500.0 E15 ; purge line
        G1 X2.4 Y145.0 Z0.3 F5000.0
        G1 X2.4 Y20 Z0.3 F1500.0 E15 ; second purge line
        G1 Z2.0 F3000
    """.trimIndent()

    private val MARLIN_END = """
        G91 ; relative positioning
        G1 E-2 F2700 ; retract
        G1 Z10 F600 ; lift nozzle
        G90 ; absolute positioning
        G1 X0 Y[bed_depth] F3000 ; present print
        M106 S0 ; fan off
        M104 S0 ; hotend off
        M140 S0 ; bed off
        M84 X Y E ; disable motors
    """.trimIndent()

    private val KLIPPER_GENERIC_START = """
        G90
        M83
        M140 S[bed_temperature_initial_layer_single]
        M104 S150
        G28
        M190 S[bed_temperature_initial_layer_single]
        M109 S[nozzle_temperature_initial_layer]
        G1 Z5 F3000
        G1 X5 Y5 F12000
        G1 Z0.3 F600
        G1 X60 Y5 E12 F1500 ; purge line
        G1 X100 Y5 E4 F3000
        G1 Z2 F600
    """.trimIndent()

    private val KLIPPER_GENERIC_END = """
        G91
        G1 E-2 F2700
        G1 Z10 F600
        G90
        G1 X5 Y[bed_depth] F6000
        M106 S0
        M104 S0
        M140 S0
        M84
    """.trimIndent()

    val printers: List<PrinterProfile> = listOf(
        PrinterProfile(
            id = "builtin-generic-marlin", name = "Generic Marlin Printer 0.4", builtin = true, vendor = "Generic",
            bedWidth = 220.0, bedDepth = 220.0, maxHeight = 250.0, flavor = GCodeFlavor.MARLIN,
            maxSpeedXY = 150.0, maxAcceleration = 1500.0, retractionLength = 1.0,
            startGcode = MARLIN_START, endGcode = MARLIN_END,
        ),
        PrinterProfile(
            id = "builtin-generic-klipper", name = "Generic Klipper Printer 0.4", builtin = true, vendor = "Generic",
            bedWidth = 300.0, bedDepth = 300.0, maxHeight = 300.0, flavor = GCodeFlavor.KLIPPER,
            maxSpeedXY = 400.0, maxAcceleration = 10000.0, squareCornerVelocity = 5.0,
            retractionLength = 0.8, zHop = 0.4,
            startGcode = KLIPPER_GENERIC_START, endGcode = KLIPPER_GENERIC_END,
        ),
        PrinterProfile(
            id = "builtin-ender3", name = "Creality Ender-3 / V2 0.4", builtin = true, vendor = "Creality",
            bedWidth = 220.0, bedDepth = 220.0, maxHeight = 250.0, flavor = GCodeFlavor.MARLIN,
            maxSpeedXY = 100.0, maxSpeedZ = 5.0, maxAcceleration = 1000.0, squareCornerVelocity = 8.0,
            retractionLength = 5.0, retractionSpeed = 45.0, deretractionSpeed = 45.0, retractionMinTravel = 2.0, zHop = 0.0,
            startGcode = MARLIN_START, endGcode = MARLIN_END,
            notes = "Bowden extruder: long retraction, moderate speeds.",
        ),
        PrinterProfile(
            id = "builtin-ender3v3se", name = "Creality Ender-3 V3 SE 0.4", builtin = true, vendor = "Creality",
            bedWidth = 220.0, bedDepth = 220.0, maxHeight = 250.0, flavor = GCodeFlavor.MARLIN,
            maxSpeedXY = 250.0, maxSpeedZ = 5.0, maxAcceleration = 2500.0, squareCornerVelocity = 10.0,
            retractionLength = 1.2, retractionSpeed = 40.0, deretractionSpeed = 30.0, retractionMinTravel = 2.0, zHop = 0.2,
            startGcode = """
                M220 S100 ; reset feedrate
                M221 S100 ; reset flowrate
                G90
                M83
                M104 S[nozzle_temperature_initial_layer]
                M190 S[bed_temperature_initial_layer_single]
                G28
                G92 E0
                G1 Z2.0 F3000
                M109 S[nozzle_temperature_initial_layer]
                G1 X-2.0 Y20 Z0.3 F5000.0
                G1 X-2.0 Y145.0 Z0.3 F1500.0 E15
                G1 X-1.7 Y145.0 Z0.3 F5000.0
                G1 X-1.7 Y20 Z0.3 F1500.0 E15
                G92 E0
                G1 Z2.0 F3000
            """.trimIndent(),
            endGcode = MARLIN_END,
        ),
        PrinterProfile(
            id = "builtin-k1c", name = "Creality K1C 0.4", builtin = true, vendor = "Creality",
            bedWidth = 220.0, bedDepth = 220.0, maxHeight = 250.0, flavor = GCodeFlavor.KLIPPER,
            maxSpeedXY = 600.0, maxSpeedZ = 12.0, maxAcceleration = 20000.0, squareCornerVelocity = 5.0,
            retractionLength = 0.8, zHop = 0.4, autoTemperatureCommands = false,
            startGcode = """
                START_PRINT EXTRUDER_TEMP=[nozzle_temperature_initial_layer] BED_TEMP=[bed_temperature_initial_layer_single]
                M204 S2000
                M109 S[nozzle_temperature_initial_layer]
                G1 Z3 F600
                M83
                G92 E0
                G1 Z1 F600
            """.trimIndent(),
            endGcode = "END_PRINT",
            notes = "Uses the START_PRINT / END_PRINT macros of the stock firmware.",
        ),
        PrinterProfile(
            id = "builtin-mk4", name = "Prusa MK4 0.4", builtin = true, vendor = "Prusa",
            bedWidth = 250.0, bedDepth = 210.0, maxHeight = 220.0, flavor = GCodeFlavor.MARLIN,
            maxSpeedXY = 300.0, maxSpeedZ = 12.0, maxAcceleration = 4000.0, squareCornerVelocity = 8.0,
            retractionLength = 0.7, retractionSpeed = 35.0, deretractionSpeed = 25.0, zHop = 0.2, emitM73 = true,
            startGcode = """
                M17 ; enable steppers
                M862.3 P "MK4" ; printer model check
                G90
                M83
                M140 S[bed_temperature_initial_layer_single]
                M104 S170 ; nozzle preheat for mesh bed levelling
                G28 ; home
                M190 S[bed_temperature_initial_layer_single]
                G29 ; mesh bed levelling
                M104 S[nozzle_temperature_initial_layer]
                G1 Z10 F720
                G0 X0 Y-4 Z15 F4800 ; move to purge area
                M109 S[nozzle_temperature_initial_layer]
                G92 E0
                G0 Z0.3 F720
                G1 E2 F2400
                G1 X60 E9 F720 ; purge line
                G1 X100 E4 F1080
                G92 E0
            """.trimIndent(),
            endGcode = """
                G1 Z[max_layer_z_plus_10] F720 ; move print head up
                M104 S0 ; turn off nozzle
                M140 S0 ; turn off bed
                M107 ; turn off fan
                G1 X241 Y170 F3600 ; park
                M84 X Y E ; disable motors
            """.trimIndent(),
        ),
        PrinterProfile(
            id = "builtin-mk3s", name = "Prusa MK3S 0.4", builtin = true, vendor = "Prusa",
            bedWidth = 250.0, bedDepth = 210.0, maxHeight = 210.0, flavor = GCodeFlavor.MARLIN,
            maxSpeedXY = 200.0, maxSpeedZ = 12.0, maxAcceleration = 1250.0, squareCornerVelocity = 8.0,
            retractionLength = 0.8, retractionSpeed = 35.0, deretractionSpeed = 25.0, zHop = 0.2, emitM73 = true,
            startGcode = """
                M862.3 P "MK3S" ; printer model check
                G90
                M83
                M104 S[nozzle_temperature_initial_layer]
                M140 S[bed_temperature_initial_layer_single]
                M190 S[bed_temperature_initial_layer_single]
                M109 S[nozzle_temperature_initial_layer]
                G28 W ; home all without mesh bed level
                G80 ; mesh bed levelling
                G1 Y-3.0 F1000.0 ; go outside print area
                G92 E0.0
                G1 X60.0 E9.0 F1000.0 ; intro line
                G1 X100.0 E12.5 F1000.0 ; intro line
                G92 E0.0
            """.trimIndent(),
            endGcode = """
                G1 Z[max_layer_z_plus_10] F720
                M104 S0
                M140 S0
                M107
                G1 X0 Y200 F3000
                M84
            """.trimIndent(),
        ),
        PrinterProfile(
            id = "builtin-voron24-350", name = "Voron 2.4 350 0.4", builtin = true, vendor = "Voron",
            bedWidth = 350.0, bedDepth = 350.0, maxHeight = 325.0, flavor = GCodeFlavor.KLIPPER,
            maxSpeedXY = 500.0, maxSpeedZ = 15.0, maxAcceleration = 20000.0, squareCornerVelocity = 5.0,
            retractionLength = 0.8, zHop = 0.4,
            startGcode = """
                M190 S[bed_temperature_initial_layer_single]
                M109 S[nozzle_temperature_initial_layer]
                PRINT_START EXTRUDER=[nozzle_temperature_initial_layer] BED=[bed_temperature_initial_layer_single]
            """.trimIndent(),
            endGcode = "PRINT_END",
            notes = "Expects PRINT_START / PRINT_END macros in printer.cfg.",
        ),
        PrinterProfile(
            id = "builtin-voron-trident-300", name = "Voron Trident 300 0.4", builtin = true, vendor = "Voron",
            bedWidth = 300.0, bedDepth = 300.0, maxHeight = 250.0, flavor = GCodeFlavor.KLIPPER,
            maxSpeedXY = 500.0, maxSpeedZ = 15.0, maxAcceleration = 20000.0, squareCornerVelocity = 5.0,
            retractionLength = 0.8, zHop = 0.4,
            startGcode = """
                M190 S[bed_temperature_initial_layer_single]
                M109 S[nozzle_temperature_initial_layer]
                PRINT_START EXTRUDER=[nozzle_temperature_initial_layer] BED=[bed_temperature_initial_layer_single]
            """.trimIndent(),
            endGcode = "PRINT_END",
            notes = "Expects PRINT_START / PRINT_END macros in printer.cfg.",
        ),
    )

    val filaments: List<FilamentProfile> = listOf(
        FilamentProfile(
            id = "builtin-pla", name = "Generic PLA", builtin = true, type = "PLA", colorHex = "#00AE42",
            density = 1.24, costPerKg = 20.0, flowRatio = 0.98, nozzleTemp = 220, nozzleTempInitial = 220,
            bedTemp = 55, bedTempInitial = 55, maxVolumetricSpeed = 15.0,
            fanMinSpeed = 100, fanMaxSpeed = 100, slowDownLayerTime = 6.0,
        ),
        FilamentProfile(
            id = "builtin-pla-plus", name = "Generic PLA+ / Tough", builtin = true, type = "PLA", colorHex = "#2E7DD7",
            density = 1.23, costPerKg = 22.0, flowRatio = 0.98, nozzleTemp = 225, nozzleTempInitial = 230,
            bedTemp = 60, bedTempInitial = 60, maxVolumetricSpeed = 14.0,
        ),
        FilamentProfile(
            id = "builtin-petg", name = "Generic PETG", builtin = true, type = "PETG", colorHex = "#F2A900",
            density = 1.27, costPerKg = 22.0, flowRatio = 0.95, nozzleTemp = 245, nozzleTempInitial = 245,
            bedTemp = 70, bedTempInitial = 70, maxVolumetricSpeed = 10.0,
            fanMinSpeed = 20, fanMaxSpeed = 50, fanCoolingLayerTime = 30.0, slowDownLayerTime = 8.0,
        ),
        FilamentProfile(
            id = "builtin-abs", name = "Generic ABS", builtin = true, type = "ABS", colorHex = "#D84315",
            density = 1.04, costPerKg = 22.0, flowRatio = 0.95, nozzleTemp = 255, nozzleTempInitial = 260,
            bedTemp = 100, bedTempInitial = 100, maxVolumetricSpeed = 16.0,
            fanMinSpeed = 10, fanMaxSpeed = 40, closeFanFirstLayers = 3, fanCoolingLayerTime = 30.0, slowDownLayerTime = 4.0,
        ),
        FilamentProfile(
            id = "builtin-asa", name = "Generic ASA", builtin = true, type = "ASA", colorHex = "#6D4C41",
            density = 1.07, costPerKg = 25.0, flowRatio = 0.95, nozzleTemp = 260, nozzleTempInitial = 260,
            bedTemp = 100, bedTempInitial = 100, maxVolumetricSpeed = 14.0,
            fanMinSpeed = 10, fanMaxSpeed = 40, closeFanFirstLayers = 3, fanCoolingLayerTime = 30.0, slowDownLayerTime = 4.0,
        ),
        FilamentProfile(
            id = "builtin-tpu", name = "Generic TPU 95A", builtin = true, type = "TPU", colorHex = "#7B1FA2",
            density = 1.21, costPerKg = 30.0, flowRatio = 1.0, nozzleTemp = 230, nozzleTempInitial = 230,
            bedTemp = 40, bedTempInitial = 40, maxVolumetricSpeed = 3.5,
            fanMinSpeed = 100, fanMaxSpeed = 100, slowDownLayerTime = 8.0, retractionLengthOverride = 0.4,
        ),
    )

    val processes: List<ProcessProfile> = listOf(
        ProcessProfile(
            id = "builtin-0.08", name = "0.08mm Extra Fine", builtin = true,
            layerHeight = 0.08, initialLayerHeight = 0.2, topShellLayers = 9, bottomShellLayers = 5,
            outerWallSpeed = 60.0, innerWallSpeed = 100.0, topSurfaceSpeed = 80.0,
        ),
        ProcessProfile(
            id = "builtin-0.12", name = "0.12mm Fine", builtin = true,
            layerHeight = 0.12, initialLayerHeight = 0.2, topShellLayers = 7, bottomShellLayers = 4,
            outerWallSpeed = 80.0, innerWallSpeed = 140.0,
        ),
        ProcessProfile(
            id = "builtin-0.16", name = "0.16mm Optimal", builtin = true,
            layerHeight = 0.16, initialLayerHeight = 0.2, topShellLayers = 6, bottomShellLayers = 4,
        ),
        ProcessProfile(id = "builtin-0.20", name = "0.20mm Standard", builtin = true),
        ProcessProfile(
            id = "builtin-0.20-strength", name = "0.20mm Strength", builtin = true,
            wallLoops = 4, sparseInfillDensity = 30.0, sparseInfillPattern = InfillPattern.GYROID,
            topShellLayers = 6, bottomShellLayers = 4,
        ),
        ProcessProfile(
            id = "builtin-0.24", name = "0.24mm Draft", builtin = true,
            layerHeight = 0.24, initialLayerHeight = 0.24, topShellLayers = 4, bottomShellLayers = 3,
            lineWidth = 0.45, outerWallLineWidth = 0.45,
        ),
        ProcessProfile(
            id = "builtin-0.28", name = "0.28mm Extra Draft", builtin = true,
            layerHeight = 0.28, initialLayerHeight = 0.28, topShellLayers = 4, bottomShellLayers = 3,
            lineWidth = 0.45, outerWallLineWidth = 0.45,
        ),
        ProcessProfile(
            id = "builtin-vase", name = "0.20mm Vase Mode", builtin = true,
            spiralMode = true, wallLoops = 1, topShellLayers = 0, bottomShellLayers = 3, sparseInfillDensity = 0.0,
            outerWallLineWidth = 0.45, outerWallSpeed = 60.0,
        ),
    )
}
