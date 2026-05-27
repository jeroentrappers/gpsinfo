package be.appmire.gpsinfo.data.fit

import be.appmire.gpsinfo.data.model.Trail
import be.appmire.gpsinfo.data.model.TrailPoint
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * Minimal Garmin FIT activity-file writer.
 *
 * Garmin publishes the FIT binary protocol; what they distribute as
 * "the SDK" is a Java helper around the same byte layout. We write
 * directly to that layout — it keeps the AGPL build SDK-free and the
 * footprint at ~300 lines.
 *
 * Layout produced (per the FIT spec — global message numbers in
 * brackets):
 *
 *   header (14 bytes)
 *   file_id     [0]   one record — declares this is an activity file
 *   event       [21]  one "timer start" event at the recording start
 *   record      [20]  one per trail point — the bulk of the file
 *   event       [21]  one "timer stop" event at the recording end
 *   lap         [19]  one summary lap covering the entire trail
 *   session     [18]  one session record summarising the trail
 *   activity    [34]  one closing activity record
 *   CRC-16      (2 bytes)
 *
 * That subset is what Strava, Garmin Connect and TrainingPeaks
 * actually require to import a workout. Per-lap splits are coalesced
 * into one lap — FIT supports many but we don't yet expose per-lap
 * detail beyond what GPX already carries.
 *
 * Encoding notes:
 *  - All multi-byte fields little-endian (architecture byte = 0).
 *  - FIT timestamps are seconds since 1989-12-31 00:00:00 UTC
 *    (the "Garmin epoch") — Unix epoch + [FIT_EPOCH_OFFSET_SECS].
 *  - Coordinates are stored in semicircles: 2^31 / 180 per degree.
 *  - Altitude is `(metres + 500) * 5` packed into uint16.
 *  - "Invalid" sentinels (0xFF, 0xFFFF, 0xFFFFFFFF) mark missing
 *    values per the spec — Strava + Garmin Connect both honour them.
 *
 * The CRC algorithm is the one published in the FIT protocol document
 * (table-driven 4-bit-lookup CRC-CCITT variant); see [crc16Update].
 */
internal object FitIo {

    fun writeActivity(trail: Trail, sink: OutputStream) {
        val body = ByteArrayOutputStream()
        val writer = FitBodyWriter(body)
        writer.writeAll(trail)
        val bodyBytes = body.toByteArray()

        // Header: 14 bytes, includes its own CRC at bytes 12-13.
        val header = ByteArray(14)
        header[0] = 14
        header[1] = 0x20.toByte() // protocol 2.0
        // profile version (Garmin "Profile" version) — anything ≥ 100 is
        // fine; pick 21.40 (= 2140) to mirror modern SDKs.
        header[2] = 0x5C.toByte() // 2140 & 0xFF
        header[3] = 0x08.toByte() // 2140 >> 8
        // data size = number of bytes between header and final CRC
        val dataSize = bodyBytes.size
        header[4] = (dataSize and 0xFF).toByte()
        header[5] = ((dataSize shr 8) and 0xFF).toByte()
        header[6] = ((dataSize shr 16) and 0xFF).toByte()
        header[7] = ((dataSize shr 24) and 0xFF).toByte()
        header[8] = '.'.code.toByte()
        header[9] = 'F'.code.toByte()
        header[10] = 'I'.code.toByte()
        header[11] = 'T'.code.toByte()
        // Header CRC: CRC of bytes 0..11. Some readers ignore it (set
        // to 0) but Garmin Connect verifies, so we compute it.
        var hcrc = 0
        for (i in 0 until 12) hcrc = crc16Update(hcrc, header[i].toInt() and 0xFF)
        header[12] = (hcrc and 0xFF).toByte()
        header[13] = ((hcrc shr 8) and 0xFF).toByte()

        // File CRC: CRC over header + body (everything before the last
        // two bytes that are the CRC itself).
        var fcrc = 0
        for (b in header) fcrc = crc16Update(fcrc, b.toInt() and 0xFF)
        for (b in bodyBytes) fcrc = crc16Update(fcrc, b.toInt() and 0xFF)

        sink.write(header)
        sink.write(bodyBytes)
        sink.write(fcrc and 0xFF)
        sink.write((fcrc shr 8) and 0xFF)
        sink.flush()
    }

    /** FIT timestamp epoch offset: 1989-12-31 00:00:00 UTC. */
    private const val FIT_EPOCH_OFFSET_SECS = 631_065_600L

