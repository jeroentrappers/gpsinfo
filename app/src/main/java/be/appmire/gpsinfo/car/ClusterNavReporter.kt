package be.appmire.gpsinfo.car

import androidx.car.app.CarContext
import androidx.car.app.model.DateTimeWithZone
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.Destination
import androidx.car.app.navigation.model.Trip
import androidx.car.app.navigation.model.TravelEstimate
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.coroutineScope
import be.appmire.gpsinfo.R
import be.appmire.gpsinfo.data.nav.NavigationController
import java.util.TimeZone
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Pushes [NavigationController] guidance to the car's instrument cluster
 * (and any head-unit nav widget) through [NavigationManager.updateTrip].
 *
 * The [NavigationTemplate] card painted by [TripDashboardScreen] only
 * reaches the projected screen — the driver's cluster display is fed
 * exclusively by the [Trip] object handed to the host here. Without this
 * bridge the cluster stays blank during turn-by-turn (the exact gap the
 * Play review flagged: "no next-turn information in the cluster display").
 *
 * Owned by [TripDashboardSession] so it spans screen pushes/pops and
 * matches the host's one-active-nav-app contract: when another app (or
 * the host) reclaims the cluster, [NavigationManagerCallback.onStopNavigation]
 * fires and we stand down. The started/ended calls are strictly balanced —
 * the host rejects an [updateTrip] outside a started session, and a second
 * `navigationStarted()` without an intervening `navigationEnded()`.
 */
class ClusterNavReporter(
    private val carContext: CarContext,
    lifecycle: Lifecycle,
) {

    /** Null on hosts without navigation support — the cluster simply
     *  stays blank and the rest of the app is unaffected. */
    private val navManager: NavigationManager? = runCatching {
        carContext.getCarService(NavigationManager::class.java)
    }.getOrNull()

    /** True between navigationStarted() and navigationEnded(): updateTrip
     *  is only legal inside this window and the calls must balance. */
    private var active = false

    init {
        navManager?.setNavigationManagerCallback(object : NavigationManagerCallback {
            override fun onStopNavigation() {
                // The host (or another nav app) took the cluster. Stand
                // down WITHOUT echoing navigationEnded() — the host already
                // ended the session — then stop our own guidance.
                active = false
                NavigationController.stop()
            }

            override fun onAutoDriveEnabled() = Unit
        })
        NavigationController.state
            .onEach(::report)
            .launchIn(lifecycle.coroutineScope)
    }

    private fun report(state: NavigationController.NavState) {
        val mgr = navManager ?: return
        // Never crash the session over a cluster update: the host can
        // reject calls if it isn't connected or we've lost nav focus.
        runCatching {
            when (state) {
                // Claim the cluster while the route computes; the loading
                // Trip and the turn steps follow once we're navigating.
                is NavigationController.NavState.Preparing -> ensureStarted(mgr)
                is NavigationController.NavState.Navigating -> {
                    ensureStarted(mgr)
                    mgr.updateTrip(buildTrip(state))
                }
                // Idle / Arrived / Failed → release the cluster.
                else -> end(mgr)
            }
        }
    }

    private fun ensureStarted(mgr: NavigationManager) {
        if (!active) {
            mgr.navigationStarted()
            active = true
        }
    }

    private fun end(mgr: NavigationManager) {
        if (active) {
            mgr.navigationEnded()
            active = false
        }
    }

    /** Build the cluster [Trip]: the next turn as a [androidx.car.app.navigation.model.Step]
     *  (arrow + cue + distance to it) plus the destination with its ETA. */
    private fun buildTrip(n: NavigationController.NavState.Navigating): Trip {
        val builder = Trip.Builder()
        n.nextTurn?.let { turn ->
            builder.addStep(CarManeuvers.step(carContext, turn), stepEstimate(n))
        }
        builder.addDestination(
            Destination.Builder()
                .setName(n.destName ?: carContext.getString(R.string.car_nav_destination))
                .build(),
            destinationEstimate(n),
        )
        return builder.build()
    }

    /** Remaining distance + arrival time to the destination. */
    private fun destinationEstimate(n: NavigationController.NavState.Navigating): TravelEstimate {
        val arrivalMillis = System.currentTimeMillis() + n.etaSeconds * 1000L
        return TravelEstimate.Builder(
            CarManeuvers.carDistance(n.distanceRemainingM),
            DateTimeWithZone.create(arrivalMillis, TimeZone.getDefault()),
        )
            .setRemainingTimeSeconds(n.etaSeconds.toLong())
            .build()
    }

    /** Distance + (estimated) time to the next turn. Time to the turn is
     *  the destination ETA scaled by the share of the route left to it —
     *  good enough for the cluster's "in X" readout without a per-step
     *  timing model. */
    private fun stepEstimate(n: NavigationController.NavState.Navigating): TravelEstimate {
        val frac = if (n.distanceRemainingM > 0) {
            (n.distanceToTurnM / n.distanceRemainingM).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        val turnSeconds = (n.etaSeconds * frac).toLong()
        val arrivalMillis = System.currentTimeMillis() + turnSeconds * 1000L
        return TravelEstimate.Builder(
            CarManeuvers.carDistance(n.distanceToTurnM),
            DateTimeWithZone.create(arrivalMillis, TimeZone.getDefault()),
        )
            .setRemainingTimeSeconds(turnSeconds)
            .build()
    }
}
