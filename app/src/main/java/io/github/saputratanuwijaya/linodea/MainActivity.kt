package io.github.saputratanuwijaya.linodea

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.saputratanuwijaya.linodea.spike.AlarmScheduler
import io.github.saputratanuwijaya.linodea.spike.BatteryPolicy
import io.github.saputratanuwijaya.linodea.spike.DeviceState
import io.github.saputratanuwijaya.linodea.spike.EndWhenHidden
import io.github.saputratanuwijaya.linodea.spike.ExitHistory
import io.github.saputratanuwijaya.linodea.spike.KeepAliveService
import io.github.saputratanuwijaya.linodea.spike.CrashLog
import io.github.saputratanuwijaya.linodea.spike.ProcessState
import io.github.saputratanuwijaya.linodea.spike.SpikeLog
import io.github.saputratanuwijaya.linodea.ui.theme.LinodeaTheme
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The alarm reliability spike.
 *
 * Not the product and not the start of it — see the brief in the desktop repo's
 * session log. This exists to answer one question on real hardware: can this
 * phone fire an exact alarm hours later, unplugged, through Doze and XOS's
 * battery manager? Everything here serves that and is meant to be thrown away.
 */
class MainActivity : ComponentActivity() {
    // Bumped on every resume so the screen re-reads what changed while it was
    // away: an alarm delivered while locked, a setting changed in Settings.
    private var resumes by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Marks the process as warm, so an alarm that arrives later can tell
        // whether it was delivered into a live process or into one Android had
        // to rebuild after something killed it.
        ProcessState.wasWarm = true
        enableEdgeToEdge()
        setContent {
            LinodeaTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    SpikeScreen(resumes, Modifier.padding(padding))
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumes += 1
    }

    override fun onStart() {
        super.onStart()
        EndWhenHidden.visibleScreens += 1
    }

    override fun onStop() {
        super.onStop()
        EndWhenHidden.visibleScreens -= 1
        // A rotation stops and restarts the screen; ending the process in the
        // gap would look like a crash. Anything else -- Home, Recents, the
        // screen turning off -- is the app leaving, and it ends here.
        if (!isChangingConfigurations) EndWhenHidden.endIfHidden(this)
    }
}

/**
 * Shown at the top so a glance confirms the phone is running this build and
 * not the one before it -- a sideload that silently failed would otherwise
 * produce results from the old instrument.
 */
private const val SPIKE_BUILD = "Instrument v3 - end when hidden"

private val CLOCK = SimpleDateFormat("EEE HH:mm:ss", Locale.getDefault())

