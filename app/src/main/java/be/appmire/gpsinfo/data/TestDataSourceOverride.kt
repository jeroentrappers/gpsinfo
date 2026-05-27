package be.appmire.gpsinfo.data

/**
 * Override hook for instrumented tests / screenshot generation.
 *
 * In production every field stays null and [be.appmire.gpsinfo.ui.viewmodel.DashboardViewModel]
 * falls back to the real data sources. An androidTest can set any subset
 * BEFORE the Activity launches, and the factory will pick them up.
 *
 * Lives in `main` (not androidTest) so the factory can reference it
 * without conditional compilation — the only cost in production is three
 * null fields on a singleton.
 */
object TestDataSourceOverride {
    @Volatile var location: LocationDataSource? = null
    @Volatile var sensor: SensorDataSource? = null
    @Volatile var settings: SettingsDataSource? = null
    @Volatile var trails: TrailDataSource? = null

    fun clear() {
        location = null
        sensor = null
        settings = null
        trails = null
    }
}
