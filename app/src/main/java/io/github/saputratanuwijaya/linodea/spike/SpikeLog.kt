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
    private const val KEY_NEXT_ID = "nextId"

    /**
     * Within this many seconds of due counts as on time. `setAlarmClock` is
     * normally delivered within a second or two; a minute leaves room for a
     * slow wake without letting a held alarm pass.
     */
    const val ON_TIME_SECONDS = 60L

    /**
     * How far past due before the app's own code may call something overdue
     * and announce it itself. Shorter, and a catch-up could beat an alarm that
     * is merely in the middle of being delivered.
     */
    const val OVERDUE_AFTER_MS = 20_000L

    /** A periodic wake with no notification: it only records that it arrived. */
    const val API_HEARTBEAT = "heartbeat"

    /** Delivered by the system calendar's reminder broadcast, not by an alarm of ours. */
    const val API_CALENDAR = "calendar reminder"

    /**
     * The calendar's alarm time and ours are the same whole minute; anything
     * within this of it is the same reminder.
     */
    private const val CALENDAR_MATCH_MS = 60_000L

    /**
     * What was true on the phone at one moment. Any field is null when it was
     * not recorded -- entries written by an older build, or a read that threw.
     */
    data class Snapshot(
        val screenOn: Boolean?,
        val locked: Boolean?,
        val plugged: Boolean?,
        val keepAlive: Boolean?,
        val batteryExempt: Boolean?,
        val endWhenHidden: Boolean? = null,
        val batteryPercent: Int? = null,
        /** Android's own battery saver. XOS may have its own that this cannot see. */
        val powerSave: Boolean? = null,
        /** How recently Android thinks the app was used: ACTIVE ... RESTRICTED. */
        val bucket: String? = null,
        /** The user-facing "restrict background activity" switch. */
        val backgroundRestricted: Boolean? = null,
        /** Doze: "deep", "light" or "no". */
        val idle: String? = null,
        /** The system's next alarm clock, from any app. 0 means there is none. */
        val nextAlarmClockAtMs: Long? = null,
        /**
         * How long the phone's processor has been asleep since it was switched
         * on. Two readings apart say how much of a wait the phone slept
         * through, which separates "the phone was asleep and nothing woke it"
         * from "the phone was awake and still did not deliver".
         */
        val sleptMs: Long? = null,
    )

    /**
     * What a delivery means, as opposed to when it happened.
     *
     * The first three runs recorded drift alone, and drift could not tell "the
     * alarm fired late" from "the alarm was held until someone woke the phone"
     * -- both are a number of seconds after due. The screen's state at the
     * moment of delivery separates them: a held alarm is released *by* the
     * screen coming on, so it arrives with the screen on, while an alarm that
     * genuinely fired in an untouched phone arrives with it off.
     */
    enum class Verdict {
        /** Not delivered yet. */
        WAITING,

        /** On time with the screen off: the alarm woke the phone by itself. The pass. */
        RANG_IN_DARK,

        /** Screen off but late: deferred by something other than a person (Doze, a rate limit, a periodic thaw). */
        LATE_IN_DARK,

        /** Late, and the screen was on: held until the phone was woken. The freeze. */
        HELD_UNTIL_SCREEN_ON,

        /** On time, but the screen was already on -- proves nothing about the screen-off case. */
        SCREEN_WAS_ON,

        /** Delivered to a build that did not look at the screen. */
        UNRECORDED,
    }

    /** One armed alarm and, once it happens, its firing. */
    data class Entry(
        val id: Int,
        val api: String,
        val armedAtMs: Long,
        val dueAtMs: Long,
        val firedAtMs: Long?,
        /** Whether the process had to be recreated between arming and firing. */
        val coldStart: Boolean,
        /** The phone's state when the alarm was armed. */
        val armed: Snapshot? = null,
        /** The phone's state at the instant the alarm was delivered. */
        val atFire: Snapshot? = null,
        /**
         * Delivered for an id the log did not have: armed before a Clear, or
         * delivered twice. Recorded rather than dropped -- an alarm that rang
         * with nowhere to write it is how the overnight run grew a phantom.
         */
        val unlogged: Boolean = false,
        /** Which test run armed this. Groups the paths that share one due time. */
        val trial: Int? = null,
        /**
         * When the app's own code saw this was overdue and announced it
         * itself -- on being opened, or woken by something else. The alarm
         * stays armed, so `firedAtMs` still records when the system finally
         * delivered it, and the gap between the two is how long a user who
         * opened the app would otherwise have gone on waiting.
         */
        val noticedAtMs: Long? = null,
        /** What was running when it was noticed: "the app being opened", "a heartbeat". */
        val noticedBy: String? = null,
        /** The phone's state when it was noticed, i.e. while the alarm was still held. */
        val atNotice: Snapshot? = null,
        /** For the calendar path: the event the system calendar was given. */
        val calendarEventId: Long? = null,
    ) {
        /** How late the alarm was, in seconds. Negative would mean early. */
        val driftSeconds: Long?
            get() = firedAtMs?.let { (it - dueAtMs) / 1000 }

        val isHeartbeat: Boolean
            get() = api == API_HEARTBEAT

        /** Past due by enough that it is not simply mid-delivery, and still not here. */
        fun isOverdue(nowMs: Long): Boolean =
            firedAtMs == null && nowMs - dueAtMs > OVERDUE_AFTER_MS

        val verdict: Verdict
            get() {
                val drift = driftSeconds ?: return Verdict.WAITING
                val screenOn = atFire?.screenOn ?: return Verdict.UNRECORDED
                val onTime = drift <= ON_TIME_SECONDS
                return when {
                    !screenOn && onTime -> Verdict.RANG_IN_DARK
                    !screenOn -> Verdict.LATE_IN_DARK
                    onTime -> Verdict.SCREEN_WAS_ON
                    else -> Verdict.HELD_UNTIL_SCREEN_ON
                }
            }

        /** Delivered within a minute of due, whatever the screen was doing. */
        val arrivedOnTime: Boolean
            get() = driftSeconds?.let { it <= ON_TIME_SECONDS } ?: false

        /**
         * Charging voids a pass, not a failure. Doze does not engage on a
         * charger, so an alarm that rang while plugged in proves nothing about
         * the unplugged case. But one armed unplugged that was held anyway
         * failed while unplugged; arriving after a charger went in only says
         * what released it -- which is how the overnight run was first
         * misread as void.
         */
        val voidedByCharging: Boolean
            get() = armed?.plugged == true ||
                (atFire?.plugged == true && verdict == Verdict.RANG_IN_DARK)

        /** Failed unplugged, then arrived on the charger: charging may be the release. */
        val releasedOnCharger: Boolean
            get() = !voidedByCharging && atFire?.plugged == true

        /**
         * The one line a person reads, on the notification and on the screen.
         * Shared so the two can never disagree about what a run meant.
         */
        fun headline(nowMs: Long): String {
            val drift = driftSeconds
            return when (verdict) {
                Verdict.WAITING ->
                    // Once the app has announced it as overdue it is not
                    // "waiting" any more, even inside the first minute.
                    if (nowMs - dueAtMs > ON_TIME_SECONDS * 1000 || noticedAtMs != null) {
                        "NOT DELIVERED - due ${formatDuration((nowMs - dueAtMs) / 1000)} ago"
                    } else {
                        "waiting"
                    }
                Verdict.RANG_IN_DARK ->
                    "PASS - arrived ${formatDuration(drift!!)} after due, screen off"
                Verdict.LATE_IN_DARK ->
                    "LATE - ${formatDuration(drift!!)} after due, screen off (deferred, not held)"
                Verdict.HELD_UNTIL_SCREEN_ON ->
                    "HELD - arrived only when the screen came on, ${formatDuration(drift!!)} after due"
                Verdict.SCREEN_WAS_ON ->
                    "INCONCLUSIVE - on time (${formatDuration(drift!!)}), but the screen was on"
                Verdict.UNRECORDED ->
                    "${formatDuration(drift!!)} after due (old build: screen not recorded)"
            }
        }

        /** "the app caught it itself 5h 44m after due (the app being opened)", or null. */
        fun noticedLine(): String? {
            val at = noticedAtMs ?: return null
            return "the app announced it itself ${formatDuration((at - dueAtMs) / 1000)} after due" +
                (noticedBy?.let { " ($it)" } ?: "")
        }
    }

    /**
     * "3m 07s" rather than "187s": a drift is read by a person deciding whether
     * it matches how long they left the phone alone.
     */
    fun formatDuration(seconds: Long): String {
        val sign = if (seconds < 0) "-" else ""
        val s = kotlin.math.abs(seconds)
        return when {
            s < 60 -> "$sign${s}s"
            s < 3600 -> "$sign${s / 60}m %02ds".format(s % 60)
            else -> "$sign${s / 3600}h %02dm".format((s % 3600) / 60)
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun JSONObject.bool(key: String): Boolean? =
        if (has(key) && !isNull(key)) getBoolean(key) else null

    private fun JSONObject.int(key: String): Int? =
        if (has(key) && !isNull(key)) getInt(key) else null

    private fun JSONObject.long(key: String): Long? =
        if (has(key) && !isNull(key)) getLong(key) else null

    private fun JSONObject.text(key: String): String? =
        if (has(key) && !isNull(key)) getString(key) else null

    private fun Snapshot.toJson() = JSONObject().apply {
        putOpt("screenOn", screenOn)
        putOpt("locked", locked)
        putOpt("plugged", plugged)
        putOpt("keepAlive", keepAlive)
        putOpt("batteryExempt", batteryExempt)
        putOpt("endWhenHidden", endWhenHidden)
        putOpt("batteryPercent", batteryPercent)
        putOpt("powerSave", powerSave)
        putOpt("bucket", bucket)
        putOpt("backgroundRestricted", backgroundRestricted)
        putOpt("idle", idle)
        putOpt("nextAlarmClockAt", nextAlarmClockAtMs)
        putOpt("slept", sleptMs)
    }

    private fun snapshotOf(o: JSONObject?): Snapshot? = o?.let {
        Snapshot(
            screenOn = it.bool("screenOn"),
            locked = it.bool("locked"),
            plugged = it.bool("plugged"),
            keepAlive = it.bool("keepAlive"),
            batteryExempt = it.bool("batteryExempt"),
            endWhenHidden = it.bool("endWhenHidden"),
            batteryPercent = it.int("batteryPercent"),
            powerSave = it.bool("powerSave"),
            bucket = it.text("bucket"),
            backgroundRestricted = it.bool("backgroundRestricted"),
            idle = it.text("idle"),
            nextAlarmClockAtMs = it.long("nextAlarmClockAt"),
            sleptMs = it.long("slept"),
        )
    }

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
                    firedAtMs = o.long("firedAt"),
                    coldStart = o.optBoolean("coldStart", false),
                    armed = snapshotOf(o.optJSONObject("armed")),
                    atFire = snapshotOf(o.optJSONObject("atFire")),
                    unlogged = o.optBoolean("unlogged", false),
                    trial = o.int("trial"),
                    noticedAtMs = o.long("noticedAt"),
                    noticedBy = o.text("noticedBy"),
                    atNotice = snapshotOf(o.optJSONObject("atNotice")),
                    calendarEventId = o.long("calendarEventId"),
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
                    entry.armed?.let { put("armed", it.toJson()) }
                    entry.atFire?.let { put("atFire", it.toJson()) }
                    if (entry.unlogged) put("unlogged", true)
                    putOpt("trial", entry.trial)
                    putOpt("noticedAt", entry.noticedAtMs)
                    putOpt("noticedBy", entry.noticedBy)
                    entry.atNotice?.let { put("atNotice", it.toJson()) }
                    putOpt("calendarEventId", entry.calendarEventId)
                }
            )
        }
        prefs(context).edit().putString(KEY, array.toString()).commit()
    }

    fun armed(
        context: Context,
        id: Int,
        api: String,
        dueAtMs: Long,
        state: Snapshot,
        trial: Int? = null,
        calendarEventId: Long? = null,
    ) {
        write(
            context,
            all(context) + Entry(
                id = id,
                api = api,
                armedAtMs = System.currentTimeMillis(),
                dueAtMs = dueAtMs,
                firedAtMs = null,
                coldStart = false,
                armed = state,
                trial = trial,
                calendarEventId = calendarEventId,
            ),
        )
    }

    /**
     * Record that an alarm actually went off. `coldStart` says whether the
     * process had to be rebuilt to deliver it, which is how a kill by the OEM
     * battery manager shows up as something other than silence; `state` says
     * whether anyone had woken the phone, which is how a freeze shows up as
     * something other than lateness.
     */
    fun fired(context: Context, id: Int, coldStart: Boolean, state: Snapshot): Entry? {
        val now = System.currentTimeMillis()
        var delivered: Entry? = null
        write(
            context,
            all(context).map { entry ->
                if (entry.id == id && entry.firedAtMs == null) {
                    entry.copy(firedAtMs = now, coldStart = coldStart, atFire = state)
                        .also { delivered = it }
                } else {
                    entry
                }
            },
        )
        return delivered
    }

    /**
     * Record a delivery the log has no entry for. The due time comes from the
     * alarm's own intent, so even a phantom gets a verdict and a drift.
     */
    fun unlogged(
        context: Context,
        id: Int,
        api: String,
        dueAtMs: Long,
        coldStart: Boolean,
        state: Snapshot,
    ): Entry {
        val entry = Entry(
            id = id,
            api = api,
            armedAtMs = dueAtMs,
            dueAtMs = dueAtMs,
            firedAtMs = System.currentTimeMillis(),
            coldStart = coldStart,
            atFire = state,
            unlogged = true,
        )
        write(context, all(context) + entry)
        return entry
    }

    /**
     * The system calendar's reminder broadcast arrived. Marks the calendar
     * entries that broadcast was for as delivered and returns them.
     *
     * `alarmAtMs` is the time the calendar says this reminder was for, or
     * null if the broadcast did not carry one. Matching on it matters: the
     * broadcast goes out for every calendar on the phone, so one for somebody's
     * meeting must not be recorded as ours arriving.
     */
    fun calendarFired(
        context: Context,
        alarmAtMs: Long?,
        coldStart: Boolean,
        state: Snapshot,
        nowMs: Long = System.currentTimeMillis(),
    ): List<Entry> {
        val delivered = mutableListOf<Entry>()
        val updated = all(context).map { entry ->
            if (calendarMatches(entry, alarmAtMs, nowMs)) {
                entry.copy(firedAtMs = nowMs, coldStart = coldStart, atFire = state)
                    .also { delivered += it }
            } else {
                entry
            }
        }
        if (delivered.isNotEmpty()) write(context, updated)
        return delivered
    }

    /** Pure, so the matching rule can be tested without a phone. */
    fun calendarMatches(entry: Entry, alarmAtMs: Long?, nowMs: Long): Boolean {
        if (entry.api != API_CALENDAR || entry.firedAtMs != null) return false
        return if (alarmAtMs != null) {
            kotlin.math.abs(alarmAtMs - entry.dueAtMs) < CALENDAR_MATCH_MS
        } else {
            entry.dueAtMs <= nowMs
        }
    }

    /**
     * Everything that should have been delivered by now and was not: marked
     * as noticed by the app's own code, and returned so the caller can
     * announce it. Heartbeats are left out -- they announce nothing, and a
     * missing one is already a result.
     */
    fun noticeOverdue(
        context: Context,
        by: String,
        state: Snapshot,
        nowMs: Long = System.currentTimeMillis(),
    ): List<Entry> {
        val caught = mutableListOf<Entry>()
        val updated = all(context).map { entry ->
            if (needsNoticing(entry, nowMs)) {
                entry.copy(noticedAtMs = nowMs, noticedBy = by, atNotice = state)
                    .also { caught += it }
            } else {
                entry
            }
        }
        if (caught.isNotEmpty()) write(context, updated)
        return caught
    }

    fun needsNoticing(entry: Entry, nowMs: Long): Boolean =
        !entry.isHeartbeat && entry.noticedAtMs == null && entry.isOverdue(nowMs)

    /**
     * Whether a delivery of ours is due about now and has not arrived: its
     * broadcast may be on its way to this very process.
     */
    fun anythingDueAround(context: Context, nowMs: Long, withinMs: Long): Boolean =
        all(context).any { isDueAround(it, nowMs, withinMs) }

    fun isDueAround(entry: Entry, nowMs: Long, withinMs: Long): Boolean =
        entry.firedAtMs == null && kotlin.math.abs(entry.dueAtMs - nowMs) <= withinMs

    /** Alarms that were armed and have not reported firing. */
    fun pending(context: Context): List<Entry> = all(context).filter { it.firedAtMs == null }

    /**
     * The next alarm id, never reused. Ids used to be recomputed from the log,
     * so a Clear reset them to 1 and a new alarm could share a number with
     * one still armed.
     */
    fun takeNextId(context: Context): Int {
        val prefs = prefs(context)
        val next = maxOf(prefs.getInt(KEY_NEXT_ID, 1), (all(context).maxOfOrNull { it.id } ?: 0) + 1)
        prefs.edit().putInt(KEY_NEXT_ID, next + 1).commit()
        return next
    }

    /** Highest id ever handed out, for cancelling everything that might still be armed. */
    fun highestId(context: Context): Int = prefs(context).getInt(KEY_NEXT_ID, 1) - 1

    /** Empties the log. Cancelling the alarms is the caller's job, and must come first. */
    fun clear(context: Context) {
        prefs(context).edit().remove(KEY).commit()
    }
}
