package nl.flwe.kcalwidget

import nl.flwe.kcalwidget.data.settings.AppSettings
import nl.flwe.kcalwidget.data.settings.FeatureFlags
import nl.flwe.kcalwidget.widget.WidgetRepository
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetNudgeTest {

    private val on = AppSettings(features = FeatureFlags(weighInIntervalDays = 7))
    private val off = AppSettings(
        features = FeatureFlags(widgetWeighInNudge = false, weighInIntervalDays = 7),
    )

    @Test
    fun `the nudge waits for the configured interval`() {
        assertTrue(!WidgetRepository.weighInDue(0, on))
        assertTrue(!WidgetRepository.weighInDue(6, on))
        assertTrue(WidgetRepository.weighInDue(7, on))
        assertTrue(WidgetRepository.weighInDue(30, on))
    }

    @Test
    fun `never weighed means the nudge is due straight away`() {
        assertTrue(WidgetRepository.weighInDue(null, on))
    }

    @Test
    fun `turning it off silences it even when long overdue`() {
        assertTrue(!WidgetRepository.weighInDue(90, off))
        assertTrue(!WidgetRepository.weighInDue(null, off))
    }

    @Test
    fun `a custom interval is honoured`() {
        val fortnightly = AppSettings(features = FeatureFlags(weighInIntervalDays = 14))
        assertTrue(!WidgetRepository.weighInDue(13, fortnightly))
        assertTrue(WidgetRepository.weighInDue(14, fortnightly))
    }
}