@Composable
private fun SpikeScreen(resumes: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf(SpikeLog.all(context)) }
    var nextId by remember { mutableStateOf((entries.maxOfOrNull { it.id } ?: 0) + 1) }

    val askNotifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

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

    var crash by remember { mutableStateOf(CrashLog.last(context)) }

    val exactAllowed = AlarmScheduler.canScheduleExact(context)
    var batteryExempt by remember { mutableStateOf(BatteryPolicy.isExempt(context)) }
    var keepAlive by remember { mutableStateOf(KeepAliveService.isRunning(context)) }
    var endWhenHidden by remember { mutableStateOf(EndWhenHidden.isEnabled(context)) }
    var exits by remember { mutableStateOf(ExitHistory.recent(context)) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    fun refresh() {
        entries = SpikeLog.all(context)
        batteryExempt = BatteryPolicy.isExempt(context)
        keepAlive = KeepAliveService.isRunning(context)
        endWhenHidden = EndWhenHidden.isEnabled(context)
        exits = ExitHistory.recent(context)
        crash = CrashLog.last(context)
        now = System.currentTimeMillis()
    }

    // Re-read on every resume: the user leaves for Settings and comes back,
    // and a cached value would still claim the app is restricted after they
    // have just fixed it. Repeated for a few seconds rather than once, because
    // a held alarm is delivered as the app thaws -- a beat *after* it opens --
    // and a single read would show "not delivered" for one that just arrived.
    // Stops after that: a loop running in the background would be the app
    // doing work while frozen-or-not is the thing under test.
    LaunchedEffect(resumes) {
        repeat(5) {
            refresh()
            delay(1_000)
        }
    }

    fun arm(api: AlarmScheduler.Api, minutes: Int) {
        val due = System.currentTimeMillis() + minutes * 60_000L
        AlarmScheduler.arm(context, nextId, api, due)
        nextId += 1
        entries = SpikeLog.all(context)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Alarm spike", style = MaterialTheme.typography.headlineSmall)
        Text(SPIKE_BUILD, style = MaterialTheme.typography.labelMedium)
        Text(DeviceState.describeDevice(), style = MaterialTheme.typography.bodySmall)

        // There is no logcat on a sideloaded build with USB debugging off, so
        // the last crash is shown here or it is not shown at all.
        crash?.let { trace ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Last crash", style = MaterialTheme.typography.titleMedium)
                    Text(
                        trace.take(1500),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    OutlinedButton(onClick = { CrashLog.clear(context); crash = null }) {
                        Text("Dismiss")
                    }
                }
            }
        }
        Text(
            "Arm an alarm, lock the screen, and DO NOT TOUCH THE PHONE until " +
                "well after the due time. Each result now records whether the " +
                "screen was on when the alarm arrived: PASS means it rang by " +
                "itself in the dark, HELD means it waited for you to wake the " +
                "phone. Unplug first -- Doze does not engage while charging, and " +
                "a plugged-in run is marked as not counting.",
            style = MaterialTheme.typography.bodySmall,
        )

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (batteryExempt) "Battery: unrestricted" else "Battery: OPTIMISED",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    if (batteryExempt) {
                        "The OS has agreed not to freeze this app. Results below " +
                            "are a fair test."
                    } else {
                        "Android may freeze this app with the screen off, which " +
                            "holds alarms until you wake the phone. That is what " +
                            "the 106s and 16s runs were measuring. Fix this before " +
                            "trusting any result."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (!batteryExempt) {
                    Button(onClick = { BatteryPolicy.requestExemption(context) }) {
                        Text("Allow unrestricted battery")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { batteryExempt = BatteryPolicy.isExempt(context) }) {
                        Text("Re-check")
                    }
                    OutlinedButton(onClick = { BatteryPolicy.openAppSettings(context) }) {
                        Text("App settings")
                    }
                }
                Text(
                    "XOS also has its own autostart / protected-app list that no " +
                        "intent can reach. Check it under App settings.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (endWhenHidden) "End when hidden: ON" else "End when hidden: off",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "The app ends itself whenever it leaves the screen, and a " +
                        "second after each alarm, so there is nothing for XOS to " +
                        "freeze and Android has to start it fresh at the due time. " +
                        "It does by itself what swiping from Recents did by hand. " +
                        "With this on, every alarm should say \"cold start\", and " +
                        "leaving the app closes it -- that is expected.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = {
                    EndWhenHidden.setEnabled(context, !endWhenHidden)
                    endWhenHidden = !endWhenHidden
                    keepAlive = KeepAliveService.isRunning(context)
                }) { Text(if (endWhenHidden) "Turn off" else "Turn on") }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (keepAlive) "Keep-alive: ON" else "Keep-alive: off",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "Tested and FAILED on XOS: with the service running and the " +
                        "process alive, the alarm was still held 3m 52s until the " +
                        "screen came on. Kept for comparison only. Turning it on " +
                        "turns End when hidden off -- a sticky service restarts " +
                        "the process, which undoes every exit.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        if (keepAlive) {
                            KeepAliveService.stop(context)
                        } else {
                            EndWhenHidden.setEnabled(context, false)
                            endWhenHidden = false
                            KeepAliveService.start(context)
                        }
                        keepAlive = !keepAlive
                    }) { Text(if (keepAlive) "Turn off" else "Turn on") }
                    OutlinedButton(onClick = { keepAlive = KeepAliveService.isRunning(context) }) {
                        Text("Re-check")
                    }
                }
            }
        }

        if (!exactAllowed) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Exact alarms are not permitted for this app, so every " +
                            "result below would be a false negative.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                    Uri.parse("package:${context.packageName}"),
                                )
                            )
                        }
                    }) { Text("Allow exact alarms") }
                }
            }
        }

        AlarmScheduler.Api.entries.forEach { api ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(api.label, style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(5, 45, 480).forEach { minutes ->
                            Button(onClick = { arm(api, minutes) }) { Text("${minutes}m") }
                        }
                    }
                    // Two alarms three minutes apart is the rate-limit probe:
                    // setExactAndAllowWhileIdle is believed to allow only one
                    // firing per app per nine minutes while idle, which would
                    // disqualify it on its own.
                    OutlinedButton(onClick = { arm(api, 3); arm(api, 6) }) {
                        Text("3m + 6m (rate-limit probe)")
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { refresh() }) { Text("Refresh") }
            OutlinedButton(onClick = { SpikeLog.clear(context); entries = emptyList() }) {
                Text("Clear")
            }
        }

        Text(
            "Results (${entries.count { it.firedAtMs != null }}/${entries.size} fired, " +
                "${entries.count { it.verdict == SpikeLog.Verdict.RANG_IN_DARK }} passed)",
            style = MaterialTheme.typography.titleMedium,
        )

        if (entries.isEmpty()) {
            Text("Nothing armed yet.", style = MaterialTheme.typography.bodySmall)
        }

        entries.sortedByDescending { it.dueAtMs }.forEach { entry ->
            EntryCard(entry, exits, now)
        }
    }
}

