package nl.flwe.kcalwidget.widget

import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.graphics.Color
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.unit.ColorProvider
import nl.flwe.kcalwidget.data.BurnSource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** What the widget should say when it cannot show numbers. */
enum class WidgetStatus { OK, NO_HEALTH_CONNECT, NEEDS_PERMISSION, ERROR }

object WidgetKeys {
    val STATUS = stringPreferencesKey("status")
    val INTAKE = doublePreferencesKey("intake")
    val RAW_INTAKE = doublePreferencesKey("raw_intake")
    val BURNED_SO_FAR = doublePreferencesKey("burned_so_far")
    val PROJECTED_BURN = doublePreferencesKey("projected_burn")
    val TYPICAL_DAY = doublePreferencesKey("typical_day")
    val CONFIDENCE = doublePreferencesKey("confidence")
    val BUDGET = doublePreferencesKey("budget")
    val REMAINING = doublePreferencesKey("remaining")
    val MOVE_KCAL = doublePreferencesKey("move_kcal")
    val WALK_MINUTES = intPreferencesKey("walk_minutes")
    val BURN_SOURCE = stringPreferencesKey("burn_source")
    val HAS_NUTRITION = booleanPreferencesKey("has_nutrition")
    val CALIBRATION_APPLIED = booleanPreferencesKey("calibration_applied")
    val WEIGHT_KG = doublePreferencesKey("weight_kg")
    val TREND_WEIGHT_KG = doublePreferencesKey("trend_weight_kg")
    val WEEKLY_TREND_KG = doublePreferencesKey("weekly_trend_kg")
    val DAYS_SINCE_WEIGH_IN = intPreferencesKey("days_since_weigh_in")
    val WEIGH_IN_DUE = booleanPreferencesKey("weigh_in_due")
    val NET_SERIES = stringPreferencesKey("net_series")
    val WEIGHT_SERIES = stringPreferencesKey("weight_series")
    val UPDATED_AT = longPreferencesKey("updated_at")
}

/**
 * Everything any of the widgets needs, read from the one cache [WidgetRepository] writes.
 *
 * The widgets differ only in how much of this they show; none of them reads Health
 * Connect, so a slow or denied read can never stall the launcher.
 */
internal data class WidgetModel(
    val status: WidgetStatus,
    val intake: Double,
    val rawIntake: Double,
    val burnedSoFar: Double,
    val projectedBurn: Double,
    val typicalDay: Double,
    val confidence: Double,
    val budget: Double,
    val remaining: Double,
    val moveKcal: Double,
    val walkMinutes: Int,
    val burnSource: String?,
    val hasNutrition: Boolean,
    val calibrationApplied: Boolean,
    val weightKg: Double,
    val trendWeightKg: Double?,
    val weeklyTrendKg: Double?,
    val daysSinceWeighIn: Int?,
    val weighInDue: Boolean,
    val netSeries: List<Double>,
    val weightSeries: List<Double>,
    val updatedAt: Long,
) {
    val isOver: Boolean get() = remaining < 0

    val accentPair: Pair<Color, Color>
        get() = when {
            remaining < 0 -> OVER
            budget > 0 && remaining < budget * 0.1 -> CLOSE
            else -> UNDER
        }

    val progress: Float
        get() = if (budget <= 0) 1f else (intake / budget).toFloat().coerceIn(0f, 1f)

    fun updatedAtLabel(): String =
        if (updatedAt == 0L) {
            "--:--"
        } else {
            TIME_FORMAT.format(Instant.ofEpochMilli(updatedAt).atZone(ZoneId.systemDefault()))
        }
}

// Glance 1.2 has no public day/night ColorProvider, so the pair is resolved by hand
// from the widget's configuration. Hosts re-provide content on a theme change.
internal val UNDER = Color(0xFF1B5E20) to Color(0xFF7CE0B6)
internal val CLOSE = Color(0xFF8A5A00) to Color(0xFFF2B33D)
internal val OVER = Color(0xFFB3261E) to Color(0xFFFFB4AB)

private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")

/**
 * Picks the day or night half of an accent pair. Glance has no LocalConfiguration, so the
 * theme is read once from the provider context; hosts re-provide content on a theme change.
 */
internal fun Pair<Color, Color>.resolve(night: Boolean): ColorProvider =
    ColorProvider(if (night) second else first)

internal fun Context.isNightMode(): Boolean =
    (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES

internal fun Preferences.toWidgetModel(): WidgetModel = WidgetModel(
    status = this[WidgetKeys.STATUS]
        ?.let { runCatching { WidgetStatus.valueOf(it) }.getOrNull() }
        ?: WidgetStatus.ERROR,
    intake = this[WidgetKeys.INTAKE] ?: 0.0,
    rawIntake = this[WidgetKeys.RAW_INTAKE] ?: 0.0,
    burnedSoFar = this[WidgetKeys.BURNED_SO_FAR] ?: 0.0,
    projectedBurn = this[WidgetKeys.PROJECTED_BURN] ?: 0.0,
    typicalDay = this[WidgetKeys.TYPICAL_DAY] ?: 0.0,
    confidence = this[WidgetKeys.CONFIDENCE] ?: 0.0,
    budget = this[WidgetKeys.BUDGET] ?: 0.0,
    remaining = this[WidgetKeys.REMAINING] ?: 0.0,
    moveKcal = this[WidgetKeys.MOVE_KCAL] ?: 0.0,
    walkMinutes = this[WidgetKeys.WALK_MINUTES] ?: 0,
    burnSource = this[WidgetKeys.BURN_SOURCE],
    hasNutrition = this[WidgetKeys.HAS_NUTRITION] ?: false,
    calibrationApplied = this[WidgetKeys.CALIBRATION_APPLIED] ?: false,
    weightKg = this[WidgetKeys.WEIGHT_KG] ?: 0.0,
    trendWeightKg = this[WidgetKeys.TREND_WEIGHT_KG],
    weeklyTrendKg = this[WidgetKeys.WEEKLY_TREND_KG],
    daysSinceWeighIn = this[WidgetKeys.DAYS_SINCE_WEIGH_IN],
    weighInDue = this[WidgetKeys.WEIGH_IN_DUE] ?: false,
    netSeries = this[WidgetKeys.NET_SERIES].toSeries(),
    weightSeries = this[WidgetKeys.WEIGHT_SERIES].toSeries(),
    updatedAt = this[WidgetKeys.UPDATED_AT] ?: 0L,
)

private fun String?.toSeries(): List<Double> =
    this?.split(',')?.mapNotNull { it.toDoubleOrNull() }.orEmpty()

internal fun burnSourceLabel(name: String?): String = when (name) {
    BurnSource.HC_TOTAL.name -> "tracker total"
    BurnSource.BMR_PLUS_ACTIVE.name -> "BMR + active"
    BurnSource.BMR_PLUS_STEPS.name -> "BMR + steps"
    BurnSource.BMR_ONLY.name -> "BMR only"
    else -> "unknown"
}

internal fun messageFor(status: WidgetStatus): String = when (status) {
    WidgetStatus.NO_HEALTH_CONNECT -> "Health Connect is not set up"
    WidgetStatus.NEEDS_PERMISSION -> "Tap to grant Health Connect access"
    else -> "No data yet, tap to open"
}
