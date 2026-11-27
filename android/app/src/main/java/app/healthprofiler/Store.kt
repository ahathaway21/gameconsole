package app.healthprofiler

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Small settings store shared by the screen and the background service. */
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("hp", Context.MODE_PRIVATE)

    fun address(c: Context): String? = sp(c).getString("address", null)
    fun deviceName(c: Context): String? = sp(c).getString("name", null)
    fun setDevice(c: Context, address: String?, name: String?) {
        sp(c).edit().putString("address", address).putString("name", name).apply()
    }

    /** Profile and settings as sent by the dashboard page (JSON). */
    fun profile(c: Context): JSONObject =
        try { JSONObject(sp(c).getString("profile", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
    fun setProfile(c: Context, json: String) { sp(c).edit().putString("profile", json).apply() }

    fun lastAlert(c: Context, type: String): Long = sp(c).getLong("alert_$type", 0)
    fun setLastAlert(c: Context, type: String, t: Long) { sp(c).edit().putLong("alert_$type", t).apply() }
}

/**
 * Readings recorded while the dashboard screen is closed. The page reads and clears
 * them ("drain") the next time it opens, and merges them into its history.
 */
object EventStore {
    private const val MAX_BYTES = 4_000_000L
    private fun file(c: Context) = File(c.filesDir, "events.jsonl")

    @Synchronized
    fun append(c: Context, evt: JSONObject) {
        val f = file(c)
        if (f.length() > MAX_BYTES) {
            val keep = f.readLines().takeLast(20_000)
            f.writeText(keep.joinToString("\n", postfix = "\n"))
        }
        f.appendText(evt.toString() + "\n")
    }

    @Synchronized
    fun drain(c: Context): JSONArray {
        val f = file(c)
        val out = JSONArray()
        if (!f.exists()) return out
        f.forEachLine { line -> if (line.isNotBlank()) try { out.put(JSONObject(line)) } catch (_: Exception) {} }
        f.delete()
        return out
    }
}
