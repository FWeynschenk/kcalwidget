package nl.flwe.kcalwidget

import nl.flwe.kcalwidget.data.BurnSource
import nl.flwe.kcalwidget.data.Energetics
import nl.flwe.kcalwidget.data.HealthSnapshot
import nl.flwe.kcalwidget.data.TdeeBaseline
import nl.flwe.kcalwidget.data.settings.AppSettings
import nl.flwe.kcalwidget.data.settings.BodyProfile
import nl.flwe.kcalwidget.data.settings.CalibrationState
import nl.flwe.kcalwidget.data.settings.FeatureFlags
import nl.flwe.kcalwidget.data.settings.GoalSettings
import nl.flwe.kcalwidget.data.settings.Sex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

class EnergeticsTest {

    private val settings = AppSettings(
        body = BodyProfile(
            sex = Sex.MALE,
            birthYear = LocalDate.now().year - 30,
            heightCm = 180,
            fallbackWeightKg = 80.0,
        ),
        goal = GoalSettings(weeklyChangeKg = -0.5),
    )

    private val bmr = Energetics.bmrMifflinStJeor(settings.body, 80.0)
    private val halfDay = Duration.ofHours(12)

    /** A deliberately flat curve, so the arithmetic in these tests is checkable by hand. */
    private val flatBaseline = TdeeBaseline(
        meanFullDayKcal = 2600.0,
        cumulativeByHour = List(25) { it / 24.0 },
        sampleDays = 14,
        dayStartHour = 0,
        computedAt = Instant.now(),
    )

    private fun snapshot(
        intake: Double? = null,
        total: Double? = null,
        active: Double? = null,
        steps: Long? = null,
        basal: Double? = null,
        weight: Double? = null,
    ) = HealthSnapshot(
        intakeKcal = intake,
        totalBurnedKcal = total,
        activeBurnedKcal = active,
        steps = steps,
        basalKcalPerDay = basal,
        weightKg = weight,
        lastWeighInAt = null,
        burnOrigins = emptySet(),
    )

    // --- resting rate and burn source -------------------------------------------------

    @Test
    fun `resting rate follows Mifflin-St Jeor`() {
        val expected = 10 * 80.0 + 6.25 * 180 - 5 * 30 + 5
        assertEquals(expected, bmr, 0.001)
        val female = settings.body.copy(sex = Sex.FEMALE)
        assertEquals(expected - 166, Energetics.bmrMifflinStJeor(female, 80.0), 0.001)
    }

    @Test
    fun `tracker total is used when it is plausible`() {
        val result = Energetics.compute(snapshot(total = 1400.0), settings, halfDay, flatBaseline)
        assertEquals(BurnSource.HC_TOTAL, result.source)
        assertEquals(1400.0, result.burnedSoFarKcal, 0.001)
    }

    @Test
    fun `an unsynced tracker total is rejected in favour of the estimate`() {
        // 100 kcal by midday is below half of resting burn, so it cannot be the whole story.
        val result = Energetics.compute(
            snapshot(total = 100.0, active = 300.0),
            settings,
            halfDay,
            flatBaseline,
        )
        assertEquals(BurnSource.BMR_PLUS_ACTIVE, result.source)
        assertEquals(bmr * 0.5 + 300.0, result.burnedSoFarKcal, 0.001)
    }

    @Test
    fun `turning off the tracker total falls back to active calories`() {
        val result = Energetics.compute(
            snapshot(total = 1400.0, active = 300.0),
            settings.copy(calculation = settings.calculation.copy(preferHcTotal = false)),
            halfDay,
            flatBaseline,
        )
        assertEquals(BurnSource.BMR_PLUS_ACTIVE, result.source)
        assertEquals(bmr * 0.5 + 300.0, result.burnedSoFarKcal, 0.001)
    }

    @Test
    fun `steps are used when nothing reports active calories`() {
        val result = Energetics.compute(snapshot(steps = 10_000), settings, halfDay, flatBaseline)
        assertEquals(BurnSource.BMR_PLUS_STEPS, result.source)
        assertEquals(
            bmr * 0.5 + Energetics.kcalFromSteps(10_000, 80.0),
            result.burnedSoFarKcal,
            0.001,
        )
    }

    @Test
    fun `resting burn alone is the last resort`() {
        val result = Energetics.compute(snapshot(), settings, halfDay, flatBaseline)
        assertEquals(BurnSource.BMR_ONLY, result.source)
        assertEquals(bmr * 0.5, result.burnedSoFarKcal, 0.001)
    }

