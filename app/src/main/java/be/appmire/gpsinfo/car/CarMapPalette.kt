package be.appmire.gpsinfo.car

import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.PropertyFactory

/**
 * Waze-style map palettes for the OpenFreeMap OpenMapTiles schema, applied by
 * layer id. Two variants:
 *  - [applyDay] warms the light *Liberty* style so white streets pop and the
 *    road hierarchy reads boldly (used in light mode).
 *  - [applyNight] recolours the *dark* style into a Waze-night look: deep slate
 *    land, dark water, roads lifted for contrast, highways amber.
 *
 * Shared by both map backends — [CarMapSnapshotter] (off-screen bitmap) and the
 * live-GL [org.maplibre.android.maps.CarGlMap] — so they look identical. Each
 * layer is set defensively: an unknown id is skipped, so it tolerates
 * style/layer-set drift between the light and dark styles.
 */
object CarMapPalette {

    /** Backwards-compatible entry point (day palette). */
    fun applyTo(getLayer: (String) -> Layer?): Boolean = applyDay(getLayer)

    fun applyDay(getLayer: (String) -> Layer?): Boolean = recolor(getLayer, DAY)

    fun applyNight(getLayer: (String) -> Layer?): Boolean = recolor(getLayer, NIGHT)

    /** Recolour the known layers with [p]. Returns false when the style isn't
     *  ready yet (no `background` layer) so the caller can retry. */
    private fun recolor(getLayer: (String) -> Layer?, p: Palette): Boolean {
        runCatching { getLayer("background") }.getOrNull() ?: return false
        fun fill(id: String, color: String) = runCatching {
            getLayer(id)?.setProperties(PropertyFactory.fillColor(color))
        }
        fun line(id: String, color: String) = runCatching {
            getLayer(id)?.setProperties(PropertyFactory.lineColor(color))
        }
        runCatching { getLayer("background")?.setProperties(PropertyFactory.backgroundColor(p.land)) }

        // Surfaces.
        fill("water", p.water)
        listOf("waterway_river", "waterway_other", "waterway_tunnel").forEach { line(it, p.water) }
        fill("park", p.park); fill("landcover_grass", p.grass); fill("landcover_wood", p.wood)
        fill("building", p.building)
        runCatching {
            getLayer("building-3d")?.setProperties(PropertyFactory.fillExtrusionColor(p.building))
        }

        // Road hierarchy — fills then casings, across surface/tunnel/bridge.
        listOf("road_motorway", "road_motorway_link", "tunnel_motorway", "tunnel_motorway_link",
            "bridge_motorway", "bridge_motorway_link").forEach { line(it, p.motorway) }
        listOf("road_motorway_casing", "road_motorway_link_casing", "tunnel_motorway_casing",
            "tunnel_motorway_link_casing", "bridge_motorway_casing", "bridge_motorway_link_casing")
            .forEach { line(it, p.motorwayCasing) }

        listOf("road_trunk_primary", "tunnel_trunk_primary", "bridge_trunk_primary",
            "road_link", "tunnel_link", "bridge_link").forEach { line(it, p.arterial) }
        listOf("road_trunk_primary_casing", "tunnel_trunk_primary_casing", "bridge_trunk_primary_casing",
            "road_link_casing", "tunnel_link_casing", "bridge_link_casing").forEach { line(it, p.arterialCasing) }

        listOf("road_secondary_tertiary", "tunnel_secondary_tertiary", "bridge_secondary_tertiary")
            .forEach { line(it, p.secondary) }
        listOf("road_secondary_tertiary_casing", "tunnel_secondary_tertiary_casing",
            "bridge_secondary_tertiary_casing").forEach { line(it, p.secondaryCasing) }

        listOf("road_minor", "road_service_track", "tunnel_minor", "tunnel_service_track",
            "tunnel_street", "bridge_street", "bridge_service_track").forEach { line(it, p.minor) }
        listOf("road_minor_casing", "road_service_track_casing", "tunnel_service_track_casing",
            "tunnel_street_casing", "bridge_street_casing", "bridge_service_track_casing")
            .forEach { line(it, p.minorCasing) }
        return true
    }

    private class Palette(
        val land: String, val water: String, val park: String, val grass: String, val wood: String,
        val building: String, val motorway: String, val motorwayCasing: String,
        val arterial: String, val arterialCasing: String, val secondary: String, val secondaryCasing: String,
        val minor: String, val minorCasing: String,
    )

    private val DAY = Palette(
        land = "#E7E4DB", water = "#A6CEF0", park = "#C2E0A2", grass = "#BCDD9C", wood = "#C6E4AC",
        building = "#DED9CB", motorway = "#F8B24A", motorwayCasing = "#E08A2E",
        arterial = "#FBD068", arterialCasing = "#E2A646", secondary = "#FCE3A0", secondaryCasing = "#D8C68C",
        minor = "#FFFFFF", minorCasing = "#C6C1B5",
    )

    // Waze-night: deep slate land, dark water, roads lifted off the ground for
    // contrast (minor roads a touch brighter than land), highways amber.
    private val NIGHT = Palette(
        land = "#232A35", water = "#122234", park = "#20302A", grass = "#233026", wood = "#1E2C22",
        building = "#2B323E", motorway = "#C79A4E", motorwayCasing = "#8A6A32",
        arterial = "#586A86", arterialCasing = "#33405A", secondary = "#4A5568", secondaryCasing = "#2E3846",
        minor = "#3E4757", minorCasing = "#2A313D",
    )
}