    // Global message numbers (from the FIT profile).
    private const val MSG_FILE_ID = 0
    private const val MSG_SESSION = 18
    private const val MSG_LAP = 19
    private const val MSG_RECORD = 20
    private const val MSG_EVENT = 21
    private const val MSG_ACTIVITY = 34

    // Local message types — arbitrary 4-bit ids we use to alias the
    // global numbers within this file.
    private const val LOCAL_FILE_ID = 0
    private const val LOCAL_EVENT = 1
    private const val LOCAL_RECORD = 2
    private const val LOCAL_LAP = 3
    private const val LOCAL_SESSION = 4
    private const val LOCAL_ACTIVITY = 5

    // FIT base type bytes.
    private const val BT_ENUM = 0x00      // 1 byte
    private const val BT_UINT8 = 0x02     // 1 byte
    private const val BT_SINT32 = 0x85    // 4 bytes
    private const val BT_UINT16 = 0x84    // 2 bytes
    private const val BT_UINT32 = 0x86    // 4 bytes
    private const val BT_UINT32Z = 0x8C   // 4 bytes (0 = invalid)

    // CRC table from the FIT protocol spec.
    private val CRC_TABLE = intArrayOf(
        0x0000, 0xCC01, 0xD801, 0x1400, 0xF001, 0x3C00, 0x2800, 0xE401,
        0xA001, 0x6C00, 0x7800, 0xB401, 0x5000, 0x9C01, 0x8801, 0x4400,
    )

    private fun crc16Update(crc: Int, byte: Int): Int {
        var c = crc
        // Lower 4 bits of byte.
        var tmp = CRC_TABLE[c and 0xF]
        c = (c shr 4) and 0x0FFF
        c = c xor tmp xor CRC_TABLE[byte and 0xF]
        // Upper 4 bits of byte.
        tmp = CRC_TABLE[c and 0xF]
        c = (c shr 4) and 0x0FFF
        c = c xor tmp xor CRC_TABLE[(byte shr 4) and 0xF]
        return c and 0xFFFF
    }

    /**
     * Body composer — turns a [Trail] into the sequence of definition
     * and data records that go between the FIT header and the final
     * CRC. Holds the [ByteArrayOutputStream] sink and a few helpers
     * for little-endian field writes.
     */
    private class FitBodyWriter(private val out: ByteArrayOutputStream) {

        fun writeAll(trail: Trail) {
            val timestamped = trail.points.filter { it.timeMillis > 0 }
            val startMs = trail.startTimeMillis
                ?: timestamped.firstOrNull()?.timeMillis
                ?: System.currentTimeMillis()
            val endMs = trail.endTimeMillis
                ?: timestamped.lastOrNull()?.timeMillis
                ?: startMs

            writeFileId(timeCreatedMs = startMs)
            writeEvent(timestampMs = startMs, eventType = 0) // start
            writeRecordDefinition()
            for (p in trail.points) writeRecord(p)
            writeEvent(timestampMs = endMs, eventType = 4) // stop_all
            writeLap(trail, startMs = startMs, endMs = endMs)
            writeSession(trail, startMs = startMs, endMs = endMs)
            writeActivity(timestampMs = endMs, startMs = startMs, endMs = endMs)
        }

        // --- file_id (global 0) --- //

        private fun writeFileId(timeCreatedMs: Long) {
            // Definition.
            out.write(0x40 or LOCAL_FILE_ID)
            out.write(0) // reserved
            out.write(0) // little endian
            writeU16(MSG_FILE_ID)
            // Fields: type(0/enum), manufacturer(1/u16), product(2/u16),
            // time_created(4/u32), serial_number(3/u32z).
            out.write(5)
            writeField(0, 1, BT_ENUM)
            writeField(1, 2, BT_UINT16)
            writeField(2, 2, BT_UINT16)
            writeField(4, 4, BT_UINT32)
            writeField(3, 4, BT_UINT32Z)
            // Data.
            out.write(LOCAL_FILE_ID)
            out.write(4) // type=4 (activity)
            writeU16(255) // manufacturer = development
            writeU16(0)   // product
            writeU32(toFitTimestamp(timeCreatedMs))
            writeU32(1)   // serial_number (anything non-zero is valid)
        }

        // --- event (global 21) --- //

