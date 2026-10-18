package com.orcaphone.core.config

import kotlinx.serialization.Serializable

@Serializable
enum class GCodeFlavor(val label: String) {
    MARLIN("Marlin 2"),
    KLIPPER("Klipper"),
    REPRAP("RepRapFirmware"),
}

@Serializable
enum class InfillPattern(val label: String) {
    GRID("Grid"),
    RECTILINEAR("Rectilinear"),
    TRIANGLES("Triangles"),
    CUBIC("Cubic"),
    GYROID("Gyroid"),
    CONCENTRIC("Concentric"),
}

@Serializable
enum class SurfacePattern(val label: String) {
    MONOTONIC("Monotonic"),
    RECTILINEAR("Rectilinear"),
    CONCENTRIC("Concentric"),
}

@Serializable
enum class SeamPosition(val label: String) {
    ALIGNED("Aligned"),
    BACK("Back"),
    NEAREST("Nearest"),
    RANDOM("Random"),
}

@Serializable
enum class WallOrder(val label: String) {
    INNER_OUTER("Inner/Outer"),
    OUTER_INNER("Outer/Inner"),
}

@Serializable
enum class SupportType(val label: String) {
    NORMAL("Normal"),
    TREE("Tree"),
    ORGANIC("Organic tree"),
}

@Serializable
enum class BrimType(val label: String) {
    NONE("No brim"),
    OUTER("Outer brim only"),
}

@Serializable
enum class HostType(val label: String) {
    OCTOPRINT("OctoPrint"),
    MOONRAKER("Klipper (Moonraker)"),
    PRUSALINK("PrusaLink"),
}

/**
 * Link of a user preset to its copy in Orca Cloud.
 * [content] is the last known cloud content (OrcaSlicer key -> serialized value), so settings OrcaPhone
 * does not model survive a round trip.
 */
@Serializable
data class CloudMeta(
    val id: String,
    val updatedTime: Long = 0,
    val content: Map<String, String> = emptyMap(),
    /** Local changes not yet uploaded. */
    val pendingPush: Boolean = false,
)

/** Machine settings ("Printer" preset in OrcaSlicer). */
@Serializable
data class PrinterProfile(
    val id: String,
    val name: String,
    val builtin: Boolean = false,
    val cloud: CloudMeta? = null,
    val vendor: String = "Custom",
    val bedWidth: Double = 220.0,
    val bedDepth: Double = 220.0,
    val maxHeight: Double = 250.0,
    val nozzleDiameter: Double = 0.4,
    val flavor: GCodeFlavor = GCodeFlavor.MARLIN,
    val maxSpeedXY: Double = 200.0,
    val maxSpeedZ: Double = 10.0,
    val maxAcceleration: Double = 2500.0,
    val squareCornerVelocity: Double = 8.0,
    val retractionLength: Double = 0.8,
    val retractionSpeed: Double = 40.0,
    val deretractionSpeed: Double = 30.0,
    val retractionMinTravel: Double = 1.0,
    val zHop: Double = 0.2,
    val firmwareRetraction: Boolean = false,
    val minLayerHeight: Double = 0.08,
    val maxLayerHeight: Double = 0.32,
    val emitM73: Boolean = false,
    val emitAcceleration: Boolean = true,
    /** Insert M190/M109 before the start G-code when it does not heat itself. */
    val autoTemperatureCommands: Boolean = true,
    val startGcode: String = "",
    val endGcode: String = "",
    val layerChangeGcode: String = "",
    val notes: String = "",
)

/** Filament settings. */
@Serializable
data class FilamentProfile(
    val id: String,
    val name: String,
    val builtin: Boolean = false,
    val cloud: CloudMeta? = null,
    val type: String = "PLA",
    val colorHex: String = "#009688",
    val diameter: Double = 1.75,
    val density: Double = 1.24,
    val costPerKg: Double = 20.0,
    val flowRatio: Double = 0.98,
    val nozzleTemp: Int = 220,
    val nozzleTempInitial: Int = 220,
    val bedTemp: Int = 55,
    val bedTempInitial: Int = 55,
    val maxVolumetricSpeed: Double = 15.0,
    val fanMinSpeed: Int = 100,
    val fanMaxSpeed: Int = 100,
    val closeFanFirstLayers: Int = 1,
    val fanCoolingLayerTime: Double = 60.0,
    val slowDownLayerTime: Double = 6.0,
    val slowDownMinSpeed: Double = 20.0,
    /** Overrides the printer retraction when > 0. */
    val retractionLengthOverride: Double = 0.0,
)

