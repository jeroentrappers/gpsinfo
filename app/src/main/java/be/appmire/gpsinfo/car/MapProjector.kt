package be.appmire.gpsinfo.car

import android.graphics.PointF
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.snapshotter.MapSnapshot

/**
 * The *only* thing the overlay drawing needs from the map engine: where a
 * given lat/lon lands on the surface, and the inverse. Abstracting it lets
 * the same route / puck / traffic / breadcrumb code run over either backend:
 *
 *  - [SnapshotProjector] — the off-screen [MapSnapshot] bitmap path (today's
 *    default; the base map is a bitmap blitted under the overlays).
 *  - the live-GL path (CarGlMap), where MapLibre renders straight onto the
 *    Android Auto surface and projection comes from its NativeMapView.
 *
 * Method names/shapes deliberately mirror [MapSnapshot] so the overlay code
 * that used to take a `MapSnapshot` ports across with only its parameter
 * *type* changed.
 */
interface MapProjector {
    /** Surface width/height in px the projection is defined against. */
    val width: Int
    val height: Int
    fun pixelForLatLng(latLng: LatLng): PointF
    fun latLngForPixel(point: PointF): LatLng
}

/** [MapProjector] backed by a completed off-screen [MapSnapshot]. */
class SnapshotProjector(private val snap: MapSnapshot) : MapProjector {
    override val width: Int get() = snap.bitmap.width
    override val height: Int get() = snap.bitmap.height
    override fun pixelForLatLng(latLng: LatLng): PointF = snap.pixelForLatLng(latLng)
    override fun latLngForPixel(point: PointF): LatLng = snap.latLngForPixel(point)
}
