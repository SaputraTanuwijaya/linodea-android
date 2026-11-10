package io.github.saputratanuwijaya.linodea.spike

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock

/**
 * Reads the conditions that decide what a firing means.
 *
 * Taken when an alarm is armed, at the instant it is delivered, and -- new
 * since the 8-hour failures -- whenever the app finds one overdue. That last
 * reading is the only look anyone gets at the phone *while* an alarm is being
 * held: a held alarm runs none of our code, so until now the record jumped
 * from "armed" straight to "released on the charger" with nothing between.
 */
object DeviceState {

    fun snapshot(context: Context): SpikeLog.Snapshot {
        val app = context.applicationContext
        val power = runCatching { app.getSystemService(PowerManager::class.java) }.getOrNull()
        return SpikeLog.Snapshot(
            // isInteractive is the screen, not the lock: true on the lock
            // screen too. That is the right question -- a frozen app thaws
            // when the screen lights, before anyone unlocks.
            screenOn = runCatching { power?.isInteractive }.getOrNull(),
            locked = runCatching { app.getSystemService(KeyguardManager::class.java).isKeyguardLocked }
                .getOrNull(),
            plugged = runCatching { isPlugged(app) }.getOrNull(),
            keepAlive = runCatching { KeepAliveService.isRunning(app) }.getOrNull(),
            batteryExempt = runCatching { BatteryPolicy.isExempt(app) }.getOrNull(),
            endWhenHidden = runCatching { EndWhenHidden.isEnabled(app) }.getOrNull(),
            // The overnight failure was released the moment a low battery went
            // on the charger, which makes the battery a suspect: OEM power
            // managers commonly tighten below a threshold and relax on charge.
            batteryPercent = runCatching { batteryPercent(app) }.getOrNull(),
            powerSave = runCatching { power?.isPowerSaveMode }.getOrNull(),
            bucket = runCatching { standbyBucket(app) }.getOrNull(),
            backgroundRestricted = runCatching { backgroundRestricted(app) }.getOrNull(),
            idle = runCatching { power?.let(::idleName) }.getOrNull(),
            nextAlarmClockAtMs = runCatching {
                app.getSystemService(AlarmManager::class.java).nextAlarmClock?.triggerTime ?: 0L
            }.getOrNull(),
            // One clock counts time asleep and the other does not; the gap
            // between them is the sleep.
            sleptMs = runCatching {
                SystemClock.elapsedRealtime() - SystemClock.uptimeMillis()
            }.getOrNull(),
        )
    }

    // A null receiver reads the sticky broadcast without registering anything,
    // which is also why it is allowed from inside a receiver.
    private fun batteryStatus(context: Context): Intent? =
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    /**
     * Plugged, not charging: a full battery on a charger reports not charging,
     * and Doze stays off either way.
     */
    private fun isPlugged(context: Context): Boolean? {
        val battery = batteryStatus(context) ?: return null
        return battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }

    private fun batteryPercent(context: Context): Int? {
        val battery = batteryStatus(context) ?: return null
        val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        return level * 100 / scale
    }

    /**
     * How recently Android believes the app was used. Stock Android slows an
     * app's background work as it sinks down these; whether XOS's own hold
     * follows the same ladder is exactly what is not known, so it is recorded
     * beside every result.
     */
    private fun standbyBucket(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val usage = context.getSystemService(UsageStatsManager::class.java) ?: return null
        return bucketName(usage.appStandbyBucket)
    }

    fun bucketName(bucket: Int): String = when (bucket) {
        5 -> "EXEMPTED"
        10 -> "ACTIVE"
        20 -> "WORKING_SET"
        30 -> "FREQUENT"
        40 -> "RARE"
        45 -> "RESTRICTED"
        50 -> "NEVER"
        else -> "bucket $bucket"
    }

    private fun backgroundRestricted(context: Context): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return context.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted
    }

    private fun idleName(power: PowerManager): String = when {
        power.isDeviceIdleMode -> "deep"
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && power.isDeviceLightIdleMode -> "light"
        else -> "no"
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
