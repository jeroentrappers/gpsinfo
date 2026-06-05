package be.appmire.gpsinfo.data.nav

import kotlin.math.floor

/**
 * BRouter's road network ships as `rd5` segment tiles, one per
 * 5°×5° cell, named by the cell's south-west corner: `E5_N50.rd5`,
 * `W10_N45.rd5`, … This object is the pure naming/coverage math —
 * unit-tested, no Android types.
 */
object Rd5Tiles {

    /** Tile file name for the cell containing ([lat], [lon]). */
    fun tileName(lat: Double, lon: Double): String {
        val lonBase = (floor(lon / 5.0) * 5.0).toInt()
        val latBase = (floor(lat / 5.0) * 5.0).toInt()
        val lonPart = if (lonBase < 0) "W${-lonBase}" else "E$lonBase"
        val latPart = if (latBase < 0) "S${-latBase}" else "N$latBase"
        return "${lonPart}_$latPart.rd5"
    }

    /**
     * All tiles needed to cover the bounding box of a route between
     * two points, padded by [marginDeg] so a route that hugs a tile
     * edge doesn't fall off the network mid-way. BRouter itself reads
     * neighbouring tiles as the search expands — missing ones simply
     * truncate the network, so we over-cover slightly.
     */
    fun tilesForBoundingBox(
        latA: Double,
        lonA: Double,
        latB: Double,
        lonB: Double,
        marginDeg: Double = 0.1,
    ): List<String> {
        val south = minOf(latA, latB) - marginDeg
        val north = maxOf(latA, latB) + marginDeg
        val west = minOf(lonA, lonB) - marginDeg
        val east = maxOf(lonA, lonB) + marginDeg
        val names = LinkedHashSet<String>()
        var lat = floor(south / 5.0) * 5.0
        while (lat <= north) {
            var lon = floor(west / 5.0) * 5.0
            while (lon <= east) {
                names.add(tileName(lat, lon))
                lon += 5.0
            }
            lat += 5.0
        }
        return names.toList()
    }

    /** Download URL on the BRouter project's public segment server. */
    fun downloadUrl(tile: String): String = "$SEGMENT_BASE_URL$tile"

    const val SEGMENT_BASE_URL = "https://brouter.de/brouter/segments4/"
}