    @Test
    fun `Health Connect weight and basal rate win over the profile`() {
        val result = Energetics.compute(
            snapshot(basal = 2000.0, weight = 95.0),
            settings,
            Duration.ofHours(24),
            flatBaseline,
        )
        assertEquals(95.0, result.weightKg, 0.001)
        assertEquals(2000.0, result.bmrPerDayKcal, 0.001)
    }

    // --- the projection ---------------------------------------------------------------

    @Test
    fun `a fresh day projects the typical day, not bare resting rate`() {
        val result = Energetics.compute(snapshot(), settings, Duration.ZERO, flatBaseline)
        assertEquals(2600.0, result.projectedBurnKcal, 0.001)
        assertTrue("must not start the day at resting rate", result.projectedBurnKcal > bmr)
        assertEquals(0.0, result.confidence, 0.001)
    }

    @Test
    fun `the projection converges on the day's actual burn by midnight`() {
        val result = Energetics.compute(
            snapshot(total = 2400.0),
            settings,
            Duration.ofHours(24),
            flatBaseline,
        )
        assertEquals(2400.0, result.projectedBurnKcal, 0.001)
        assertEquals(1.0, result.confidence, 0.001)
    }

    @Test
    fun `a busy morning is not projected forward`() {
        // 1800 by midday against 1300 expected is 500 ahead, but that buys nothing in
        // advance. The floor governs: 1800 burned plus resting for the rest of the day.
        val result = Energetics.compute(snapshot(total = 1800.0), settings, halfDay, flatBaseline)
        assertEquals(500.0, result.surplusVsTypicalKcal, 0.001)
        assertEquals(1800.0 + bmr * 0.5, result.projectedBurnKcal, 0.001)
    }

    @Test
    fun `a moderate walk does not move the projection at all`() {
        // The afternoon walk problem, in one assertion.
        val ordinary = Energetics.compute(snapshot(total = 1300.0), settings, halfDay, flatBaseline)
        val afterWalk = Energetics.compute(snapshot(total = 1600.0), settings, halfDay, flatBaseline)
        assertEquals(ordinary.projectedBurnKcal, afterWalk.projectedBurnKcal, 0.001)
    }

    @Test
    fun `a big day is still credited, through the floor rather than a guess`() {
        val ordinary = Energetics.compute(snapshot(total = 1300.0), settings, halfDay, flatBaseline)
        val afterHike = Energetics.compute(snapshot(total = 2800.0), settings, halfDay, flatBaseline)
        assertTrue(afterHike.projectedBurnKcal > ordinary.projectedBurnKcal)
        assertEquals(2800.0 + bmr * 0.5, afterHike.projectedBurnKcal, 0.001)
    }

    @Test
    fun `an active day earns room through the evening instead of losing it`() {
        // Walk at midday and a normal evening after it: the projection should climb.
        val readings = (12..23).map { hour ->
            val evening = 2600.0 * ((hour - 12) / 24.0)
            Energetics.compute(
                snapshot(total = 1600.0 + evening),
                settings,
                Duration.ofHours(hour.toLong()),
                flatBaseline,
            ).projectedBurnKcal
        }
        assertEquals(readings.sorted(), readings)
    }

    @Test
    fun `a walk followed by a quiet evening does not collapse the budget`() {
        // Midday walk of 300, then nothing but resting burn until 22:00.
        val atWalk = Energetics.compute(snapshot(total = 1600.0), settings, halfDay, flatBaseline)
        val restingSince = bmr * (10.0 / 24.0)
        val evening = Energetics.compute(
            snapshot(total = 1600.0 + restingSince),
            settings,
            Duration.ofHours(22),
            flatBaseline,
        )

        val drop = atWalk.projectedBurnKcal - evening.projectedBurnKcal
        // All that remains is the day genuinely ending below a typical one. The walk itself
        // contributes nothing to the fall, because it was never promised.
        assertTrue("the budget must not visibly collapse, fell by $drop", drop < 100.0)
        assertTrue("and the projection should still end sane", evening.projectedBurnKcal > bmr)
    }

    @Test
    fun `falling behind is taken at face value straight away`() {
        // Under-promising can be handed back later; over-promising has already been eaten.
        val result = Energetics.compute(snapshot(total = 900.0), settings, halfDay, flatBaseline)
        assertEquals(-400.0, result.surplusVsTypicalKcal, 0.001)
        assertEquals(2200.0, result.projectedBurnKcal, 0.001)
    }

