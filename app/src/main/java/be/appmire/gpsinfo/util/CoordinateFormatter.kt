package be.appmire.gpsinfo.util

import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

enum class CoordinateFormat { DMS, DECIMAL, PLUS_CODE, MAIDENHEAD, MGRS }

/**
 * One-shot parser for the "lat" or "lon" half a user types or pastes
 * into the navigation picker. Accepts:
 *
 *  - plain decimal, comma- or dot-separator: "51.1302", "51,1302"
 *  - DMS with any combination of `° ' "` or whitespace separators:
 *    "51°07'48.730" N", "51 7 48.730 N", "N 51 7 48.73"
 *  - signed prefix or compass-letter suffix/prefix: "-51", "S 51 7 0"
 *
 * Returns null on anything it can't confidently parse. Pass [isLat]
 * so the compass-letter validator can reject "E" on a latitude field
 * etc.
 */
object CoordinateParser {

    fun parseHalf(input: String, isLat: Boolean): Double? {
        val t = input.trim()
        if (t.isEmpty()) return null
        // Try decimal first — fast path that covers the bulk of cases.
        // Accept both `.` and `,` as decimal separator since European
        // locales paste commas.
        val asDecimal = t.replace(',', '.').toDoubleOrNull()
        if (asDecimal != null) return asDecimal.takeIf { inRange(it, isLat) }
        return parseDms(t, isLat)
    }

    private val dmsTokens = Regex("[NSEW+-]|\\d+\\.?\\d*", RegexOption.IGNORE_CASE)

    private fun parseDms(input: String, isLat: Boolean): Double? {
        // Pull every number and direction marker out, in order, and
        // collapse into (sign, deg, min, sec). Most DMS variants the
        // user might type fall into this token sequence:
        //   ["N" or "S" or "+/-"] D M [S]
        //   D M [S] ["N" or "S"]
        val tokens = dmsTokens.findAll(input).map { it.value.uppercase() }.toList()
        if (tokens.isEmpty()) return null

        var sign = 1
        val numbers = mutableListOf<Double>()
        for (tk in tokens) {
            when (tk) {
                "N", "E", "+" -> sign = 1
                "S", "W", "-" -> sign = -1
                else -> tk.toDoubleOrNull()?.let { numbers.add(it) }
            }
        }
        if (numbers.isEmpty()) return null
        val deg = numbers.getOrNull(0) ?: return null
        val min = numbers.getOrNull(1) ?: 0.0
        val sec = numbers.getOrNull(2) ?: 0.0
        if (min < 0.0 || min >= 60.0) return null
        if (sec < 0.0 || sec >= 60.0) return null
        val dec = sign * (abs(deg) + min / 60.0 + sec / 3600.0)
        return dec.takeIf { inRange(it, isLat) }
    }

    private fun inRange(d: Double, isLat: Boolean): Boolean =
        if (isLat) d in -90.0..90.0 else d in -180.0..180.0

    /**
     * Decode a Maidenhead Locator (4-char "JO21" or 6-char "JO21in")
     * into the centre lat/lon of its cell. Compatible with the
     * encoder in [CoordinateFormatter.toMaidenhead].
     *
     * 4-char precision is ~2°×1° (about 200 km at mid-latitude). The
     * encoder ships 6-char; the parser accepts either since hams will
     * often quote just 4 chars in conversation.
     */
    fun parseMaidenhead(input: String): Pair<Double, Double>? {
        val cleaned = input.trim()
        if (cleaned.length != 4 && cleaned.length != 6) return null
        // Field — uppercase A-R for both lat and lon halves.
        val fieldLon = cleaned[0].uppercaseChar() - 'A'
        val fieldLat = cleaned[1].uppercaseChar() - 'A'
        if (fieldLon !in 0..17 || fieldLat !in 0..17) return null
        // Square — digits 0-9.
        val squareLon = cleaned[2].digitToIntOrNull() ?: return null
        val squareLat = cleaned[3].digitToIntOrNull() ?: return null
        if (squareLon !in 0..9 || squareLat !in 0..9) return null

        var lon = fieldLon * 20.0 + squareLon * 2.0
        var lat = fieldLat * 10.0 + squareLat * 1.0
        // Centre defaults to half a 2°×1° square; refined when a
        // 6-char subsquare is present.
        var halfLon = 1.0
        var halfLat = 0.5
        if (cleaned.length == 6) {
            // Subsquare — lowercase a-x.
            val subLon = cleaned[4].lowercaseChar() - 'a'
            val subLat = cleaned[5].lowercaseChar() - 'a'
            if (subLon !in 0..23 || subLat !in 0..23) return null
            lon += subLon * (2.0 / 24.0)
            lat += subLat * (1.0 / 24.0)
            halfLon = (2.0 / 24.0) / 2.0
            halfLat = (1.0 / 24.0) / 2.0
        }
        val centreLat = lat + halfLat - 90.0
        val centreLon = lon + halfLon - 180.0
        if (centreLat !in -90.0..90.0 || centreLon !in -180.0..180.0) return null
        return centreLat to centreLon
    }

