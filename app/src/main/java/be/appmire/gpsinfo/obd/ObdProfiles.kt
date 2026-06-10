package be.appmire.gpsinfo.obd

/**
 * Registry of vehicle profiles. The generic profile maps the standard
 * mode-01 PIDs onto roles; make-specific profiles (currently the VW
 * MEB / ID.Buzz UDS set, ported from id.dash) add the EV reads that
 * standard PIDs can't give — SoC, instantaneous power, range.
 *
 * UDS DIDs are best-effort from public MEB reverse-engineering and can
 * shift across firmware; the probe verifies each against a live reply,
 * and a wrong/absent DID simply decodes to null (gauge reads "—").
 */
object ObdProfiles {

    val GENERIC: VehicleProfile = VehicleProfile(
        id = "generic",
        displayName = "Generic OBD2",
        wmiPrefixes = emptyList(),
        commands = buildList {
            // Standard PIDs that map cleanly onto a role.
            StandardPids.BY_PID[0x0D]?.let { add(ProfileCommand(ObdRole.SPEED, it)) }
            StandardPids.BY_PID[0x0C]?.let { add(ProfileCommand(ObdRole.RPM, it)) }
            StandardPids.BY_PID[0x05]?.let { add(ProfileCommand(ObdRole.COOLANT_TEMP, it)) }
            // Ambient/outside air temperature (PID 0146) — many cars expose
            // it; those that only have intake temp return NO DATA and drop.
            StandardPids.BY_PID[0x46]?.let { add(ProfileCommand(ObdRole.AMBIENT_TEMP, it)) }
            StandardPids.BY_PID[0x42]?.let { add(ProfileCommand(ObdRole.VOLTAGE_12V, it)) }
            // Hybrid battery remaining-life ≈ SoC on many hybrids/PHEVs.
            StandardPids.BY_PID[0x5B]?.let { add(ProfileCommand(ObdRole.BATTERY_SOC, it)) }
        },
    )

    // VW MEB (ID.Buzz / ID.3 / ID.4 …) UDS commands, ported from
    // id.dash. Each request carries its ECU header + response filter.
    private const val HDR_BMS = "ATSH7E5"
    private const val FLT_BMS = "ATCRA7ED"
    private const val HDR_MOTOR = "ATSH7E0"
    private const val FLT_MOTOR = "ATCRA7E8"

    private fun signed16(b: IntArray, i: Int): Int? {
        if (b.size < i + 2) return null
        var v = b[i] * 256 + b[i + 1]
        if (v >= 0x8000) v -= 0x10000
        return v
    }

    val VW_MEB: VehicleProfile = VehicleProfile(
        id = "vw_meb",
        displayName = "VW ID. (MEB)",
        // MEB cars built by VW / VWN; WMI alone is only a hint.
        wmiPrefixes = listOf("WVG", "WV1", "WV2", "WVW", "XL9"),
        commands = listOf(
            ProfileCommand(
                ObdRole.BATTERY_SOC,
                ObdCommand("soc", "Battery SoC", "%", "$HDR_BMS;$FLT_BMS;22028C") { b ->
                    b.getOrNull(0)?.let { it * 0.4 }
                },
            ),
            ProfileCommand(
                ObdRole.POWER_KW,
                ObdCommand("power", "Power", "kW", "$HDR_BMS;$FLT_BMS;221E32") { b ->
                    signed16(b, 0)?.let { it / 100.0 }
                },
            ),
            ProfileCommand(
                ObdRole.RANGE_KM,
                ObdCommand("range", "Range", "km", "$HDR_BMS;$FLT_BMS;2222D7") { b ->
                    if (b.size < 2) null else (b[0] * 256 + b[1]).toDouble()
                },
            ),
            ProfileCommand(
                ObdRole.HV_VOLTAGE,
                ObdCommand("hv_v", "HV pack", "V", "$HDR_BMS;$FLT_BMS;221E3B") { b ->
                    if (b.size < 2) null else (b[0] * 256 + b[1]) * 0.25
                },
            ),
            ProfileCommand(
                ObdRole.HV_CURRENT,
                ObdCommand("hv_a", "HV current", "A", "$HDR_BMS;$FLT_BMS;221E3D") { b ->
                    signed16(b, 0)?.let { it * 0.1 }
                },
            ),
            ProfileCommand(
                ObdRole.HV_TEMP,
                ObdCommand("hv_t", "HV pack temp", "°C", "$HDR_BMS;$FLT_BMS;221E0E") { b ->
                    b.getOrNull(0)?.let { it - 40.0 }
                },
            ),
            ProfileCommand(
                ObdRole.MOTOR_TEMP,
                ObdCommand("motor_t", "Motor inverter", "°C", "$HDR_MOTOR;$FLT_MOTOR;221E1B") { b ->
                    b.getOrNull(0)?.let { it - 40.0 }
                },
            ),
        ),
    )

    val all: List<VehicleProfile> = listOf(GENERIC, VW_MEB)

    /** Make-specific profiles only (skip generic) — what the probe tries
     *  for EV data after the standard PID sweep. */
    val makeProfiles: List<VehicleProfile> = all.filter { it.id != GENERIC.id }

    fun byId(id: String?): VehicleProfile = all.firstOrNull { it.id == id } ?: GENERIC

    /** VIN-WMI hint only — confirm by polling. Returns null when no
     *  make profile's WMI matches. */
    fun hintForVin(vin: String?): VehicleProfile? {
        val v = vin?.uppercase() ?: return null
        return makeProfiles.firstOrNull { p -> p.wmiPrefixes.any { v.startsWith(it) } }
    }
}
