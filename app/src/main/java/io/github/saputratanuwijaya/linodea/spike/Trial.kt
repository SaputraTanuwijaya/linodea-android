package io.github.saputratanuwijaya.linodea.spike

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * One test run: every way this phone can be asked to deliver a reminder, all
 * armed for the same minute, so a single wait compares them.
 *
 * Each 8-hour run costs Saputra a night, and the two so far each answered one
 * question. This arms, together:
 *
 * - `setAlarmClock` -- the path that has failed twice at this distance;
 * - `setExactAndAllowWhileIdle` -- never yet tried with the process dead;
 * - a reminder in the phone's own calendar (`CalendarRoute`);
 * - a ladder of heartbeats on the way there, which says *when* a hold starts
 *   and whether waking the app periodically keeps the phone delivering to it;
 * - a countdown notification that needs nothing to run at the due time.
 *
 * A run where everything fails is still an answer; a run where one path
 * passes says which way to build.
 */
object Trial {
    private const val PREFS = "linodea.alarm.spike.trials"
    private const val KEY = "trials"
    private const val KEY_NEXT = "next"

    data class Info(
        val id: Int,
        val label: String,
        val startedAtMs: Long,
        val dueAtMs: Long,
        val beatEveryMs: Long,
        /** What happened when the calendar path was armed, in words. */
        val calendar: String,
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(context: Context): List<Info> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { index ->
                val o = array.getJSONObject(index)
                Info(
                    id = o.getInt("id"),
                    label = o.getString("label"),
                    startedAtMs = o.getLong("startedAt"),
                    dueAtMs = o.getLong("dueAt"),
                    beatEveryMs = o.optLong("beatEvery", 0L),
                    calendar = o.optString("calendar", ""),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun save(context: Context, trials: List<Info>) {
        val array = JSONArray()
        trials.forEach { t ->
            array.put(
                JSONObject().apply {
                    put("id", t.id)
                    put("label", t.label)
                    put("startedAt", t.startedAtMs)
                    put("dueAt", t.dueAtMs)
                    put("beatEvery", t.beatEveryMs)
                    put("calendar", t.calendar)
                }
            )
        }
        prefs(context).edit().putString(KEY, array.toString()).commit()
    }

    fun start(context: Context, label: String, minutes: Int, beatMinutes: Int): Info {
        // Every pass so far came from a dead process, and every live one was
        // frozen. A run with this off would compare nothing.
        EndWhenHidden.setEnabled(context, true)

        val prefs = prefs(context)
        val id = prefs.getInt(KEY_NEXT, 1)
        prefs.edit().putInt(KEY_NEXT, id + 1).commit()

        val now = System.currentTimeMillis()
        val due = TrialPlan.dueAt(now, minutes)
        val every = beatMinutes * 60_000L

        AlarmScheduler.arm(
            context, SpikeLog.takeNextId(context), AlarmScheduler.Api.ALARM_CLOCK, due, trial = id,
        )
        AlarmScheduler.arm(
            context, SpikeLog.takeNextId(context), AlarmScheduler.Api.EXACT_WHILE_IDLE, due, trial = id,
        )
        TrialPlan.beatTimes(now, due, every).forEach { at ->
            AlarmScheduler.arm(
                context,
                SpikeLog.takeNextId(context),
                AlarmScheduler.Api.EXACT_WHILE_IDLE,
                at,
                label = SpikeLog.API_HEARTBEAT,
                trial = id,
            )
        }

        val calendar = CalendarRoute.arm(context, "Linodea test #$id", due).fold(
            onSuccess = { eventId ->
                SpikeLog.armed(
                    context,
                    SpikeLog.takeNextId(context),
                    SpikeLog.API_CALENDAR,
                    due,
                    DeviceState.snapshot(context),
                    trial = id,
                    calendarEventId = eventId,
                )
                "handed to the phone's calendar (event $eventId)"
            },
            onFailure = { "NOT TESTED - ${it.message ?: it.javaClass.simpleName}" },
        )

        Notify.upcoming(context, id, due)

        val info = Info(id, label, now, due, every, calendar)
        save(context, all(context) + info)
        Journal.note(context, "test #$id armed ($label), due $due, calendar: $calendar")
        return info
    }

    fun clear(context: Context) {
        // The counter is kept: a test number is never reused, for the same
        // reason an alarm id is not.
        prefs(context).edit().remove(KEY).commit()
    }
}
