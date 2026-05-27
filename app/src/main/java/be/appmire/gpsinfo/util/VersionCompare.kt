package be.appmire.gpsinfo.util

/**
 * Compares dotted-numeric version names ("2.0.1" vs "2.1").
 *
 *  - A leading "v"/"V" is tolerated ("v2.0.1" == "2.0.1").
 *  - Components are compared numerically, left to right.
 *  - Non-numeric tails are ignored ("2.1.0-beta" -> 2,1,0).
 *  - Missing trailing components count as 0 ("2.1" == "2.1.0").
 *
 * Pure logic, extracted so the update-nudge gate is unit-testable without
 * a device.
 */
object VersionCompare {

    /** True when [candidate] is a strictly newer version than [current]. */
    fun isNewer(candidate: String, current: String): Boolean =
        compare(candidate, current) > 0

    /** Standard comparator contract: negative / 0 / positive. */
    fun compare(a: String, b: String): Int {
        val pa = parts(a)
        val pb = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    private fun parts(v: String): List<Int> =
        v.trim().removePrefix("v").removePrefix("V")
            .split('.')
            .map { component -> component.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
}
