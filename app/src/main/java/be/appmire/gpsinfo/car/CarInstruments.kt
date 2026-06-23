package be.appmire.gpsinfo.car

import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.location.Location
import be.appmire.gpsinfo.data.model.GForceSample
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.roundToInt
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

    // ── Speed dial ─────────────────────────────────────────────────

    fun drawSpeedDial(
        canvas: Canvas,
        cell: RectF,
        loc: Location?,
        speedLimitKmh: Int?,
        odometerKm: Double,
    ) {
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

        val over = speedLimitKmh != null && kmh > speedLimitKmh + SPEED_OVER_TOL

        // Posted-limit marker on the scale, just inside the ticks. Amber
        // normally; the dot and its glow flare red once the limit is
        // exceeded — a glanceable "you're speeding" cue on the dial.
        if (speedLimitKmh != null) {
            val frac = speedFraction(speedLimitKmh.toDouble())
            val ang = Math.toRadians((START_DEG + SWEEP_DEG * frac).toDouble())
            val dx = cos(ang).toFloat()
            val dy = sin(ang).toFloat()
            val rr = g.r * 0.90f
            val dotX = g.cx + rr * dx
            val dotY = g.cy + rr * dy
            val dotColor = if (over) NORTH_RED else LIMIT_DOT
            fillPaint.color = fade(dotColor, if (over) 0.55f else 0.28f)
            canvas.drawCircle(dotX, dotY, g.r * 0.12f, fillPaint)
            fillPaint.color = dotColor
            canvas.drawCircle(dotX, dotY, g.r * 0.055f, fillPaint)
        }

        // Big speed digit — fixed 3-digit width with leading zeros so the
        // readout never shifts as the speed crosses 9→10→100. Turns red
        // when over the posted limit.
        centerPaint.color = if (over) NORTH_RED else LCD_BRIGHT
        centerPaint.textSize = g.r * 0.38f
        centerPaint.isFakeBoldText = true
        val digit = if (loc != null && loc.hasSpeed())
            "%03d".format(Locale.ROOT, kmh.toInt().coerceIn(0, 999)) else "———"
        canvas.drawText(digit, g.cx, g.cy + g.r * 0.40f, centerPaint)
        centerPaint.isFakeBoldText = false

        // Trip odometer — distance driven this session, tucked in the
        // open lower gap under the speed digit.
        centerPaint.color = LCD_UNIT
        centerPaint.textSize = g.r * 0.15f
        centerPaint.isFakeBoldText = true
        canvas.drawText(
            "%.1f km".format(Locale.ROOT, odometerKm),
            g.cx, g.cy + g.r * 0.62f, centerPaint,
        )
        centerPaint.isFakeBoldText = false

        // Posted-limit roundel at the dial's bottom centre (EU sign),
        // always present — a muted dash when no limit is known.
        drawLimitSign(canvas, g.cx, g.cy + g.r * 0.85f, g.r * 0.135f, speedLimitKmh)
    }

    /** EU-style posted-limit roundel — white disc, red ring, black
     *  number. Grey dash when the limit is unknown. */
    private fun drawLimitSign(canvas: Canvas, cx: Float, cy: Float, radius: Float, limitKmh: Int?) {
        val known = limitKmh != null
        fillPaint.color = Color.WHITE
        canvas.drawCircle(cx, cy, radius, fillPaint)
        ringPaint.color = if (known) SIGN_RED else ACCURACY_GREY
        ringPaint.strokeWidth = radius * 0.22f
        canvas.drawCircle(cx, cy, radius * 0.86f, ringPaint)
        val txt = limitKmh?.toString() ?: "–"
        centerPaint.color = if (known) Color.BLACK else ACCURACY_GREY
        centerPaint.isFakeBoldText = true
        centerPaint.textSize = if (txt.length >= 3) radius * 0.78f else radius * 1.0f
        canvas.drawText(txt, cx, cy + centerPaint.textSize * 0.36f, centerPaint)
        centerPaint.isFakeBoldText = false
    }

    /** Phone gauge's piecewise scale: 60% of sweep to the pivot. */
    private fun speedFraction(kmh: Double): Float {
        val v = kmh.coerceIn(0.0, SPEED_MAX.toDouble())
        return if (v <= SPEED_PIVOT) (v / SPEED_PIVOT * 0.6).toFloat()
        else (0.6 + (v - SPEED_PIVOT) / (SPEED_MAX - SPEED_PIVOT) * 0.4).toFloat()
    }

    // ── Merged compass / G-meter ───────────────────────────────────

    /**
     * One dynamics dial combining the gimballed compass (where the car
     * points, world frame) with the G-meter (how it's being driven, car
     * frame). The rose — ticks + cardinals + red North — rides the rim
     * and rotates by GPS course; the inner plot is the G-meter (lateral
     * = cornering, longitudinal = braking/accel) with a short fading
     * trail, drawn un-rotated in the car's own frame. Heading reads at
     * the top gap, the live horizontal-G magnitude at the bottom. Sized
     * a touch smaller than the speed/power dials — it's the supporting
     * instrument, not the headline.
     */
    fun drawCompassGMeter(
        canvas: Canvas,
        cell: RectF,
        headingDeg: Float,
        hasHeading: Boolean,
        loc: Location?,
        trail: List<GForceSample>,
    ) {
        val g = housing(canvas, cell)

        // ── Compass rose on the rim (gimballed + course-rotated) ──
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
            // Ticks hug the rim so the inner disc is free for the G-plot.
            val inner = g.r * if (cardinal) 0.86f else if (major) 0.89f else 0.92f
            canvas.drawLine(
                g.cx + inner * c, g.cy + inner * s,
                g.cx + g.r * 0.95f * c, g.cy + g.r * 0.95f * s,
                tickPaint,
            )
            deg += 10
        }
        val cards = arrayOf("N", "E", "S", "W")
        cards.forEachIndexed { i, cName ->
            val a = Math.toRadians((i * 90 - 90).toDouble())
            labelPaint.textSize = g.r * 0.17f
            labelPaint.isFakeBoldText = true
            labelPaint.color = if (i == 0) NORTH_RED else TICK_MAJOR
            canvas.drawText(
                cName,
                g.cx + (g.r * 0.74f) * cos(a).toFloat(),
                g.cy + (g.r * 0.74f) * sin(a).toFloat() + labelPaint.textSize * 0.35f,
                labelPaint,
            )
            labelPaint.isFakeBoldText = false
        }
        canvas.restore()

        // Fixed lubber line at the bowl top (vehicle axis).
        val lubber = Path().apply {
            moveTo(g.cx, g.cy - g.r * 0.84f)
            lineTo(g.cx - g.r * 0.055f, g.cy - g.r * 0.95f)
            lineTo(g.cx + g.r * 0.055f, g.cy - g.r * 0.95f)
            close()
        }
        fillPaint.color = LCD_UNIT
        canvas.drawPath(lubber, fillPaint)

        // ── Inner G-plot (car frame, un-rotated) ── kept clear of the
        // cardinal-letter band (0.74·r) and the heading/G readouts.
        val plotR = g.r * 0.42f
        for (ring in 1..2) {
            val gv = GM_MAX_G * ring / 2f
            ringPaint.color = if (ring == 2) TICK_MAJOR else TICK_MINOR
            ringPaint.strokeWidth = g.side * if (ring == 2) 0.010f else 0.005f
            canvas.drawCircle(g.cx, g.cy, plotR * gmLogFrac(gv), ringPaint)
        }
        tickPaint.color = TICK_MINOR
        tickPaint.strokeWidth = g.side * 0.005f
        canvas.drawLine(g.cx - plotR, g.cy, g.cx + plotR, g.cy, tickPaint)
        canvas.drawLine(g.cx, g.cy - plotR, g.cx, g.cy + plotR, tickPaint)

        val n = trail.size
        trail.forEachIndexed { i, s ->
            val frac = (i + 1f) / n
            val f = sqrt(frac)
            val m = s.horizontalMagnitudeG
            val rr = plotR * gmLogFrac(m)
            val ux = if (m > 1e-4f) s.lateralG / m else 0f
            val uy = if (m > 1e-4f) s.longitudinalG / m else 0f
            val px = g.cx + ux * rr
            val py = g.cy - uy * rr
            if (i == n - 1) {
                fillPaint.color = Color.WHITE
                canvas.drawCircle(px, py, g.r * 0.06f, fillPaint)
                fillPaint.color = ACCENT
                canvas.drawCircle(px, py, g.r * 0.042f, fillPaint)
            } else {
                fillPaint.color = fade(ACCENT, 0.16f + 0.55f * f)
                canvas.drawCircle(px, py, g.r * (0.018f + 0.022f * f), fillPaint)
            }
        }

        // Heading readout above the plot (untilted, always legible),
        // sitting between the plot rim (0.42·r) and the N letter (0.74·r).
        centerPaint.color = LCD_BRIGHT
        centerPaint.textSize = g.r * 0.18f
        centerPaint.isFakeBoldText = true
        canvas.drawText(
            if (hasHeading) "%03d°".format(Locale.ROOT, (headingDeg.toInt() % 360 + 360) % 360)
            else "———°",
            g.cx, g.cy - g.r * 0.50f, centerPaint,
        )
        // Live G magnitude below the plot, clear of the S letter.
        val mag = trail.lastOrNull()?.horizontalMagnitudeG ?: 0f
        centerPaint.color = if (mag > GM_MAX_G) NORTH_RED else LCD_UNIT
        centerPaint.textSize = g.r * 0.16f
        canvas.drawText("%.2f G".format(Locale.ROOT, mag), g.cx, g.cy + g.r * 0.58f, centerPaint)
        centerPaint.isFakeBoldText = false
    }

    // ── Energy meter (id.dash power dial) ──────────────────────────

    /**
     * Energy meter. The kW power needle + readout stay the headline
     * (fed by OBD2 when connected). Below it, a battery block: state of
     * charge and range remaining, plus — while navigating — the
     * estimated range and SOC *on arrival* (range minus the remaining
     * drive, SOC scaled by the same ratio). Any value that's unknown
     * (no Car API / OBD2 source) shows a dash. The lower-centre of the
     * dial is the gauge's open gap (the 240° sweep leaves the bottom
     * clear), so the stacked readouts never collide with the scale.
     */
    fun drawEnergyDial(
        canvas: Canvas,
        cell: RectF,
        kw: Double?,
        socPercent: Float?,
        rangeKm: Float?,
        arrivalRangeKm: Float?,
        arrivalSocPercent: Float?,
    ) {
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

        // Headline kW readout (the dial's primary value). Sign + fixed
        // 3-digit width so it stays put across −100…240 kW.
        centerPaint.color = if (kw != null) LCD_BRIGHT else ACCURACY_GREY
        centerPaint.textSize = g.r * 0.30f
        centerPaint.isFakeBoldText = true
        canvas.drawText(
            kw?.let { "%+04d".format(Locale.ROOT, it.toInt().coerceIn(-999, 999)) } ?: "———",
            g.cx,
            g.cy + g.r * 0.44f,
            centerPaint,
        )
        centerPaint.isFakeBoldText = false

        // Battery block, in the open lower gap of the dial.
        // Line 1: state of charge (coloured by level) · range remaining.
        val socTxt = socPercent?.let { "${it.roundToInt()}%" } ?: "–%"
        val rangeTxt = rangeKm?.let { "${it.roundToInt()} km" } ?: "– km"
        drawTwoTone(
            canvas, g.cx, g.cy + g.r * 0.66f, g.r * 0.155f,
            left = socTxt,
            leftColor = socPercent?.let { socColor(it) } ?: ACCURACY_GREY,
            right = rangeTxt,
            rightColor = if (rangeKm != null) LCD_UNIT else ACCURACY_GREY,
        )
        // Line 2 (only while navigating): estimated arrival range · SOC.
        if (arrivalRangeKm != null || arrivalSocPercent != null) {
            val aRange = arrivalRangeKm?.let { "${it.roundToInt()} km" } ?: "– km"
            val aSoc = arrivalSocPercent?.let { "${it.roundToInt()}%" } ?: "–%"
            // Won't-make-it: negative arrival range or near-empty SOC.
            val short = (arrivalRangeKm != null && arrivalRangeKm < 0f) ||
                (arrivalSocPercent != null && arrivalSocPercent < 5f)
            val arrColor = if (short) NORTH_RED else LCD_UNIT
            drawTwoTone(
                canvas, g.cx, g.cy + g.r * 0.83f, g.r * 0.125f,
                left = "→ $aRange",
                leftColor = arrColor,
                right = aSoc,
                rightColor = arrColor,
            )
        }
    }

    /** Two-colour, single line, centred about [cx] at [baselineY]. */
    private fun drawTwoTone(
        canvas: Canvas,
        cx: Float,
        baselineY: Float,
        textSize: Float,
        left: String,
        leftColor: Int,
        right: String,
        rightColor: Int,
    ) {
        centerPaint.textSize = textSize
        centerPaint.isFakeBoldText = true
        centerPaint.textAlign = Paint.Align.LEFT
        val wl = centerPaint.measureText(left)
        val gap = centerPaint.measureText("  ")
        val wr = centerPaint.measureText(right)
        var x = cx - (wl + gap + wr) / 2f
        centerPaint.color = leftColor
        canvas.drawText(left, x, baselineY, centerPaint)
        x += wl + gap
        centerPaint.color = rightColor
        canvas.drawText(right, x, baselineY, centerPaint)
        centerPaint.textAlign = Paint.Align.CENTER
        centerPaint.isFakeBoldText = false
    }

    /** SOC colour ramp: green healthy, amber low, red critical. */
    private fun socColor(pct: Float): Int = when {
        pct >= 50f -> ECO_GREEN
        pct >= 20f -> SOC_AMBER
        else -> NORTH_RED
    }

    private fun powerFraction(kw: Double): Float =
        ((kw.coerceIn(KW_MIN, KW_MAX) - KW_MIN) / (KW_MAX - KW_MIN)).toFloat()

    /** Log radius fraction for the G-meter: small forces exaggerated,
     *  large ones compressed. f(0)=0, f([GM_MAX_G])=1. */
    private fun gmLogFrac(magG: Float): Float {
        val x = (magG / GM_MAX_G).coerceIn(0f, 1f)
        return ln(1f + GM_LOG_K * x) / ln(1f + GM_LOG_K)
    }

    /** Same RGB, replaced alpha (0..1). */
    private fun fade(color: Int, alpha: Float): Int =
        ((alpha.coerceIn(0f, 1f) * 255f).toInt() shl 24) or (color and 0x00FFFFFF)

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
        // A couple of pixels of seam between housings — the dials
        // should otherwise own their full cell.
        val gap = 2f
        val box = RectF(cell.left + gap, cell.top + gap, cell.right - gap, cell.bottom - gap)
        val shortest = min(box.width(), box.height())
        fillPaint.color = HOUSING
        canvas.drawRoundRect(box, shortest * 0.10f, shortest * 0.10f, fillPaint)
        return DialGeometry(
            cx = box.centerX(),
            cy = box.centerY(),
            r = shortest / 2f * 0.98f,
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
        val bezelInset = g.side * 0.012f
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
    // Monospace so every digit occupies the same width — the readouts
    // stay rock-steady as values change (paired with zero-padding).
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.MONOSPACE
    }

    private companion object {
        // RetroDial geometry — shared with the phone gauge + id.dash.
        const val START_DEG = 150f
        const val SWEEP_DEG = 240f

        const val SPEED_MAX = 180
        const val SPEED_PIVOT = 100.0
        val SPEED_ACCENTS = setOf(30, 50, 70, 90, 120)
        /** Small tolerance (km/h) before flagging over-limit, to absorb
         *  GNSS speed jitter. */
        const val SPEED_OVER_TOL = 1.0

        const val KW_MIN = -100.0
        const val KW_MAX = 240.0

        // G-meter: ±1 g full-scale (road cars rarely exceed it), log
        // exaggeration matched to the phone dashboard card.
        const val GM_MAX_G = 1.0f
        const val GM_LOG_K = 12f

        const val COMPASS_TILT_DEG = 16f

        // Palette — RetroDial / id.dash hexes.
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
        /** Posted-limit marker dot (amber until the limit is exceeded). */
        const val LIMIT_DOT = 0xFFFFC107.toInt()
        /** EU speed-limit sign ring. */
        const val SIGN_RED = 0xFFD32F2F.toInt()
        const val REGEN_BLUE = 0xFF35B0E6.toInt()
        const val ECO_GREEN = 0xFF35B047.toInt()
        const val SOC_AMBER = 0xFFF9A825.toInt()
    }
}
