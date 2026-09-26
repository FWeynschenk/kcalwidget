package nl.flwe.kcalwidget

import nl.flwe.kcalwidget.data.history.BankedCarry
import nl.flwe.kcalwidget.data.history.DayRow
import nl.flwe.kcalwidget.data.history.History
import nl.flwe.kcalwidget.data.history.HistoryDiagnostics
import nl.flwe.kcalwidget.data.weight.WeightPoint
import nl.flwe.kcalwidget.data.weight.WeightTrend
import nl.flwe.kcalwidget.ui.history.toCsv
import nl.flwe.kcalwidget.widget.encodeSeries
import nl.flwe.kcalwidget.widget.toSeries
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

/**
 * Both of these shipped broken on a Dutch phone: the default locale writes "92,74", and
 * both formats are comma-separated, so every decimal value split into two fields. The
 * widget plotted nonsense and the CSV silently shifted every column after the weight.
 *
 * These run under a comma-decimal locale on purpose. Under Locale.US they pass either way,
 * which is exactly how the bug survived being written.
 */
class LocaleSerialisationTest {

    private val original = Locale.getDefault()

    @Before
    fun useCommaDecimals() = Locale.setDefault(Locale.GERMANY)

    @After
    fun restore() = Locale.setDefault(original)

    @Test
    fun `a weight series survives the round trip`() {
        val weights = listOf(92.74, 93.01, 92.55)
        assertEquals(weights, encodeSeries(weights, decimals = 2).toSeries())
    }

    @Test
    fun `the encoded series uses periods, so commas stay delimiters`() {
        assertEquals("92.74,93.01", encodeSeries(listOf(92.74, 93.01), decimals = 2))
    }

    @Test
    fun `a net series round trips`() {
        val nets = listOf(-1202.0, 156.0, -438.0)
        assertEquals(nets, encodeSeries(nets).toSeries())
    }

    @Test
    fun `an empty series is empty, not a list holding nothing`() {
        assertEquals(emptyList<Double>(), encodeSeries(emptyList()).toSeries())
    }

    @Test
    fun `every CSV row has the same number of columns as the header`() {
        val date = LocalDate.of(2026, 9, 20)
        val history = History(
            rows = listOf(DayRow(date, 1955.0, 2854.0, 92.74)),
            trend = WeightTrend(listOf(WeightPoint(date, 92.74, 93.01)), 93.01, -0.6, date, 1),
            carry = BankedCarry.NONE,
            diagnostics = HistoryDiagnostics(0, 0, true, 0, 0, 0, emptyList(), emptyList(), emptyList()),
        )
        val lines = toCsv(history).trim().lines()
        val header = lines.first().count { it == ',' }
        lines.drop(1).forEach { row ->
            assertEquals("row shifted: $row", header, row.count { it == ',' })
        }
    }
}
