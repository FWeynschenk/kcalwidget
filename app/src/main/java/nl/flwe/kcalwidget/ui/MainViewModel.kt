package nl.flwe.kcalwidget.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import nl.flwe.kcalwidget.data.BaselineRepository
import nl.flwe.kcalwidget.data.DayEnergy
import nl.flwe.kcalwidget.data.DayWindow
import nl.flwe.kcalwidget.data.Energetics
import nl.flwe.kcalwidget.data.HealthAvailability
import nl.flwe.kcalwidget.data.HealthRepository
import nl.flwe.kcalwidget.data.HealthSnapshot
import nl.flwe.kcalwidget.data.TdeeBaseline
import nl.flwe.kcalwidget.data.history.History
import nl.flwe.kcalwidget.data.history.HistoryRepository
import nl.flwe.kcalwidget.data.settings.AppSettings
import nl.flwe.kcalwidget.data.settings.LoggingAccuracy
import nl.flwe.kcalwidget.data.settings.SettingsRepository
import nl.flwe.kcalwidget.data.sources.SourceCatalog
import nl.flwe.kcalwidget.data.sources.SourceDiscovery
import nl.flwe.kcalwidget.data.weight.Calibration
import nl.flwe.kcalwidget.data.weight.CalibrationResult
import nl.flwe.kcalwidget.notify.ReminderWorker
import nl.flwe.kcalwidget.widget.RefreshWorker
import nl.flwe.kcalwidget.widget.WidgetRepository