    /**
     * Decode a full-length Plus Code (10 alphabet chars + `+`, e.g.
     * "7FG49QCJ+2V") into the centre lat/lon of its cell. Returns
     * null for malformed input or short/area codes — we want a usable
     * point for navigation, not a region.
     *
     * Compatible with the encoder in [CoordinateFormatter.toPlusCode].
     */
    fun parsePlusCode(input: String): Pair<Double, Double>? {
        val cleaned = input.trim().uppercase()
        if (!cleaned.contains('+')) return null
        // Strip the separator and any tail padding ('0' would mean a
        // short code; we don't support those because they're
        // location-relative).
        val raw = cleaned.replace("+", "")
        if (raw.length != 10) return null
        if (raw.any { it == '0' }) return null
        val alphabet = "23456789CFGHJMPQRVWX"
        val sizes = doubleArrayOf(20.0, 1.0, 0.05, 0.0025, 0.000125)
        var lat = -90.0
        var lon = -180.0
        for (i in 0 until 5) {
            val latIdx = alphabet.indexOf(raw[i * 2])
            val lonIdx = alphabet.indexOf(raw[i * 2 + 1])
            if (latIdx < 0 || lonIdx < 0) return null
            lat += latIdx * sizes[i]
            lon += lonIdx * sizes[i]
        }
        // Centre of the smallest cell.
        val centreLat = lat + sizes[4] / 2.0
        val centreLon = lon + sizes[4] / 2.0
        if (centreLat !in -90.0..90.0 || centreLon !in -180.0..180.0) return null
        return centreLat to centreLon
    }
}

/**
 * Result of formatting a lat/lon pair. Two shapes:
 *
 * - [Pair] — separate lat / lon strings (DMS, Decimal). The card stacks them.
 * - [Single] — one self-contained string (Plus Code, Maidenhead, MGRS).
 *   These formats don't decompose into "lat" / "lon" lines; the card
 *   renders them as a single line.
 */
sealed interface FormattedCoord {
    data class Pair(val lat: String, val lon: String) : FormattedCoord
    data class Single(val text: String) : FormattedCoord
}

// Coordinates are technical data — period decimal separators across all
// locales, so comma-decimal locales don't surface "51,130203°".
object CoordinateFormatter {
    fun format(latDeg: Double, lonDeg: Double, fmt: CoordinateFormat): FormattedCoord =
        when (fmt) {
            CoordinateFormat.DMS -> FormattedCoord.Pair(
                toDms(latDeg, isLat = true),
                toDms(lonDeg, isLat = false),
            )
            CoordinateFormat.DECIMAL -> FormattedCoord.Pair(
                "%.6f°".format(Locale.ROOT, latDeg),
                "%.6f°".format(Locale.ROOT, lonDeg),
            )
            CoordinateFormat.PLUS_CODE -> FormattedCoord.Single(toPlusCode(latDeg, lonDeg))
            CoordinateFormat.MAIDENHEAD -> FormattedCoord.Single(toMaidenhead(latDeg, lonDeg))
            CoordinateFormat.MGRS -> FormattedCoord.Single(toMgrs(latDeg, lonDeg))
        }

    /** Clipboard-friendly single-string rendering — used for "copy
     *  coordinates" so the user gets one paste regardless of format. */
    fun copyString(coord: FormattedCoord): String = when (coord) {
        is FormattedCoord.Pair -> "${coord.lat}, ${coord.lon}"
        is FormattedCoord.Single -> coord.text
    }

    private fun toDms(deg: Double, isLat: Boolean): String {
        val hemi = when {
            isLat && deg >= 0 -> "N"
            isLat -> "S"
            !isLat && deg >= 0 -> "E"
            else -> "W"
        }
        val a = abs(deg)
        val d = floor(a).toInt()
        val mFull = (a - d) * 60.0
        val m = floor(mFull).toInt()
        val s = (mFull - m) * 60.0
        return "%02d°%02d'%06.3f\" %s".format(Locale.ROOT, d, m, s, hemi)
    }

