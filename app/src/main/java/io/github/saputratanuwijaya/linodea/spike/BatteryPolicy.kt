package io.github.saputratanuwijaya.linodea.spike

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Whether the OS is allowed to hold this app's alarms.
 *
 * Observed on a real Infinix (XOS): alarms armed with `setAlarmClock` did not
 * arrive while the screen was off. They arrived the moment the screen came on —
 * 106 seconds after the due time in one run, 16 in another, both exactly as
 * long as the phone was left alone. The alarm was not late; it was **frozen**,
 * and the recorded "drift" was measuring how long until someone looked.
 *
 * That is the OEM battery manager, not Doze. `setAlarmClock` is exempt from
 * Doze, but it is not exempt from a manufacturer deciding to freeze the whole
 * app. The standard mitigation is an exemption from battery optimisation, which
 * the user has to grant — it cannot be claimed in the manifest.
 *
 * This is also why the check belongs in the shipped app and not only here: an
 * app in this state shows reminders with their times and silently never rings,
 * which is the worst failure a reminder app can have because it looks
 * identical to working.
 */
object BatteryPolicy {

    /** True when the OS has agreed to stop optimising this app. */
    fun isExempt(context: Context): Boolean {
        val power = context.getSystemService(PowerManager::class.java) ?: return false
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Ask for the exemption directly, falling back to the settings list.
     *
     * The direct dialog is one tap; the list is several and requires finding
     * the app by name. The fallback exists because some builds refuse the
     * direct intent, and landing the user somewhere useful beats an unhandled
     * `ActivityNotFoundException`.
     */
    fun requestExemption(context: Context) {
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        )
        val listed = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        runCatching { context.startActivity(direct) }
            .recoverCatching { context.startActivity(listed) }
    }

    /**
     * The app's own settings page, for the parts no intent can reach.
     *
     * Transsion, Xiaomi, Oppo and Vivo each add their own autostart and
     * "protected app" lists on top of the standard exemption, and none of them
     * are reachable through a documented intent. Dropping the user on the app
     * info screen is the closest a portable app can get.
     */
    fun openAppSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}"),
                )
            )
        }
    }
}
