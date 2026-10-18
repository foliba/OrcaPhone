package com.orcaphone.core.mesh

data class Bounds3(
    val minX: Double, val minY: Double, val minZ: Double,
    val maxX: Double, val maxY: Double, val maxZ: Double,
) {
    val sizeX get() = maxX - minX
    val sizeY get() = maxY - minY
    val sizeZ get() = maxZ - minZ
    val centerX get() = (minX + maxX) / 2
    val centerY get() = (minY + maxY) / 2
    val centerZ get() = (minZ + maxZ) / 2
    val isEmpty get() = minX > maxX

    fun translated(dx: Double, dy: Double, dz: Double) =
        Bounds3(minX + dx, minY + dy, minZ + dz, maxX + dx, maxY + dy, maxZ + dz)

    fun union(o: Bounds3): Bounds3 = when {
        isEmpty -> o
        o.isEmpty -> this
        else -> Bounds3(
            minOf(minX, o.minX), minOf(minY, o.minY), minOf(minZ, o.minZ),
            maxOf(maxX, o.maxX), maxOf(maxY, o.maxY), maxOf(maxZ, o.maxZ),
        )
    }

    companion object {
        val EMPTY = Bounds3(
            Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE,
            -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE,
        )
    }
}
