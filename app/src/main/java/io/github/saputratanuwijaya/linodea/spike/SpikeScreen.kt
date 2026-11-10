package io.github.saputratanuwijaya.linodea.spike

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Shown at the top so a glance confirms the phone is running this build and
 * not the one before it -- a sideload that silently failed would otherwise
 * produce results from the old instrument.
 */
const val SPIKE_BUILD = "Instrument v6 - one test, every path"

/**
 * Builds before v5 reused ids from 1 after every Clear, so anything they left
 * armed sits in this range. Sweeping it costs a few lookups.
 */
private const val CANCEL_SWEEP = 100

/**
 * How long after a test's due time the Clock control rings.
 *
 * Not at the same minute. A ringing alarm lights the screen, and the screen
 * coming on is exactly what releases a held delivery on XOS. Tried on the
 * emulator with both at 13:35:00: every path arrived on time and every one
 * was recorded "INCONCLUSIVE - the screen was on", because the Clock got
 * there first. Two minutes apart, anything of ours that only arrives when the
 * Clock rings is more than a minute late and reads as HELD, which is the
 * truth.
 */
private const val CLOCK_CONTROL_AFTER_MS = 2 * 60_000L

private val CLOCK = SimpleDateFormat("EEE HH:mm:ss", Locale.getDefault())
private val HOUR_MINUTE = SimpleDateFormat("HH:mm", Locale.getDefault())

private fun clock(ms: Long): String = CLOCK.format(Date(ms))

/** Everything the screen shows, read in one go so no two cards disagree. */
private data class Screen(
    val nowMs: Long,
    val entries: List<SpikeLog.Entry>,
    val trials: List<Trial.Info>,
    val crash: String?,
    val exactAllowed: Boolean,
    val notificationsOn: Boolean,
    val calendarAllowed: Boolean,
    val keepAlive: Boolean,
    val endWhenHidden: Boolean,
    val state: SpikeLog.Snapshot,
    val exits: List<ExitHistory.Exit>?,
    val starts: List<StartHistory.Start>?,
    val calendarApps: List<String>,
    val calendarRows: Map<Long, List<CalendarRoute.AlertRow>?>,
    val journal: List<Journal.Line>,
    val calendarProblem: String?,
) {
    val setup: List<String>
        get() = listOf(
            "battery: " + if (state.batteryExempt == true) "unrestricted" else "OPTIMISED",
            "exact alarms: " + if (exactAllowed) "allowed" else "NOT ALLOWED",
            "notifications: " + if (notificationsOn) "on" else "OFF",
            "calendar permission: " + if (calendarAllowed) "granted" else "NOT GRANTED",
            "end when hidden: " + if (endWhenHidden) "on" else "off",
            "keep-alive: " + if (keepAlive) "ON" else "off",
        )

    fun report(): String = Report.build(
        Report.Input(
            build = SPIKE_BUILD,
            device = DeviceState.describeDevice(),
            nowMs = nowMs,
            now = state,
            setup = setup,
            calendarApps = calendarApps,
            trials = trials,
            entries = entries,
            calendarRows = calendarRows,
            exits = exits,
            starts = starts,
            journal = journal,
            crash = crash,
            calendarProblem = calendarProblem,
        ),
        ::clock,
    )
}

private fun read(context: Context): Screen {
    val entries = SpikeLog.all(context)
    return Screen(
        nowMs = System.currentTimeMillis(),
        entries = entries,
        trials = Trial.all(context),
        crash = CrashLog.last(context),
        exactAllowed = AlarmScheduler.canScheduleExact(context),
        notificationsOn = NotificationManagerCompat.from(context).areNotificationsEnabled(),
        calendarAllowed = CalendarRoute.hasPermission(context),
        keepAlive = KeepAliveService.isRunning(context),
        endWhenHidden = EndWhenHidden.isEnabled(context),
        state = DeviceState.snapshot(context),
        exits = ExitHistory.recent(context, 32),
        starts = StartHistory.recent(context, 32),
        calendarApps = CalendarRoute.reminderApps(context),
        calendarRows = entries.mapNotNull { it.calendarEventId }.distinct()
            .associateWith { CalendarRoute.alertRows(context, it) },
        journal = Journal.recent(context),
        // Read after the rows above, which is what sets it.
        calendarProblem = CalendarRoute.lastReadProblem,
    )
}

