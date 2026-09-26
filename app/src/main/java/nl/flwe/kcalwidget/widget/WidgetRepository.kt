package nl.flwe.kcalwidget.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.glance.state.PreferencesGlanceStateDefinition
import kotlinx.coroutines.flow.first
import nl.flwe.kcalwidget.data.BaselineRepository
import nl.flwe.kcalwidget.data.DayEnergy
import nl.flwe.kcalwidget.data.Energetics
import nl.flwe.kcalwidget.data.HealthAvailability
import nl.flwe.kcalwidget.data.HealthRepository
import nl.flwe.kcalwidget.data.history.History
import nl.flwe.kcalwidget.data.history.HistoryRepository
import nl.flwe.kcalwidget.data.settings.AppSettings
import nl.flwe.kcalwidget.data.settings.SettingsRepository

/**
 * Computes today's balance and pushes it into every widget instance of every kind.
 *
 * No widget touches Health Connect: they render the cache written here, so a slow or
 * permission-denied read can never stall the launcher.
 */
object WidgetRepository {

    /** Every widget kind, so a refresh reaches all of them rather than only the default. */
    private val widgetKinds: List<Pair<Class<out GlanceAppWidget>, () -> GlanceAppWidget>> =
        listOf(
            KcalWidget::class.java to { KcalWidget() },
            MinimalWidget::class.java to { MinimalWidget() },
            DetailedWidget::class.java to { DetailedWidget() },
            TrendWidget::class.java to { TrendWidget() },
        )

    /** Days of history drawn on the trend widget. */
    private const val TREND_DAYS = 14

    /**
     * @param minAgeMs skip the read when every widget's cache is younger than this. Unlock
     *   fires this on every unlock, and a lock/unlock flurry does not need a read each time.
     */
    suspend fun refresh(context: Context, minAgeMs: Long = 0L) {
        val manager = GlanceAppWidgetManager(context)
        val idsByKind = widgetKinds.associate { (clazz, _) -> clazz to manager.getGlanceIds(clazz) }
        val allIds = idsByKind.values.flatten()
        if (allIds.isEmpty()) return
        if (minAgeMs > 0L && !isStale(context, allIds, minAgeMs)) return

        val health = HealthRepository(context)
        val settings = SettingsRepository(context).settings.first()

        val status: WidgetStatus
        var energy: DayEnergy? = null
        var history: History? = null
        when {
            health.availability() != HealthAvailability.AVAILABLE -> {
                status = WidgetStatus.NO_HEALTH_CONNECT
            }

            !health.hasRequiredPermissions() -> {
                status = WidgetStatus.NEEDS_PERMISSION
            }

            else -> {
                // Profile-based BMR is good enough to size the baseline: it only gates the
                // "was the tracker worn" filter and the no-history fallback level.
                val bmr = Energetics.bmrMifflinStJeor(settings.body, settings.body.fallbackWeightKg)
                val baseline = runCatching {
                    BaselineRepository(context).get(health, settings, bmr)
                }.getOrNull()

                // History is only read when something on screen needs it: the trend widget
                // draws it, and banking needs the carried balance. It is a much heavier
                // query than the day itself, and widgets refresh often.
                val needsTrend = idsByKind[TrendWidget::class.java]?.isNotEmpty() == true
                val days = if (needsTrend) TREND_DAYS else HistoryRepository.BANKING_DAYS + 1
                if (needsTrend || settings.goal.useWeeklyBanking) {
                    history = runCatching {
                        HistoryRepository(context, health).load(settings, days)
                    }.getOrNull()
                }

                energy = runCatching {
                    health.todayEnergy(settings, baseline, history?.bankedAdjustmentKcal ?: 0.0)
                }.getOrNull()
                status = if (energy == null) WidgetStatus.ERROR else WidgetStatus.OK
            }
        }

        allIds.forEach { id ->
            updateAppWidgetState(context, id) { prefs ->
                prefs[WidgetKeys.STATUS] = status.name
                prefs[WidgetKeys.UPDATED_AT] = System.currentTimeMillis()
                if (energy != null) {
                    prefs[WidgetKeys.INTAKE] = energy.intakeKcal
                    prefs[WidgetKeys.RAW_INTAKE] = energy.rawIntakeKcal
                    prefs[WidgetKeys.BURNED_SO_FAR] = energy.burnedSoFarKcal
                    prefs[WidgetKeys.PROJECTED_BURN] = energy.projectedBurnKcal
                    prefs[WidgetKeys.TYPICAL_DAY] = energy.typicalDayKcal
                    prefs[WidgetKeys.CONFIDENCE] = energy.confidence
                    prefs[WidgetKeys.BUDGET] = energy.budgetKcal
                    prefs[WidgetKeys.REMAINING] = energy.remainingKcal
                    prefs[WidgetKeys.MOVE_KCAL] = energy.moveKcalToClear
                    prefs[WidgetKeys.WALK_MINUTES] = energy.walkMinutesToClear
                    prefs[WidgetKeys.BURN_SOURCE] = energy.source.name
                    prefs[WidgetKeys.HAS_NUTRITION] = energy.hasNutritionData
                    prefs[WidgetKeys.CALIBRATION_APPLIED] = energy.calibrationApplied
                    prefs[WidgetKeys.GOAL_DELTA] = energy.goalDeltaKcal
                    prefs[WidgetKeys.WEIGHT_KG] = energy.weightKg
                    prefs[WidgetKeys.WEIGH_IN_DUE] = weighInDue(energy.daysSinceWeighIn, settings)
                    energy.daysSinceWeighIn?.let { prefs[WidgetKeys.DAYS_SINCE_WEIGH_IN] = it }
                }
                history?.let { h ->
                    h.trend.currentTrendKg?.let { prefs[WidgetKeys.TREND_WEIGHT_KG] = it }
                    h.trend.weeklyChangeKg?.let { prefs[WidgetKeys.WEEKLY_TREND_KG] = it }
                    prefs[WidgetKeys.NET_SERIES] = encodeSeries(
                        h.rows.takeLast(TREND_DAYS).mapNotNull { it.netKcal }
                    )
                    prefs[WidgetKeys.WEIGHT_SERIES] = encodeSeries(
                        h.trend.points.takeLast(TREND_DAYS * 2).map { it.trendKg },
                        decimals = 2,
                    )
                }
            }
        }

        widgetKinds.forEach { (_, factory) -> factory().updateAll(context) }
    }

    /**
     * Whether to nudge for a weigh-in. Off entirely when the user has turned the nudge off,
     * and never before the configured interval has actually elapsed.
     */
    internal fun weighInDue(daysSinceWeighIn: Int?, settings: AppSettings): Boolean {
        if (!settings.features.widgetWeighInNudge) return false
        val days = daysSinceWeighIn ?: return true
        return days >= settings.features.weighInIntervalDays
    }

    private suspend fun isStale(context: Context, ids: List<GlanceId>, minAgeMs: Long): Boolean {
        val newest = ids.maxOfOrNull { id ->
            getAppWidgetState(context, PreferencesGlanceStateDefinition, id)[WidgetKeys.UPDATED_AT]
                ?: 0L
        } ?: 0L
        return System.currentTimeMillis() - newest >= minAgeMs
    }

}
