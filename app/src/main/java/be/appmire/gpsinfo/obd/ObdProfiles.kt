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
    // Flow control (ATFCSH/ATFCSD/ATFCSM) is required: the current DID
    // returns a multi-frame ISO-TP reply on the real ID.Buzz, and without
    // it only the first frame arrives (current → power can't decode).
    private fun bms(did: String) =
        "ATSP7;ATSH17FC007B;ATCRA17FE007B;ATFCSH17FC007B;ATFCSD300000;ATFCSM1;$did"
    private fun hvac(did: String) = "ATSP7;ATSH00000746;ATCRA000007B0;$did"

    // Byte extractors at an offset within the decoded payload (multi-frame
    // platforms pack many values into one long response).
    private fun u8(b: IntArray, i: Int): Int? = b.getOrNull(i)
    private fun s8(b: IntArray, i: Int): Int? = b.getOrNull(i)?.let { if (it >= 0x80) it - 0x100 else it }
    private fun u16(b: IntArray, i: Int): Int? {
        val h = b.getOrNull(i) ?: return null
        val l = b.getOrNull(i + 1) ?: return null
        return h * 256 + l
    }
    private fun s16(b: IntArray, i: Int): Int? = u16(b, i)?.let { if (it >= 0x8000) it - 0x10000 else it }
    private fun u32(b: IntArray, i: Int): Long? {
        if (b.size < i + 4) return null
        return b[i].toLong() * 0x1000000L + b[i + 1] * 0x10000L + b[i + 2] * 0x100L + b[i + 3]
    }
    private fun s32(b: IntArray, i: Int): Long? =
        u32(b, i)?.let { if (it >= 0x80000000L) it - 0x100000000L else it }

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
                    u16(b, 0)?.let { it / 4.0 }
                },
            ),
            // HV pack current (DID 1E3D, 4 bytes, (u32 − 150000) ÷ 100).
            // Per the sources: negative = discharge (driving), positive =
            // charge/regen.
            ProfileCommand(
                ObdRole.HV_CURRENT,
                ObdCommand("hv_a", "HV current", "A", bms("221E3D")) { b ->
                    u32(b, 0)?.let { (it - 150_000L) / 100.0 }
                },
            ),
            // HV battery (max cell) temperature. 222A0B returned NO DATA
            // on a real 2026 ID.Buzz, so use 221E0E (evDash "max battery
            // temp", 2 bytes ÷ 64) instead.
            ProfileCommand(
                ObdRole.HV_TEMP,
                ObdCommand("hv_t", "HV pack temp", "°C", bms("221E0E")) { b ->
                    u16(b, 0)?.let { it / 64.0 }
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

    // ── Hyundai / Kia (E-GMP: Ioniq 5/6, EV6/9, GV60; + Kona/Niro EV) ──
    // 11-bit addressing. The BMS (7E4→7EC) answers 220101 / 220105 with
    // long multi-frame ISO-TP responses (flow control required); values
    // live at fixed byte offsets within them. Outside temp comes from the
    // HVAC ECU (7B3→7BB), single-frame. DIDs/offsets corroborated across
    // JejuSoul, Esprit1st, evDash and OVMS. Power is derived V×I.
    private fun hkBms(did: String) =
        "ATSP6;ATSH7E4;ATCRA7EC;ATFCSH7E4;ATFCSD300000;ATFCSM1;$did"
    private fun hkHvac(did: String) = "ATSP6;ATSH7B3;ATCRA7BB;$did"

    val HYUNDAI_KIA: VehicleProfile = VehicleProfile(
        id = "hyundai_kia",
        displayName = "Hyundai / Kia EV",
        wmiPrefixes = listOf("KMH", "KNA", "KNE", "KME", "U5Y", "U6Y", "TMA", "LJ"),
        commands = listOf(
            // Display SoC: 220105 data byte 31 ÷ 2 (dashboard value).
            ProfileCommand(
                ObdRole.BATTERY_SOC,
                ObdCommand("soc", "Battery SoC", "%", hkBms("220105")) { b -> u8(b, 31)?.let { it / 2.0 } },
            ),
            // From 220101: voltage bytes 12-13 ÷10; current bytes 10-11
            // signed ÷10 (raw +=charge, −=discharge); temp byte 14 (int8).
            ProfileCommand(
                ObdRole.HV_VOLTAGE,
                ObdCommand("hv_v", "HV pack", "V", hkBms("220101")) { b -> u16(b, 12)?.let { it / 10.0 } },
            ),
            ProfileCommand(
                ObdRole.HV_CURRENT,
                ObdCommand("hv_a", "HV current", "A", hkBms("220101")) { b -> s16(b, 10)?.let { it / 10.0 } },
            ),
            ProfileCommand(
                ObdRole.HV_TEMP,
                ObdCommand("hv_t", "HV pack temp", "°C", hkBms("220101")) { b -> s8(b, 14)?.toDouble() },
            ),
            ProfileCommand(
                ObdRole.AMBIENT_TEMP,
                ObdCommand("ambient", "Outside temp", "°C", hkHvac("220100")) { b -> u8(b, 6)?.let { it / 2.0 - 40.0 } },
            ),
        ),
    )

    // ── BMW EVs ── D-CAN extended 11-bit addressing: tester 6F1, target
    // ECU via ATCEA (SME battery = 07 → resp 607; cluster KOM = 60 → 660).
    // DIDs/formulas from OVMS (i3) + OBDb. Two profiles: i3 (SoC DDBC) and
    // the G-platform i4/iX/iX1/iX3/i5/i7 (SoC E5CE); the rest of the SME
    // DIDs (voltage/current/temp) are shared. Power derived V×I; current
    // raw is −=discharge (no negation here → derive matches the dial).
    private fun bmwSme(did: String) =
        "ATSP6;ATSH6F1;ATCEA07;ATCRA607;ATFCSH6F1;ATFCSD300000;ATFCSM1;$did"
    private fun bmwKom(did: String) = "ATSP6;ATSH6F1;ATCEA60;ATCRA660;$did"

    private fun bmwSmeCommands(socDid: String, socDiv: Double) = listOf(
        ProfileCommand(
            ObdRole.BATTERY_SOC,
            ObdCommand("soc", "Battery SoC", "%", bmwSme(socDid)) { b -> u16(b, 0)?.let { it / socDiv } },
        ),
        ProfileCommand(
            ObdRole.HV_VOLTAGE,
            ObdCommand("hv_v", "HV pack", "V", bmwSme("22DD68")) { b -> u16(b, 0)?.let { it / 100.0 } },
        ),
        ProfileCommand(
            ObdRole.HV_CURRENT,
            ObdCommand("hv_a", "HV current", "A", bmwSme("22DD69")) { b -> s32(b, 0)?.let { it / 100.0 } },
        ),
        ProfileCommand(
            ObdRole.HV_TEMP,
            ObdCommand("hv_t", "HV pack temp", "°C", bmwSme("22DDC0")) { b -> s16(b, 2)?.let { it / 100.0 } },
        ),
        ProfileCommand(
            ObdRole.AMBIENT_TEMP,
            ObdCommand("ambient", "Outside temp", "°C", bmwKom("22D112")) { b -> u8(b, 0)?.let { it / 2.0 - 40.0 } },
        ),
    )

    val BMW_I3: VehicleProfile = VehicleProfile(
        id = "bmw_i3",
        displayName = "BMW i3",
        wmiPrefixes = listOf("WBY"),
        commands = bmwSmeCommands(socDid = "22DDBC", socDiv = 10.0),
    )

    val BMW_G: VehicleProfile = VehicleProfile(
        id = "bmw_g",
        displayName = "BMW iX / i4 / i5 / i7 / iX1 / iX3",
        wmiPrefixes = listOf("WBA", "WBX", "WBS", "5UX"),
        commands = bmwSmeCommands(socDid = "22E5CE", socDiv = 100.0),
    )

    val all: List<VehicleProfile> = listOf(GENERIC, VW_MEB, HYUNDAI_KIA, BMW_I3, BMW_G)

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
