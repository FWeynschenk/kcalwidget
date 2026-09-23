package nl.flwe.kcalwidget.data.weight

/**
 * The standard BMI bands.
 *
 * BMI is a crude proxy: it knows nothing about build or muscle, and a lifter and a
 * sedentary person of the same weight read identically. It is used here only to suggest
 * sensible milestones, never to tell anyone what they should weigh.
 */
enum class BmiBand(val lowerBound: Double, val label: String) {
    UNDERWEIGHT(0.0, "Underweight"),
    HEALTHY(18.5, "Healthy"),
    OVERWEIGHT(25.0, "Overweight"),
    OBESE_I(30.0, "Obesity, class I"),
    OBESE_II(35.0, "Obesity, class II"),
    OBESE_III(40.0, "Obesity, class III"),
}

object Bmi {

    const val HEALTHY_LOW = 18.5
    const val HEALTHY_HIGH = 25.0

    /** Middle of the healthy range, a reasonable "settled" target in either direction. */
    const val HEALTHY_MID = 22.0

    fun value(weightKg: Double, heightCm: Int): Double {
        val metres = heightCm / 100.0
        return if (metres <= 0) 0.0 else weightKg / (metres * metres)
    }

    fun weightForBmi(bmi: Double, heightCm: Int): Double {
        val metres = heightCm / 100.0
        return bmi * metres * metres
    }

    fun bandFor(bmi: Double): BmiBand =
        BmiBand.entries.last { bmi >= it.lowerBound }

    fun bandForWeight(weightKg: Double, heightCm: Int): BmiBand =
        bandFor(value(weightKg, heightCm))
}
