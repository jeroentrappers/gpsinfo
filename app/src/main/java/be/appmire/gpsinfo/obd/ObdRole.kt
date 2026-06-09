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
enum class ObdRole(val label: String, val unit: String, val energyDial: Boolean = false) {
    SPEED("Speed", "km/h"),
    RPM("Motor RPM", "rpm"),
    COOLANT_TEMP("Coolant", "°C"),
    VOLTAGE_12V("12V battery", "V"),
    POWER_KW("Power", "kW", energyDial = true),
    BATTERY_SOC("Battery SoC", "%", energyDial = true),
    RANGE_KM("Range", "km", energyDial = true),
    HV_VOLTAGE("HV pack", "V"),
    HV_CURRENT("HV current", "A"),
    HV_TEMP("HV pack temp", "°C"),
    MOTOR_TEMP("Motor inverter", "°C"),
}
