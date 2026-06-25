package be.appmire.gpsinfo.car

import androidx.car.app.CarContext
import androidx.car.app.model.Distance
import androidx.car.app.navigation.model.Lane
import androidx.car.app.navigation.model.LaneDirection
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.Step
import be.appmire.gpsinfo.R
import be.appmire.gpsinfo.data.nav.TurnCommand
import be.appmire.gpsinfo.data.nav.TurnHint

/**
 * Single source of truth for turning a routing [TurnHint] into Car App
 * Library maneuver furniture. Shared by the on-screen [NavigationTemplate]
 * card ([TripDashboardScreen]) and the instrument-cluster [Trip]
 * ([ClusterNavReporter]) so the head-unit screen and the driver's cluster
 * always render the identical turn arrow, cue and countdown.
 */
internal object CarManeuvers {

    /** Map a [TurnHint] to a host-drawn [Maneuver] (the host renders the
     *  arrow from the type — no icons to ship). */
    fun maneuver(turn: TurnHint): Maneuver {
        val type = when (turn.command) {
            TurnCommand.TURN_LEFT -> Maneuver.TYPE_TURN_NORMAL_LEFT
            TurnCommand.TURN_SLIGHT_LEFT -> Maneuver.TYPE_TURN_SLIGHT_LEFT
            TurnCommand.TURN_SHARP_LEFT -> Maneuver.TYPE_TURN_SHARP_LEFT
            TurnCommand.TURN_RIGHT -> Maneuver.TYPE_TURN_NORMAL_RIGHT
            TurnCommand.TURN_SLIGHT_RIGHT -> Maneuver.TYPE_TURN_SLIGHT_RIGHT
            TurnCommand.TURN_SHARP_RIGHT -> Maneuver.TYPE_TURN_SHARP_RIGHT
            TurnCommand.KEEP_LEFT -> Maneuver.TYPE_KEEP_LEFT
            TurnCommand.KEEP_RIGHT -> Maneuver.TYPE_KEEP_RIGHT
            TurnCommand.U_TURN -> Maneuver.TYPE_U_TURN_LEFT
            // Right-hand traffic → counter-clockwise roundabouts.
            TurnCommand.ROUNDABOUT -> Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW
            TurnCommand.STRAIGHT, TurnCommand.OFF_ROUTE, TurnCommand.UNKNOWN ->
                Maneuver.TYPE_STRAIGHT
        }
        return Maneuver.Builder(type).apply {
            if (type == Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW && turn.exitNumber > 0) {
                setRoundaboutExitNumber(turn.exitNumber)
            }
        }.build()
    }

    /** A [Step] (maneuver + cue, plus lane guidance when the route carries
     *  it). The host draws the lanes image; the Lane metadata is also added
     *  for the cluster/HUD. Wrapped defensively — if the host rejects the
     *  lane data we fall back to a plain step rather than crash the template. */
    fun step(context: CarContext, turn: TurnHint): Step {
        val lanes = turn.lanes
        if (!lanes.isNullOrEmpty()) {
            runCatching {
                val b = Step.Builder()
                    .setManeuver(maneuver(turn))
                    .setCue(cueFor(context, turn))
                lanes.take(MAX_LANES).forEach { lane ->
                    val lb = Lane.Builder()
                    val dirs = lane.directions.ifEmpty { listOf(TurnCommand.STRAIGHT) }
                    dirs.forEach { lb.addDirection(LaneDirection.create(laneShape(it), lane.active)) }
                    b.addLane(lb.build())
                }
                CarLaneImage.render(context, lanes)?.let { b.setLanesImage(it) }
                return b.build()
            }
        }
        return Step.Builder()
            .setManeuver(maneuver(turn))
            .setCue(cueFor(context, turn))
            .build()
    }

    /** Cap on lanes drawn — the host bounds the count and image width. */
    private const val MAX_LANES = 8

    /** App [TurnCommand] → Car App Library [LaneDirection] shape. */
    private fun laneShape(c: TurnCommand): Int = when (c) {
        TurnCommand.STRAIGHT -> LaneDirection.SHAPE_STRAIGHT
        TurnCommand.TURN_LEFT -> LaneDirection.SHAPE_NORMAL_LEFT
        TurnCommand.TURN_SLIGHT_LEFT, TurnCommand.KEEP_LEFT -> LaneDirection.SHAPE_SLIGHT_LEFT
        TurnCommand.TURN_SHARP_LEFT -> LaneDirection.SHAPE_SHARP_LEFT
        TurnCommand.TURN_RIGHT -> LaneDirection.SHAPE_NORMAL_RIGHT
        TurnCommand.TURN_SLIGHT_RIGHT, TurnCommand.KEEP_RIGHT -> LaneDirection.SHAPE_SLIGHT_RIGHT
        TurnCommand.TURN_SHARP_RIGHT -> LaneDirection.SHAPE_SHARP_RIGHT
        TurnCommand.U_TURN -> LaneDirection.SHAPE_U_TURN_LEFT
        else -> LaneDirection.SHAPE_UNKNOWN
    }

    fun cueFor(context: CarContext, turn: TurnHint): String = when (turn.command) {
        TurnCommand.TURN_LEFT -> context.getString(R.string.car_nav_turn_left)
        TurnCommand.TURN_SLIGHT_LEFT -> context.getString(R.string.car_nav_slight_left)
        TurnCommand.TURN_SHARP_LEFT -> context.getString(R.string.car_nav_sharp_left)
        TurnCommand.TURN_RIGHT -> context.getString(R.string.car_nav_turn_right)
        TurnCommand.TURN_SLIGHT_RIGHT -> context.getString(R.string.car_nav_slight_right)
        TurnCommand.TURN_SHARP_RIGHT -> context.getString(R.string.car_nav_sharp_right)
        TurnCommand.KEEP_LEFT -> context.getString(R.string.car_nav_keep_left)
        TurnCommand.KEEP_RIGHT -> context.getString(R.string.car_nav_keep_right)
        TurnCommand.U_TURN -> context.getString(R.string.car_nav_u_turn)
        TurnCommand.ROUNDABOUT ->
            if (turn.exitNumber > 0)
                context.getString(R.string.car_nav_roundabout_exit, turn.exitNumber)
            else context.getString(R.string.car_nav_roundabout)
        else -> context.getString(R.string.car_nav_continue)
    }

    /** Round to a glanceable unit: km above 1 km, else metres in 10 m steps. */
    fun carDistance(meters: Double): Distance =
        if (meters >= 1000) {
            Distance.create(meters / 1000.0, Distance.UNIT_KILOMETERS)
        } else {
            Distance.create((meters / 10).toInt() * 10.0, Distance.UNIT_METERS)
        }
}
