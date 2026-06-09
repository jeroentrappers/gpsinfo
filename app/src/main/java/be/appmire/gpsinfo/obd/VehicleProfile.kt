package be.appmire.gpsinfo.obd

/** A command a profile offers, tagged with the app role it can fill
 *  (null = informational only — surfaced in the Lab, not auto-mapped). */
data class ProfileCommand(val role: ObdRole?, val command: ObdCommand)

/**
 * A vehicle/platform profile: the set of reads that get useful data out
 * of a given family of cars. The [GENERIC][ObdProfiles.GENERIC] profile
 * is just the standard J1979 mode-01 PIDs; make-specific profiles add
 * UDS (mode 22) commands with the right `ATSH` header per ECU.
 *
 * [wmiPrefixes] gives a VIN-based hint, but matching is confirmed by
 * actually polling the profile's commands during the probe — a VIN WMI
 * can't tell an ID.Buzz from a Golf, but a live SoC reply can.
 */
data class VehicleProfile(
    val id: String,
    val displayName: String,
    val wmiPrefixes: List<String>,
    val commands: List<ProfileCommand>,
) {
    /** Commands that fill a concrete [ObdRole], newest-first by role. */
    val roleCommands: List<ProfileCommand> get() = commands.filter { it.role != null }
}