        // We pre-emit a definition exactly once, then reuse the local
        // type for both the start and the stop event.
        private var eventDefEmitted = false

        private fun writeEvent(timestampMs: Long, eventType: Int) {
            if (!eventDefEmitted) {
                out.write(0x40 or LOCAL_EVENT)
                out.write(0); out.write(0)
                writeU16(MSG_EVENT)
                // timestamp(253/u32), event(0/enum), event_type(1/enum),
                // event_group(4/u8).
                out.write(4)
                writeField(253, 4, BT_UINT32)
                writeField(0, 1, BT_ENUM)
                writeField(1, 1, BT_ENUM)
                writeField(4, 1, BT_UINT8)
                eventDefEmitted = true
            }
            out.write(LOCAL_EVENT)
            writeU32(toFitTimestamp(timestampMs))
            out.write(0)               // event = TIMER
            out.write(eventType)       // 0 = start, 4 = stop_all
            out.write(0)               // event_group
        }

        // --- record (global 20) --- //

        private fun writeRecordDefinition() {
            out.write(0x40 or LOCAL_RECORD)
            out.write(0); out.write(0)
            writeU16(MSG_RECORD)
            // Fields, in the order we'll write them per data row:
            //   253 timestamp     u32
            //     0 position_lat  s32 (semicircles)
            //     1 position_long s32 (semicircles)
            //     2 altitude      u16 (scale 5, offset 500 m)
            //     6 speed         u16 (scale 1000, m/s)
            //     5 distance      u32 (scale 100, m)  — left invalid here
            //     3 heart_rate    u8  (bpm)
            //     7 power         u16 (watts)
            out.write(8)
            writeField(253, 4, BT_UINT32)
            writeField(0, 4, BT_SINT32)
            writeField(1, 4, BT_SINT32)
            writeField(2, 2, BT_UINT16)
            writeField(6, 2, BT_UINT16)
            writeField(5, 4, BT_UINT32)
            writeField(3, 1, BT_UINT8)
            writeField(7, 2, BT_UINT16)
        }

        private fun writeRecord(p: TrailPoint) {
            out.write(LOCAL_RECORD)
            writeU32(if (p.timeMillis > 0) toFitTimestamp(p.timeMillis) else 0xFFFFFFFFL.toInt())
            writeS32(degreesToSemicircles(p.latDeg))
            writeS32(degreesToSemicircles(p.lonDeg))
            // Altitude: (m + 500) * 5; invalid 0xFFFF for null.
            val altRaw = p.eleMeters?.let {
                ((it + 500.0) * 5.0).toInt().coerceIn(0, 0xFFFE)
            } ?: 0xFFFF
            writeU16(altRaw)
            // Speed: m/s * 1000; invalid 0xFFFF.
            val spdRaw = p.speedMps?.let {
                (it * 1000f).toInt().coerceIn(0, 0xFFFE)
            } ?: 0xFFFF
            writeU16(spdRaw)
            // distance — left invalid; cumulative-distance would need a
            // running tally we don't compute here. Strava + Garmin
            // Connect both back-fill it from the position stream.
            writeU32(0xFFFFFFFFL.toInt())
            // Heart rate: u8, invalid 0xFF.
            out.write(p.heartRateBpm?.coerceIn(0, 254) ?: 0xFF)
            // Power: u16, invalid 0xFFFF.
            val powerRaw = p.powerWatts?.coerceIn(0, 0xFFFE) ?: 0xFFFF
            writeU16(powerRaw)
        }

        // --- lap (global 19) --- //

        private fun writeLap(trail: Trail, startMs: Long, endMs: Long) {
            out.write(0x40 or LOCAL_LAP)
            out.write(0); out.write(0)
            writeU16(MSG_LAP)
            // 253 timestamp u32, 2 start_time u32, 7 total_elapsed_time
            // u32 (ms-scaled by 1000), 9 total_distance u32 (cm), 25 sport enum.
            out.write(5)
            writeField(253, 4, BT_UINT32)
            writeField(2, 4, BT_UINT32)
            writeField(7, 4, BT_UINT32)
            writeField(9, 4, BT_UINT32)
            writeField(25, 1, BT_ENUM)
            out.write(LOCAL_LAP)
            writeU32(toFitTimestamp(endMs))
            writeU32(toFitTimestamp(startMs))
            writeU32(((endMs - startMs).coerceAtLeast(0L)).toInt()) // ms == seconds*1000
            writeU32(((trail.distanceMeters * 100.0).toLong().coerceIn(0L, 0xFFFFFFFFL)).toInt())
            out.write(sportFromTrail(trail))
        }