@Composable
fun SpikeScreen(resumes: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var s by remember { mutableStateOf(read(context)) }
    fun refresh() {
        s = read(context)
    }

    val askNotifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refresh() }
    val askCalendar = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refresh() }

    // Asked once on open rather than at fire time: a denial on Android 13+ is
    // silent, and a spike that cannot show a notification looks exactly like an
    // alarm that never fired.
    //
    // In a LaunchedEffect, NOT during composition. `rememberLauncherForActivity-
    // Result` does not register its launcher until composition completes, so
    // calling launch() while composing throws "Launcher has not been
    // initialized" -- which crashed every open of the first build.
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Re-read on every resume: the user leaves for Settings and comes back,
    // and a cached value would still claim the app is restricted after they
    // have just fixed it. Repeated for a few seconds rather than once, because
    // a held alarm can be delivered a beat *after* the app opens, and a single
    // read would show "not delivered" for one that just arrived. Stops after
    // that: a loop running in the background would be the app doing work
    // while whether it is left alone is the thing under test.
    LaunchedEffect(resumes) {
        // Being opened is itself a chance to deliver: anything overdue is
        // announced now instead of whenever the phone lets its alarm through.
        Delivery.catchUp(context, by = "the app being opened")
        repeat(5) {
            refresh()
            delay(1_000)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Linodea phone test", style = MaterialTheme.typography.headlineSmall)
        Text(SPIKE_BUILD, style = MaterialTheme.typography.labelMedium)
        Text(DeviceState.describeDevice(), style = MaterialTheme.typography.bodySmall)

        // There is no logcat on a sideloaded build with USB debugging off, so
        // the last crash is shown here or it is not shown at all.
        s.crash?.let { trace ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Last crash", style = MaterialTheme.typography.titleMedium)
                    Text(
                        trace.take(1500),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    OutlinedButton(onClick = { CrashLog.clear(context); refresh() }) {
                        Text("Dismiss")
                    }
                }
            }
        }

        LatestTestCard(
            s = s,
            onClock = { dueAtMs -> setClockAlarm(context, dueAtMs) },
            onCopy = { copyReport(context, read(context).report()) },
            onShare = { shareReport(context, read(context).report()) },
        )

        SetupCard(
            s = s,
            onBattery = { BatteryPolicy.requestExemption(context) },
            onExact = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    runCatching {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                Uri.parse("package:${context.packageName}"),
                            )
                        )
                    }
                }
            },
            onNotifications = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
            onCalendar = { askCalendar.launch(CalendarRoute.PERMISSIONS) },
            onAppSettings = { BatteryPolicy.openAppSettings(context) },
            onRecheck = { refresh() },
        )

        RunTestCard(
            onStart = { label, minutes, beatMinutes ->
                Trial.start(context, label, minutes, beatMinutes)
                refresh()
            },
        )

        Text(
            "Results (${s.entries.count { it.firedAtMs != null }}/${s.entries.size} arrived, " +
                "${s.entries.count { it.verdict == SpikeLog.Verdict.RANG_IN_DARK }} passed)",
            style = MaterialTheme.typography.titleMedium,
        )
        if (s.entries.isEmpty()) {
            Text("Nothing armed yet.", style = MaterialTheme.typography.bodySmall)
        }

        s.trials.sortedByDescending { it.id }.forEach { trial ->
            val arms = s.entries.filter { it.trial == trial.id }
            TrialCard(trial, arms, s)
            arms.filterNot { it.isHeartbeat }.sortedBy { it.id }.forEach { EntryCard(it, s) }
        }
        s.entries.filter { it.trial == null }.sortedByDescending { it.dueAtMs }.forEach {
            EntryCard(it, s)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { refresh() }) { Text("Refresh") }
            OutlinedButton(onClick = {
                // Alarms first, then the log: the other order leaves armed
                // alarms with no entry to record them.
                AlarmScheduler.cancelAll(context, maxOf(SpikeLog.highestId(context), CANCEL_SWEEP))
                CalendarRoute.deleteAllEvents(context)
                Notify.cancelAll(context)
                SpikeLog.clear(context)
                Trial.clear(context)
                refresh()
            }) {
                Text("Clear + cancel all")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { copyReport(context, read(context).report()) }) {
                Text("Copy report")
            }
            OutlinedButton(onClick = { shareReport(context, read(context).report()) }) {
                Text("Send report")
            }
        }

        ManualCards(
            s = s,
            onArm = { api, minutes ->
                val due = System.currentTimeMillis() + minutes * 60_000L
                AlarmScheduler.arm(context, SpikeLog.takeNextId(context), api, due)
                refresh()
            },
            onEndWhenHidden = {
                EndWhenHidden.setEnabled(context, !s.endWhenHidden)
                refresh()
            },
            onKeepAlive = {
                if (s.keepAlive) {
                    KeepAliveService.stop(context)
                } else {
                    EndWhenHidden.setEnabled(context, false)
                    KeepAliveService.start(context)
                }
                refresh()
            },
        )

        JournalCard(s)
    }
}

