package be.appmire.gpsinfo.data

import be.appmire.gpsinfo.data.model.HeartRateReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeartRateRepositoryTest {

    // Test vectors built by hand from the Bluetooth SIG Heart Rate
    // Measurement spec (Service 0x180D / Characteristic 0x2A37). Flags
    // byte first, then HR value, then optional fields.

    @Test fun parses_uint8_with_no_extras() {
        // flags=0x00 (uint8 HR, no extras), HR=72
        val data = byteArrayOf(0x00, 72)
        val r = HeartRateRepository.parseHeartRateMeasurement(data)!!
        assertEquals(72, r.bpm)
        assertEquals(HeartRateReading.SensorContact.NotSupported, r.sensorContact)
        assertEquals(emptyList<Int>(), r.rrIntervalsMs)
    }

    @Test fun parses_uint16_value() {
        // flags=0x01 (uint16), HR=280 (0x118 = 0x18, 0x01 little-endian).
        // Picked to exceed the uint8 range so we know the 16-bit path is
        // exercised, while staying inside the sanity range (0..400 BPM).
        val data = byteArrayOf(0x01, 0x18, 0x01)
        val r = HeartRateRepository.parseHeartRateMeasurement(data)!!
        assertEquals(280, r.bpm)
    }

    @Test fun parses_sensor_contact_in_contact() {
        // flags bits 1-2 = 0b10 → sensor in contact. Bit pattern: 0b100 = 0x04
        val data = byteArrayOf(0x04, 80)
        val r = HeartRateRepository.parseHeartRateMeasurement(data)!!
        assertEquals(HeartRateReading.SensorContact.InContact, r.sensorContact)
    }

    @Test fun parses_sensor_contact_not_in_contact() {
        // flags bits 1-2 = 0b11 → sensor not in contact. Bit pattern: 0b110 = 0x06
        val data = byteArrayOf(0x06, 80)
        val r = HeartRateRepository.parseHeartRateMeasurement(data)!!
        assertEquals(HeartRateReading.SensorContact.NotInContact, r.sensorContact)
    }

    @Test fun parses_rr_intervals() {
        // flags=0x10 (RR present, uint8 HR), HR=80, RR=1024 (1.000s) and 512 (0.500s).
        // 1024 = 0x400 → little-endian bytes 0x00, 0x04
        // 512  = 0x200 → little-endian bytes 0x00, 0x02
        val data = byteArrayOf(0x10, 80, 0x00, 0x04, 0x00, 0x02)
        val r = HeartRateRepository.parseHeartRateMeasurement(data)!!
        assertEquals(80, r.bpm)
        // Stored in milliseconds; raw 1024 → 1000ms; raw 512 → 500ms.
        assertEquals(listOf(1000, 500), r.rrIntervalsMs)
    }

    @Test fun skips_energy_expended_field() {
        // flags=0x08 (energy-expended present, uint8 HR), HR=70,
        // energy=300 kJ → little-endian uint16: 0x012C → 0x2C, 0x01
        val data = byteArrayOf(0x08, 70, 0x2C, 0x01)
        val r = HeartRateRepository.parseHeartRateMeasurement(data)!!
        assertEquals(70, r.bpm)
    }

    @Test fun rejects_empty_payload() {
        assertNull(HeartRateRepository.parseHeartRateMeasurement(byteArrayOf()))
    }

    @Test fun rejects_truncated_uint16() {
        // Claims uint16 but only one byte follows.
        assertNull(HeartRateRepository.parseHeartRateMeasurement(byteArrayOf(0x01, 0x08)))
    }

    @Test fun rejects_out_of_range_bpm() {
        // 0xFF 0xFF = 65535 — outside [0, 400] sanity range.
        assertNull(HeartRateRepository.parseHeartRateMeasurement(
            byteArrayOf(0x01, 0xFF.toByte(), 0xFF.toByte())
        ))
    }
}
