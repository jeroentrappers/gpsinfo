package be.appmire.gpsinfo.car

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Everything the driver's instrument cluster shows, drawn straight onto the
 * Android Auto video surface as an overlay on the full-bleed map.
 *
 * Two layouts, picked by the host surface's aspect ratio (see [CarMapRenderer]):
 *
 *  - **Cockpit** ([drawCockpit]) — for wide/landscape surfaces. The gauges are
 *    HUD overlays hugging the screen sides as tall curved scales that fade into
 *    the map through a black→transparent scrim. Speed on the left, power on the
 *    right; the current value is a slim needle tip riding the scale (no pivot),
 *    with a glowing level fill. The merged compass/G-meter floats lower-right,
 *    and the bottom-left is held clear for the host's ETA card.
 *
 *  - **Integrated** ([drawIntegrated]) — for narrow/portrait surfaces. The
 *    single combined gauge: one arc (speed left, power right), the merged
 *    compass/G-meter in the centre, readouts and units on the dial face.
 *
 * Both share one non-linear, equal-spaced "stop list" scale: fine resolution
 * where you actually drive, coarse up top (speed 0·5·10·20·30·50·70·90·120·…;
 * power a fine ±band around zero, coarse tails). Colours, fonts and geometry are
 * the values locked in the design mockup.
 */
class CarInstruments {

    // ── Public entry points ─────────────────────────────────────────