data class UiState(
    val loading: Boolean = true,
    val availability: HealthAvailability = HealthAvailability.UNSUPPORTED,
    val hasPermissions: Boolean = false,
    val hasBackgroundPermission: Boolean = false,
    val hasHistoryPermission: Boolean = false,
    val settings: AppSettings = AppSettings(),
    val energy: DayEnergy? = null,
    val sourceCatalog: SourceCatalog? = null,
    val discoveringSources: Boolean = false,
    val history: History? = null,
    val historyLoading: Boolean = false,
    val readFailed: Boolean = false,
    /** Why the last history read produced nothing, for the diagnostics panel. */
    val historyError: String? = null,
    val calibration: CalibrationResult? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val health = HealthRepository(app)
    private val settingsRepo = SettingsRepository(app)
    private val baselines = BaselineRepository(app)
    private val discovery = SourceDiscovery(app, health)
    private val historyRepo = HistoryRepository(app, health)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * The last raw Health Connect read, with the baseline that went with it. Settings edits
     * recompute the day from these instead of reading again: the numbers depend on the
     * settings only through pure arithmetic, so a slider drag costs a few multiplications
     * rather than a round trip per frame.
     */
    private var snapshot: HealthSnapshot? = null
    private var baseline: TdeeBaseline? = null
    private var history: History? = null

    private var loadJob: Job? = null
    private var commitJob: Job? = null

    init {
        RefreshWorker.enqueuePeriodic(app)
        ReminderWorker.enqueue(app)
    }

    /** Re-reads Health Connect. Called on resume and after a permission result. */
    fun load() {
        // Startup fires this from resume while a permission result may already be in
        // flight; one read is enough, and both produce the same answer.
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            val settings = settingsRepo.settings.first()
            // Every Health Connect call is IPC; none of it belongs on the main thread, and
            // a provider that never answers must not leave the screen reading forever.
            val read = withTimeoutOrNull(READ_TIMEOUT_MS) {
              withContext(Dispatchers.IO) {
                val availability = health.availability()
                val granted = health.grantedPermissions()
                val hasPermissions = granted.containsAll(HealthRepository.REQUIRED_PERMISSIONS)
                val bmr = Energetics.bmrMifflinStJeor(settings.body, settings.body.fallbackWeightKg)
                Reading(
                    availability = availability,
                    hasPermissions = hasPermissions,
                    hasBackgroundPermission = granted.contains(HealthRepository.BACKGROUND_PERMISSION),
                    hasHistoryPermission = granted.contains(HealthRepository.HISTORY_PERMISSION),
                    snapshot = if (hasPermissions) health.readToday(settings) else null,
                    baseline = if (hasPermissions) baselines.get(health, settings, bmr) else null,
                    // Only the short banking window is read on every resume. The full
                    // 90-day history is a much heavier query and is fetched on demand by
                    // the History screen, so opening the app stays quick.
                    history = if (hasPermissions && settings.goal.useWeeklyBanking) {
                        historyRepo.load(settings, HistoryRepository.BANKING_DAYS + 1)
                    } else {
                        null
                    },
                )
              }
            }
            if (read == null) {
                // Say so rather than sitting on "Reading…" until the user gives up.
                _state.update { it.copy(loading = false, readFailed = true, settings = settings) }
                return@launch
            }
            snapshot = read.snapshot
            baseline = read.baseline
            history = read.history

            _state.value = UiState(
                loading = false,
                readFailed = false,
                availability = read.availability,
                hasPermissions = read.hasPermissions,
                hasBackgroundPermission = read.hasBackgroundPermission,
                hasHistoryPermission = read.hasHistoryPermission,
                settings = settings,
                energy = read.snapshot?.let {
                    Energetics.compute(
                        it,
                        settings,
                        elapsedToday(settings),
                        read.baseline,
                        read.history?.bankedAdjustmentKcal ?: 0.0,
                    )
                },
                sourceCatalog = _state.value.sourceCatalog,
                history = read.history ?: _state.value.history,
            )
            runCatching { WidgetRepository.refresh(getApplication()) }
        }
    }

    /**
     * Applies a settings change immediately, then persists it once the edits stop.
     *
     * The visible numbers update synchronously from the cached snapshot; writing to
     * DataStore and pushing the widget are debounced, so holding a slider or typing into
     * a field does not queue a disk write and a launcher update per frame.
     */
    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val current = _state.value.settings
        val next = transform(current)
        if (next == current) return
        val boundaryChanged = next.calculation.dayStartHour != current.calculation.dayStartHour
        val sourcesChanged = next.sources != current.sources

        _state.update { state ->
            state.copy(
                settings = next,
                energy = snapshot?.let {
                    Energetics.compute(
                        it,
                        next,
                        elapsedToday(next),
                        baseline,
                        history?.bankedAdjustmentKcal ?: 0.0,
                    )
                },
            )
        }

        commitJob?.cancel()
        commitJob = viewModelScope.launch {
            delay(COMMIT_DEBOUNCE_MS)
            settingsRepo.update { next }
            // The learned curve is indexed against the day boundary, and the baseline is
            // aggregated from chosen sources, so either change means a re-read rather than
            // a recompute of stale numbers.
            if (boundaryChanged || sourcesChanged) load()
            runCatching { WidgetRepository.refresh(getApplication()) }
        }
    }

    /** Loads the full history, on demand, for the History screen. */
    fun loadHistory() {
        if (_state.value.historyLoading) return
        viewModelScope.launch {
            _state.update { it.copy(historyLoading = true) }
            // The flag has to come back down on every path. Left up by a cancellation or a
            // throw, it wedges this screen on "reading" for the life of the process,
            // because the guard above then refuses every retry.
            try {
                val settings = settingsRepo.settings.first()
                // Whatever goes wrong here has to reach the screen: a failure the user
                // cannot see is indistinguishable from having no data.
                var failure: String? = null
                val started = System.currentTimeMillis()
                val loaded = withTimeoutOrNull(HISTORY_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) {
                        runCatching { historyRepo.load(settings) }
                            .onFailure {
                                // runCatching catches Throwable, which includes the
                                // cancellation withTimeout uses to unwind. Swallowing that
                                // breaks structured concurrency and disguises a timeout as
                                // an ordinary failure, so it goes back up.
                                if (it is kotlinx.coroutines.CancellationException) throw it
                                failure = "${it.javaClass.simpleName}: ${it.message}"
                            }
                            .getOrNull()
                    }
                }
                val elapsed = System.currentTimeMillis() - started
                if (loaded == null && failure == null) {
                    failure = "timed out after ${elapsed}ms"
                }
                // Keep it even with zero rows: the diagnostics inside are the whole point
                // when there is nothing else to show.
                if (loaded != null) history = loaded
                val analysis = Calibration.analyse(loaded ?: history, settings.calibration)
                _state.update {
                    it.copy(
                        history = loaded ?: it.history,
                        calibration = analysis,
                        historyError = failure,
                    )
                }
                // Remembering the measured bias lets the prompt survive a restart without
                // re-reading ninety days every time the app opens.
                val bias = (analysis as? CalibrationResult.Bias)?.kcalPerDay
                runCatching {
                    settingsRepo.update {
                        it.copy(
                            calibration = it.calibration.copy(
                                lastBiasKcal = bias,
                                lastComputedAt = System.currentTimeMillis(),
                            )
                        )
                    }
                }
            } finally {
                _state.update { it.copy(historyLoading = false) }
            }
        }
    }

    /** Records how much the user believes their own food logging, and re-splits the bias. */
    fun setLoggingAccuracy(accuracy: LoggingAccuracy) {
        updateSettings {
            it.copy(calibration = it.calibration.copy(loggingAccuracy = accuracy))
        }
        _state.update {
            it.copy(calibration = Calibration.analyse(history, it.settings.calibration.copy(loggingAccuracy = accuracy)))
        }
    }

    /**
     * Turns automatic correction on or off.
     *
     * Switching it on applies the correction straight away rather than waiting for the
     * next daily recheck. A switch that visibly does nothing for a day reads as broken,
     * and the measurement it needs is already on screen.
     */
    fun setAutoCalibration(enabled: Boolean) {
        val result = _state.value.calibration
        updateSettings { settings ->
            val withFlag = settings.copy(
                features = settings.features.copy(autoCalibration = enabled)
            )
            if (result == null) return@updateSettings withFlag
            // nextFactors leaves them alone unless there is a measured bias to act on.
            val factors = Calibration.nextFactors(withFlag.calibration, result, enabled)
            withFlag.copy(
                calibration = withFlag.calibration.copy(
                    expenditureFactor = factors.expenditure,
                    intakeFactor = factors.intake,
                )
            )
        }
    }

    /** Eases the suggested correction into the live settings and turns auto on. */
    fun applyCalibration(result: CalibrationResult.Bias) {
        updateSettings { current ->
            current.copy(
                features = current.features.copy(autoCalibration = true),
                calibration = current.calibration.copy(
                    expenditureFactor = Calibration.ease(
                        current.calibration.expenditureFactor,
                        result.suggestedExpenditureFactor,
                    ),
                    intakeFactor = Calibration.ease(
                        current.calibration.intakeFactor,
                        result.suggestedIntakeFactor,
                    ),
                    lastComputedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    /** Drops any learned correction and goes back to the raw numbers. */
    fun resetCalibration() {
        updateSettings {
            it.copy(
                features = it.features.copy(autoCalibration = false),
                calibration = it.calibration.copy(expenditureFactor = 1.0, intakeFactor = 1.0),
            )
        }
    }

    /** Looks up which apps write which metrics, for the data sources screen. */
    fun discoverSources(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            _state.update { it.copy(discoveringSources = true) }
            try {
                val catalog = withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) {
                        runCatching { discovery.catalog(forceRefresh) }.getOrNull()
                    }
                }
                _state.update { it.copy(sourceCatalog = catalog ?: it.sourceCatalog) }
            } finally {
                _state.update { it.copy(discoveringSources = false) }
            }
        }
    }

    private fun elapsedToday(settings: AppSettings) =
        DayWindow.elapsed(settings.calculation.dayStartHour)

    private data class Reading(
        val availability: HealthAvailability,
        val hasPermissions: Boolean,
        val hasBackgroundPermission: Boolean,
        val hasHistoryPermission: Boolean,
        val snapshot: HealthSnapshot?,
        val baseline: TdeeBaseline?,
        val history: History?,
    )

    private companion object {
        const val COMMIT_DEBOUNCE_MS = 500L

        // Health Connect can be slow. It must never be allowed to be infinite.
        const val READ_TIMEOUT_MS = 20_000L
        const val HISTORY_TIMEOUT_MS = 30_000L
        const val DISCOVERY_TIMEOUT_MS = 20_000L
    }
}