    @Test
    fun `the projection is steadier through the day than full credit would be`() {
        // Walk at midday, resting afterwards, sampled hourly. The swing in the projection
        // is what the user actually experiences as the budget moving under them.
        val readings = (12..23).map { hour ->
            val resting = bmr * ((hour - 12) / 24.0)
            Energetics.compute(
                snapshot(total = 1600.0 + resting),
                settings,
                Duration.ofHours(hour.toLong()),
                flatBaseline,
            ).projectedBurnKcal
        }
        val swing = readings.max() - readings.min()
        assertTrue("projection swung by $swing kcal over the afternoon", swing < 100.0)
    }

    @Test
    fun `a quiet morning pulls it down, still above the typical day's resting floor`() {
        val result = Energetics.compute(snapshot(total = 900.0), settings, halfDay, flatBaseline)
        assertEquals(2200.0, result.projectedBurnKcal, 0.001)
        assertTrue(result.projectedBurnKcal < flatBaseline.meanFullDayKcal)
    }

    @Test
    fun `an exceptional day is not held back by the clamp`() {
        // Implied 6667 is clamped to 1.8x typical, but what is already burned outranks it.
        val result = Energetics.compute(
            snapshot(total = 5000.0),
            settings,
            Duration.ofHours(18),
            flatBaseline,
        )
        val restOfDayAtRest = 5000.0 + bmr * 0.25
        assertEquals(restOfDayAtRest, result.projectedBurnKcal, 0.001)
        assertTrue(result.projectedBurnKcal > flatBaseline.meanFullDayKcal * 1.8)
    }

    @Test
    fun `the projection never drops below finishing the day at rest`() {
        for (hours in 0..24) {
            val result = Energetics.compute(
                snapshot(total = 600.0),
                settings,
                Duration.ofHours(hours.toLong()),
                flatBaseline,
            )
            val floor = result.burnedSoFarKcal + bmr * (1.0 - hours / 24.0)
            assertTrue(
                "hour $hours projected ${result.projectedBurnKcal} below floor $floor",
                result.projectedBurnKcal >= floor - 0.001,
            )
        }
    }

    @Test
    fun `without history the typical day is a multiple of resting rate`() {
        val result = Energetics.compute(snapshot(), settings, Duration.ZERO, baseline = null)
        assertEquals(bmr * Energetics.DEFAULT_ACTIVITY_FACTOR, result.typicalDayKcal, 0.001)
        assertEquals(0, result.baselineDays)
        assertTrue(result.projectedBurnKcal > bmr)
    }

    // --- the intraday curve -----------------------------------------------------------

    @Test
    fun `the default curve knows the small hours are cheap`() {
        val atSeven = TdeeBaseline.DEFAULT_CURVE[7]
        assertTrue(
            "sleeping hours must count for less than wall-clock time: $atSeven",
            atSeven < 7.0 / 24.0,
        )
        assertEquals(0.0, TdeeBaseline.DEFAULT_CURVE.first(), 0.001)
        assertEquals(1.0, TdeeBaseline.DEFAULT_CURVE.last(), 0.001)
    }

    @Test
    fun `the default curve is monotone`() {
        TdeeBaseline.DEFAULT_CURVE.zipWithNext { a, b -> assertTrue(b >= a) }
    }

    @Test
    fun `fractions interpolate within the hour`() {
        val baseline = TdeeBaseline.fallback(bmr)
        val six = baseline.expectedFractionAt(6.0)
        val seven = baseline.expectedFractionAt(7.0)
        assertEquals((six + seven) / 2, baseline.expectedFractionAt(6.5), 0.0001)
        assertEquals(0.0, baseline.expectedFractionAt(-5.0), 0.001)
        assertEquals(1.0, baseline.expectedFractionAt(99.0), 0.001)
    }

    // --- goal and budget --------------------------------------------------------------

    @Test
    fun `the weight goal sets the daily budget`() {
        val result = Energetics.compute(
            snapshot(intake = 1000.0, total = 1400.0),
            settings,
            halfDay,
            flatBaseline,
        )
        val expectedDelta = -0.5 * Energetics.KCAL_PER_KG_FAT / 7.0
        assertEquals(expectedDelta, settings.goal.dailyEnergyDelta, 0.001)
        assertEquals(result.projectedBurnKcal + expectedDelta, result.budgetKcal, 0.001)
        assertEquals(result.budgetKcal - 1000.0, result.remainingKcal, 0.001)
        assertEquals(0.0, result.moveKcalToClear, 0.001)
    }

    @Test
    fun `going over budget turns into a movement target`() {
        val result = Energetics.compute(
            snapshot(intake = 4000.0, total = 1400.0),
            settings,
            halfDay,
            flatBaseline,
        )
        assertTrue(result.remainingKcal < 0)
        assertEquals(-result.remainingKcal, result.moveKcalToClear, 0.001)
        val perMinute = Energetics.walkKcalPerMinute(80.0)
        assertEquals(
            Math.round(result.moveKcalToClear / perMinute).toInt(),
            result.walkMinutesToClear,
        )
    }