    /**
     * Open Location Code ("Plus Code"), 10-character full code with the
     * `+` separator after position 8. Spec:
     *   https://github.com/google/open-location-code/blob/main/docs/specification.md
     *
     * Resolution at 10 characters is roughly 14 m × 14 m at the equator —
     * fine for "drop a pin at this spot" without needing grid refinement.
     * Encode-only; decoding isn't surfaced anywhere in the app.
     */
    internal fun toPlusCode(latDeg: Double, lonDeg: Double): String {
        // Clamp lat slightly inside ±90° so floor() at the pole doesn't
        // step into the unused alphabet slots beyond index 17 (lat) / 17
        // (lon) for the field digits. Longitude normalised to (-180, 180].
        val latClamped = latDeg.coerceIn(-90.0 + 1e-12, 90.0 - 1e-12)
        val lonNorm = normaliseLon(lonDeg)
        var latShifted = latClamped + 90.0
        var lonShifted = lonNorm + 180.0
        val out = StringBuilder()
        // Five pair levels; each pair shrinks the cell 20× along each axis.
        for (size in PLUS_CODE_SIZES) {
            val latIdx = (latShifted / size).toInt().coerceIn(0, 19)
            val lonIdx = (lonShifted / size).toInt().coerceIn(0, 19)
            out.append(PLUS_CODE_ALPHABET[latIdx])
            out.append(PLUS_CODE_ALPHABET[lonIdx])
            latShifted -= latIdx * size
            lonShifted -= lonIdx * size
        }
        // "+" goes after position 8 in the unseparated 10-char output.
        return "${out.substring(0, 8)}+${out.substring(8)}"
    }

    /**
     * Maidenhead Locator ("Grid Square"), 6-character precision. Format:
     *   Field      — two uppercase letters A–R   (20° lon × 10° lat cells)
     *   Square     — two digits 0–9              ( 2° lon ×  1° lat cells)
     *   Subsquare  — two lowercase letters a–x   ( 5′ lon × 2.5′ lat cells)
     *
     * Example: "JO21in". The ham-radio / SAR audience reads this fluently.
     */
    internal fun toMaidenhead(latDeg: Double, lonDeg: Double): String {
        val latClamped = latDeg.coerceIn(-90.0 + 1e-12, 90.0 - 1e-12)
        val lonNorm = normaliseLon(lonDeg)
        val latShifted = latClamped + 90.0
        val lonShifted = lonNorm + 180.0

        val fieldLon = (lonShifted / 20.0).toInt().coerceIn(0, 17)
        val fieldLat = (latShifted / 10.0).toInt().coerceIn(0, 17)
        val remLon1 = lonShifted - fieldLon * 20.0
        val remLat1 = latShifted - fieldLat * 10.0

        val squareLon = (remLon1 / 2.0).toInt().coerceIn(0, 9)
        val squareLat = remLat1.toInt().coerceIn(0, 9)
        val remLon2 = remLon1 - squareLon * 2.0
        val remLat2 = remLat1 - squareLat * 1.0

        // 24 subsquares fit in each 2° lon / 1° lat square, so the index
        // scaling is *12 (lon) and *24 (lat).
        val subLon = (remLon2 * 12.0).toInt().coerceIn(0, 23)
        val subLat = (remLat2 * 24.0).toInt().coerceIn(0, 23)

        return "${'A' + fieldLon}${'A' + fieldLat}" +
            "$squareLon$squareLat" +
            "${'a' + subLon}${'a' + subLat}"
    }

