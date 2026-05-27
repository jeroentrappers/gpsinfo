package be.appmire.gpsinfo.ui.components

import android.content.Context
import java.io.DataInputStream

/**
 * Land polygons sourced from Natural Earth 1:110m (public domain).
 *
 * Stored as `assets/world_110m.bin` to avoid the JVM's 64 KB method-size
 * limit hit when inlining ~5,000 floats into a `<clinit>` block.
 *
 * Binary layout (big-endian):
 *   int32 polyCount
 *   for each polygon:
 *     int32 pointCount
 *     repeated: float32 lat, float32 lon
 */
internal object WorldCoastlines {

    @Volatile private var cached: List<FloatArray>? = null

    fun load(context: Context): List<FloatArray> {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val polys = context.assets.open("world_110m.bin").use { input ->
                DataInputStream(input.buffered()).use { d ->
                    val polyCount = d.readInt()
                    ArrayList<FloatArray>(polyCount).apply {
                        repeat(polyCount) {
                            val n = d.readInt()
                            val arr = FloatArray(n * 2)
                            for (i in arr.indices) arr[i] = d.readFloat()
                            add(arr)
                        }
                    }
                }
            }
            cached = polys
            return polys
        }
    }
}