    /** Cockpit edge-HUD over the full-bleed map. Everything is laid out inside
     *  [area] — the host's safe rectangle in surface pixels — so when the host
     *  claims a region for its nav rail (turn card + ETA, which it stacks on the
     *  left during turn-by-turn) the whole cluster shifts to the space that's
     *  left and never sits under the host chrome. */
    fun drawCockpit(canvas: Canvas, w: Int, h: Int, d: ClusterData, area: RectF) {
        val W = w.toFloat()
        val H = h.toFloat()
        // Gauges hug the physical left/right edges. The host stacks its turn card
        // and destination card in a left rail during turn-by-turn — detected by a
        // large left inset on the safe area — so the SPEED scale tucks into the
        // vertical gap between those two cards then, and uses the full safe height
        // otherwise. The power scale (right, no cards there) always uses the full
        // safe height.
        val edge = W * CK_EDGE
        val aL = edge
        val aR = W - edge
        val aW = aR - aL
        // Vertical band = the host's safe area top/bottom. A top inset is a top
        // (turn) card, a bottom inset is a bottom (destination) card — so the
        // scales tuck between exactly what THIS host draws, whether that's both
        // cards or just the bottom one. (On hosts that stack both cards in a left
        // rail instead, the rail shows as a left inset, handled below.)
        val aT = area.top.coerceIn(0f, H * 0.42f)
        val aB = area.bottom.coerceIn(H * 0.58f, H)

        // Scrims blend the edge gauges into the map.
        val scrimW = aW * CK_SCRIM
        scrim.shader = LinearGradient(aL, 0f, aL + scrimW, 0f, SCRIM_ON, SCRIM_OFF, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, aL + scrimW, H, scrim)
        scrim.shader = LinearGradient(aR, 0f, aR - scrimW, 0f, SCRIM_ON, SCRIM_OFF, Shader.TileMode.CLAMP)
        canvas.drawRect(aR - scrimW, 0f, W, H, scrim)
        scrim.shader = null

        val gr = H * 0.42f * SHARED_R
        val f = Fonts(gr)
        val sweep = CK_CURVE
        val sinSweep = sin(Math.toRadians(sweep.toDouble())).toFloat()
        // When the host stacks its cards in a LEFT rail (large left inset), tuck
        // the speed scale into the rail's vertical middle — the gap between the
        // turn card (top) and the destination card (bottom). Hosts that lay the
        // cards out as top/bottom bands already express that gap through the
        // aT/aB insets, so there the left band is simply the full safe height.
        val leftRail = area.left > W * 0.12f
        val railH = aB - aT
        val lTop = if (leftRail) aT + railH * CK_RAIL_GAP else aT
        val lBot = if (leftRail) aB - railH * CK_RAIL_GAP else aB
        val yL = (lTop + lBot) / 2f
        val rLL = ((lBot - lTop) / 2f).coerceAtLeast(H * 0.07f) / sinSweep
        val yMid = (aT + aB) / 2f
        val rL = ((aB - aT) / 2f).coerceAtLeast(H * 0.10f) / sinSweep
        val cxL = aL + rLL          // arc's near edge hugs the left screen edge
        val cxR = aR - rL           // …and the right screen edge
        val spA = { t: Float -> 180f - sweep + 2f * sweep * t }   // left: bottom → top
        val pwA = { t: Float -> sweep - 2f * sweep * t }          // right: bottom → top
        val tickLen = gr * 0.16f
        val tipLen = (gr * 0.14f).coerceAtMost((edge * 0.9f).coerceAtLeast(8f))
        val fillW = gr * 0.05f
        val lx = aL + aW * CK_NUMINSET
        val rx = aR - aW * CK_NUMINSET

        // Clock + temp top-centre (between the host's top-left turn card and the
        // top-right action strip).
        clockTemp(canvas, (aL + aR) / 2f, aT + f.clock * 0.2f, f, d)

        val sStops = speedStops()
        val pStops = powerStops()

        // ── LEFT — speed scale + level fill + limit dot + value tip ──
        scaleTicks(canvas, cxL, yL, rLL, tickLen, rLL - gr * 0.36f, gr, f.tick, sStops, SPEED_KNEES, false, spA)
        fillArc(canvas, cxL, yL, rLL, spA(0f), spA(speedFrac(d.kmh)), LCD, fillW)
        if (d.speedLimitKmh != null) {
            limitDot(canvas, cxL, yL, rLL - gr * CK_LIMINSET, spA(speedFrac(d.speedLimitKmh.toDouble())), gr * CK_LIMDOT)
        }
        outsideTip(canvas, cxL, yL, rLL, spA(speedFrac(d.kmh)), LCD, tipLen)
        // Readout (number · km/h · ±acc/odo), centred at the inner column.
        mono.textAlign = Paint.Align.CENTER
        mono.color = TEXT; mono.isFakeBoldText = true; mono.textSize = f.digit
        canvas.drawText(if (d.hasSpeed) "${d.kmh.toInt()}" else "––", lx, yL, mono)
        mono.color = MUTED; mono.textSize = f.unit
        canvas.drawText("km/h", lx, yL + f.unit * 1.6f, mono)
        mono.color = LCD_DIM; mono.textSize = f.sub; mono.isFakeBoldText = false
        canvas.drawText(subLine(d), lx, yL + f.unit * 1.6f + f.sub * 1.6f, mono)

        // ── RIGHT — power scale + level fill + peak + value tip ──
        scaleTicks(canvas, cxR, yMid, rL, tickLen, rL - gr * 0.36f, gr, f.tick, pStops, POWER_KNEES, !d.obd, pwA)
        if (d.obd) {
            val kw = d.kw ?: 0.0
            fillArc(canvas, cxR, yMid, rL, pwA(powerFrac(0.0)), pwA(powerFrac(kw)), if (kw >= 0) ACCENT else REGEN, fillW)
            d.peakKw?.let { peakMark(canvas, cxR, yMid, rL, pwA(powerFrac(it)), gr) }
            outsideTip(canvas, cxR, yMid, rL, pwA(powerFrac(kw)), if (kw >= 0) ACCENT else REGEN, tipLen)
            powerReadout(canvas, rx, yMid, f, d, inlineUnit = true)
        }

        // ── Merged compass + G-meter, anchored a fixed margin above the safe
        // bottom (no longer sunk to a fixed Y). ──
        val dialR = H * CK_DIALSCALE
        val dialCy = (aB - dialR - (aB - aT) * 0.04f).coerceAtLeast(yMid)
        combinedCentre(canvas, aL + aW * CK_DIALX, dialCy, dialR, d)
    }