    @Test
    fun `the intake floor outranks an aggressive goal`() {
        // A 1 kg/week deficit against a quiet day would land under any sane floor.
        val aggressive = settings.copy(
            goal = settings.goal.copy(weeklyChangeKg = -1.0, minIntakeFloorKcal = 1800),
        )
        val result = Energetics.compute(
            snapshot(total = 900.0),
            aggressive,
            halfDay,
            flatBaseline,
        )
        assertEquals(1800.0, result.budgetKcal, 0.001)
        assertTrue(result.intakeFloorApplied)
    }

    @Test
    fun `the floor stays out of the way when the goal is reasonable`() {
        val result = Energetics.compute(snapshot(total = 1400.0), settings, halfDay, flatBaseline)
        assertTrue(result.budgetKcal > settings.goal.minIntakeFloorKcal)
        assertTrue(!result.intakeFloorApplied)
    }

    @Test
    fun `banking is ignored until it is switched on`() {
        val withBank = Energetics.compute(
            snapshot(total = 1400.0),
            settings,
            halfDay,
            flatBaseline,
            bankedAdjustmentKcal = 500.0,
        )
        val without = Energetics.compute(snapshot(total = 1400.0), settings, halfDay, flatBaseline)
        assertEquals(without.budgetKcal, withBank.budgetKcal, 0.001)
        assertEquals(0.0, withBank.bankedAdjustmentKcal, 0.001)
    }

    @Test
    fun `banking carries the week's surplus into today`() {
        val banking = settings.copy(goal = settings.goal.copy(useWeeklyBanking = true))
        val plain = Energetics.compute(snapshot(total = 1400.0), banking, halfDay, flatBaseline)
        val credited = Energetics.compute(
            snapshot(total = 1400.0),
            banking,
            halfDay,
            flatBaseline,
            bankedAdjustmentKcal = 500.0,
        )
        assertEquals(plain.budgetKcal + 500.0, credited.budgetKcal, 0.001)

        val owed = Energetics.compute(
            snapshot(total = 1400.0),
            banking,
            halfDay,
            flatBaseline,
            bankedAdjustmentKcal = -400.0,
        )
        assertEquals(plain.budgetKcal - 400.0, owed.budgetKcal, 0.001)
    }

    @Test
    fun `calibration factors do nothing until auto is on`() {
        val advisoryOnly = settings.copy(
            calibration = CalibrationState(expenditureFactor = 0.9, intakeFactor = 1.1),
        )
        val plain = Energetics.compute(
            snapshot(intake = 1000.0, total = 1400.0),
            settings,
            halfDay,
            flatBaseline,
        )
        val advised = Energetics.compute(
            snapshot(intake = 1000.0, total = 1400.0),
            advisoryOnly,
            halfDay,
            flatBaseline,
        )
        assertEquals(plain.projectedBurnKcal, advised.projectedBurnKcal, 0.001)
        assertEquals(1000.0, advised.intakeKcal, 0.001)
        assertTrue(!advised.calibrationApplied)
    }

    @Test
    fun `applied calibration scales burn and intake but not the reported total`() {
        val calibrated = settings.copy(
            features = FeatureFlags(autoCalibration = true),
            calibration = CalibrationState(expenditureFactor = 0.9, intakeFactor = 1.1),
        )
        val plain = Energetics.compute(
            snapshot(intake = 1000.0, total = 1400.0),
            settings,
            halfDay,
            flatBaseline,
        )
        val result = Energetics.compute(
            snapshot(intake = 1000.0, total = 1400.0),
            calibrated,
            halfDay,
            flatBaseline,
        )
        assertEquals(plain.projectedBurnKcal * 0.9, result.projectedBurnKcal, 0.001)
        assertEquals(1100.0, result.intakeKcal, 0.001)
        // What the tracker actually said is preserved, so the disagreement stays visible.
        assertEquals(1000.0, result.rawIntakeKcal, 0.001)
        assertEquals(1400.0, result.burnedSoFarKcal, 0.001)
        assertTrue(result.calibrationApplied)
    }

    @Test
    fun `a gaining goal raises the budget above the burn`() {
        val gaining = settings.copy(goal = settings.goal.copy(weeklyChangeKg = 0.25))
        val result = Energetics.compute(snapshot(total = 1400.0), gaining, halfDay, flatBaseline)
        assertTrue(result.budgetKcal > result.projectedBurnKcal)
    }
}