    /**
     * Military Grid Reference System (MGRS), 10-digit precision (1 m).
     *
     * Format: `<zone><band> <col><row> <eastingDDDDD> <northingDDDDD>`
     *
     * Example: `31U DQ 48217 12059` (Eiffel Tower).
     *
     * Encoder for the UTM range only (lat in [-80°, 84°]); polar regions
     * (UPS grid) return `—`, since the app's audience — hikers, runners,
     * SAR, ham radio — is overwhelmingly mid-latitude. We use WGS84 and
     * the standard Snyder transverse-Mercator forward formula. Tested
     * against published references at multiple latitudes / hemispheres.
     *
     * Notes:
     *   - The Norway / Svalbard zone exceptions (31V, 32V, 31X–37X) are
     *     not applied. Outputs in those areas are off by one zone for
     *     points strictly within the exception bounds. Acceptable for
     *     v1; flagged in code for a future correction pass.
     */
    internal fun toMgrs(latDeg: Double, lonDeg: Double): String {
        if (latDeg < -80.0 || latDeg > 84.0) return "—"
        val latNorm = latDeg
        val lonNorm = normaliseLon(lonDeg)

        // UTM zone (1..60). Zone 1 starts at lon = -180°, each zone is 6° wide.
        val zone = ((((lonNorm + 180.0) / 6.0).toInt()) + 1).coerceIn(1, 60)
        val centralMeridianDeg = (zone - 1) * 6.0 + 3.0 - 180.0

        // WGS84 transverse-Mercator forward (Snyder, 1987). All trig in radians.
        val phi = Math.toRadians(latNorm)
        val lambda = Math.toRadians(lonNorm)
        val lambda0 = Math.toRadians(centralMeridianDeg)

        val a = 6_378_137.0                  // WGS84 semi-major axis (m)
        val eSq = 0.006_694_379_990_14       // first eccentricity²
        val ePrimeSq = eSq / (1 - eSq)       // second eccentricity²
        val k0 = 0.9996                      // UTM scale factor

        val sinPhi = sin(phi); val cosPhi = cos(phi); val tanPhi = tan(phi)
        val N = a / sqrt(1 - eSq * sinPhi * sinPhi)
        val T = tanPhi * tanPhi
        val C = ePrimeSq * cosPhi * cosPhi
        val A = cosPhi * (lambda - lambda0)

        val M = a * (
            (1 - eSq / 4 - 3 * eSq * eSq / 64 - 5 * eSq.pow(3) / 256) * phi
                - (3 * eSq / 8 + 3 * eSq * eSq / 32 + 45 * eSq.pow(3) / 1024) * sin(2 * phi)
                + (15 * eSq * eSq / 256 + 45 * eSq.pow(3) / 1024) * sin(4 * phi)
                - (35 * eSq.pow(3) / 3072) * sin(6 * phi)
            )

        // Easting and northing inside the zone (metres). Adds the
        // standard false easting (500 km); false northing (10 000 km)
        // only kicks in for the southern hemisphere.
        val x = k0 * N * (
            A + (1 - T + C) * A.pow(3) / 6.0
                + (5 - 18 * T + T * T + 72 * C - 58 * ePrimeSq) * A.pow(5) / 120.0
            ) + 500_000.0
        var y = k0 * (
            M + N * tanPhi * (
                A * A / 2.0
                    + (5 - T + 9 * C + 4 * C * C) * A.pow(4) / 24.0
                    + (61 - 58 * T + T * T + 600 * C - 330 * ePrimeSq) * A.pow(6) / 720.0
                )
            )
        if (latNorm < 0.0) y += 10_000_000.0

        // Latitude band (C..X, omitting I and O). Eight degrees per band
        // except X (12°), idx 19 stretches from 72° to 84°.
        val bandLetters = "CDEFGHJKLMNPQRSTUVWX"
        val bandIdx = ((latNorm + 80.0) / 8.0).toInt().coerceIn(0, 19)
        val bandLetter = bandLetters[bandIdx]

        // 100-km grid square. Column letter cycles in groups of 8 by zone
        // (mod 3); row letter cycles in groups of 20 with a 5-letter
        // phase shift for even-numbered zones.
        val colLetters = when ((zone - 1) % 3) {
            0 -> "ABCDEFGH"
            1 -> "JKLMNPQR"
            else -> "STUVWXYZ"
        }
        val colIdx = ((x / 100_000.0).toInt() - 1).coerceIn(0, 7)
        val colLetter = colLetters[colIdx]

        val rowLetters =
            if (zone % 2 == 1) "ABCDEFGHJKLMNPQRSTUV"
            else "FGHJKLMNPQRSTUVABCDE"
        val rowIdx = ((y / 100_000.0).toInt() % 20 + 20) % 20
        val rowLetter = rowLetters[rowIdx]

        // Last five digits of easting / northing inside the 100-km cell.
        val east5 = ((x % 100_000.0).toInt()).coerceIn(0, 99_999)
        val north5 = ((y % 100_000.0).toInt()).coerceIn(0, 99_999)

        return "%d%s %s%s %05d %05d".format(
            Locale.ROOT, zone, bandLetter, colLetter, rowLetter, east5, north5,
        )
    }

    /** Wrap arbitrary lon input into (-180, 180]. The two `%` calls are
     *  cheaper and clearer than a while loop for the values we get. */
    private fun normaliseLon(lon: Double): Double {
        val mod = ((lon + 180.0) % 360.0 + 360.0) % 360.0
        return mod - 180.0
    }

    private const val PLUS_CODE_ALPHABET = "23456789CFGHJMPQRVWX"
    private val PLUS_CODE_SIZES = doubleArrayOf(20.0, 1.0, 0.05, 0.0025, 0.000125)
}