    /** Integrated single gauge filling [cell] (narrow/portrait surfaces). */
    fun drawIntegrated(canvas: Canvas, cell: RectF, d: ClusterData) {
        val W = cell.width()
        val H = cell.height()
        val cx = cell.left + W / 2f
        val cy = cell.top + H * CY
        val R = min(W * 0.30f, H * 0.44f) * SHARED_R
        val rc = R * 0.46f * SHARED_RC
        val f = Fonts(R)

        fill.color = HOUSING
        val cr = min(W, H) * 0.05f
        canvas.drawRoundRect(RectF(cell.left + 4f, cell.top + 4f, cell.right - 4f, cell.bottom - 4f), cr, cr, fill)

        val span = 180f - IN_GAP_TOP - IN_GAP_BOTTOM
        val spA = { t: Float -> 90f + IN_GAP_BOTTOM + t * span }
        val pwA = { t: Float -> 90f - IN_GAP_BOTTOM - t * span }
        val sStops = speedStops()
        val pStops = powerStops()

        scaleTicks(canvas, cx, cy, R * 0.95f, R * 0.09f, R * 0.72f, R, f.tick, sStops, SPEED_KNEES, false, spA)
        powerBands(canvas, cx, cy, R * 0.95f, pwA, !d.obd)
        scaleTicks(canvas, cx, cy, R * 0.95f, R * 0.09f, R * 0.72f, R, f.tick, pStops, POWER_KNEES, !d.obd, pwA)

        needle(canvas, cx, cy, R * 0.93f, spA(speedFrac(d.kmh)))
        if (d.obd) {
            val kw = d.kw ?: 0.0
            d.peakKw?.let { peakNeedle(canvas, cx, cy, R * 0.93f, pwA(powerFrac(it))) }
            needle(canvas, cx, cy, R * 0.93f, pwA(powerFrac(kw)))
        }

        clockTemp(canvas, cx + IN_TOP_X * R, cy + IN_TOP_Y * R, f, d)
        combinedCentre(canvas, cx, cy, rc, d)

        // Speed readout (number · ±acc/odo) + EU limit roundel; units on the face.
        mono.textAlign = Paint.Align.CENTER
        val sx = cx + IN_SP_X * R
        val sy = cy + IN_SP_Y * R
        mono.color = TEXT; mono.isFakeBoldText = true; mono.textSize = f.digit
        canvas.drawText(if (d.hasSpeed) "${d.kmh.toInt()}" else "––", sx, sy, mono)
        mono.color = LCD_DIM; mono.isFakeBoldText = false; mono.textSize = f.sub
        canvas.drawText(subLine(d), sx, sy + f.sub * 1.4f, mono)
        if (d.speedLimitKmh != null) limitSign(canvas, sx, sy - f.digit * 1.5f, f.digit * 0.62f, d.speedLimitKmh)

        if (d.obd) powerReadout(canvas, cx + IN_PW_X * R, cy + IN_PW_Y * R, f, d, inlineUnit = false)

        unit(canvas, cx + IN_SPU_X * R, cy + IN_SPU_Y * R, "km/h", f)
        if (d.obd) unit(canvas, cx + IN_PWU_X * R, cy + IN_PWU_Y * R, "kW", f)
    }

    // ── Piecewise, equal-spaced "stop list" scales ──────────────────

    private class Band(val to: Double, val step: Double)

    private fun speedStops(): DoubleArray {
        val s = arrayListOf(0.0)
        var cur = 0.0
        for (b in SPEED_BANDS) {
            val top = min(b.to, SPEED_MAX)
            var v = cur + b.step
            while (v <= top + 1e-6) { s.add(v); v += b.step }
            cur = top
            if (cur >= SPEED_MAX - 1e-6) break
        }
        if (s.last() < SPEED_MAX - 1e-6) s.add(SPEED_MAX)
        return s.toDoubleArray()
    }

