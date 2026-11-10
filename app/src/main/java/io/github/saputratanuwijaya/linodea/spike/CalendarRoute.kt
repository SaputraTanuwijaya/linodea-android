package io.github.saputratanuwijaya.linodea.spike

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.CalendarAlerts
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import androidx.core.content.ContextCompat
import java.util.TimeZone

/**
 * Hands a reminder to the phone's own calendar instead of fighting for the
 * right to wake up.
 *
 * Every lever tried so far asks XOS to let *Linodea's* alarm through, and on
 * an 8-hour wait it does not. This one stops asking. The reminder is written
 * into a calendar that exists only on this phone -- account type LOCAL, which
 * has no account behind it and is never synced anywhere -- and the system's
 * calendar storage, a part of Android itself, schedules the alarm and sends
 * the reminder broadcast when it is due.
 *
 * Two things then hear that broadcast: whatever calendar app the phone has,
 * which shows its own notification, and Linodea's `CalendarReminderReceiver`.
 * Either arriving is a delivery. Whether XOS lets a system component's
 * reminder through where it holds ours is the whole question, and it is
 * untested until this runs on the phone.
 *
 * **Not yet a product decision.** SKILLS.md says `AlarmManager` owns the
 * alarm; this bends that wording while keeping what it protects (fires on the
 * phone, offline, no server). It ships only if it works *and* Saputra revises
 * the wording. Here it is one arm of a comparison.
 */
object CalendarRoute {
    private const val ACCOUNT = "Linodea"
    private const val ACCOUNT_TYPE = CalendarContract.ACCOUNT_TYPE_LOCAL
    private const val CALENDAR_COLOR = 0xFF5B6CFF.toInt()

    /** A calendar entry needs an end; a reminder has none, so it gets a token minute. */
    private const val EVENT_LENGTH_MS = 60_000L

    val PERMISSIONS = arrayOf(
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR,
    )

    fun hasPermission(context: Context): Boolean = PERMISSIONS.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Creating a calendar is reserved for whatever owns its account. A local
     * calendar has no owner, so the app names itself as one for this call.
     */
    private fun asOwner(uri: Uri): Uri = uri.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(Calendars.ACCOUNT_NAME, ACCOUNT)
        .appendQueryParameter(Calendars.ACCOUNT_TYPE, ACCOUNT_TYPE)
        .build()

    private fun calendarId(context: Context): Long? =
        context.contentResolver.query(
            Calendars.CONTENT_URI,
            arrayOf(Calendars._ID),
            "${Calendars.ACCOUNT_NAME} = ? AND ${Calendars.ACCOUNT_TYPE} = ?",
            arrayOf(ACCOUNT, ACCOUNT_TYPE),
            null,
        )?.use { if (it.moveToFirst()) it.getLong(0) else null }

    private fun ensureCalendar(context: Context): Long {
        calendarId(context)?.let { return it }
        val values = ContentValues().apply {
            put(Calendars.ACCOUNT_NAME, ACCOUNT)
            put(Calendars.ACCOUNT_TYPE, ACCOUNT_TYPE)
            put(Calendars.NAME, "linodea")
            put(Calendars.CALENDAR_DISPLAY_NAME, "Linodea")
            put(Calendars.CALENDAR_COLOR, CALENDAR_COLOR)
            put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
            put(Calendars.OWNER_ACCOUNT, ACCOUNT)
            // Both required: the system only schedules reminders for
            // calendars that are visible and whose events are kept.
            put(Calendars.VISIBLE, 1)
            put(Calendars.SYNC_EVENTS, 1)
            put(Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().id)
        }
        val created = context.contentResolver.insert(asOwner(Calendars.CONTENT_URI), values)
            ?: error("the phone's calendar storage refused to create a calendar")
        return ContentUris.parseId(created)
    }

