package be.appmire.gpsinfo.car

import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.location.Location
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The car screen's left-hand instrument column: speed dial, gimballed
 * compass, energy meter — drawn straight onto the surface canvas.
 *
 * This is a Canvas port of the shared RetroDial component (the phone
 * app's ui/components/RetroDial.kt, itself ported from id.dash): 240°
 * sweep from 150°, tick marks whose inner ends share one ring at
 * 0.94·r while their outer ends follow the rounded-square bezel (so
 * corner ticks run visibly longer), labels at 0.78·r, the tapered
 * needle with counterweight tail, and the cyan unit label above the
 * hub. Same hex palette throughout.
 *
 * The speed dial adds the phone gauge's centre stack: big digit plus
 * the ± error band from [Location.speedAccuracyMetersPerSecond], and
 * keeps the piecewise scale (60% of sweep to 100 km/h).
 *
 * The compass is the dashboard card's "slightly gimballed" rose —
 * perspective rotateX tilt, rose rotated by GPS course (never the
 * magnetometer in a car), red North, fixed lubber line, heading +
 * altitude in the centre.
 *
 * The energy meter is the id.dash power dial: −100…240 kW, blue regen
 * band below zero, green efficiency band to 60 kW. Until an OBD2
 * source feeds it, the needle parks at 0 with a dimmed readout.
 */
class CarInstruments {

    private val camera = Camera()
    private val tiltMatrix = Matrix()

    /** Solid-black column backdrop behind the three housings. */
    fun drawColumnBackground(canvas: Canvas, columnW: Float, h: Float) {
        fillPaint.color = COLUMN_BLACK
        canvas.drawRect(0f, 0f, columnW, h, fillPaint)
    }

    // ── Speed dial ─────────────────────────────────────────────────

    fun drawSpeedDial(canvas: Canvas, cell: RectF, loc: Location?) {
        val g = housing(canvas, cell)

        drawDialScale(
            canvas, g,
            minValue = 0f, maxValue = SPEED_MAX.toFloat(),
            tickStep = 10f, labelStep = 30f,
            fraction = { v -> speedFraction(v.toDouble()) },
            accents = SPEED_ACCENTS,
        )

        // Unit label above the hub — RetroDial's centre label slot.
        unitLabel(canvas, g, "km/h")

        val kmh = if (loc != null && loc.hasSpeed()) loc.speed * 3.6 else 0.0
        drawNeedle(canvas, g, START_DEG + SWEEP_DEG * speedFraction(kmh))

        // Centre stack below the hub: big digit + ± accuracy band,
        // the phone speed gauge's signature pair.
        centerPaint.color = LCD_BRIGHT
        centerPaint.textSize = g.r * 0.40f
        centerPaint.isFakeBoldText = true
        val digit = if (loc != null && loc.hasSpeed()) kmh.toInt().toString() else "—"
        canvas.drawText(digit, g.cx, g.cy + g.r * 0.52f, centerPaint)
        centerPaint.isFakeBoldText = false
        if (loc != null && loc.hasSpeedAccuracy()) {
            centerPaint.color = ACCURACY_GREY
            centerPaint.textSize = g.r * 0.13f
            canvas.drawText(
                "± %.1f km/h".format(Locale.ROOT, loc.speedAccuracyMetersPerSecond * 3.6f),
                g.cx,
                g.cy + g.r * 0.70f,
                centerPaint,
            )
        }
    }

    /** Phone gauge's piecewise scale: 60% of sweep to the pivot. */
    private fun speedFraction(kmh: Double): Float {
        val v = kmh.coerceIn(0.0, SPEED_MAX.toDouble())
        return if (v <= SPEED_PIVOT) (v / SPEED_PIVOT * 0.6).toFloat()
        else (0.6 + (v - SPEED_PIVOT) / (SPEED_MAX - SPEED_PIVOT) * 0.4).toFloat()
    }

    // ── Gimballed compass ──────────────────────────────────────────

    fun drawCompass(
        canvas: Canvas,
        cell: RectF,
        headingDeg: Float,
        hasHeading: Boolean,
        loc: Location?,
    ) {
        val g = housing(canvas, cell)

        // The "gimbal": a fixed forward tilt of the rose plane — the
        // dashboard card uses 12°; a touch more reads better across
        // the car cabin.
        camera.save()
        camera.setLocation(0f, 0f, -8f * 12f)
        camera.rotateX(COMPASS_TILT_DEG)
        camera.getMatrix(tiltMatrix)
        camera.restore()
        tiltMatrix.preTranslate(-g.cx, -g.cy)
        tiltMatrix.postTranslate(g.cx, g.cy)

        canvas.save()
        canvas.concat(tiltMatrix)
        canvas.rotate(if (hasHeading) -headingDeg else 0f, g.cx, g.cy)

        // Rose ring + ticks: minors every 10°, majors every 30°,
        // cardinals heavier; inner ends share a ring like RetroDial.
        ringPaint.color = TICK_MINOR
        canvas.drawCircle(g.cx, g.cy, g.r * 0.95f, ringPaint)
        var deg = 0
        while (deg < 360) {
            val major = deg % 30 == 0
            val cardinal = deg % 90 == 0
            tickPaint.color = when {
                deg == 0 -> NORTH_RED
                cardinal || major -> TICK_MAJOR
                else -> TICK_MINOR
            }
            tickPaint.strokeWidth = g.side * if (major) 0.014f else 0.006f
            val rad = Math.toRadians((deg - 90).toDouble())
            val c = cos(rad).toFloat()
            val s = sin(rad).toFloat()
            val inner = g.r * if (cardinal) 0.78f else if (major) 0.84f else 0.90f
            canvas.drawLine(
                g.cx + inner * c, g.cy + inner * s,
                g.cx + g.r * 0.95f * c, g.cy + g.r * 0.95f * s,
                tickPaint,
            )
            deg += 10
        }
        // Cardinal letters rotate with the card; N stays red.
        val cards = arrayOf("N", "E", "S", "W")
        cards.forEachIndexed { i, cName ->
            val a = Math.toRadians((i * 90 - 90).toDouble())
            labelPaint.textSize = g.r * 0.22f
            labelPaint.isFakeBoldText = true
            labelPaint.color = if (i == 0) NORTH_RED else TICK_MAJOR
            canvas.drawText(
                cName,
                g.cx + (g.r * 0.60f) * cos(a).toFloat(),
                g.cy + (g.r * 0.60f) * sin(a).toFloat() + labelPaint.textSize * 0.35f,
                labelPaint,
            )
            labelPaint.isFakeBoldText = false
        }
        canvas.restore()

        // Fixed lubber line at the bowl top (vehicle axis).
        val lubber = Path().apply {
            moveTo(g.cx, g.cy - g.r * 0.80f)
            lineTo(g.cx - g.r * 0.06f, g.cy - g.r * 0.94f)
            lineTo(g.cx + g.r * 0.06f, g.cy - g.r * 0.94f)
            close()
        }
        fillPaint.color = LCD_UNIT
        canvas.drawPath(lubber, fillPaint)

        // Centre readout (untilted, always legible): heading + altitude.
        centerPaint.color = LCD_BRIGHT
        centerPaint.textSize = g.r * 0.28f
        centerPaint.isFakeBoldText = true
        canvas.drawText(
            if (hasHeading) "${headingDeg.toInt()}°" else "—",
            g.cx,
            g.cy + g.r * 0.10f,
            centerPaint,
        )
        centerPaint.isFakeBoldText = false
        if (loc != null && loc.hasAltitude()) {
            centerPaint.textSize = g.r * 0.14f
            centerPaint.color = LCD_UNIT
            canvas.drawText("▲ ${loc.altitude.toInt()} m", g.cx, g.cy + g.r * 0.32f, centerPaint)
        }
    }

    // ── Energy meter (id.dash power dial) ──────────────────────────

    fun drawEnergyDial(canvas: Canvas, cell: RectF, kw: Double?) {
        val g = housing(canvas, cell)

        // Coloured zones as thin arcs at the dial-face rim (0.96·r,
        // stroke 0.02·r — RetroDial's exact placement): blue regen
        // below zero, green efficiency band to 60 kW.
        val arcRect = RectF(
            g.cx - g.r * 0.96f, g.cy - g.r * 0.96f,
            g.cx + g.r * 0.96f, g.cy + g.r * 0.96f,
        )
        zonePaint.strokeWidth = g.r * 0.02f
        zonePaint.color = REGEN_BLUE
        canvas.drawArc(
            arcRect,
            START_DEG + SWEEP_DEG * powerFraction(KW_MIN),
            SWEEP_DEG * (powerFraction(0.0) - powerFraction(KW_MIN)),
            false,
            zonePaint,
        )
        zonePaint.color = ECO_GREEN
        canvas.drawArc(
            arcRect,
            START_DEG + SWEEP_DEG * powerFraction(0.0),
            SWEEP_DEG * (powerFraction(60.0) - powerFraction(0.0)),
            false,
            zonePaint,
        )

        drawDialScale(
            canvas, g,
            minValue = KW_MIN.toFloat(), maxValue = KW_MAX.toFloat(),
            tickStep = 20f, labelStep = 40f,
            fraction = { v -> powerFraction(v.toDouble()) },
            accents = emptySet(),
            labelAbs = true,
        )

        unitLabel(canvas, g, "kW")

        drawNeedle(canvas, g, START_DEG + SWEEP_DEG * powerFraction(kw ?: 0.0))

        centerPaint.color = if (kw != null) LCD_BRIGHT else ACCURACY_GREY
        centerPaint.textSize = g.r * 0.32f
        centerPaint.isFakeBoldText = true
        canvas.drawText(
            kw?.let { "%+.0f".format(Locale.ROOT, it) } ?: "—",
            g.cx,
            g.cy + g.r * 0.52f,
            centerPaint,
        )
        centerPaint.isFakeBoldText = false
    }

    private fun powerFraction(kw: Double): Float =
        ((kw.coerceIn(KW_MIN, KW_MAX) - KW_MIN) / (KW_MAX - KW_MIN)).toFloat()

    // ── Shared RetroDial furniture ─────────────────────────────────

    /** Dial geometry within a cell: centre, radius, housing box. */
    private class DialGeometry(
        val cx: Float,
        val cy: Float,
        val r: Float,
        val side: Float,
        val halfW: Float,
        val halfH: Float,
        val cornerR: Float,
    )

    /** Rounded-RECT housing filling the whole cell (tiny seam gap) —
     *  #0B0B0B with a 10% corner radius, the RetroDial DialHousing.
     *  The dial circle sizes from the short side; the bezel-following
     *  ticks handle the wide box naturally (roundedRectRayDistance
     *  takes halfW/halfH independently), so edge ticks just run
     *  longer toward the wide sides — instrument-pod style. */
    private fun housing(canvas: Canvas, cell: RectF): DialGeometry {
        val gap = min(cell.width(), cell.height()) * 0.02f
        val box = RectF(cell.left + gap, cell.top + gap, cell.right - gap, cell.bottom - gap)
        val shortest = min(box.width(), box.height())
        fillPaint.color = HOUSING
        canvas.drawRoundRect(box, shortest * 0.10f, shortest * 0.10f, fillPaint)
        return DialGeometry(
            cx = box.centerX(),
            cy = box.centerY(),
            r = shortest / 2f * 0.92f,
            side = shortest,
            halfW = box.width() / 2f,
            halfH = box.height() / 2f,
            cornerR = shortest * 0.10f,
        )
    }

    /** RetroDial scale: tick inner ends share the 0.94·r ring, outer
     *  ends follow the rounded-square bezel (corner ticks longer),
     *  labels at 0.78·r in #EDEDED ExtraBold. */
    private fun drawDialScale(
        canvas: Canvas,
        g: DialGeometry,
        minValue: Float,
        maxValue: Float,
        tickStep: Float,
        labelStep: Float,
        fraction: (Float) -> Float,
        accents: Set<Int>,
        labelAbs: Boolean = false,
    ) {
        val sharedInnerT = g.r * 0.94f
        val bezelInset = g.side * 0.025f
        val labelT = g.r * 0.78f
        var v = minValue
        while (v <= maxValue + 0.0001f) {
            val frac = fraction(v).coerceIn(0f, 1f)
            val angle = Math.toRadians((START_DEG + SWEEP_DEG * frac).toDouble())
            val cosA = cos(angle).toFloat()
            val sinA = sin(angle).toFloat()
            val rel = v - minValue
            val isMajor = (rel % labelStep).let { it < 0.0001f || (labelStep - it) < 0.0001f }
            val isAccent = accents.any { abs(v - it) < 0.001f }
            val outerT = roundedRectRayDistance(
                dx = cosA, dy = sinA,
                halfW = g.halfW, halfH = g.halfH,
                cornerR = g.cornerR,
            ) - bezelInset
            if (sharedInnerT < outerT) {
                tickPaint.strokeWidth = g.side * if (isMajor) 0.014f else 0.006f
                tickPaint.color = when {
                    isAccent -> ACCENT
                    isMajor -> TICK_MAJOR
                    else -> TICK_MINOR
                }
                canvas.drawLine(
                    g.cx + outerT * cosA, g.cy + outerT * sinA,
                    g.cx + sharedInnerT * cosA, g.cy + sharedInnerT * sinA,
                    tickPaint,
                )
                if (isMajor) {
                    labelPaint.textSize = g.r * 0.155f
                    labelPaint.isFakeBoldText = true
                    labelPaint.color = TICK_MAJOR
                    val shown = if (labelAbs) abs(v.toInt()) else v.toInt()
                    canvas.drawText(
                        shown.toString(),
                        g.cx + labelT * cosA,
                        g.cy + labelT * sinA + labelPaint.textSize * 0.35f,
                        labelPaint,
                    )
                    labelPaint.isFakeBoldText = false
                }
            }
            v += tickStep
        }
    }

    /** Cyan unit label just above the hub — RetroDial's centre slot. */
    private fun unitLabel(canvas: Canvas, g: DialGeometry, text: String) {
        centerPaint.color = LCD_UNIT
        centerPaint.textSize = g.r * 0.14f
        centerPaint.isFakeBoldText = true
        canvas.drawText(text, g.cx, g.cy - g.r * 0.30f, centerPaint)
        centerPaint.isFakeBoldText = false
    }

    /** RetroDial needle: tapered pointer to 0.93·r with counterweight
     *  tail to −0.26·r, tiny dark pivot screw. */
    private fun drawNeedle(canvas: Canvas, g: DialGeometry, angleDeg: Float) {
        canvas.save()
        canvas.rotate(angleDeg, g.cx, g.cy)
        val r = g.r
        val needle = Path().apply {
            moveTo(g.cx - r * 0.26f, g.cy - r * 0.026f)
            lineTo(g.cx + r * 0.89f, g.cy - r * 0.012f)
            lineTo(g.cx + r * 0.93f, g.cy)
            lineTo(g.cx + r * 0.89f, g.cy + r * 0.012f)
            lineTo(g.cx - r * 0.26f, g.cy + r * 0.026f)
            close()
        }
        fillPaint.color = NEEDLE
        canvas.drawPath(needle, fillPaint)
        fillPaint.color = PIVOT
        canvas.drawCircle(g.cx, g.cy, r * 0.010f, fillPaint)
        canvas.restore()
    }

    /**
     * Distance from the dial centre along a unit ray at which it exits
     * the rounded-square housing — RetroDial's trick for making corner
     * ticks longer than edge ticks. Direct port of the Compose helper.
     */
    private fun roundedRectRayDistance(
        dx: Float,
        dy: Float,
        halfW: Float,
        halfH: Float,
        cornerR: Float,
    ): Float {
        val adx = abs(dx)
        val ady = abs(dy)
        if (adx < 1e-6f && ady < 1e-6f) return 0f

        val tx = if (adx > 1e-6f) halfW / adx else Float.POSITIVE_INFINITY
        val ty = if (ady > 1e-6f) halfH / ady else Float.POSITIVE_INFINITY
        val tStraight = min(tx, ty)

        val px = tStraight * adx
        val py = tStraight * ady
        if (cornerR > 0f && px > halfW - cornerR && py > halfH - cornerR) {
            val p = halfW - cornerR
            val q = halfH - cornerR
            val qa = adx * adx + ady * ady
            val qb = -2f * (adx * p + ady * q)
            val qc = p * p + q * q - cornerR * cornerR
            val disc = qb * qb - 4f * qa * qc
            if (disc >= 0f) {
                return (-qb + sqrt(disc)) / (2f * qa)
            }
        }
        return tStraight
    }

    // ── Paints (single-threaded reuse) ─────────────────────────────

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.BUTT }
    private val zonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    private companion object {
        // RetroDial geometry — shared with the phone gauge + id.dash.
        const val START_DEG = 150f
        const val SWEEP_DEG = 240f

        const val SPEED_MAX = 180
        const val SPEED_PIVOT = 100.0
        val SPEED_ACCENTS = setOf(30, 50, 70, 90, 120)

        const val KW_MIN = -100.0
        const val KW_MAX = 240.0

        const val COMPASS_TILT_DEG = 16f

        // Palette — RetroDial / id.dash hexes.
        const val COLUMN_BLACK = 0xFF000000.toInt()
        const val HOUSING = 0xFF0B0B0B.toInt()
        const val NEEDLE = 0xDDFFFFFF.toInt()
        const val PIVOT = 0xFF1A1A1A.toInt()
        const val TICK_MAJOR = 0xFFEDEDED.toInt()
        const val TICK_MINOR = 0xFF9A9A9A.toInt()
        const val ACCENT = 0xFFE67635.toInt()
        const val LCD_BRIGHT = 0xFF7FE3FF.toInt()
        const val LCD_UNIT = 0xFF7FCCFF.toInt()
        const val ACCURACY_GREY = 0xFF8AA0AA.toInt()
        const val NORTH_RED = 0xFFEF5350.toInt()
        const val REGEN_BLUE = 0xFF35B0E6.toInt()
        const val ECO_GREEN = 0xFF35B047.toInt()
    }
}