private fun copyReport(context: Context, text: String) {
    // Kept on the phone too: the clipboard is lost to the next thing copied,
    // and with a cable this file is how a report is read back off an emulator.
    runCatching { File(context.filesDir, "last-report.txt").writeText(text) }
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("Linodea test report", text))
    // Android 13+ shows its own "copied" panel; older versions show nothing.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, "Report copied", Toast.LENGTH_SHORT).show()
    }
}

private fun shareReport(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, "Linodea test report")
        .putExtra(Intent.EXTRA_TEXT, text)
    runCatching { context.startActivity(Intent.createChooser(send, "Send the report")) }
}

/**
 * The control: the phone's own Clock app, asked to ring at the same minute.
 * It is the one alarm nobody doubts the phone lets through, so if it rings
 * and everything of ours is held, the hold is aimed at Linodea, not at alarms.
 */
private fun setClockAlarm(context: Context, dueAtMs: Long) {
    val at = Calendar.getInstance().apply { timeInMillis = dueAtMs }
    val intent = Intent(AlarmClock.ACTION_SET_ALARM)
        .putExtra(AlarmClock.EXTRA_HOUR, at.get(Calendar.HOUR_OF_DAY))
        .putExtra(AlarmClock.EXTRA_MINUTES, at.get(Calendar.MINUTE))
        .putExtra(AlarmClock.EXTRA_MESSAGE, "Linodea test control")
        .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
    // In the journal, so the report says a control was set and for when.
    // Whether it rang is the one thing only a person can report.
    Journal.note(context, "asked the Clock app for an alarm at ${HOUR_MINUTE.format(Date(dueAtMs))}")
    runCatching { context.startActivity(intent) }.onFailure {
        Journal.note(context, "the Clock app refused: ${it.javaClass.simpleName}")
        Toast.makeText(
            context,
            "The Clock app did not take it. Set an alarm for " +
                "${HOUR_MINUTE.format(Date(dueAtMs))} by hand.",
            Toast.LENGTH_LONG,
        ).show()
    }
}

