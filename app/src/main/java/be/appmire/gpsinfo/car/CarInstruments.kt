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
    fun drawCockpit(canvas: Canvas, w: Int, h: Int, d: ClusterData, area: RectF, showCompass: Boolean = true) {
        val g = cockpitGeom(w, h, area)
        ckScrims(canvas, g, d)
        ckClock(canvas, g, d)
        ckSpeedGauge(canvas, g, d)
        ckSpeedText(canvas, g, d)
        if (d.obd) {
            ckEnergyGauge(canvas, g, d)
            ckEnergyText(canvas, g, d)
        }
        if (showCompass) ckCompass(canvas, g, d)
    }

    /** Shared cockpit geometry + each piece's natural bounds, computed once so
     *  the pieces (drawn separately, so each can be an independently editable
     *  overlay) all agree. See [drawCockpit] for the assembled result. */
    fun cockpitGeom(w: Int, h: Int, area: RectF): CockpitGeom {
        val W = w.toFloat()
        val H = h.toFloat()
        val edge = W * CK_EDGE
        val aL = edge
        val aR = W - edge
        val aW = aR - aL
        val aT = area.top.coerceIn(0f, H * 0.42f)
        val aB = area.bottom.coerceIn(H * 0.58f, H)
        val scrimW = aW * CK_SCRIM
        val gr = H * 0.42f * SHARED_R
        val f = Fonts(gr)
        val sweep = CK_CURVE
        val sinSweep = sin(Math.toRadians(sweep.toDouble())).toFloat()
        val leftRail = area.left > W * 0.12f
        val railH = aB - aT
        val lTop = if (leftRail) aT + railH * CK_RAIL_GAP else aT
        val lBot = if (leftRail) aB - railH * CK_RAIL_GAP else aB
        val yL = (lTop + lBot) / 2f
        val rLL = ((lBot - lTop) / 2f).coerceAtLeast(H * 0.07f) / sinSweep
        val yMid = (aT + aB) / 2f
        val rL = ((aB - aT) / 2f).coerceAtLeast(H * 0.10f) / sinSweep
        val sL = (rLL / rL).coerceIn(0.5f, 1f)
        val cxL = aL + rLL
        val cxR = aR - rL
        val tickLen = gr * 0.16f
        val tipLen = (gr * 0.14f).coerceAtMost((edge * 0.9f).coerceAtLeast(8f))
        val fillW = gr * 0.05f
        val lx = aL + aW * CK_NUMINSET
        val rx = aR - aW * CK_NUMINSET
        val dialR = H * CK_DIALSCALE
        val dialCx = aL + aW * CK_DIALX
        val dialCy = (aB - dialR - (aB - aT) * 0.04f).coerceAtLeast(yMid)
        val clockX = (aL + aR) / 2f
        val clockY = aT + f.clock * 0.2f
        return CockpitGeom(
            gr = gr, sweep = sweep, cxL = cxL, yL = yL, rLL = rLL, sL = sL,
            cxR = cxR, yMid = yMid, rL = rL, lx = lx, rx = rx,
            tickLen = tickLen, tipLen = tipLen, fillW = fillW,
            aL = aL, aR = aR, aT = aT, aB = aB, w = W, h = H, scrimW = scrimW,
            dialCx = dialCx, dialCy = dialCy, dialR = dialR, clockX = clockX, clockY = clockY,
            bgRect = RectF(0f, aT, W, aB),
            speedRect = RectF(0f, lTop, aL + aW * 0.22f, lBot),
            speedTextRect = RectF(lx - aW * 0.12f, yL - gr * 0.35f, lx + aW * 0.12f, yL + gr * 0.6f),
            energyRect = RectF(aR - aW * 0.22f, aT, W, aB),
            energyTextRect = RectF(rx - aW * 0.12f, yMid - gr * 0.35f, rx + aW * 0.12f, yMid + gr * 0.6f),
            compassRect = RectF(dialCx - dialR, dialCy - dialR, dialCx + dialR, dialCy + dialR),
            clockRect = RectF(clockX - gr * 0.7f, clockY - gr * 0.05f, clockX + gr * 0.7f, clockY + f.clock * 1.3f),
        )
    }

    // ── Cockpit pieces (each an independently positionable overlay) ──

    /** Edge scrims that blend the gauges into the map (the "background").
     *  Multi-stop, eased falloff + dithered paint so the wide gradient reads
     *  smooth instead of banding into steps. */
    fun ckScrims(canvas: Canvas, g: CockpitGeom, d: ClusterData) {
        scrim.shader = LinearGradient(g.aL, 0f, g.aL + g.scrimW, 0f, SCRIM_COLS, SCRIM_POS, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, g.aL + g.scrimW, g.h, scrim)
        if (d.obd) {
            scrim.shader = LinearGradient(g.aR, 0f, g.aR - g.scrimW, 0f, SCRIM_COLS, SCRIM_POS, Shader.TileMode.CLAMP)
            canvas.drawRect(g.aR - g.scrimW, 0f, g.w, g.h, scrim)
        }
        scrim.shader = null
    }

    fun ckClock(canvas: Canvas, g: CockpitGeom, d: ClusterData) =
        clockTemp(canvas, g.clockX, g.clockY, Fonts(g.gr), d)

    /** Left speed scale + level fill + limit dot + value tip. */
    fun ckSpeedGauge(canvas: Canvas, g: CockpitGeom, d: ClusterData) {
        val f = Fonts(g.gr)
        val spA = { t: Float -> 180f - g.sweep + 2f * g.sweep * t }
        scaleTicks(canvas, g.cxL, g.yL, g.rLL, g.tickLen * g.sL, g.rLL - g.gr * 0.36f * g.sL, g.gr * g.sL, f.tick * g.sL, speedStops(), SPEED_KNEES, false, spA)
        fillArc(canvas, g.cxL, g.yL, g.rLL, spA(0f), spA(speedFrac(d.kmh)), LCD, g.fillW * g.sL)
        if (d.speedLimitKmh != null) {
            limitDot(canvas, g.cxL, g.yL, g.rLL - g.gr * CK_LIMINSET * g.sL, spA(speedFrac(d.speedLimitKmh.toDouble())), g.gr * CK_LIMDOT * g.sL)
        }
        outsideTip(canvas, g.cxL, g.yL, g.rLL, spA(speedFrac(d.kmh)), LCD, g.tipLen * g.sL)
    }

    /** Speed read-out (number · km/h · ±acc/odo). */
    fun ckSpeedText(canvas: Canvas, g: CockpitGeom, d: ClusterData) {
        val f = Fonts(g.gr)
        mono.textAlign = Paint.Align.CENTER
        mono.color = TEXT; mono.isFakeBoldText = true; mono.textSize = f.digit
        canvas.drawText(if (d.hasSpeed) "%.1f".format(Locale.ROOT, d.kmh) else "––", g.lx, g.yL, mono)
        mono.color = MUTED; mono.textSize = f.unit
        canvas.drawText("km/h", g.lx, g.yL + f.unit * 1.6f, mono)
        mono.color = LCD_DIM; mono.textSize = f.sub; mono.isFakeBoldText = false
        canvas.drawText(subLine(d), g.lx, g.yL + f.unit * 1.6f + f.sub * 1.6f, mono)
    }

    /** Right power scale + level fill + peak + value tip. */
    fun ckEnergyGauge(canvas: Canvas, g: CockpitGeom, d: ClusterData) {
        val f = Fonts(g.gr)
        val pwA = { t: Float -> g.sweep - 2f * g.sweep * t }
        scaleTicks(canvas, g.cxR, g.yMid, g.rL, g.tickLen, g.rL - g.gr * 0.36f, g.gr, f.tick, powerStops(), POWER_KNEES, false, pwA)
        val kw = d.kw ?: 0.0
        fillArc(canvas, g.cxR, g.yMid, g.rL, pwA(powerFrac(0.0)), pwA(powerFrac(kw)), if (kw >= 0) ACCENT else REGEN, g.fillW)
        d.peakKw?.let { peakMark(canvas, g.cxR, g.yMid, g.rL, pwA(powerFrac(it)), g.gr) }
        outsideTip(canvas, g.cxR, g.yMid, g.rL, pwA(powerFrac(kw)), if (kw >= 0) ACCENT else REGEN, g.tipLen)
    }

    /** Power read-out (kW · kWh/100 · SoC/range). */
    fun ckEnergyText(canvas: Canvas, g: CockpitGeom, d: ClusterData) =
        powerReadout(canvas, g.rx, g.yMid, Fonts(g.gr), d, inlineUnit = true)

    /** Merged compass + G-meter dial. */
    fun ckCompass(canvas: Canvas, g: CockpitGeom, d: ClusterData) =
        combinedCentre(canvas, g.dialCx, g.dialCy, g.dialR, d)

    /** Integrated single gauge filling [cell] (narrow/portrait surfaces). */
    fun drawIntegrated(canvas: Canvas, cell: RectF, d: ClusterData, showCompass: Boolean = true) {
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
        if (showCompass) combinedCentre(canvas, cx, cy, rc, d)

        // Speed readout (number · ±acc/odo) + EU limit roundel; units on the face.
        mono.textAlign = Paint.Align.CENTER
        val sx = cx + IN_SP_X * R
        val sy = cy + IN_SP_Y * R
        mono.color = TEXT; mono.isFakeBoldText = true; mono.textSize = f.digit
        canvas.drawText(if (d.hasSpeed) "%.1f".format(Locale.ROOT, d.kmh) else "––", sx, sy, mono)
        mono.color = LCD_DIM; mono.isFakeBoldText = false; mono.textSize = f.sub
        canvas.drawText(subLine(d), sx, sy + f.sub * 1.4f, mono)
        if (d.speedLimitKmh != null) limitSign(canvas, sx, sy - f.digit * 1.5f, f.digit * 0.62f, d.speedLimitKmh)

        if (d.obd) powerReadout(canvas, cx + IN_PW_X * R, cy + IN_PW_Y * R, f, d, inlineUnit = false)

        unit(canvas, cx + IN_SPU_X * R, cy + IN_SPU_Y * R, "km/h", f)
        if (d.obd) unit(canvas, cx + IN_PWU_X * R, cy + IN_PWU_Y * R, "kW", f)
    }

    /**
     * Bespoke **portrait** cockpit for the phone: a map-centric HUD with the
     * energy scale on top and the speed scale on the bottom (both horizontal,
     * spanning the width), the big speed read-out above the bottom scale, the
     * speed-limit roundel bottom-left and the compass/G dial bottom-right, over
     * top+bottom black gradients that let the map read through the middle. All
     * positions/scales are fractions of [cell] (x/size = width, y/height =
     * height) — the [PORT] values tuned in the design mock.
     */
    fun drawCockpitPortrait(canvas: Canvas, cell: RectF, d: ClusterData, showCompass: Boolean = true) {
        // Assembled from the same pieces the phone lays out as independently
        // editable overlays (see CockpitClusterPhone) — one drawing path.
        ckPortScrims(canvas, cell, d)
        ckPortClock(canvas, cell, d)
        if (d.obd) ckPortPower(canvas, cell, d)
        ckPortSpeed(canvas, cell, d)
        ckPortLimit(canvas, cell, d)
        if (showCompass) ckPortCompass(canvas, cell, d)
    }

    // ── Portrait map-HUD pieces (each an independently positionable overlay) ──
    // Coordinates are absolute within [cell], like the landscape ck* pieces, so
    // the phone can draw one piece at a time and transform it on its own.

    /** Top + bottom black gradients (the map shows through the middle). */
    fun ckPortScrims(canvas: Canvas, cell: RectF, d: ClusterData) {
        val H = cell.height()
        scrim.shader = LinearGradient(0f, cell.top, 0f, cell.top + H * PORT_TOP_H, blackA(PORT_TOP_A), 0x00000000, Shader.TileMode.CLAMP)
        canvas.drawRect(cell.left, cell.top, cell.right, cell.top + H * PORT_TOP_H, scrim)
        scrim.shader = LinearGradient(0f, cell.bottom, 0f, cell.bottom - H * PORT_BOT_H, blackA(PORT_BOT_A), 0x00000000, Shader.TileMode.CLAMP)
        canvas.drawRect(cell.left, cell.bottom - H * PORT_BOT_H, cell.right, cell.bottom, scrim)
        scrim.shader = null
    }

    fun ckPortClock(canvas: Canvas, cell: RectF, d: ClusterData) =
        clockTemp(canvas, cell.centerX(), cell.top + cell.height() * PORT_CLOCK_Y, Fonts(cell.width() * PORT_CLOCK_SIZE), d)

    fun ckPortPower(canvas: Canvas, cell: RectF, d: ClusterData) {
        val W = cell.width(); val H = cell.height()
        val ref = W * PORT_SCALE_TICK
        val xL = cell.left + W * PORT_SCALE_INSET
        val xR = cell.left + W * (1f - PORT_SCALE_INSET)
        val kw = d.kw ?: 0.0
        val pcol = if (kw >= 0) ACCENT else REGEN
        mono.textAlign = Paint.Align.CENTER; mono.isFakeBoldText = true
        mono.color = pcol; mono.textSize = W * PORT_PWR_VAL_SIZE
        canvas.drawText("${if (kw > 0) "+" else ""}${kw.roundToInt()} kW", cell.centerX(), cell.top + H * PORT_PWR_VAL_Y, mono)
        hScale(canvas, xL, xR, cell.top + H * PORT_PWR_SCALE_Y, powerStops(), POWER_KNEES, kw, 0.0, pcol, ref)
        mono.isFakeBoldText = false; mono.color = LCD_DIM; mono.textSize = W * PORT_PWR_SUB_SIZE
        val soc = d.socPct?.let { "${it.roundToInt()}%" } ?: "–%"
        val range = d.rangeKm?.let { "${it.roundToInt()} km" } ?: "– km"
        canvas.drawText("$soc   ·   $range", cell.centerX(), cell.top + H * PORT_PWR_SUB_Y + W * PORT_PWR_SUB_SIZE * 0.9f, mono)
    }

    fun ckPortSpeed(canvas: Canvas, cell: RectF, d: ClusterData) {
        val W = cell.width(); val H = cell.height()
        val ref = W * PORT_SCALE_TICK
        val xL = cell.left + W * PORT_SCALE_INSET
        val xR = cell.left + W * (1f - PORT_SCALE_INSET)
        mono.textAlign = Paint.Align.CENTER; mono.isFakeBoldText = true
        mono.color = TEXT; mono.textSize = W * PORT_SPD_NUM_SIZE
        canvas.drawText(if (d.hasSpeed) "%.1f".format(Locale.ROOT, d.kmh) else "––", cell.centerX(), cell.top + H * PORT_SPD_NUM_Y, mono)
        mono.isFakeBoldText = false; mono.color = MUTED; mono.textSize = W * PORT_SPD_UNIT_SIZE
        canvas.drawText("km/h", cell.centerX(), cell.top + H * PORT_SPD_UNIT_Y, mono)
        hScale(canvas, xL, xR, cell.top + H * PORT_SPD_SCALE_Y, speedStops(), SPEED_KNEES, d.kmh, 0.0, LCD, ref)
    }

    fun ckPortLimit(canvas: Canvas, cell: RectF, d: ClusterData) {
        if (d.speedLimitKmh == null) return
        limitSign(canvas, cell.left + cell.width() * PORT_LIM_X, cell.top + cell.height() * PORT_LIM_Y, cell.width() * PORT_LIM_R, d.speedLimitKmh)
    }

    fun ckPortCompass(canvas: Canvas, cell: RectF, d: ClusterData) =
        combinedCentre(canvas, cell.left + cell.width() * PORT_COMP_X, cell.top + cell.height() * PORT_COMP_Y, cell.width() * PORT_COMP_R, d)

    /** The power block's natural bounds (value + scale + sub) for edit hit-testing. */
    fun portPowerRect(cell: RectF): RectF {
        val W = cell.width(); val H = cell.height()
        return RectF(
            cell.left + W * PORT_SCALE_INSET, cell.top + H * PORT_PWR_VAL_Y - W * PORT_PWR_VAL_SIZE,
            cell.left + W * (1f - PORT_SCALE_INSET), cell.top + H * PORT_PWR_SUB_Y + W * PORT_PWR_SUB_SIZE * 1.5f,
        )
    }

    /** The speed block's natural bounds (number + unit + scale). */
    fun portSpeedRect(cell: RectF): RectF {
        val W = cell.width(); val H = cell.height()
        return RectF(
            cell.left + W * PORT_SCALE_INSET, cell.top + H * PORT_SPD_NUM_Y - W * PORT_SPD_NUM_SIZE,
            cell.left + W * (1f - PORT_SCALE_INSET), cell.top + H * PORT_SPD_SCALE_Y + W * PORT_SCALE_TICK * 3f,
        )
    }

    /** The compass/G-meter dial bounds. */
    fun portCompassRect(cell: RectF): RectF {
        val W = cell.width(); val H = cell.height()
        val cx = cell.left + W * PORT_COMP_X; val cy = cell.top + H * PORT_COMP_Y; val r = W * PORT_COMP_R
        return RectF(cx - r, cy - r, cx + r, cy + r)
    }

    /** Black with the given 0..1 alpha (for the portrait gradients). */
    private fun blackA(a: Float): Int = ((a.coerceIn(0f, 1f) * 255f).toInt() shl 24)

    /** Horizontal scale: rail + ticks/labels (above), a glowing fill from the
     *  zero stop to the value, and a downward value tip. */
    private fun hScale(
        c: Canvas, xL: Float, xR: Float, y: Float, stops: DoubleArray, knees: DoubleArray,
        value: Double, zeroVal: Double, color: Int, ref: Float,
    ) {
        val n = stops.size
        val span = xR - xL
        val xOf = { t: Float -> xL + span * t }
        ring.color = fade(MUTED, 0.3f); ring.strokeWidth = ref * 0.45f
        c.drawLine(xL, y, xR, y, ring)
        val tickLen = ref * 1.1f
        labelPaint.textAlign = Paint.Align.CENTER; labelPaint.isFakeBoldText = true; labelPaint.textSize = ref * 1.7f
        for (i in 0 until n) {
            val x = xOf(i.toFloat() / (n - 1))
            val knee = knees.any { abs(stops[i] - it) < 1e-6 }
            tickPaint.color = if (knee) ACCENT else TEXT
            tickPaint.strokeWidth = ref * if (knee) 0.4f else 0.2f
            c.drawLine(x, y, x, y - tickLen, tickPaint)
            labelPaint.color = if (knee) ACCENT else MUTED
            c.drawText(abs(stops[i].roundToInt()).toString(), x, y - tickLen - ref * 0.4f, labelPaint)
        }
        labelPaint.isFakeBoldText = false
        arc.color = color; arc.strokeWidth = ref * 1.5f
        c.drawLine(xOf(fracFromStops(zeroVal, stops)), y, xOf(fracFromStops(value, stops)), y, arc)
        val tw = ref * 1.9f
        val xv = xOf(fracFromStops(value, stops))
        fill.color = color
        c.drawPath(Path().apply { moveTo(xv, y + tw); lineTo(xv - tw * 0.7f, y); lineTo(xv + tw * 0.7f, y); close() }, fill)
    }

    // ── Standalone single-gauge dials ───────────────────────────────
    // Each draws ONE self-contained round gauge filling [cell], in the same
    // visual language as the cluster, so the phone can lay them out as
    // independently movable/scalable elements (speed, energy, compass) rather
    // than one monolithic block. A 270° scale with the gap at the bottom; the
    // value rides the scale as a glowing fill + outside tip, leaving the centre
    // free for the readout.

    /** Angle (deg) along a 270° bottom-gap dial for fraction [t] in [0,1]. */
    private fun dialAng(t: Float): Float = 135f + t * 270f

    private fun dialHousing(c: Canvas, cx: Float, cy: Float, R: Float) {
        fill.color = HOUSING
        c.drawCircle(cx, cy, R * 1.06f, fill)
        ring.color = fade(MUTED, 0.5f)
        ring.strokeWidth = R * 0.02f
        c.drawCircle(cx, cy, R * 1.04f, ring)
    }

    /** Self-contained speed dial: scale + level fill + value tip, big readout
     *  and the posted-limit roundel in the middle. */
    fun drawSpeedDial(c: Canvas, cell: RectF, d: ClusterData) {
        val R = min(cell.width(), cell.height()) * 0.46f
        val cx = cell.centerX()
        val cy = cell.centerY()
        val f = Fonts(R)
        dialHousing(c, cx, cy, R)
        scaleTicks(c, cx, cy, R * 0.92f, R * 0.10f, R * 0.72f, R, f.tick, speedStops(), SPEED_KNEES, false, ::dialAng)
        val frac = speedFrac(d.kmh)
        fillArc(c, cx, cy, R * 0.92f, dialAng(0f), dialAng(frac), LCD, R * 0.05f)
        outsideTip(c, cx, cy, R * 0.92f, dialAng(frac), LCD, R * 0.10f)
        if (d.speedLimitKmh != null) limitSign(c, cx, cy - R * 0.34f, R * 0.18f, d.speedLimitKmh)
        mono.textAlign = Paint.Align.CENTER
        mono.color = TEXT; mono.isFakeBoldText = true; mono.textSize = f.digit
        c.drawText(if (d.hasSpeed) "%.1f".format(Locale.ROOT, d.kmh) else "––", cx, cy + f.digit * 0.35f, mono)
        mono.color = MUTED; mono.isFakeBoldText = false; mono.textSize = f.unit
        c.drawText("km/h", cx, cy + f.digit * 0.95f, mono)
        mono.color = LCD_DIM; mono.textSize = f.sub
        c.drawText(subLine(d), cx, cy + f.digit * 0.95f + f.sub * 1.5f, mono)
    }

    /** Self-contained power/energy dial. Without an OBD feed the scale still
     *  draws (so it reads as a gauge) with a dashed centre readout. */
    fun drawPowerDial(c: Canvas, cell: RectF, d: ClusterData) {
        val R = min(cell.width(), cell.height()) * 0.46f
        val cx = cell.centerX()
        val cy = cell.centerY()
        val f = Fonts(R)
        dialHousing(c, cx, cy, R)
        scaleTicks(c, cx, cy, R * 0.92f, R * 0.10f, R * 0.72f, R, f.tick, powerStops(), POWER_KNEES, !d.obd, ::dialAng)
        val kw = d.kw
        if (kw != null) {
            fillArc(c, cx, cy, R * 0.92f, dialAng(powerFrac(0.0)), dialAng(powerFrac(kw)), if (kw >= 0) ACCENT else REGEN, R * 0.05f)
            d.peakKw?.let { peakMark(c, cx, cy, R * 0.92f, dialAng(powerFrac(it)), R) }
            outsideTip(c, cx, cy, R * 0.92f, dialAng(powerFrac(kw)), if (kw >= 0) ACCENT else REGEN, R * 0.10f)
            powerReadout(c, cx, cy + f.digit * 0.1f, f, d, inlineUnit = true)
        } else {
            mono.textAlign = Paint.Align.CENTER
            mono.color = MUTED; mono.isFakeBoldText = true; mono.textSize = f.digit
            c.drawText("––", cx, cy + f.digit * 0.35f, mono)
            mono.isFakeBoldText = false; mono.textSize = f.unit
            c.drawText("kW", cx, cy + f.digit * 0.95f, mono)
        }
    }

    /** Self-contained compass + G-meter dial (the merged dynamics dial). */
    fun drawCompassDial(c: Canvas, cell: RectF, d: ClusterData) {
        val R = min(cell.width(), cell.height()) * 0.48f
        combinedCentre(c, cell.centerX(), cell.centerY(), R, d)
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
        "±%.1f   %.1f km".format(Locale.ROOT, d.speedAccKmh ?: 0f, d.odometerKm)

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
    // Dither smooths the black→transparent alpha ramp; without it the wide,
    // low-gradient scrim bands badly on the car surface (often RGB_565).
    private val scrim = Paint(Paint.FILTER_BITMAP_FLAG).apply { isDither = true }
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

        // Portrait map-HUD (phone). x/size/inset/radius = fraction of width,
        // y/height = fraction of height, alpha 0..1. Tuned in the design mock.
        const val PORT_CLOCK_Y = 0.030f
        const val PORT_CLOCK_SIZE = 0.34f
        const val PORT_PWR_VAL_Y = 0.100f
        const val PORT_PWR_VAL_SIZE = 0.080f
        const val PORT_PWR_SCALE_Y = 0.150f
        const val PORT_PWR_SUB_Y = 0.175f
        const val PORT_PWR_SUB_SIZE = 0.042f
        const val PORT_SCALE_INSET = 0.055f
        const val PORT_SCALE_TICK = 0.017f
        const val PORT_SPD_NUM_Y = 0.875f
        const val PORT_SPD_NUM_SIZE = 0.110f
        const val PORT_SPD_UNIT_Y = 0.900f
        const val PORT_SPD_UNIT_SIZE = 0.032f
        const val PORT_SPD_SCALE_Y = 0.940f
        const val PORT_COMP_X = 0.860f
        const val PORT_COMP_Y = 0.820f
        const val PORT_COMP_R = 0.130f
        const val PORT_LIM_X = 0.150f
        const val PORT_LIM_Y = 0.860f
        const val PORT_LIM_R = 0.082f
        const val PORT_TOP_H = 0.22f
        const val PORT_TOP_A = 0.90f
        const val PORT_BOT_H = 0.42f
        const val PORT_BOT_A = 0.93f

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
        // Eased, multi-stop black→transparent falloff for the edge scrims
        // (smoother than a 2-stop linear ramp; paired with a dithered paint).
        val SCRIM_POS = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        val SCRIM_COLS = intArrayOf(
            0xEB000000.toInt(), 0xC8000000.toInt(), 0x8C000000.toInt(), 0x46000000.toInt(), 0x00000000,
        )
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

/** Shared cockpit geometry + each piece's natural bounds (its designed screen
 *  rect, used as the pivot/hit-box when the piece is an editable overlay).
 *  Produced by [CarInstruments.cockpitGeom]. */
class CockpitGeom(
    val gr: Float, val sweep: Float,
    val cxL: Float, val yL: Float, val rLL: Float, val sL: Float,
    val cxR: Float, val yMid: Float, val rL: Float,
    val lx: Float, val rx: Float,
    val tickLen: Float, val tipLen: Float, val fillW: Float,
    val aL: Float, val aR: Float, val aT: Float, val aB: Float,
    val w: Float, val h: Float, val scrimW: Float,
    val dialCx: Float, val dialCy: Float, val dialR: Float,
    val clockX: Float, val clockY: Float,
    val bgRect: RectF, val speedRect: RectF, val speedTextRect: RectF,
    val energyRect: RectF, val energyTextRect: RectF,
    val compassRect: RectF, val clockRect: RectF,
)
