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

    // VW MEB (ID.3/4/Buzz, Škoda Enyaq, Cupra Born, Audi Q4 e-tron).
    // 29-bit extended UDS addressing (ISO 15765-4 → ATSP7): the BMS sits
    // at request 0x17FC007B / response 0x17FE007B, the HVAC ECU at
    // 0x00000746 / 0x000007B0. DIDs + formulas are from community
    // reverse-engineering, cross-checked across two independent sources
    // (nickn17/evDash CarVWID3 and spot2000 MEB CAN parameters). Each
    // request forces 29-bit mode + the ECU header; ObdManager's header
    // reuse sends that prefix once per ECU, then just the DID.
    //
    // Instantaneous power is NOT a DID on MEB — it's derived V×I by the
    // live feed. Remaining range and motor/inverter temp aren't reliably
    // documented, so they're intentionally omitted rather than guessed.
    private fun bms(did: String) = "ATSP7;ATSH17FC007B;ATCRA17FE007B;$did"
    private fun hvac(did: String) = "ATSP7;ATSH00000746;ATCRA000007B0;$did"

    private fun u16(b: IntArray): Int? = if (b.size < 2) null else b[0] * 256 + b[1]
    private fun u32(b: IntArray): Long? =
        if (b.size < 4) null
        else b[0].toLong() * 0x1000000L + b[1] * 0x10000L + b[2] * 0x100L + b[3]

    val VW_MEB: VehicleProfile = VehicleProfile(
        id = "vw_meb",
        displayName = "VW ID. (MEB)",
        // MEB cars built by VW / VWN; WMI alone is only a hint — the
        // probe confirms by reading a live SoC.
        wmiPrefixes = listOf("WVG", "WV1", "WV2", "WVW", "XL9"),
        commands = listOf(
            // BMS State of Charge (DID 028C, 1 byte ÷ 2.5). This is the
            // raw BMS value; the dash HMI shows ≈ soc×51/46 − 6.4.
            ProfileCommand(
                ObdRole.BATTERY_SOC,
                ObdCommand("soc", "Battery SoC", "%", bms("22028C")) { b ->
                    b.getOrNull(0)?.let { it / 2.5 }
                },
            ),
            // HV pack voltage (DID 1E3B, 2 bytes ÷ 4).
            ProfileCommand(
                ObdRole.HV_VOLTAGE,
                ObdCommand("hv_v", "HV pack", "V", bms("221E3B")) { b ->
                    u16(b)?.let { it / 4.0 }
                },
            ),
            // HV pack current (DID 1E3D, 4 bytes, (u32 − 150000) ÷ 100).
            // Per the sources: negative = discharge (driving), positive =
            // charge/regen.
            ProfileCommand(
                ObdRole.HV_CURRENT,
                ObdCommand("hv_a", "HV current", "A", bms("221E3D")) { b ->
                    u32(b)?.let { (it - 150_000L) / 100.0 }
                },
            ),
            // Main HV battery temperature (DID 2A0B, 1 byte ÷ 2 − 40).
            ProfileCommand(
                ObdRole.HV_TEMP,
                ObdCommand("hv_t", "HV pack temp", "°C", bms("222A0B")) { b ->
                    b.getOrNull(0)?.let { it / 2.0 - 40.0 }
                },
            ),
            // Outside/ambient temp from the HVAC ECU (DID 2609, 1 byte ÷
            // 2 − 50) — more reliable on a BEV than standard PID 0146.
            ProfileCommand(
                ObdRole.AMBIENT_TEMP,
                ObdCommand("ambient", "Outside temp", "°C", hvac("222609")) { b ->
                    b.getOrNull(0)?.let { it / 2.0 - 50.0 }
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
