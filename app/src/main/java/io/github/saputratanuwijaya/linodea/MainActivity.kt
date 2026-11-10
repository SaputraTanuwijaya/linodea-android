package io.github.saputratanuwijaya.linodea

import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.saputratanuwijaya.linodea.spike.ProcessState
import io.github.saputratanuwijaya.linodea.spike.SpikeScreen
import io.github.saputratanuwijaya.linodea.spike.Trial
import io.github.saputratanuwijaya.linodea.ui.theme.LinodeaTheme

/**
 * The alarm reliability spike.
 *
 * Not the product and not the start of it — see the brief in the desktop repo's
 * session log. This exists to answer one question on real hardware: can this
 * phone deliver a reminder hours later, unplugged, through Doze and XOS's
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
        startTestFromAdb(savedInstanceState)
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

    /**
     * Lets a test of any length be started from a computer:
     * `adb shell am start -n <package>/.MainActivity --ei trialMinutes 2 --ei beatMinutes 1`.
     *
     * For checking the instrument on an emulator, where waiting out the
     * shortest button on the screen for every change would take all day.
     * Debuggable builds only, and it does nothing without those extras.
     */
    private fun startTestFromAdb(savedInstanceState: Bundle?) {
        if (savedInstanceState != null) return
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val minutes = intent.getIntExtra("trialMinutes", 0)
        if (minutes <= 0) return
        Trial.start(this, "$minutes-minute run from adb", minutes, intent.getIntExtra("beatMinutes", 1))
        // Consumed: a rotation or a return from Recents must not start another.
        intent.removeExtra("trialMinutes")
    }
}
