package nl.flwe.kcalwidget

import nl.flwe.kcalwidget.data.settings.AppSettings
import nl.flwe.kcalwidget.data.settings.FeatureFlags
import nl.flwe.kcalwidget.data.settings.GoalSettings
import nl.flwe.kcalwidget.notify.Reminders
import org.junit.Assert.assertTrue
import org.junit.Test

class RemindersTest {

    private val now = 1_000_000_000L
    private val notifying = AppSettings(
        features = FeatureFlags(weighInNotification = true, weighInIntervalDays = 7),
    )

    @Test
    fun `notifications stay off unless asked for`() {
        val quiet = AppSettings(features = FeatureFlags(weighInIntervalDays = 7))
        assertTrue(!quiet.features.weighInNotification)
        assertTrue(!Reminders.weighInNotificationDue(90, quiet, 0L, now))
    }

    @Test
    fun `it waits for the interval, then fires`() {
        assertTrue(!Reminders.weighInNotificationDue(6, notifying, 0L, now))
        assertTrue(Reminders.weighInNotificationDue(7, notifying, 0L, now))
    }

    @Test
    fun `a daily check does not become daily nagging`() {
        // Already told them an hour ago; overdue or not, stay quiet.
        val recentlyTold = now - 60 * 60 * 1000L
        assertTrue(!Reminders.weighInNotificationDue(30, notifying, recentlyTold, now))
        val yesterday = now - Reminders.MIN_GAP_MS - 1
        assertTrue(Reminders.weighInNotificationDue(30, notifying, yesterday, now))
    }

    @Test
    fun `never having weighed in prompts immediately`() {
        assertTrue(Reminders.weighInNotificationDue(null, notifying, 0L, now))
    }

    @Test
    fun `reaching a losing goal is detected once`() {
        val goal = AppSettings(goal = GoalSettings(targetWeightKg = 85.0, weeklyChangeKg = -0.5))
        assertTrue(Reminders.goalJustReached(84.8, goal))
        assertTrue(!Reminders.goalJustReached(88.0, goal))
        // Already celebrated: do not say it twice.
        val done = goal.copy(goal = goal.goal.copy(goalAchievedAt = now))
        assertTrue(!Reminders.goalJustReached(84.8, done))
    }

    @Test
    fun `reaching a gaining goal is detected too`() {
        val goal = AppSettings(goal = GoalSettings(targetWeightKg = 62.0, weeklyChangeKg = 0.25))
        assertTrue(Reminders.goalJustReached(62.4, goal))
        assertTrue(!Reminders.goalJustReached(58.0, goal))
    }

    @Test
    fun `crossing the target does not un-reach it`() {
        // Direction must come from the rate, not from where you are standing: position
        // flips the moment you cross, which would make arriving undetectable.
        val losing = AppSettings(goal = GoalSettings(targetWeightKg = 85.0, weeklyChangeKg = -0.5))
        assertTrue(Reminders.goalJustReached(84.9, losing))
        assertTrue(Reminders.goalJustReached(80.0, losing))

        val gaining = AppSettings(goal = GoalSettings(targetWeightKg = 62.0, weeklyChangeKg = 0.25))
        assertTrue(Reminders.goalJustReached(62.1, gaining))
        assertTrue(Reminders.goalJustReached(70.0, gaining))
    }

    @Test
    fun `no target and no weight mean nothing to celebrate`() {
        assertTrue(!Reminders.goalJustReached(80.0, AppSettings()))
        val goal = AppSettings(goal = GoalSettings(targetWeightKg = 85.0))
        assertTrue(!Reminders.goalJustReached(null, goal))
    }
}
