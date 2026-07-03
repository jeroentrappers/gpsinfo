package be.appmire.gpsinfo.car

import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.PropertyFactory

/**
 * The Waze-style day palette for the OpenFreeMap *Liberty* style, applied by
 * layer id. The stock light style reads bland on the car screen (very pale
 * land, weak road casings); this warms the land so white streets pop,
 * saturates water/greens, and gives the road hierarchy bolder fills +
 * casings.
 *
 * Shared by both map backends — [CarMapSnapshotter] (off-screen bitmap) and
 * the live-GL [org.maplibre.android.maps.CarGlMap] — so the two look
 * identical. Each layer is set defensively: an unknown id is skipped, so it
 * tolerates style/layer-set drift.
 */
object CarMapPalette {

    /** Apply the palette using a caller-provided layer lookup (each backend
     *  resolves layers its own way). Safe to call once the style is loaded. */
    fun applyTo(getLayer: (String) -> Layer?) {
        fun fill(id: String, color: String) = runCatching {
            getLayer(id)?.setProperties(PropertyFactory.fillColor(color))
        }
        fun line(id: String, color: String) = runCatching {
            getLayer(id)?.setProperties(PropertyFactory.lineColor(color))
        }
        runCatching {
            getLayer("background")?.setProperties(PropertyFactory.backgroundColor(LAND))
        }

        // Surfaces.
        fill("water", WATER)
        listOf("waterway_river", "waterway_other", "waterway_tunnel").forEach { line(it, WATER) }
        fill("park", PARK); fill("landcover_grass", GRASS); fill("landcover_wood", WOOD)
        fill("building", BUILDING)
        runCatching {
            getLayer("building-3d")?.setProperties(PropertyFactory.fillExtrusionColor(BUILDING))
        }

        // Road hierarchy — fills then casings, across surface/tunnel/bridge.
        listOf("road_motorway", "road_motorway_link", "tunnel_motorway", "tunnel_motorway_link",
            "bridge_motorway", "bridge_motorway_link").forEach { line(it, MOTORWAY) }
        listOf("road_motorway_casing", "road_motorway_link_casing", "tunnel_motorway_casing",
            "tunnel_motorway_link_casing", "bridge_motorway_casing", "bridge_motorway_link_casing")
            .forEach { line(it, MOTORWAY_CASING) }

        listOf("road_trunk_primary", "tunnel_trunk_primary", "bridge_trunk_primary",
            "road_link", "tunnel_link", "bridge_link").forEach { line(it, ARTERIAL) }
        listOf("road_trunk_primary_casing", "tunnel_trunk_primary_casing", "bridge_trunk_primary_casing",
            "road_link_casing", "tunnel_link_casing", "bridge_link_casing").forEach { line(it, ARTERIAL_CASING) }

        listOf("road_secondary_tertiary", "tunnel_secondary_tertiary", "bridge_secondary_tertiary")
            .forEach { line(it, SECONDARY) }
        listOf("road_secondary_tertiary_casing", "tunnel_secondary_tertiary_casing",
            "bridge_secondary_tertiary_casing").forEach { line(it, SECONDARY_CASING) }

        listOf("road_minor", "road_service_track", "tunnel_minor", "tunnel_service_track",
            "tunnel_street", "bridge_street", "bridge_service_track").forEach { line(it, MINOR) }
        listOf("road_minor_casing", "road_service_track_casing", "tunnel_service_track_casing",
            "tunnel_street_casing", "bridge_street_casing", "bridge_service_track_casing")
            .forEach { line(it, MINOR_CASING) }
    }

    const val LAND = "#E7E4DB"
    const val WATER = "#A6CEF0"
    const val PARK = "#C2E0A2"
    const val GRASS = "#BCDD9C"
    const val WOOD = "#C6E4AC"
    const val BUILDING = "#DED9CB"
    const val MOTORWAY = "#F8B24A"
    const val MOTORWAY_CASING = "#E08A2E"
    const val ARTERIAL = "#FBD068"
    const val ARTERIAL_CASING = "#E2A646"
    const val SECONDARY = "#FCE3A0"
    const val SECONDARY_CASING = "#D8C68C"
    const val MINOR = "#FFFFFF"
    const val MINOR_CASING = "#C6C1B5"
}