@Composable
private fun SetupCard(
    s: Screen,
    onBattery: () -> Unit,
    onExact: () -> Unit,
    onNotifications: () -> Unit,
    onCalendar: () -> Unit,
    onAppSettings: () -> Unit,
    onRecheck: () -> Unit,
) {
    val batteryOk = s.state.batteryExempt == true
    val ready = batteryOk && s.exactAllowed && s.notificationsOn && s.calendarAllowed
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (ready) "Setup: ready" else "Setup: NOT READY",
                style = MaterialTheme.typography.titleMedium,
                color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            // Once everything is in order the list is noise above the thing
            // the screen is for, so it folds down to its title.
            if (!ready) {
                SetupLine("Battery unrestricted", batteryOk)
                if (!batteryOk) Button(onClick = onBattery) { Text("Allow unrestricted battery") }
                SetupLine("Exact alarms allowed", s.exactAllowed)
                if (!s.exactAllowed) Button(onClick = onExact) { Text("Allow exact alarms") }
                SetupLine("Notifications on", s.notificationsOn)
                if (!s.notificationsOn) Button(onClick = onNotifications) { Text("Allow notifications") }
                SetupLine("Calendar access (for the calendar path)", s.calendarAllowed)
                if (!s.calendarAllowed) {
                    Button(onClick = onCalendar) { Text("Allow calendar access") }
                    Text(
                        "Calendar access lets the test write a reminder into a calendar " +
                            "that lives only on this phone. Without it the test still " +
                            "runs, and that one path is marked NOT TESTED.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRecheck) { Text("Re-check") }
                OutlinedButton(onClick = onAppSettings) { Text("App settings") }
            }
            Text(
                "What Android says about this app right now: " +
                    (Describe.system(s.state) ?: "nothing readable") +
                    ". Apps told about calendar reminders: " +
                    s.calendarApps.joinToString(", ").ifEmpty { "none found" } + ".",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SetupLine(label: String, ok: Boolean) {
    Text(
        (if (ok) "OK   " else "NO   ") + label,
        style = MaterialTheme.typography.bodyMedium,
        color = if (ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
    )
}

/**
 * The first thing on the screen the morning after: what the last test did,
 * and the button that takes it off the phone.
 */
@Composable
private fun LatestTestCard(
    s: Screen,
    onClock: (dueAtMs: Long) -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
) {
    val trial = s.trials.maxByOrNull { it.id } ?: return
    val arms = s.entries.filter { it.trial == trial.id }
    val away = (trial.dueAtMs - s.nowMs) / 1000
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Latest test: #${trial.id} (${trial.label})",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "Due ${clock(trial.dueAtMs)} - " +
                    if (away >= 0) "in ${SpikeLog.formatDuration(away)}"
                    else "${SpikeLog.formatDuration(-away)} ago",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
            )
            arms.filterNot { it.isHeartbeat }.sortedBy { it.id }.forEach { arm ->
                Text(
                    "${arm.api}: ${arm.headline(s.nowMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = verdictColor(arm, s.nowMs),
                )
            }
            if (arms.none { it.api == SpikeLog.API_CALENDAR }) {
                Text(
                    "calendar path: ${trial.calendar}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            val beats = arms.filter { it.isHeartbeat }
            Text(
                "heartbeats: " + TrialPlan.ladderSentence(TrialPlan.ladder(beats, s.nowMs), ::clock),
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onCopy) { Text("Copy report") }
                OutlinedButton(onClick = onShare) { Text("Send report") }
            }
            if (away > 60) {
                val controlAt = trial.dueAtMs + CLOCK_CONTROL_AFTER_MS
                OutlinedButton(onClick = { onClock(controlAt) }) {
                    Text("Also set the Clock app for ${HOUR_MINUTE.format(Date(controlAt))}")
                }
                Text(
                    "Optional control: the phone's own alarm, two minutes after the " +
                        "test is due. It rings out loud. Two minutes later on " +
                        "purpose: a ringing alarm lights the screen, and at the same " +
                        "minute that would hide whether the others arrived by themselves.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun RunTestCard(onStart: (label: String, minutes: Int, beatMinutes: Int) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Run a test", style = MaterialTheme.typography.titleMedium)
            Text(
                "One tap arms every way this phone can deliver a reminder, all for " +
                    "the same minute, so one wait compares them. Do the 6-minute " +
                    "check first: it proves each path works here before a night is " +
                    "spent on it.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "1. Unplug the charger.\n" +
                    "2. Tap a test.\n" +
                    "3. Press Home. The app closes itself; that is expected.\n" +
                    "4. Lock the phone and use it as you normally would, or not at all.\n" +
                    "5. After the due time, open the app BEFORE plugging in, and tap Copy report.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onStart("6-minute check", 6, 2) }) { Text("6 min check") }
                Button(onClick = { onStart("1 hour", 60, 10) }) { Text("1 hour") }
                Button(onClick = { onStart("8 hours", 480, 30) }) { Text("8 hours") }
            }
        }
    }
}

@Composable
private fun verdictColor(entry: SpikeLog.Entry, nowMs: Long): Color = when (entry.verdict) {
    SpikeLog.Verdict.RANG_IN_DARK -> MaterialTheme.colorScheme.primary
    SpikeLog.Verdict.HELD_UNTIL_SCREEN_ON, SpikeLog.Verdict.LATE_IN_DARK -> MaterialTheme.colorScheme.error
    SpikeLog.Verdict.WAITING ->
        if (nowMs - entry.dueAtMs > SpikeLog.ON_TIME_SECONDS * 1000) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurface
        }
    else -> MaterialTheme.colorScheme.onSurface
}

/** One test's header and its heartbeat ladder; the paths follow as their own cards. */
@Composable
private fun TrialCard(trial: Trial.Info, arms: List<SpikeLog.Entry>, s: Screen) {
    val beats = arms.filter { it.isHeartbeat }.sortedBy { it.dueAtMs }
    val ladder = TrialPlan.ladder(beats, s.nowMs)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "Test #${trial.id} - ${trial.label}",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "armed ${clock(trial.startedAtMs)}  -  due ${clock(trial.dueAtMs)}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("calendar path: ${trial.calendar}", style = MaterialTheme.typography.bodySmall)
            Text(
                "heartbeats: ${TrialPlan.ladderSentence(ladder, ::clock)}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = if (ladder.unbroken) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
            )
            beats.forEach { beat ->
                Text(
                    "${HOUR_MINUTE.format(Date(beat.dueAtMs))}  ${Describe.beat(beat, s.nowMs)}" +
                        (beat.atFire?.batteryPercent?.let { "  $it%" } ?: "") +
                        (beat.atFire?.bucket?.let { "  $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

/**
 * One path, read as a result rather than a timestamp: the verdict first, then
 * the evidence for it, so a screenshot of this card is a complete report.
 */
@Composable
private fun EntryCard(entry: SpikeLog.Entry, s: Screen) {
    val armedMinutes = (entry.dueAtMs - entry.armedAtMs + 30_000) / 60_000

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "#${entry.id}  ${entry.api}  " +
                    (entry.trial?.let { "test #$it  " } ?: "") +
                    if (entry.unlogged) "NOT IN THE LOG (armed before a Clear)" else "${armedMinutes}m",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                entry.headline(s.nowMs),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = verdictColor(entry, s.nowMs),
            )
            if (entry.voidedByCharging) {
                Text(
                    "DOES NOT COUNT - plugged in, so the phone was never tested asleep",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (entry.releasedOnCharger) {
                Text(
                    "Armed unplugged and arrived on the charger - charging may be what released it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            entry.noticedLine()?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Text(
                "due ${clock(entry.dueAtMs)}" +
                    (entry.firedAtMs?.let { "  -  arrived ${clock(it)}" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )
            entry.armed?.let {
                Text("armed: ${Describe.armed(it)}", style = MaterialTheme.typography.bodySmall)
            }
            if (entry.api == AlarmScheduler.Api.ALARM_CLOCK.label) {
                Describe.clockAccepted(entry.dueAtMs, entry.armed, ::clock)?.let {
                    Text("clock alarm: $it", style = MaterialTheme.typography.bodySmall)
                }
            }
            entry.atFire?.let {
                Text(
                    "arrived: ${Describe.moment(it)}" +
                        if (entry.coldStart) " - cold start (process was rebuilt)" else " - warm process",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            entry.firedAtMs?.let { at ->
                Describe.asleep(entry.armed, entry.armedAtMs, entry.atFire, at)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
            entry.atNotice?.let {
                Text(
                    "while it was held: ${Describe.moment(it)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            entry.noticedAtMs?.let { at ->
                Describe.asleep(entry.armed, entry.armedAtMs, entry.atNotice, at)?.let {
                    Text("by then, $it", style = MaterialTheme.typography.bodySmall)
                }
            }
            entry.calendarEventId?.let { eventId ->
                Text(
                    "calendar's own record: " + Report.calendarRecord(s.calendarRows[eventId], ::clock),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                "process while waiting:" + describeProcess(entry, s),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * Every start and death of the process between arming and delivery, one per
 * line in time order, so "who brought it back and who killed it" reads as a
 * sequence instead of two lists to cross-reference.
 */
private fun describeProcess(entry: SpikeLog.Entry, s: Screen): String {
    val to = entry.firedAtMs ?: s.nowMs
    val lines = mutableListOf<Pair<Long, String>>()
    s.exits?.let { all ->
        ExitHistory.between(all, entry.armedAtMs, to).forEach { exit ->
            val detail = listOfNotNull(
                exit.standing?.let { "was $it" },
                exit.description?.takeIf { it.isNotBlank() }?.let { "\"$it\"" },
            ).joinToString(", ")
            lines += exit.atMs to "died: ${exit.reason}" + if (detail.isEmpty()) "" else " ($detail)"
        }
    }
    s.starts?.let { all ->
        StartHistory.between(all, entry.armedAtMs, to).forEach { start ->
            lines += start.atMs to "started by ${start.reason} (${start.type})"
        }
    }
    val notes = listOfNotNull(
        if (s.exits == null) "deaths unknown (needs Android 11+)" else null,
        if (s.starts == null) "starts unknown (needs Android 15+)" else null,
    )
    if (lines.isEmpty()) return " none" + notes.joinToString("") { "\n  $it" }
    return lines.sortedBy { it.first }
        .joinToString("") { (at, text) -> "\n  ${clock(at)} $text" } +
        notes.joinToString("") { "\n  $it" }
}

/** The single-alarm buttons and switches from the earlier runs, kept for comparison. */
@Composable
private fun ManualCards(
    s: Screen,
    onArm: (AlarmScheduler.Api, Int) -> Unit,
    onEndWhenHidden: () -> Unit,
    onKeepAlive: () -> Unit,
) {
    Text("Single alarms and switches (earlier tests)", style = MaterialTheme.typography.titleMedium)

    AlarmScheduler.Api.entries.forEach { api ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(api.label, style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 45, 480).forEach { minutes ->
                        Button(onClick = { onArm(api, minutes) }) { Text("${minutes}m") }
                    }
                }
                // Two alarms three minutes apart is the rate-limit probe:
                // setExactAndAllowWhileIdle is believed to allow only one
                // firing per app per nine minutes while idle, which would
                // disqualify it on its own.
                OutlinedButton(onClick = { onArm(api, 3); onArm(api, 6) }) {
                    Text("3m + 6m (rate-limit probe)")
                }
            }
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (s.endWhenHidden) "End when hidden: ON" else "End when hidden: off",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "The app ends itself whenever it leaves the screen, a second after " +
                    "each alarm, and shortly after any start that nothing used, so " +
                    "there is nothing for XOS to freeze. Starting a test turns this on.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = onEndWhenHidden) { Text(if (s.endWhenHidden) "Turn off" else "Turn on") }
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (s.keepAlive) "Keep-alive: ON" else "Keep-alive: off",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "Tested and FAILED on XOS: with the service running and the " +
                    "process alive, the alarm was still held 3m 52s until the " +
                    "screen came on. Kept for comparison only. Turning it on " +
                    "turns End when hidden off.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = onKeepAlive) { Text(if (s.keepAlive) "Turn off" else "Turn on") }
        }
    }
}

/** The tail of the app's own record of its starts, wakes and exits. */
@Composable
private fun JournalCard(s: Screen) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Journal (latest last)", style = MaterialTheme.typography.titleMedium)
            Text(
                "Every time this app started, woke or ended itself. \"process created\" " +
                    "with nothing after it is the phone starting the app on its own.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (s.journal.isEmpty()) {
                Text("Nothing yet.", style = MaterialTheme.typography.bodySmall)
            }
            s.journal.takeLast(40).forEach { line ->
                Text(
                    "${clock(line.atMs)} ${line.text}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
