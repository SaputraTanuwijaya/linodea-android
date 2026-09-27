package io.github.saputratanuwijaya.linodea.spike

import android.app.ActivityManager
import android.app.ApplicationStartInfo
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.annotation.RequiresApi

/**
 * What started this app's process, and when, as the system recorded it.
 *
 * The other half of `ExitHistory`. The overnight run logged a kill at 12:03:28
 * of a process that should not have existed: the app had ended itself at
 * 11:50:19 and no alarm was due until 12:04:34. Something started it in
 * between, and only the start record can say what -- an alarm, a broadcast,
 * the launcher, Recents, a job.
 *
 * Android 15+ only; older versions return null and the screen says so.
 */
object StartHistory {

    data class Start(val atMs: Long, val reason: String, val type: String)

    fun recent(context: Context, max: Int = 16): List<Start>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return null
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        return runCatching {
            manager.getHistoricalProcessStartReasons(max).mapNotNull { info ->
                val at = wallClockOf(info) ?: return@mapNotNull null
                Start(at, reasonName(info.reason), typeName(info.startType))
            }
        }.getOrNull()
    }

    fun between(starts: List<Start>, fromMs: Long, toMs: Long): List<Start> =
        starts.filter { it.atMs in fromMs..toMs }.sortedBy { it.atMs }

    /**
     * Start timestamps are nanoseconds on the since-boot clock; converted here
     * to wall time so they line up with the death and alarm times on screen.
     */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun wallClockOf(info: ApplicationStartInfo): Long? {
        val stamps = info.startupTimestamps
        val sinceBootNs = stamps[ApplicationStartInfo.START_TIMESTAMP_LAUNCH]
            ?: stamps[ApplicationStartInfo.START_TIMESTAMP_FORK]
            ?: stamps.values.minOrNull()
            ?: return null
        val agoMs = (SystemClock.elapsedRealtimeNanos() - sinceBootNs) / 1_000_000
        return System.currentTimeMillis() - agoMs
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationStartInfo.START_REASON_ALARM -> "an alarm"
        ApplicationStartInfo.START_REASON_BACKUP -> "backup"
        ApplicationStartInfo.START_REASON_BOOT_COMPLETE -> "boot"
        ApplicationStartInfo.START_REASON_BROADCAST -> "a broadcast"
        ApplicationStartInfo.START_REASON_CONTENT_PROVIDER -> "a content provider"
        ApplicationStartInfo.START_REASON_JOB -> "a job"
        ApplicationStartInfo.START_REASON_LAUNCHER -> "the launcher"
        ApplicationStartInfo.START_REASON_LAUNCHER_RECENTS -> "Recents"
        ApplicationStartInfo.START_REASON_OTHER -> "other"
        ApplicationStartInfo.START_REASON_PUSH -> "a push"
        ApplicationStartInfo.START_REASON_SERVICE -> "a service"
        ApplicationStartInfo.START_REASON_START_ACTIVITY -> "an activity start"
        else -> "reason $reason"
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun typeName(type: Int): String = when (type) {
        ApplicationStartInfo.START_TYPE_COLD -> "cold"
        ApplicationStartInfo.START_TYPE_WARM -> "warm"
        ApplicationStartInfo.START_TYPE_HOT -> "hot"
        else -> "?"
    }
}