    private fun powerStops(): DoubleArray {
        val s = ArrayList<Double>()
        var v = -P_BAND
        while (v <= P_BAND + 1e-6) { s.add(v); v += P_FINE }
        v = -P_BAND - P_COARSE
        while (v >= KW_MIN - 1e-6) { s.add(0, v); v -= P_COARSE }
        v = P_BAND + P_COARSE
        while (v <= POWER_MAX + 1e-6) { s.add(v); v += P_COARSE }
        if (s.first() > KW_MIN + 1e-6) s.add(0, KW_MIN)
        if (s.last() < POWER_MAX - 1e-6) s.add(POWER_MAX)
        return s.toDoubleArray()
    }

    /** Map a value to its arc fraction by interpolating between the two stops it
     *  falls between — the stops are placed at equal angular spacing. */
    private fun fracFromStops(value: Double, stops: DoubleArray): Float {
        val n = stops.size
        val v = value.coerceIn(stops[0], stops[n - 1])
        for (k in 0 until n - 1) {
            if (v <= stops[k + 1] + 1e-9) {
                return ((k + (v - stops[k]) / (stops[k + 1] - stops[k])) / (n - 1)).toFloat()
            }
        }
        return 1f
    }

    private fun speedFrac(kmh: Double) = fracFromStops(kmh, speedStops())
    private fun powerFrac(kw: Double) = fracFromStops(kw, powerStops())

    // ── Drawing primitives ──────────────────────────────────────────

    private fun ray(c: Canvas, cx: Float, cy: Float, deg: Float, r0: Float, r1: Float, p: Paint) {
        val a = Math.toRadians(deg.toDouble())
        val cs = cos(a).toFloat()
        val sn = sin(a).toFloat()
        c.drawLine(cx + r0 * cs, cy + r0 * sn, cx + r1 * cs, cy + r1 * sn, p)
    }

    /** Equal-spaced ticks + labels along an arc. [angOf] maps fraction→degrees;
     *  ticks run inward from [outerR] by [tickLen], labels sit at [labelR]. */
    private fun scaleTicks(
        c: Canvas, cx: Float, cy: Float, outerR: Float, tickLen: Float, labelR: Float,
        strokeRef: Float, fTick: Float, stops: DoubleArray, knees: DoubleArray,
        dimmed: Boolean, angOf: (Float) -> Float,
    ) {
        val n = stops.size
        labelPaint.isFakeBoldText = true
        labelPaint.textSize = fTick
        for (i in 0 until n) {
            val v = stops[i]
            val deg = angOf(i.toFloat() / (n - 1))
            val a = Math.toRadians(deg.toDouble())
            val cs = cos(a).toFloat()
            val sn = sin(a).toFloat()
            val knee = knees.any { abs(v - it) < 1e-6 }
            val col = when { dimmed -> fade(MUTED, 0.4f); knee -> ACCENT; else -> TEXT }
            tickPaint.color = col
            tickPaint.strokeWidth = strokeRef * if (knee) 0.02f else 0.011f
            c.drawLine(
                cx + (outerR - tickLen) * cs, cy + (outerR - tickLen) * sn,
                cx + outerR * cs, cy + outerR * sn, tickPaint,
            )
            labelPaint.color = col
            c.drawText(abs(v.roundToInt()).toString(), cx + labelR * cs, cy + labelR * sn + fTick * 0.36f, labelPaint)
        }
        labelPaint.isFakeBoldText = false
    }

