package app.healthprofiler

import kotlin.math.roundToInt

data class Alert(val type: String, val level: String, val title: String, val detail: String, val watch: String?)

/** Same ranges as the dashboard page (AHA heart rate, SpO2 guidance, AHA target zones). */
object Health {
    fun restingHR(hr: Int): Alert? = when {
        hr > 120 -> Alert("restHR", "alert", "Resting heart rate high: $hr bpm",
            "Well above the normal resting range of 60–100 bpm. Sit down, breathe slowly and re-check in 5 minutes. If it stays this high, or you feel dizzy, short of breath or have chest discomfort, seek medical care.",
            "HR $hr at rest - high. Sit, breathe slowly, recheck in 5 min.")
        hr > 100 -> Alert("restHR", "caution", "Resting heart rate above normal: $hr bpm",
            "Normal resting range is 60–100 bpm. Caffeine, dehydration, stress, poor sleep or illness can raise it. Rest and re-check.",
            "HR $hr at rest is above 100. Rest & recheck.")
        hr < 40 -> Alert("restHR", "alert", "Resting heart rate very low: $hr bpm",
            "Below 40 bpm is unusually low. If you feel faint, tired or short of breath, seek medical care. Check the watch is snug.",
            "HR $hr - very low. Check fit; seek care if faint.")
        hr < 50 -> Alert("restHR", "caution", "Resting heart rate low: $hr bpm",
            "Below 50 bpm can be normal for fit people or during sleep, but tell your doctor if you also feel dizzy or tired.",
            "HR $hr at rest is low. Note any dizziness.")
        else -> null
    }

    fun activeHR(hr: Int, age: Int): Alert? {
        if (age <= 0) return null
        val max = (208 - 0.7 * age).roundToInt()
        return when {
            hr >= (max * 0.95).roundToInt() -> Alert("activeHR", "alert", "Heart rate $hr bpm — near your maximum",
                "Above 95% of your estimated maximum ($max bpm). Slow down and let your heart rate come down.",
                "HR $hr near max ($max). Slow down now.")
            hr >= (max * 0.85).roundToInt() -> Alert("activeHR", "caution", "Heart rate $hr bpm — above vigorous zone",
                "Above 85% of your estimated max. Ease off to stay in a safer training range.",
                "HR $hr above your zone. Ease off.")
            else -> null
        }
    }

    fun spo2(v: Int): Alert? = when {
        v < 88 -> Alert("spo2", "urgent", "Blood oxygen very low: $v%",
            "Re-measure sitting still with warm hands. If it is still below 88%, or you are short of breath, confused or have bluish lips, seek medical care now.",
            "SpO2 $v% very low. Re-measure; seek care if breathless.")
        v < 92 -> Alert("spo2", "alert", "Blood oxygen low: $v%",
            "Below 92% is low. Wrist sensors can under-read, so re-measure while still. If it stays low, contact your doctor.",
            "SpO2 $v% low. Sit still & re-measure.")
        v < 95 -> Alert("spo2", "caution", "Blood oxygen slightly low: $v%",
            "Normal is 95–100%. A single reading of 92–94% is often sensor error — re-check later.",
            "SpO2 $v% slightly low. Re-check later.")
        else -> null
    }

    fun median(values: List<Int>): Int? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else ((s[m - 1] + s[m]) / 2.0).roundToInt()
    }
}
