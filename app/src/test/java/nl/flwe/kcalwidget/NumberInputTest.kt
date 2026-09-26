package nl.flwe.kcalwidget

import nl.flwe.kcalwidget.data.weight.WeightGoal
import nl.flwe.kcalwidget.ui.components.parseDecimal
import nl.flwe.kcalwidget.ui.components.parseWholeNumber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The target-weight field displayed "92,5" on a Dutch phone and then could not parse its
 * own output, because toDoubleOrNull accepts a full stop and nothing else.
 */
class NumberInputTest {

    @Test
    fun `a comma is a decimal point, which is what the keypad offers`() {
        assertEquals(92.5, parseDecimal("92,5")!!, 0.0001)
    }

    @Test
    fun `a full stop still works`() {
        assertEquals(92.5, parseDecimal("92.5")!!, 0.0001)
    }

    @Test
    fun `the field can parse what it displays, in either convention`() {
        listOf("92,5", "92.5").forEach {
            assertEquals("round trip failed for $it", 92.5, parseDecimal(it)!!, 0.0001)
        }
    }

    @Test
    fun `with both separators the last one is the decimal point`() {
        assertEquals(1234.5, parseDecimal("1.234,5")!!, 0.0001)
        assertEquals(1234.5, parseDecimal("1,234.5")!!, 0.0001)
    }

    @Test
    fun `repeated separators are digit grouping`() {
        assertEquals(1234567.0, parseDecimal("1.234.567")!!, 0.0001)
        assertEquals(1234567.0, parseDecimal("1,234,567")!!, 0.0001)
    }

    @Test
    fun `spaces, including the ones number formatters use, are ignored`() {
        assertEquals(1234.5, parseDecimal("1 234,5")!!, 0.0001)
        assertEquals(1234.5, parseDecimal("1\u00A0234,5")!!, 0.0001)
    }

    @Test
    fun `nonsense is rejected rather than guessed at`() {
        assertNull(parseDecimal(""))
        assertNull(parseDecimal("   "))
        assertNull(parseDecimal("abc"))
        assertNull(parseDecimal(","))
        assertNull(parseDecimal("NaN"))
    }

    @Test
    fun `a negative value survives, since a goal rate can be one`() {
        assertEquals(-0.5, parseDecimal("-0,5")!!, 0.0001)
    }

    @Test
    fun `a grouped whole number is not rejected`() {
        assertEquals(1200, parseWholeNumber("1.200"))
        assertEquals(1200, parseWholeNumber("1,200"))
        assertEquals(1200, parseWholeNumber("1200"))
    }

    @Test
    fun `a whole-number field rounds rather than truncating`() {
        assertEquals(93, parseWholeNumber("92,7"))
        assertEquals(1969, parseWholeNumber("1969"))
    }
}

/**
 * The two parsers deliberately read "1.200" differently, so the reason is written down.
 * A kcal floor cannot be one and a fifth, so there the grouped reading is the only one
 * the user can have meant. A weight field can hold a fraction, so there a lone separator
 * stays a decimal point.
 */
class ParserDivergenceTest {

    @Test
    fun `a whole-number field reads a three-digit group as grouping`() {
        assertEquals(1200, parseWholeNumber("1.200"))
    }

    @Test
    fun `a decimal field reads the same text as a fraction`() {
        assertEquals(1.2, parseDecimal("1.200")!!, 0.0001)
    }

    @Test
    fun `fewer than three digits is a decimal point in both`() {
        assertEquals(93, parseWholeNumber("92,7"))
        assertEquals(92.7, parseDecimal("92,7")!!, 0.0001)
    }
}

/** The preset label is built from a BMI band name, and roman numerals do not survive a
 *  blanket lowercase. */
class BandLabelTest {

    @Test
    fun `a preset keeps the roman numeral in the band it names`() {
        val presets = WeightGoal.presets(currentKg = 92.5, heightCm = 170)
        presets.forEach { preset ->
            assertEquals(
                "roman numeral lowercased in: ${preset.label}",
                false,
                preset.label.contains("class i") || preset.label.contains("class ii"),
            )
        }
    }
}
