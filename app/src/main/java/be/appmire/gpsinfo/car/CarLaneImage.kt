package be.appmire.gpsinfo.car

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.car.app.model.CarIcon
import androidx.core.graphics.drawable.IconCompat
import be.appmire.gpsinfo.data.nav.Lane
import be.appmire.gpsinfo.data.nav.TurnCommand

/**
 * Renders a lane-assist image for the Car App Library [androidx.car.app.navigation.model.Step].
 * The host draws this image (it takes priority over the Lane metadata), so we
 * paint one cell per lane with an arrow per allowed direction — the lane(s)
 * the driver should be in (active) bright, the rest dimmed. Kept within the
 * template's 294×44 dp lanes-image budget, transparent background.
 */
internal object CarLaneImage {

    private const val MAX_LANES = 8
    private const val LANE_DP = 36f
    private const val HEIGHT_DP = 44f
    private const val MAX_WIDTH_DP = 294f
    private const val ACTIVE = 0xFFFFFFFF.toInt()
    private const val DIMMED = 0x66FFFFFF.toInt()

    /** A lanes [CarIcon] for [lanes], or null if there's nothing to show. */
    fun render(context: Context, lanes: List<Lane>): CarIcon? {
        if (lanes.isEmpty()) return null
        val d = context.resources.displayMetrics.density
        val n = lanes.size.coerceAtMost(MAX_LANES)
        val h = (HEIGHT_DP * d).toInt().coerceAtLeast(1)
        val w = ((LANE_DP * d * n).coerceAtMost(MAX_WIDTH_DP * d)).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cellW = w.toFloat() / n
        for (i in 0 until n) {
            val lane = lanes[i]
            paint.color = if (lane.active) ACTIVE else DIMMED
            paint.strokeWidth = h * (if (lane.active) 0.11f else 0.07f)
            val cx = cellW * i + cellW / 2f
            val dirs = lane.directions.ifEmpty { listOf(TurnCommand.STRAIGHT) }
            dirs.forEach { drawArrow(canvas, cx, h.toFloat(), it) }
        }
        return CarIcon.Builder(IconCompat.createWithBitmap(bmp)).build()
    }

    /** A stem-plus-head arrow rising from the cell base, rotated to the
     *  turn direction (negative = left). */
    private fun drawArrow(canvas: Canvas, cx: Float, h: Float, dir: TurnCommand) {
        val angle = when (dir) {
            TurnCommand.STRAIGHT -> 0f
            TurnCommand.TURN_SLIGHT_LEFT, TurnCommand.KEEP_LEFT -> -30f
            TurnCommand.TURN_LEFT -> -70f
            TurnCommand.TURN_SHARP_LEFT -> -110f
            TurnCommand.TURN_SLIGHT_RIGHT, TurnCommand.KEEP_RIGHT -> 30f
            TurnCommand.TURN_RIGHT -> 70f
            TurnCommand.TURN_SHARP_RIGHT -> 110f
            TurnCommand.U_TURN -> -150f
            else -> 0f
        }
        val base = h * 0.88f
        val pivotY = h * 0.82f
        val tip = h * 0.18f
        val head = h * 0.16f
        canvas.save()
        canvas.rotate(angle, cx, pivotY)
        canvas.drawLine(cx, base, cx, tip, paint)             // stem
        canvas.drawLine(cx, tip, cx - head, tip + head, paint) // head left
        canvas.drawLine(cx, tip, cx + head, tip + head, paint) // head right
        canvas.restore()
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
}
