package com.orcaphone.core.mesh

/** What a non-printing volume of an object does (from Bambu Studio / OrcaSlicer projects). */
enum class VolumeKind {
    /** Cut out of the part: holes and recesses. */
    NEGATIVE,
    /** No support is generated inside it. */
    SUPPORT_BLOCKER,
    /** Overhangs inside it get support, even where automatic support would not add any. */
    SUPPORT_ENFORCER,
}

/** A volume attached to an object, in the same coordinates as the object's mesh. */
class PartVolume(val kind: VolumeKind, val mesh: Mesh) {
    fun transformed(m: com.orcaphone.core.math.Mat4) = PartVolume(kind, mesh.transformed(m))
}