    /**
     * Writes one reminder into the local calendar. Returns its event id, or a
     * failure whose message is fit to show: a missing permission and a phone
     * with no calendar storage both land here, and both mean "this arm was not
     * tested", which must not be mistaken for "this arm failed".
     */
    fun arm(context: Context, title: String, dueAtMs: Long): Result<Long> = runCatching {
        check(hasPermission(context)) { "calendar permission not granted" }
        val resolver = context.contentResolver
        val calendar = ensureCalendar(context)
        val event = resolver.insert(
            Events.CONTENT_URI,
            ContentValues().apply {
                put(Events.CALENDAR_ID, calendar)
                put(Events.TITLE, title)
                put(Events.DTSTART, dueAtMs)
                put(Events.DTEND, dueAtMs + EVENT_LENGTH_MS)
                put(Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                put(Events.HAS_ALARM, 1)
                put(Events.AVAILABILITY, Events.AVAILABILITY_FREE)
            },
        ) ?: error("the phone's calendar storage refused the event")
        val eventId = ContentUris.parseId(event)
        resolver.insert(
            Reminders.CONTENT_URI,
            ContentValues().apply {
                put(Reminders.EVENT_ID, eventId)
                // Zero minutes before: the reminder is the event.
                put(Reminders.MINUTES, 0)
                put(Reminders.METHOD, Reminders.METHOD_ALERT)
            },
        ) ?: error("the phone's calendar storage refused the reminder")
        eventId
    }

    /**
     * The calendar's own record of one reminder: whether it scheduled it, and
     * whether a calendar app says it was received and shown.
     */
    data class AlertRow(
        val state: String,
        val alarmAtMs: Long,
        /** When a calendar app says it showed the reminder, if one did. */
        val notifiedAtMs: Long?,
    )

    /**
     * Why the calendar's record could not be read the last time it was
     * tried, or null. Kept because a phone's calendar storage can differ from
     * stock Android's, and "cannot be read" with no reason teaches nothing.
     */
    @Volatile
    var lastReadProblem: String? = null
        private set

    /**
     * Null when it cannot be read; an empty list when the calendar has no
     * record yet. The system only writes one for the *next* reminder due on
     * the phone, so "no record yet" hours ahead is normal, not a failure.
     */
    fun alertRows(context: Context, eventId: Long): List<AlertRow>? {
        if (!hasPermission(context)) return null
        return runCatching {
            context.contentResolver.query(
                CalendarAlerts.CONTENT_URI,
                // Not RECEIVED_TIME: the documentation lists it, and stock
                // Android 15 refuses it as an invalid column.
                arrayOf(
                    CalendarAlerts.STATE,
                    CalendarAlerts.ALARM_TIME,
                    CalendarAlerts.NOTIFY_TIME,
                ),
                "${CalendarAlerts.EVENT_ID} = ?",
                arrayOf(eventId.toString()),
                "${CalendarAlerts.ALARM_TIME} ASC",
            )?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            AlertRow(
                                state = stateName(cursor.getInt(0)),
                                alarmAtMs = cursor.getLong(1),
                                notifiedAtMs = cursor.getLong(2).takeIf { it > 0 },
                            )
                        )
                    }
                }
            }
        }.onSuccess { lastReadProblem = null }
            .onFailure { lastReadProblem = "${it.javaClass.simpleName}: ${it.message}" }
            .getOrNull()
    }

    fun stateName(state: Int): String = when (state) {
        CalendarAlerts.STATE_SCHEDULED -> "scheduled"
        CalendarAlerts.STATE_FIRED -> "fired"
        CalendarAlerts.STATE_DISMISSED -> "dismissed"
        else -> "state $state"
    }

    /** Removes every test event. The calendar itself stays, empty. */
    fun deleteAllEvents(context: Context): Int {
        if (!hasPermission(context)) return 0
        return runCatching {
            val calendar = calendarId(context) ?: return 0
            context.contentResolver.delete(
                asOwner(Events.CONTENT_URI),
                "${Events.CALENDAR_ID} = ?",
                arrayOf(calendar.toString()),
            )
        }.getOrDefault(0)
    }

    /**
     * Every app on the phone that is told when a calendar reminder is due.
     * Linodea is one. If it is the only one, no calendar app will show a
     * notification of its own and Linodea's receiver is the whole test.
     */
    fun reminderApps(context: Context): List<String> = runCatching {
        val probe = Intent(
            CalendarContract.ACTION_EVENT_REMINDER,
            ContentUris.withAppendedId(CalendarContract.CONTENT_URI, 0),
        )
        context.packageManager.queryBroadcastReceivers(probe, 0)
            .mapNotNull { it.activityInfo?.packageName }
            .distinct()
            .sorted()
    }.getOrDefault(emptyList())
}
