package com.orcaphone.core.mesh

/** Minimal open-addressing Long -> Int map (no boxing), used for vertex welding and edge lookups. */
class LongIntHashMap(expected: Int = 16) {
    private var keys: LongArray
    private var values: IntArray
    private var used: BooleanArray
    var size = 0
        private set

    init {
        var cap = 16
        while (cap < expected * 2) cap = cap shl 1
        keys = LongArray(cap)
        values = IntArray(cap)
        used = BooleanArray(cap)
    }

    private fun mix(k: Long): Int {
        var h = k * -0x61c8864680b583ebL
        h = h xor (h ushr 29)
        return h.toInt()
    }

    fun get(key: Long, default: Int = -1): Int {
        val mask = keys.size - 1
        var i = mix(key) and mask
        while (used[i]) {
            if (keys[i] == key) return values[i]
            i = (i + 1) and mask
        }
        return default
    }

    fun put(key: Long, value: Int) {
        if ((size + 1) * 2 > keys.size) grow()
        val mask = keys.size - 1
        var i = mix(key) and mask
        while (used[i]) {
            if (keys[i] == key) {
                values[i] = value
                return
            }
            i = (i + 1) and mask
        }
        used[i] = true
        keys[i] = key
        values[i] = value
        size++
    }

    private fun grow() {
        val oldK = keys
        val oldV = values
        val oldU = used
        keys = LongArray(oldK.size * 2)
        values = IntArray(oldK.size * 2)
        used = BooleanArray(oldK.size * 2)
        size = 0
        for (i in oldK.indices) if (oldU[i]) put(oldK[i], oldV[i])
    }
}
