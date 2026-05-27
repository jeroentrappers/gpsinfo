package be.appmire.gpsinfo.data.gpx

import be.appmire.gpsinfo.data.model.TrailPoint
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Ramer–Douglas–Peucker simplification on a trail's track points.
 *
 * The classical RDP: walk the simplified line from start to end, find the
 * point farthest from it, keep that point if it's farther than the
 * tolerance, recurse on both halves. Here:
 *   * the distance is computed in 3D against the segment between the two
 *     anchor points — projecting `(lat, lon)` to local metres around the
 *     start anchor (so ε is in metres and matches GPS accuracy units),
 *     and treating `eleMeters` as a directly-comparable axis. Points
 *     without elevation are simplified in 2D against each other.
 *   * the recursion is iterative with an explicit stack — a near-straight
 *     trail of 30 k points would otherwise blow the default JVM stack.
 *
 * Why RDP and not Visvalingam–Whyatt: the natural threshold for GPS
 * data is "metres off the simplified line", which is RDP's ε directly.
 * VW's "effective area" threshold is harder to map to GPS accuracy.
 *
 * Why no separate stationary-cluster collapse stage: RDP already does
 * this. When the device sits still, GPS noise scatters points within a
 * few metres of the resting position; the line from before-cluster to
 * after-cluster is short and straight, and every noisy point in between
 * sits within ε of it. RDP drops them all in one pass.
 */
object TrailSimplifier {

    /**
     * Three presets covering the realistic GPS accuracy envelope.
     *
     *   * [Light]      — minimal cleanup, ε just below typical good-sky
     *                    accuracy. Removes only very obvious filler.
     *   * [Default]    — ε at typical GPS accuracy. Cleans noise without
     *                    distorting shape; usually 50–70 % reduction on
     *                    a walking trail.
     *   * [Aggressive] — ε at "urban-canyon" accuracy. Keeps only the
     *                    structurally important inflection points;
     *                    typically 85–95 % reduction.
     */
    enum class Preset(val epsilonMeters: Double) {
        Light(2.0),
        Default(5.0),
        Aggressive(12.0),
    }

    /**
     * Returns the indices of points to *keep* in [pts], with the
     * first and last point always retained. Index-returning rather than
     * point-returning so callers can reuse the original [TrailPoint]
     * objects without allocating new ones.
     */
    fun keptIndices(pts: List<TrailPoint>, epsilonMeters: Double): BooleanArray {
        val n = pts.size
        val keep = BooleanArray(n)
        if (n == 0) return keep
        keep[0] = true
        keep[n - 1] = true
        if (n < 3) return keep

        // Iterative RDP. The stack holds (lo, hi) index pairs encoded as
        // packed longs to avoid allocating Pair<Int, Int> per recursion.
        val stack = ArrayDeque<Long>()
        stack.addLast(pack(0, n - 1))
        while (stack.isNotEmpty()) {
            val frame = stack.removeLast()
            val lo = unpackLo(frame)
            val hi = unpackHi(frame)
            if (hi - lo < 2) continue

            var maxDist = 0.0
            var maxIdx = lo
            val a = pts[lo]
            val b = pts[hi]
            // Pre-compute the segment basis once per frame — every point
            // in this frame projects against the same A→B.
            val basis = segmentBasis(a, b)
            for (i in lo + 1 until hi) {
                val d = perpendicularDistance(pts[i], a, basis)
                if (d > maxDist) {
                    maxDist = d
                    maxIdx = i
                }
            }
            if (maxDist > epsilonMeters) {
                keep[maxIdx] = true
                stack.addLast(pack(lo, maxIdx))
                stack.addLast(pack(maxIdx, hi))
            }
        }
        return keep
    }

    /**
     * Convenience: returns a new list with the dropped points removed.
     * `TrailPoint` is immutable so this is a shallow copy.
     */
    fun simplify(pts: List<TrailPoint>, epsilonMeters: Double): List<TrailPoint> {
        if (pts.size < 3) return pts
        val keep = keptIndices(pts, epsilonMeters)
        val out = ArrayList<TrailPoint>(pts.size)
        for (i in pts.indices) if (keep[i]) out.add(pts[i])
        return out
    }

    fun simplify(pts: List<TrailPoint>, preset: Preset): List<TrailPoint> =
        simplify(pts, preset.epsilonMeters)

    // ---- internals ----

    /**
     * Precomputed pieces of the line A→B that don't change while we walk
     * through the intermediate points: the local-meters offset of B from
     * A, plus the squared length used to project points onto AB.
     */
    private class SegmentBasis(
        val bx: Double, val by: Double, val bz: Double,
        val abLen2: Double,
        val cosLatA: Double,
    )

    private fun segmentBasis(a: TrailPoint, b: TrailPoint): SegmentBasis {
        val cosLatA = cos(Math.toRadians(a.latDeg))
        val bx = Math.toRadians(b.lonDeg - a.lonDeg) * EARTH_R * cosLatA
        val by = Math.toRadians(b.latDeg - a.latDeg) * EARTH_R
        // Mixing 2D and 3D points: if either endpoint lacks elevation we
        // fall back to a 2D segment. The intermediate points are then
        // also projected with bz=0 (see perpendicularDistance).
        val bz = if (a.eleMeters != null && b.eleMeters != null)
            b.eleMeters - a.eleMeters else 0.0
        return SegmentBasis(bx, by, bz, bx * bx + by * by + bz * bz, cosLatA)
    }

    /**
     * Distance from [p] to the segment AB encoded in [basis]. Uses
     * segment distance (clamp the projection parameter to [0,1]) rather
     * than infinite-line distance — that way a "before A" or "past B"
     * point is measured to the endpoint, which is the natural reading
     * for trail data.
     */
    private fun perpendicularDistance(
        p: TrailPoint, a: TrailPoint, basis: SegmentBasis,
    ): Double {
        val px = Math.toRadians(p.lonDeg - a.lonDeg) * EARTH_R * basis.cosLatA
        val py = Math.toRadians(p.latDeg - a.latDeg) * EARTH_R
        val pz = if (a.eleMeters != null && p.eleMeters != null)
            p.eleMeters - a.eleMeters else 0.0

        if (basis.abLen2 < 1e-9) {
            // A and B coincide — distance is just from P to A.
            return sqrt(px * px + py * py + pz * pz)
        }
        val t = ((px * basis.bx) + (py * basis.by) + (pz * basis.bz)) / basis.abLen2
        val tc = t.coerceIn(0.0, 1.0)
        val cx = tc * basis.bx
        val cy = tc * basis.by
        val cz = tc * basis.bz
        val dx = px - cx
        val dy = py - cy
        val dz = pz - cz
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    // Packing two non-negative ints into a Long: high 32 bits = hi, low 32 = lo.
    // The stack pops millions of frames on a big trail; avoiding Pair<>
    // allocations cuts GC pressure noticeably.
    private fun pack(lo: Int, hi: Int): Long = (hi.toLong() shl 32) or (lo.toLong() and 0xFFFFFFFFL)
    private fun unpackLo(v: Long): Int = (v and 0xFFFFFFFFL).toInt()
    private fun unpackHi(v: Long): Int = (v ushr 32).toInt()

    private const val EARTH_R = 6_371_000.0
}
