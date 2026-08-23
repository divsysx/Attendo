package com.attendo.core.model

/**
 * Which individual hours of a session were attended, as a bitmask: bit *i* set
 * means unit *i* (the (i+1)-th hour of the session) was attended.
 *
 * Storing the *set* of attended units rather than just a count is what makes the
 * per-hour toggle UI round-trip correctly: reopening a session shows exactly
 * which hour was missed, not merely that one of two was.
 *
 * Every read is masked to the session's current planned length. That is not
 * defensive noise — it is the invariant that makes shortening a class safe. If a
 * 2-hour class is marked fully present (bits 0b11) and then shortened to 1 hour,
 * an unmasked popcount would report 2 units attended out of 1 planned, i.e. 200%.
 */
@JvmInline
value class UnitMask(val bits: Int) {

    fun isAttended(unitIndex: Int): Boolean =
        unitIndex in 0 until MAX_UNITS && (bits and bitOf(unitIndex)) != 0

    fun with(unitIndex: Int, attended: Boolean): UnitMask {
        requireValidIndex(unitIndex)
        val bit = bitOf(unitIndex)
        return UnitMask(if (attended) bits or bit else bits and bit.inv())
    }

    fun toggled(unitIndex: Int): UnitMask = with(unitIndex, !isAttended(unitIndex))

    /**
     * Attended units among the first [units] only. Bits beyond [units] are ignored
     * so a mask left over from a longer session can never over-count.
     */
    fun countAttended(units: Int): Int = Integer.bitCount(bits and rangeMask(units))

    /** Drops any bits at or beyond [units]; call this whenever a session shrinks. */
    fun clampedTo(units: Int): UnitMask = UnitMask(bits and rangeMask(units))

    fun attendedIndices(units: Int): List<Int> =
        (0 until units.coerceIn(0, MAX_UNITS)).filter(::isAttended)

    fun isFullyAttended(units: Int): Boolean = units > 0 && countAttended(units) == units

    fun isFullyMissed(units: Int): Boolean = countAttended(units) == 0

    companion object {
        /**
         * A session cannot outlast the teaching day, so 9 units is the real
         * ceiling; 12 leaves headroom without risking the 32-bit shift edge.
         */
        const val MAX_UNITS: Int = 12

        val NONE: UnitMask = UnitMask(0)

        fun allPresent(units: Int): UnitMask = UnitMask(rangeMask(units))

        fun of(vararg attendedIndices: Int): UnitMask =
            attendedIndices.fold(NONE) { acc, i -> acc.with(i, true) }

        private fun bitOf(unitIndex: Int): Int = 1 shl unitIndex

        /** Low [units] bits set; 0 for non-positive, saturating at [MAX_UNITS]. */
        private fun rangeMask(units: Int): Int =
            if (units <= 0) 0 else (1 shl units.coerceAtMost(MAX_UNITS)) - 1

        private fun requireValidIndex(unitIndex: Int) {
            require(unitIndex in 0 until MAX_UNITS) {
                "unit index must be 0..${MAX_UNITS - 1}, was $unitIndex"
            }
        }
    }
}
