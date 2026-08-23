package com.attendo.core.model

/**
 * A percentage held as an exact integer number of basis points (1 bp = 0.01%).
 *
 * Attendance is compared *at* a threshold — "am I still above 75%?" — and binary
 * floating point cannot represent 75/100 exactly. A student sitting on precisely
 * 60 of 80 units must never be told they are below 75% because the double came
 * out as 74.99999999999999. Basis points keep every threshold comparison and
 * every "how many can I skip" answer in exact integer arithmetic.
 *
 * Note that [ofRatio] *rounds* to the nearest basis point and is therefore for
 * display only. The engine never compares rounded percentages: it cross-multiplies
 * the raw unit counts instead. See [com.attendo.core.engine.AttendanceEngine].
 */
@JvmInline
value class Percent private constructor(val basisPoints: Int) : Comparable<Percent> {

    val asDouble: Double get() = basisPoints / BP_PER_PERCENT.toDouble()

    /** 0f..1f, handy for Compose progress indicators. */
    val asFraction: Float get() = basisPoints / BP_FULL.toFloat()

    override fun compareTo(other: Percent): Int = basisPoints.compareTo(other.basisPoints)

    /**
     * Renders with up to [decimals] places, dropping trailing zeros so whole
     * numbers read as "75" rather than "75.0" while 82.5 stays "82.5".
     */
    fun format(decimals: Int = 1): String {
        require(decimals in 0..2) { "decimals must be 0..2, was $decimals" }
        // Locale.ROOT, not the device locale: a comma decimal separator would break
        // both the layout and every string assertion in the tests.
        val raw = String.format(java.util.Locale.ROOT, "%.${decimals}f", asDouble)
        return if (decimals == 0) raw else raw.trimEnd('0').trimEnd('.')
    }

    override fun toString(): String = "${format()}%"

    companion object {
        /** Basis points in one percent. */
        const val BP_PER_PERCENT: Int = 100

        /** Basis points in 100%. */
        const val BP_FULL: Int = 10_000

        val ZERO: Percent = Percent(0)
        val FULL: Percent = Percent(BP_FULL)

        /** The threshold most Delhi University programmes enforce. */
        val DEFAULT_TARGET: Percent = ofPercent(75.0)

        fun ofBasisPoints(basisPoints: Int): Percent {
            require(basisPoints >= 0) { "percent cannot be negative, was $basisPoints bp" }
            return Percent(basisPoints)
        }

        fun ofPercent(percent: Double): Percent {
            require(percent >= 0.0) { "percent cannot be negative, was $percent" }
            return Percent(Math.round(percent * BP_PER_PERCENT).toInt())
        }

        /**
         * Rounds [numerator]/[denominator] to the nearest basis point, or returns
         * null when nothing has been held yet — an undefined percentage is a real
         * state ("no classes yet") and must not silently become 0%.
         */
        fun ofRatio(numerator: Int, denominator: Int): Percent? {
            if (denominator == 0) return null
            require(numerator >= 0 && denominator > 0) {
                "bad ratio $numerator/$denominator"
            }
            val bp = (numerator.toLong() * BP_FULL + denominator / 2) / denominator
            return Percent(bp.toInt())
        }
    }
}