/**
 * One alarm, read as a result rather than a timestamp: the verdict first, then
 * the evidence for it, so a screenshot of this card is a complete report.
 */
@Composable
private fun EntryCard(entry: SpikeLog.Entry, exits: List<ExitHistory.Exit>?, now: Long) {
    val verdictColor = when (entry.verdict) {
        SpikeLog.Verdict.RANG_IN_DARK -> MaterialTheme.colorScheme.primary
        SpikeLog.Verdict.HELD_UNTIL_SCREEN_ON -> MaterialTheme.colorScheme.error
        SpikeLog.Verdict.WAITING ->
            if (now - entry.dueAtMs > SpikeLog.ON_TIME_SECONDS * 1000) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            }
        else -> MaterialTheme.colorScheme.onSurface
    }
    val armedMinutes = (entry.dueAtMs - entry.armedAtMs + 30_000) / 60_000

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "#${entry.id}  ${entry.api}  ${armedMinutes}m",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                entry.headline(now),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = verdictColor,
            )
            if (entry.wasPluggedIn) {
                Text(
                    "DOES NOT COUNT - plugged in, so Doze could not engage",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                "due ${CLOCK.format(Date(entry.dueAtMs))}" +
                    (entry.firedAtMs?.let { "  -  arrived ${CLOCK.format(Date(it))}" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )
            entry.armed?.let {
                Text("armed: ${describeArmed(it)}", style = MaterialTheme.typography.bodySmall)
            }
            entry.atFire?.let {
                Text(
                    "arrived: ${describeArrival(it)}" +
                        if (entry.coldStart) " - cold start (process was rebuilt)" else " - warm process",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                "process deaths while waiting: " + describeDeaths(entry, exits, now),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun yesNo(value: Boolean?, yes: String, no: String): String? = when (value) {
    true -> yes
    false -> no
    null -> null
}

private fun describeArmed(s: SpikeLog.Snapshot): String = listOfNotNull(
    yesNo(s.endWhenHidden, "end-when-hidden ON", "end-when-hidden off"),
    yesNo(s.keepAlive, "keep-alive ON", "keep-alive off"),
    yesNo(s.plugged, "PLUGGED IN", "unplugged"),
    yesNo(s.batteryExempt, "battery unrestricted", "battery OPTIMISED"),
).joinToString(" - ")

private fun describeArrival(s: SpikeLog.Snapshot): String = listOfNotNull(
    yesNo(s.screenOn, "screen ON", "screen off"),
    yesNo(s.locked, "locked", "unlocked"),
    yesNo(s.plugged, "PLUGGED IN", "unplugged"),
    yesNo(s.keepAlive, "keep-alive running", "keep-alive NOT running"),
).joinToString(" - ")

private fun describeDeaths(entry: SpikeLog.Entry, exits: List<ExitHistory.Exit>?, now: Long): String {
    if (exits == null) return "unknown (needs Android 11+)"
    val during = ExitHistory.between(exits, entry.armedAtMs, entry.firedAtMs ?: now)
    if (during.isEmpty()) return "none"
    return during.joinToString("; ") { "${CLOCK.format(Date(it.atMs))} ${it.reason}" }
}
