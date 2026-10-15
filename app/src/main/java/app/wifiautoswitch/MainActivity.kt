package app.wifiautoswitch

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.wifiautoswitch.shizuku.ShizukuConnector
import app.wifiautoswitch.ui.AppTheme
import app.wifiautoswitch.ui.AppRoot

class MainActivity : ComponentActivity() {

    companion object {
        /** Used by the post-reboot notification; opening the app is enough to resume. */
        const val ACTION_RESUME = "app.wifiautoswitch.action.RESUME"
        /** From the "new device" notification: open Who's on my Wi-Fi. */
        const val ACTION_OPEN_DEVICES = "app.wifiautoswitch.action.OPEN_DEVICES"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ShizukuConnector.init(applicationContext)
        Notifications.ensureChannels(this)
        Notifications.cancelResumePrompt(this)
        handle(intent)
        val repo = SettingsRepository(applicationContext)
        setContent {
            AppTheme { AppRoot(repo) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent?.action == ACTION_OPEN_DEVICES) AppState.requestedScreen.value = "DEVICES"
    }
}
