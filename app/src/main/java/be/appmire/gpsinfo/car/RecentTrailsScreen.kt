package be.appmire.gpsinfo.car

import android.text.SpannableString
import android.text.style.RelativeSizeSpan
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import be.appmire.gpsinfo.R
import be.appmire.gpsinfo.data.TrailRepository
import be.appmire.gpsinfo.data.TrailSummary
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Read-only browser of the user's recorded trails on Android Auto.
 *
 * Parked-restricted: each row is informational (no on-click action),
 * so the host won't gate the screen behind a "stop the car first"
 * banner. A driving user just sees the same rows; we don't surface
 * destructive operations here.
 *
 * Capped at [ConstraintManager.CONTENT_LIMIT_TYPE_LIST] rows so larger
 * libraries don't blow the host's render budget — we sort by
 * date-descending so the most recent trails are always shown.
 */
class RecentTrailsScreen(carContext: CarContext) : Screen(carContext), DefaultLifecycleObserver {

    private var summaries: List<TrailSummary> = emptyList()
    private var collectJob: Job? = null

    init {
        lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        val repo = TrailRepository(carContext.applicationContext)
        collectJob = repo.trails
            .onEach {
                summaries = it
                invalidate()
            }
            .launchIn(owner.lifecycleScope)
    }

    override fun onStop(owner: LifecycleOwner) {
        collectJob?.cancel()
        collectJob = null
    }

    override fun onGetTemplate(): Template {
        val limit = carContext
            .getCarService(ConstraintManager::class.java)
            .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
            .coerceAtLeast(1)
        val rows = summaries.take(limit).map { trailRow(it) }
        val itemList = ItemList.Builder().apply {
            if (rows.isEmpty()) {
                setNoItemsMessage(carContext.getString(R.string.car_recent_empty))
            } else {
                rows.forEach { addItem(it) }
            }
        }.build()
        return ListTemplate.Builder()
            .setTitle(carContext.getString(R.string.car_recent_title))
            .setHeaderAction(Action.BACK)
            .setSingleList(itemList)
            .build()
    }

    private fun trailRow(t: TrailSummary): Row {
        val dist = if (t.distanceMeters < 1_000.0) {
            "%d m".format(Locale.ROOT, t.distanceMeters.toInt())
        } else {
            "%.2f km".format(Locale.ROOT, t.distanceMeters / 1_000.0)
        }
        val dur = formatDuration(t.durationMillis)
        val date = t.startTimeMillis?.let {
            DateFormat.getDateInstance(DateFormat.SHORT).format(Date(it))
        } ?: "—"
        val secondary = SpannableString("$dist · $dur · $date").apply {
            setSpan(RelativeSizeSpan(0.9f), 0, length, 0)
        }
        return Row.Builder()
            .setTitle(t.name)
            .addText(secondary)
            .build()
    }

    private fun formatDuration(ms: Long): String {
        if (ms <= 0L) return "—"
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        return if (hours > 0) "%dh %02dm".format(Locale.ROOT, hours, minutes)
        else "%dm".format(Locale.ROOT, minutes)
    }
}
