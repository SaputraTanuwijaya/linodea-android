package io.github.saputratanuwijaya.linodea.spike

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The spike's own instrument.
 *
 * Nobody watches logcat at 3am, and the phone has to be unplugged for the test
 * to mean anything (Doze does not engage while charging), so the app has to
 * record its own results and show them later. A run that leaves no record
 * produces no answer.
 *
 * Written from a BroadcastReceiver, so it is deliberately synchronous and
 * boring: `commit()` rather than `apply()`, because the process may be killed
 * the instant the receiver returns and a queued write would be the one result
 * that went missing.
 */
object SpikeLog {
    private const val PREFS = "linodea.alarm.spike"
    private const val KEY = "entries"

    /** One armed alarm and, once it happens, its firing. */
    data class Entry(
        val id: Int,
        val api: String,
        val armedAtMs: Long,
        val dueAtMs: Long,
        val firedAtMs: Long?,
        /** Whether the process had to be recreated between arming and firing. */
        val coldStart: Boolean,
    ) {
        /** How late the alarm was, in seconds. Negative would mean early. */
        val driftSeconds: Long?
            get() = firedAtMs?.let { (it - dueAtMs) / 1000 }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(context: Context): List<Entry> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { index ->
                val o = array.getJSONObject(index)
                Entry(
                    id = o.getInt("id"),
                    api = o.getString("api"),
                    armedAtMs = o.getLong("armedAt"),
                    dueAtMs = o.getLong("dueAt"),
                    firedAtMs = if (o.isNull("firedAt")) null else o.getLong("firedAt"),
                    coldStart = o.optBoolean("coldStart", false),
                )
            }
        }.getOrDefault(emptyList())
        // A corrupt file reads as no history rather than crashing on open. The
        // whole point of this app is to be readable the morning after.
    }

    private fun write(context: Context, entries: List<Entry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject().apply {
                    put("id", entry.id)
                    put("api", entry.api)
                    put("armedAt", entry.armedAtMs)
                    put("dueAt", entry.dueAtMs)
                    if (entry.firedAtMs == null) put("firedAt", JSONObject.NULL)
                    else put("firedAt", entry.firedAtMs)
                    put("coldStart", entry.coldStart)
                }
            )
        }
        prefs(context).edit().putString(KEY, array.toString()).commit()
    }

    fun armed(context: Context, id: Int, api: String, dueAtMs: Long) {
        write(context, all(context) + Entry(id, api, System.currentTimeMillis(), dueAtMs, null, false))
    }

    /**
     * Record that an alarm actually went off. `coldStart` says whether the
     * process had to be rebuilt to deliver it, which is how a kill by the OEM
     * battery manager shows up as something other than silence.
     */
    fun fired(context: Context, id: Int, coldStart: Boolean) {
        val now = System.currentTimeMillis()
        write(
            context,
            all(context).map { entry ->
                if (entry.id == id && entry.firedAtMs == null) {
                    entry.copy(firedAtMs = now, coldStart = coldStart)
                } else {
                    entry
                }
            },
        )
    }

    /** Alarms that were armed and have not reported firing. */
    fun pending(context: Context): List<Entry> = all(context).filter { it.firedAtMs == null }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY).commit()
    }
}
