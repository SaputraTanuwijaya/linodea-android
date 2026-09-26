package io.github.saputratanuwijaya.linodea.spike

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

/**
 * Reads the conditions that decide what a firing means.
 *
 * Taken twice per alarm -- when it is armed and at the instant it is
 * delivered -- so each result carries its own evidence: whether the screen was
 * on (held vs fired), whether the phone was charging (a void run), and whether
 * keep-alive was actually running rather than merely switched on.
 */
object DeviceState {

    fun snapshot(context: Context): SpikeLog.Snapshot {
        val app = context.applicationContext
        return SpikeLog.Snapshot(
            // isInteractive is the screen, not the lock: true on the lock
            // screen too. That is the right question -- a frozen app thaws
            // when the screen lights, before anyone unlocks.
            screenOn = runCatching { app.getSystemService(PowerManager::class.java).isInteractive }
                .getOrNull(),
            locked = runCatching { app.getSystemService(KeyguardManager::class.java).isKeyguardLocked }
                .getOrNull(),
            plugged = runCatching { isPlugged(app) }.getOrNull(),
            keepAlive = runCatching { KeepAliveService.isRunning(app) }.getOrNull(),
            batteryExempt = runCatching { BatteryPolicy.isExempt(app) }.getOrNull(),
            endWhenHidden = runCatching { EndWhenHidden.isEnabled(app) }.getOrNull(),
        )
    }

    /**
     * Plugged, not charging: a full battery on a charger reports not charging,
     * and Doze stays off either way.
     */
    private fun isPlugged(context: Context): Boolean? {
        // A null receiver reads the sticky broadcast without registering
        // anything, which is also why it is allowed from inside a receiver.
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return null
        return battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }

    /**
     * Which phone produced a screenshot. Matters once a second brand is
     * borrowed: the result has to say where it came from without anyone
     * writing it down.
     */
    fun describeDevice(): String =
        "${Build.MANUFACTURER} ${Build.MODEL} - Android ${Build.VERSION.RELEASE} " +
            "(API ${Build.VERSION.SDK_INT}) - ${Build.DISPLAY}"
}
