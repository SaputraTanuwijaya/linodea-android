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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.saputratanuwijaya.linodea.spike.AlarmScheduler
import io.github.saputratanuwijaya.linodea.spike.BatteryPolicy
import io.github.saputratanuwijaya.linodea.spike.CrashLog
import io.github.saputratanuwijaya.linodea.spike.ProcessState
import io.github.saputratanuwijaya.linodea.spike.SpikeLog
import io.github.saputratanuwijaya.linodea.ui.theme.LinodeaTheme
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
                    SpikeScreen(Modifier.padding(padding))
                }
            }
        }
    }
}

private val CLOCK = SimpleDateFormat("EEE HH:mm:ss", Locale.getDefault())

@Composable
private fun SpikeScreen(modifier: Modifier = Modifier) {
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
    // Re-read on every recomposition rather than remembered: the user leaves
    // for Settings and comes back, and a cached value would still claim the
    // app is restricted after they have just fixed it.
    var batteryExempt by remember { mutableStateOf(BatteryPolicy.isExempt(context)) }

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
                "after the due time — waking it is what made the last two runs " +
                "look late. Unplug for anything longer than five minutes. " +
                "Doze does not engage while charging, so a plugged-in run passes " +
                "regardless and proves nothing.",
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
            OutlinedButton(onClick = { entries = SpikeLog.all(context) }) { Text("Refresh") }
            OutlinedButton(onClick = { SpikeLog.clear(context); entries = emptyList() }) {
                Text("Clear")
            }
        }

        Text(
            "Results (${entries.count { it.firedAtMs != null }}/${entries.size} fired)",
            style = MaterialTheme.typography.titleMedium,
        )

        if (entries.isEmpty()) {
            Text("Nothing armed yet.", style = MaterialTheme.typography.bodySmall)
        }

        entries.sortedByDescending { it.dueAtMs }.forEach { entry ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("#${entry.id}  ${entry.api}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "due ${CLOCK.format(Date(entry.dueAtMs))}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    val drift = entry.driftSeconds
                    Text(
                        when {
                            drift == null -> "waiting"
                            else -> "fired ${CLOCK.format(Date(entry.firedAtMs!!))}  " +
                                "(${drift}s late)" + if (entry.coldStart) "  cold start" else ""
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
