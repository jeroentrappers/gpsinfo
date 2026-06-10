package be.appmire.gpsinfo.obd

/**
 * An app-facing input an OBD reading can be wired to. The probe + the
 * confirm step map a concrete [ObdCommand] (standard PID or a per-make
 * UDS command) onto each role; the live source then polls the mapped
 * command and feeds the matching consumer.
 *
 * [POWER_KW], [BATTERY_SOC] and [RANGE_KM] are what the car energy dial
 * consumes today (CarMapRenderer.updatePower / updateEnergy); the rest
 * are surfaced in the OBD Lab and available for future readouts.
 */
enum class ObdRole(
    val label: String,
    val unit: String,
    val energyDial: Boolean = false,
    /** How often the live feed should refresh this — the power gauge
     *  needle needs FAST; charge/range drift slowly; temps barely move. */
    val tier: PollTier = PollTier.VERY_SLOW,
) {
    SPEED("Speed", "km/h", tier = PollTier.VERY_SLOW),
    RPM("Motor RPM", "rpm", tier = PollTier.VERY_SLOW),
    COOLANT_TEMP("Coolant", "°C"),
    AMBIENT_TEMP("Outside temp", "°C"),
    VOLTAGE_12V("12V battery", "V"),
    POWER_KW("Power", "kW", energyDial = true, tier = PollTier.FAST),
    BATTERY_SOC("Battery SoC", "%", energyDial = true, tier = PollTier.SLOW),
    RANGE_KM("Range", "km", energyDial = true, tier = PollTier.SLOW),
    // FAST because MEB power is derived V×I — both terms need to be fresh.
    HV_VOLTAGE("HV pack", "V", tier = PollTier.FAST),
    HV_CURRENT("HV current", "A", tier = PollTier.FAST),
    HV_TEMP("HV pack temp", "°C"),
    MOTOR_TEMP("Motor inverter", "°C"),
}

/** Refresh tier for the live poller. */
enum class PollTier { FAST, SLOW, VERY_SLOW }