/** Print process settings ("Process" preset in OrcaSlicer), grouped like Orca's tabs. */
@Serializable
data class ProcessProfile(
    val id: String,
    val name: String,
    val builtin: Boolean = false,
    val cloud: CloudMeta? = null,
    // Quality
    val layerHeight: Double = 0.2,
    val initialLayerHeight: Double = 0.2,
    val lineWidth: Double = 0.42,
    val outerWallLineWidth: Double = 0.42,
    val initialLayerLineWidth: Double = 0.5,
    val seamPosition: SeamPosition = SeamPosition.ALIGNED,
    val elephantFootCompensation: Double = 0.1,
    val ironing: Boolean = false,
    val ironingSpacing: Double = 0.1,
    val ironingFlow: Double = 10.0,
    val ironingSpeed: Double = 30.0,
    // Strength
    val wallLoops: Int = 2,
    val wallOrder: WallOrder = WallOrder.INNER_OUTER,
    val topShellLayers: Int = 5,
    val bottomShellLayers: Int = 3,
    val sparseInfillDensity: Double = 15.0,
    val sparseInfillPattern: InfillPattern = InfillPattern.GRID,
    val topSurfacePattern: SurfacePattern = SurfacePattern.MONOTONIC,
    val bottomSurfacePattern: SurfacePattern = SurfacePattern.MONOTONIC,
    val infillWallOverlap: Double = 15.0,
    val solidInfillDirection: Double = 45.0,
    val sparseInfillDirection: Double = 45.0,
    // Speed (mm/s), acceleration (mm/s²)
    val initialLayerSpeed: Double = 50.0,
    val initialLayerInfillSpeed: Double = 80.0,
    val outerWallSpeed: Double = 120.0,
    val innerWallSpeed: Double = 180.0,
    val sparseInfillSpeed: Double = 200.0,
    val internalSolidInfillSpeed: Double = 180.0,
    val topSurfaceSpeed: Double = 120.0,
    val supportSpeed: Double = 150.0,
    val supportInterfaceSpeed: Double = 80.0,
    val travelSpeed: Double = 300.0,
    val defaultAcceleration: Double = 5000.0,
    val outerWallAcceleration: Double = 3000.0,
    val topSurfaceAcceleration: Double = 2000.0,
    val initialLayerAcceleration: Double = 500.0,
    val travelAcceleration: Double = 6000.0,
    // Support
    val enableSupport: Boolean = false,
    val supportType: SupportType = SupportType.ORGANIC,
    /** Tree supports: maximum branch lean from vertical (degrees). */
    val treeBranchAngle: Double = 40.0,
    /** Tree supports: branch diameter (mm); merged trunks may grow up to twice this. */
    val treeBranchDiameter: Double = 5.0,
    // Organic tree supports (OrcaSlicer defaults)
    val organicBranchAngle: Double = 40.0,
    /** Lean used when branches move only to merge, not to avoid the model. */
    val organicPreferredAngle: Double = 25.0,
    val organicTipDiameter: Double = 0.8,
    val organicBranchDiameter: Double = 2.0,
    /** Branches thicken towards the bottom at this angle. */
    val organicDiameterAngle: Double = 5.0,
    /** Share of the overhang covered by branch tips (%). */
    val organicBranchDensity: Double = 30.0,
    val supportThresholdAngle: Double = 30.0,
    val supportOnBuildPlateOnly: Boolean = false,
    val supportTopZDistance: Double = 0.2,
    val supportObjectXYDistance: Double = 0.35,
    val supportBaseSpacing: Double = 2.5,
    val supportInterfaceLayers: Int = 2,
    val supportInterfaceSpacing: Double = 0.5,
    // Others
    val skirtLoops: Int = 1,
    val skirtDistance: Double = 3.0,
    val skirtHeight: Int = 1,
    val brimType: BrimType = BrimType.NONE,
    val brimWidth: Double = 5.0,
    val brimObjectGap: Double = 0.1,
    val spiralMode: Boolean = false,
)

/** Network connection to a printer host. */
@Serializable
data class PrinterConnection(
    val id: String,
    val name: String,
    val type: HostType = HostType.MOONRAKER,
    val url: String = "http://",
    val apiKey: String = "",
)
