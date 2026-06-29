package be.appmire.gpsinfo.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import be.appmire.gpsinfo.R
import be.appmire.gpsinfo.obd.ObdLiveController

/**
 * Asked after [ObdLiveController] has retried the OBD link for five minutes
 * without success: keep trying, or give up. Retrying is paused while this is
 * up, so a dead car can't make us hammer Bluetooth forever.
 *
 * Dismissing without choosing (host back) defaults to giving up — the safer
 * default than leaving the feed paused with no visible prompt. [onResolved]
 * lets the owner clear its "prompt shown" guard so a later failure can ask
 * again.
 */
class ObdReconnectScreen(
    carContext: CarContext,
    private val onResolved: () -> Unit,
) : Screen(carContext), DefaultLifecycleObserver {

    private var resolved = false

    init {
        lifecycle.addObserver(this)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        // Back / host dismissal without a choice → give up (stop hammering BT).
        if (!resolved) {
            resolved = true
            ObdLiveController.giveUp()
            onResolved()
        }
    }

    override fun onGetTemplate(): Template {
        val keepTrying = Action.Builder()
            .setTitle(carContext.getString(R.string.car_obd_keep_trying))
            .setOnClickListener { resolve { ObdLiveController.continueReconnecting() } }
            .build()
        val giveUp = Action.Builder()
            .setTitle(carContext.getString(R.string.car_obd_give_up))
            .setOnClickListener { resolve { ObdLiveController.giveUp() } }
            .build()
        return MessageTemplate.Builder(carContext.getString(R.string.car_obd_lost_body))
            .setTitle(carContext.getString(R.string.car_obd_lost_title))
            .setHeaderAction(Action.APP_ICON)
            .addAction(keepTrying)
            .addAction(giveUp)
            .build()
    }

    private fun resolve(action: () -> Unit) {
        resolved = true
        action()
        onResolved()
        screenManager.pop()
    }
}
