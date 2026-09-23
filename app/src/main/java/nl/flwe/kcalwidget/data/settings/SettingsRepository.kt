package nl.flwe.kcalwidget.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore("kcal_settings")

/**
 * Reads and writes [AppSettings]. Every field is stored as its own primitive key so a
 * setting added later reads back as its default rather than failing to parse.
 */
class SettingsRepository(private val context: Context) {

    val settings: Flow<AppSettings> = context.settingsStore.data.map { it.toSettings() }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.settingsStore.edit { prefs ->
            prefs.write(transform(prefs.toSettings()))
        }
    }

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            body = BodyProfile(
                sex = this[Keys.SEX]?.let { runCatching { Sex.valueOf(it) }.getOrNull() }
                    ?: d.body.sex,
                birthYear = this[Keys.BIRTH_YEAR] ?: d.body.birthYear,
                heightCm = this[Keys.HEIGHT_CM] ?: d.body.heightCm,
                fallbackWeightKg = this[Keys.FALLBACK_WEIGHT] ?: d.body.fallbackWeightKg,
            ),
            goal = GoalSettings(
                mode = this[Keys.GOAL_MODE]?.let { runCatching { GoalMode.valueOf(it) }.getOrNull() }
                    ?: d.goal.mode,
                weeklyChangeKg = this[Keys.WEEKLY_CHANGE] ?: d.goal.weeklyChangeKg,
                targetWeightKg = this[Keys.TARGET_WEIGHT],
                useWeeklyBanking = this[Keys.WEEKLY_BANKING] ?: d.goal.useWeeklyBanking,
                minIntakeFloorKcal = this[Keys.MIN_INTAKE] ?: d.goal.minIntakeFloorKcal,
                goalAchievedAt = this[Keys.GOAL_ACHIEVED_AT],
            ),
            sources = SourceSettings(selections = readSources()),
            calculation = CalculationSettings(
                preferHcTotal = this[Keys.PREFER_HC_TOTAL] ?: d.calculation.preferHcTotal,
                preferHcBasal = this[Keys.PREFER_HC_BASAL] ?: d.calculation.preferHcBasal,
                dayStartHour = (this[Keys.DAY_START_HOUR] ?: d.calculation.dayStartHour)
                    .coerceIn(0, 23),
            ),
            features = FeatureFlags(
                autoCalibration = this[Keys.AUTO_CALIBRATION] ?: d.features.autoCalibration,
                widgetWeighInNudge = this[Keys.WIDGET_NUDGE] ?: d.features.widgetWeighInNudge,
                weighInNotification = this[Keys.WEIGH_IN_NOTIFY] ?: d.features.weighInNotification,
                weighInIntervalDays = (this[Keys.WEIGH_IN_DAYS] ?: d.features.weighInIntervalDays)
                    .coerceIn(1, 60),
            ),
            calibration = CalibrationState(
                loggingAccuracy = this[Keys.LOGGING_ACCURACY]
                    ?.let { runCatching { LoggingAccuracy.valueOf(it) }.getOrNull() },
                lastBiasKcal = this[Keys.LAST_BIAS],
                expenditureFactor = this[Keys.EXPENDITURE_FACTOR] ?: d.calibration.expenditureFactor,
                intakeFactor = this[Keys.INTAKE_FACTOR] ?: d.calibration.intakeFactor,
                lastComputedAt = this[Keys.CALIBRATED_AT] ?: 0L,
                lastPromptedAt = this[Keys.PROMPTED_AT] ?: 0L,
            ),
            reminders = ReminderState(
                lastWeighInNotifiedAt = this[Keys.WEIGH_IN_NOTIFIED_AT] ?: 0L,
            ),
            onboardingCompleted = this[Keys.ONBOARDED] ?: d.onboardingCompleted,
        )
    }

    /**
     * Per-metric sources, falling back to the single burn origin this app used before
     * sources were split per metric. Without that, anyone who had pinned a tracker would
     * silently go back to summing every writer.
     */
    private fun Preferences.readSources(): Map<HealthMetric, Set<String>> {
        val legacy = this[Keys.LEGACY_PREFERRED_ORIGIN]?.takeIf { it.isNotBlank() }
        return HealthMetric.entries.mapNotNull { metric ->
            val raw = this[Keys.source(metric)]
            val packages = when {
                raw != null -> raw.split(',').filter { it.isNotBlank() }.toSet()
                legacy != null && metric in LEGACY_ORIGIN_METRICS -> setOf(legacy)
                else -> emptySet()
            }
            if (packages.isEmpty()) null else metric to packages
        }.toMap()
    }

    private fun MutablePreferences.write(s: AppSettings) {
        this[Keys.SEX] = s.body.sex.name
        this[Keys.BIRTH_YEAR] = s.body.birthYear
        this[Keys.HEIGHT_CM] = s.body.heightCm
        this[Keys.FALLBACK_WEIGHT] = s.body.fallbackWeightKg

        this[Keys.GOAL_MODE] = s.goal.mode.name
        this[Keys.WEEKLY_CHANGE] = s.goal.weeklyChangeKg
        s.goal.targetWeightKg.let { if (it == null) remove(Keys.TARGET_WEIGHT) else this[Keys.TARGET_WEIGHT] = it }
        this[Keys.WEEKLY_BANKING] = s.goal.useWeeklyBanking
        this[Keys.MIN_INTAKE] = s.goal.minIntakeFloorKcal
        s.goal.goalAchievedAt.let { if (it == null) remove(Keys.GOAL_ACHIEVED_AT) else this[Keys.GOAL_ACHIEVED_AT] = it }

        HealthMetric.entries.forEach { metric ->
            this[Keys.source(metric)] = s.sources.packagesFor(metric).joinToString(",")
        }

        this[Keys.PREFER_HC_TOTAL] = s.calculation.preferHcTotal
        this[Keys.PREFER_HC_BASAL] = s.calculation.preferHcBasal
        this[Keys.DAY_START_HOUR] = s.calculation.dayStartHour

        this[Keys.AUTO_CALIBRATION] = s.features.autoCalibration
        this[Keys.WIDGET_NUDGE] = s.features.widgetWeighInNudge
        this[Keys.WEIGH_IN_NOTIFY] = s.features.weighInNotification
        this[Keys.WEIGH_IN_DAYS] = s.features.weighInIntervalDays

        s.calibration.loggingAccuracy.let {
            if (it == null) remove(Keys.LOGGING_ACCURACY) else this[Keys.LOGGING_ACCURACY] = it.name
        }
        s.calibration.lastBiasKcal.let {
            if (it == null) remove(Keys.LAST_BIAS) else this[Keys.LAST_BIAS] = it
        }
        this[Keys.EXPENDITURE_FACTOR] = s.calibration.expenditureFactor
        this[Keys.INTAKE_FACTOR] = s.calibration.intakeFactor
        this[Keys.CALIBRATED_AT] = s.calibration.lastComputedAt
        this[Keys.PROMPTED_AT] = s.calibration.lastPromptedAt

        this[Keys.WEIGH_IN_NOTIFIED_AT] = s.reminders.lastWeighInNotifiedAt
        this[Keys.ONBOARDED] = s.onboardingCompleted
    }

    private object Keys {
        val SEX = stringPreferencesKey("sex")
        val BIRTH_YEAR = intPreferencesKey("birth_year")
        val HEIGHT_CM = intPreferencesKey("height_cm")
        val FALLBACK_WEIGHT = doublePreferencesKey("fallback_weight_kg")

        val GOAL_MODE = stringPreferencesKey("goal_mode")
        val WEEKLY_CHANGE = doublePreferencesKey("weekly_change_kg")
        val TARGET_WEIGHT = doublePreferencesKey("target_weight_kg")
        val WEEKLY_BANKING = booleanPreferencesKey("weekly_banking")
        val MIN_INTAKE = intPreferencesKey("min_intake_kcal")
        val GOAL_ACHIEVED_AT = longPreferencesKey("goal_achieved_at")

        val PREFER_HC_TOTAL = booleanPreferencesKey("prefer_hc_total")
        val PREFER_HC_BASAL = booleanPreferencesKey("prefer_hc_basal")
        val DAY_START_HOUR = intPreferencesKey("day_start_hour")

        val AUTO_CALIBRATION = booleanPreferencesKey("auto_calibration")
        val WIDGET_NUDGE = booleanPreferencesKey("widget_weigh_in_nudge")
        val WEIGH_IN_NOTIFY = booleanPreferencesKey("weigh_in_notification")
        val WEIGH_IN_DAYS = intPreferencesKey("weigh_in_interval_days")

        val LOGGING_ACCURACY = stringPreferencesKey("logging_accuracy")
        val LAST_BIAS = doublePreferencesKey("last_bias_kcal")
        val EXPENDITURE_FACTOR = doublePreferencesKey("expenditure_factor")
        val INTAKE_FACTOR = doublePreferencesKey("intake_factor")
        val CALIBRATED_AT = longPreferencesKey("calibrated_at")
        val PROMPTED_AT = longPreferencesKey("calibration_prompted_at")

        val WEIGH_IN_NOTIFIED_AT = longPreferencesKey("weigh_in_notified_at")
        val ONBOARDED = booleanPreferencesKey("onboarding_completed")

        fun source(metric: HealthMetric) = stringPreferencesKey("source_${metric.name.lowercase()}")

        /** Written by versions before sources were split per metric; it meant burn only. */
        val LEGACY_PREFERRED_ORIGIN = stringPreferencesKey("preferred_origin")
    }

    private companion object {
        val LEGACY_ORIGIN_METRICS = setOf(HealthMetric.TOTAL_BURN, HealthMetric.ACTIVE_BURN)
    }
}