        // --- session (global 18) --- //

        private fun writeSession(trail: Trail, startMs: Long, endMs: Long) {
            out.write(0x40 or LOCAL_SESSION)
            out.write(0); out.write(0)
            writeU16(MSG_SESSION)
            out.write(6)
            writeField(253, 4, BT_UINT32)
            writeField(2, 4, BT_UINT32)
            writeField(7, 4, BT_UINT32)
            writeField(9, 4, BT_UINT32)
            writeField(5, 1, BT_ENUM)   // sport
            writeField(26, 2, BT_UINT16) // num_laps
            out.write(LOCAL_SESSION)
            writeU32(toFitTimestamp(endMs))
            writeU32(toFitTimestamp(startMs))
            writeU32(((endMs - startMs).coerceAtLeast(0L)).toInt())
            writeU32(((trail.distanceMeters * 100.0).toLong().coerceIn(0L, 0xFFFFFFFFL)).toInt())
            out.write(sportFromTrail(trail))
            writeU16(1) // num_laps
        }

        // --- activity (global 34) --- //

        private fun writeActivity(timestampMs: Long, startMs: Long, endMs: Long) {
            out.write(0x40 or LOCAL_ACTIVITY)
            out.write(0); out.write(0)
            writeU16(MSG_ACTIVITY)
            // 253 timestamp u32, 0 total_timer_time u32, 1 num_sessions u16,
            // 2 type enum, 3 event enum, 4 event_type enum.
            out.write(6)
            writeField(253, 4, BT_UINT32)
            writeField(0, 4, BT_UINT32)
            writeField(1, 2, BT_UINT16)
            writeField(2, 1, BT_ENUM)
            writeField(3, 1, BT_ENUM)
            writeField(4, 1, BT_ENUM)
            out.write(LOCAL_ACTIVITY)
            writeU32(toFitTimestamp(timestampMs))
            writeU32(((endMs - startMs).coerceAtLeast(0L)).toInt())
            writeU16(1)        // num_sessions
            out.write(0)       // type = MANUAL
            out.write(26)      // event = ACTIVITY
            out.write(1)       // event_type = STOP
        }

        // --- helpers --- //

        private fun writeField(num: Int, size: Int, baseType: Int) {
            out.write(num)
            out.write(size)
            out.write(baseType)
        }

        private fun writeU16(value: Int) {
            out.write(value and 0xFF)
            out.write((value shr 8) and 0xFF)
        }

        private fun writeU32(value: Int) {
            out.write(value and 0xFF)
            out.write((value shr 8) and 0xFF)
            out.write((value shr 16) and 0xFF)
            out.write((value shr 24) and 0xFF)
        }

        private fun writeS32(value: Int) = writeU32(value)

        private fun toFitTimestamp(unixMillis: Long): Int {
            val fitSecs = (unixMillis / 1000L) - FIT_EPOCH_OFFSET_SECS
            return fitSecs.toInt() // 32-bit truncation is the spec
        }

        private fun degreesToSemicircles(deg: Double): Int {
            // 2^31 / 180. Using Long math then truncating to Int keeps
            // values near the poles from overflowing the intermediate.
            val raw = (deg * 11930464.711111).toLong()
            return raw.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
        }
    }

    /**
     * Inspect a trail's tags for sport hints and map to the FIT sport
     * enum. Anything ambiguous falls through to "generic" — Strava and
     * Garmin Connect both default that to "Workout" in their import UI
     * which the user can correct in two taps.
     *
     * FIT sport enum values (subset):
     *   0 generic, 1 running, 2 cycling, 11 walking, 17 hiking,
     *   18 mountaineering, 41 sailing, 43 motorcycling.
     */
    private fun sportFromTrail(trail: Trail): Int {
        val tags = trail.tags.map { it.lowercase(Locale.ROOT) }
        for (t in tags) {
            when {
                t.contains("run") -> return 1
                t.contains("bike") || t.contains("cycl") || t.contains("ride") -> return 2
                t.contains("walk") -> return 11
                t.contains("hike") -> return 17
                t.contains("sail") -> return 41
                t.contains("moto") -> return 43
            }
        }
        return 0
    }

    @Suppress("unused")
    private fun u32Buffer(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
}
