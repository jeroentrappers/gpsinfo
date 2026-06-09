package be.appmire.gpsinfo.obd

/**
 * Initialisation sequence for an ELM327 v1.5/v2.1 adapter.
 *
 * Order matters: reset first, then echo/linefeeds off so replies parse
 * cleanly. `ATSP0` lets the adapter auto-detect the protocol; `ATCAF1`
 * turns on CAN auto-formatting so multi-frame ISO-TP replies are
 * reassembled for us. The trailing `0100` wakes the bus and triggers
 * protocol detection.
 *
 * Headers stay off (ATH0) for standard mode-01 reads; a per-make UDS
 * profile can re-enable them ad-hoc with an ATSH prefix in its request.
 */
object ElmInit {
    val SEQUENCE: List<String> = listOf(
        "ATZ", // full reset (slow; needed after a power cycle)
        "ATE0", // echo off
        "ATL0", // linefeeds off
        "ATS0", // spaces off (less bandwidth + parsing)
        "ATH0", // headers off
        "ATAT1", // adaptive timing on
        "ATST32", // ~200 ms response timeout
        "ATSP0", // automatic protocol detection
        "ATCAF1", // CAN auto-format on
        "0100", // wake the bus + trigger protocol detection
    )
}