    /** Coloured regen/eco zones (integrated only). */
    private fun powerBands(c: Canvas, cx: Float, cy: Float, r: Float, pwA: (Float) -> Float, dimmed: Boolean) {
        zone.strokeWidth = (r * 0.04f).coerceAtLeast(3f)
        fun band(f0: Float, f1: Float, color: Int) {
            val a0 = pwA(f0); val a1 = pwA(f1)
            zone.color = if (dimmed) fade(color, 0.3f) else color
            c.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), min(a0, a1), abs(a1 - a0), false, zone)
        }
        band(powerFrac(KW_MIN), powerFrac(0.0), REGEN)
        band(powerFrac(0.0), powerFrac(min(60.0, POWER_MAX)), ECO)
    }

    /** Glowing level fill running along the scale (cockpit). */
    private fun fillArc(c: Canvas, cx: Float, cy: Float, r: Float, a0: Float, a1: Float, color: Int, w: Float) {
        arc.color = color
        arc.strokeWidth = w
        c.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), min(a0, a1), abs(a1 - a0), false, arc)
    }

    /** Slim needle tip poking just outside the scale (cockpit value indicator). */
    private fun outsideTip(c: Canvas, cx: Float, cy: Float, r: Float, deg: Float, color: Int, len: Float) {
        val a = Math.toRadians(deg.toDouble())
        val cs = cos(a).toFloat(); val sn = sin(a).toFloat()
        val tx = -sn; val ty = cs; val hw = len * 0.16f
        val p = Path().apply {
            moveTo(cx + (r + len) * cs, cy + (r + len) * sn)
            lineTo(cx + r * cs + tx * hw, cy + r * sn + ty * hw)
            lineTo(cx + r * cs - tx * hw, cy + r * sn - ty * hw)
            close()
        }
        fill.color = color
        c.drawPath(p, fill)
    }

    /** Subtle yellow speed-limit dot riding the scale (cockpit). */
    private fun limitDot(c: Canvas, cx: Float, cy: Float, r: Float, deg: Float, size: Float) {
        val a = Math.toRadians(deg.toDouble())
        fill.color = AMBER
        fill.alpha = 235
        c.drawCircle(cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat(), size, fill)
        fill.alpha = 255
    }

    /** Hollow amber 30 s power-peak telltale on the scale (cockpit). */
    private fun peakMark(c: Canvas, cx: Float, cy: Float, r: Float, deg: Float, strokeRef: Float) {
        val a = Math.toRadians(deg.toDouble())
        ring.color = AMBER
        ring.strokeWidth = strokeRef * 0.03f
        c.drawCircle(cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat(), strokeRef * 0.06f, ring)
    }

    /** Tapered pivoting needle (integrated value indicator). */
    private fun needle(c: Canvas, cx: Float, cy: Float, len: Float, deg: Float) {
        c.save()
        c.rotate(deg, cx, cy)
        val p = Path().apply {
            moveTo(cx, cy - len * 0.018f)
            lineTo(cx + len * 0.985f, cy)
            lineTo(cx, cy + len * 0.018f)
            lineTo(cx - len * 0.16f, cy)
            close()
        }
        fill.color = NEEDLE
        c.drawPath(p, fill)
        c.restore()
    }

    /** Slim amber peak needle (integrated). */
    private fun peakNeedle(c: Canvas, cx: Float, cy: Float, len: Float, deg: Float) {
        c.save()
        c.rotate(deg, cx, cy)
        ring.color = AMBER
        ring.strokeWidth = (len * 0.011f).coerceAtLeast(2f)
        ring.strokeCap = Paint.Cap.ROUND
        c.drawLine(cx + len * 0.34f, cy, cx + len * 0.93f, cy, ring)
        ring.strokeCap = Paint.Cap.BUTT
        val p = Path().apply {
            moveTo(cx + len * 0.985f, cy)
            lineTo(cx + len * 0.88f, cy - len * 0.035f)
            lineTo(cx + len * 0.88f, cy + len * 0.035f)
            close()
        }
        fill.color = AMBER
        c.drawPath(p, fill)
        c.restore()
    }

    /** Merged compass rose (course-rotated) + inner G-meter, with heading and
     *  live-G readouts. The dynamics dial — world frame outside, car frame in. */
    private fun combinedCentre(c: Canvas, cx: Float, cy: Float, r: Float, d: ClusterData) {
        fill.color = HOUSING
        c.drawCircle(cx, cy, r, fill)
        ring.color = fade(MUTED, 0.5f)
        ring.strokeWidth = r * 0.02f
        c.drawCircle(cx, cy, r * 0.99f, ring)

        c.save()
        c.rotate(if (d.hasHeading) -d.headingDeg else 0f, cx, cy)
        var deg = 0
        while (deg < 360) {
            val major = deg % 30 == 0
            val card = deg % 90 == 0
            tickPaint.color = if (deg == 0) NORTH_RED else if (card || major) TEXT else MUTED
            tickPaint.strokeWidth = r * if (major) 0.028f else 0.013f
            val ri = r * if (card) 0.74f else if (major) 0.80f else 0.86f
            ray(c, cx, cy, (deg - 90).toFloat(), ri, r * 0.92f, tickPaint)
            deg += 10
        }
        sans.isFakeBoldText = true
        sans.textSize = r * 0.17f
        arrayOf("N", "E", "S", "W").forEachIndexed { i, name ->
            val a = Math.toRadians((i * 90 - 90).toDouble())
            sans.color = if (i == 0) NORTH_RED else TEXT
            c.drawText(name, cx + r * 0.6f * cos(a).toFloat(), cy + r * 0.6f * sin(a).toFloat() + r * 0.17f * 0.35f, sans)
        }
        sans.isFakeBoldText = false
        c.restore()

        // Fixed lubber line at the top.
        fill.color = LCD_DIM
        c.drawPath(Path().apply {
            moveTo(cx, cy - r * 0.86f); lineTo(cx - r * 0.05f, cy - r * 0.97f); lineTo(cx + r * 0.05f, cy - r * 0.97f); close()
        }, fill)

        // Inner G-plot (car frame, un-rotated).
        val pr = r * 0.4f
        ring.color = fade(MUTED, 0.6f)
        ring.strokeWidth = r * 0.01f
        c.drawCircle(cx, cy, pr * gFrac(0.5f), ring)
        c.drawCircle(cx, cy, pr * gFrac(1f), ring)
        tickPaint.color = fade(MUTED, 0.6f)
        tickPaint.strokeWidth = r * 0.01f
        ray(c, cx, cy, 0f, -pr, pr, tickPaint)
        ray(c, cx, cy, 90f, -pr, pr, tickPaint)
        val m = sqrt(d.latG * d.latG + d.lonG * d.lonG)
        val uf = pr * gFrac(min(1f, m))
        val px = cx + (if (m > 1e-3f) d.latG / m else 0f) * uf
        val py = cy - (if (m > 1e-3f) d.lonG / m else 0f) * uf
        fill.color = Color.WHITE
        c.drawCircle(px, py, r * 0.07f, fill)
        fill.color = ACCENT
        c.drawCircle(px, py, r * 0.05f, fill)

        mono.textAlign = Paint.Align.CENTER
        mono.color = LCD
        mono.isFakeBoldText = true
        mono.textSize = r * 0.19f
        val hdg = if (d.hasHeading) "%03d°".format(Locale.ROOT, (d.headingDeg.toInt() % 360 + 360) % 360) else "———°"
        c.drawText(hdg, cx, cy - r * 0.5f + r * 0.19f * 0.35f, mono)
        mono.color = if (m > GM_MAX_G) NORTH_RED else LCD_DIM
        mono.textSize = r * 0.15f
        c.drawText("%.2f G".format(Locale.ROOT, m), cx, cy + r * 0.6f + r * 0.15f * 0.35f, mono)
        mono.isFakeBoldText = false
    }

    /** EU posted-limit roundel (integrated). */
    private fun limitSign(c: Canvas, cx: Float, cy: Float, r: Float, limitKmh: Int) {
        fill.color = Color.WHITE
        c.drawCircle(cx, cy, r, fill)
        ring.color = SIGN_RED
        ring.strokeWidth = r * 0.22f
        c.drawCircle(cx, cy, r * 0.84f, ring)
        val s = limitKmh.toString()
        sans.color = Color.BLACK
        sans.isFakeBoldText = true
        var fs = r * 1.0f
        sans.textSize = fs
        while (sans.measureText(s) > r * 1.4f && fs > 4f) { fs *= 0.9f; sans.textSize = fs }
        c.drawText(s, cx, cy + fs * 0.36f, sans)
        sans.isFakeBoldText = false
    }

    private fun powerReadout(c: Canvas, x: Float, y: Float, f: Fonts, d: ClusterData, inlineUnit: Boolean) {
        val kw = d.kw ?: return
        mono.textAlign = Paint.Align.CENTER
        mono.color = TEXT; mono.isFakeBoldText = true; mono.textSize = f.digit * 0.9f
        c.drawText("${if (kw > 0) "+" else ""}${kw.toInt()}", x, y, mono)
        mono.isFakeBoldText = false
        var row = if (inlineUnit) {
            mono.color = MUTED; mono.textSize = f.unit
            c.drawText("kW", x, y + f.unit * 1.6f, mono)
            y + f.unit * 1.6f + f.sub * 1.6f
        } else {
            y + f.sub * 1.4f
        }
        mono.textSize = f.sub
        mono.color = if (d.consKwh100 != null && d.consKwh100 < 0) ECO else LCD_DIM
        c.drawText(
            d.consKwh100?.let { "%.1f kWh/100km".format(Locale.ROOT, it) } ?: "—  kWh/100km",
            x, row, mono,
        )
        row += f.sub * (if (inlineUnit) 1.5f else 1.4f)
        mono.color = d.socPct?.let { socColor(it) } ?: ACCURACY_GREY
        c.drawText(d.socPct?.let { "${it.roundToInt()}%" } ?: "–%", x - f.sub * 2.6f, row, mono)
        mono.color = if (d.rangeKm != null) LCD_DIM else ACCURACY_GREY
        c.drawText(d.rangeKm?.let { "${it.roundToInt()} km" } ?: "– km", x + f.sub * 2.8f, row, mono)
    }

    private fun unit(c: Canvas, x: Float, y: Float, t: String, f: Fonts) {
        mono.textAlign = Paint.Align.CENTER
        mono.color = MUTED
        mono.textSize = f.unit
        c.drawText(t, x, y + f.unit * 0.35f, mono)
    }

    private fun clockTemp(c: Canvas, x: Float, y: Float, f: Fonts, d: ClusterData) {
        mono.textAlign = Paint.Align.CENTER
        mono.color = TEXT; mono.isFakeBoldText = true; mono.textSize = f.clock
        c.drawText(d.clock, x, y + f.clock * 0.35f, mono)
        mono.isFakeBoldText = false; mono.color = LCD_DIM; mono.textSize = f.sub
        c.drawText(d.ambientTempC?.let { "%.0f°C".format(Locale.ROOT, it) } ?: "––°C", x, y + f.clock, mono)
    }

    private fun subLine(d: ClusterData): String =
        "±${d.speedAccKmh?.roundToInt() ?: 0}   %.1f km".format(Locale.ROOT, d.odometerKm)

    private fun gFrac(x: Float): Float {
        val xx = x.coerceIn(0f, 1f)
        return (ln(1.0 + GM_LOG_K * xx) / ln(1.0 + GM_LOG_K)).toFloat()
    }

    private fun socColor(pct: Float) = when { pct >= 50f -> ECO; pct >= 20f -> AMBER; else -> NORTH_RED }

    private fun fade(color: Int, alpha: Float): Int =
        ((alpha.coerceIn(0f, 1f) * 255f).toInt() shl 24) or (color and 0x00FFFFFF)

    /** Font sizes for a dial of base radius [r] (mockup's % of radius). */
    private class Fonts(r: Float) {
        val tick = r * F_TICK
        val digit = r * F_DIGIT
        val sub = r * F_SUB
        val clock = r * F_CLOCK
        val unit = r * F_UNIT
    }

    // ── Paints (single-threaded reuse) ──────────────────────────────

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.BUTT }
    private val zone = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f }
    private val scrim = Paint()
    private val mono = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; typeface = Typeface.MONOSPACE
    }
    private val sans = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; typeface = Typeface.MONOSPACE
    }

    private companion object {
        // Scale shape (shared).
        const val SPEED_MAX = 180.0
        const val POWER_MAX = 280.0
        const val KW_MIN = -100.0
        const val P_BAND = 20.0
        const val P_FINE = 10.0
        const val P_COARSE = 60.0
        val SPEED_BANDS = listOf(Band(10.0, 5.0), Band(30.0, 10.0), Band(90.0, 20.0), Band(Double.POSITIVE_INFINITY, 30.0))
        val SPEED_KNEES = doubleArrayOf(10.0, 30.0, 90.0)
        val POWER_KNEES = doubleArrayOf(-P_BAND, P_BAND)

        // Shared geometry / fonts (mockup % values).
        const val SHARED_R = 0.71f
        const val SHARED_RC = 1.33f
        const val CY = 0.50f
        const val F_TICK = 0.11f
        const val F_DIGIT = 0.28f
        const val F_SUB = 0.10f
        const val F_CLOCK = 0.14f
        const val F_UNIT = 0.09f

        // Cockpit.
        const val CK_CURVE = 15f
        const val CK_EDGE = 0.04f
        const val CK_SCRIM = 0.33f
        /** On a left-rail host, the speed scale tucks into the rail's middle: this
         *  is the fraction of the rail height reserved at top (turn card) and at
         *  bottom (destination card). */
        const val CK_RAIL_GAP = 0.30f
        const val CK_NUMINSET = 0.14f
        const val CK_DIALX = 0.80f
        const val CK_DIALSCALE = 0.12f
        const val CK_LIMDOT = 0.06f
        const val CK_LIMINSET = 0.0f

        // Integrated.
        const val IN_GAP_TOP = 23f
        const val IN_GAP_BOTTOM = 53f
        const val IN_SP_X = -0.32f
        const val IN_SP_Y = 0.60f
        const val IN_PW_X = 0.31f
        const val IN_PW_Y = 0.52f
        const val IN_TOP_X = 0f
        const val IN_TOP_Y = -0.86f
        const val IN_SPU_X = -0.50f
        const val IN_SPU_Y = 0f
        const val IN_PWU_X = 0.50f
        const val IN_PWU_Y = 0f

        const val GM_MAX_G = 1.0f
        const val GM_LOG_K = 12.0

        // Palette (mockup dark / HUD).
        const val HOUSING = 0xFF0B0B0B.toInt()
        const val TEXT = 0xFFEDEDED.toInt()
        const val MUTED = 0xFF9A9A9A.toInt()
        const val ACCENT = 0xFFE67635.toInt()
        const val LCD = 0xFF7FE3FF.toInt()
        const val LCD_DIM = 0xFF7FCCFF.toInt()
        const val NORTH_RED = 0xFFEF5350.toInt()
        const val REGEN = 0xFF35B0E6.toInt()
        const val ECO = 0xFF35B047.toInt()
        const val AMBER = 0xFFF9A825.toInt()
        const val SIGN_RED = 0xFFD32F2F.toInt()
        const val NEEDLE = 0xEBFFFFFF.toInt()
        const val ACCURACY_GREY = 0xFF8AA0AA.toInt()
        const val SCRIM_ON = 0xEB000000.toInt()
        const val SCRIM_OFF = 0x00000000
    }
}

/** Snapshot of everything the cluster renders, assembled per frame by
 *  [CarMapRenderer]. Unknown values are null and show a dash. */
data class ClusterData(
    val kmh: Double,
    val hasSpeed: Boolean,
    val speedAccKmh: Float?,
    val kw: Double?,
    val peakKw: Double?,
    val consKwh100: Double?,
    val socPct: Float?,
    val rangeKm: Float?,
    val headingDeg: Float,
    val hasHeading: Boolean,
    val latG: Float,
    val lonG: Float,
    val speedLimitKmh: Int?,
    val odometerKm: Double,
    val ambientTempC: Double?,
    val clock: String,
    val obd: Boolean,
)
