package app.wifiautoswitch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.wifiautoswitch.shizuku.ShizukuConnector
import app.wifiautoswitch.ui.AppTheme
import app.wifiautoswitch.ui.HomeScreen

class MainActivity : ComponentActivity() {

    companion object {
        /** Used by the post-reboot notification; opening the app is enough to resume. */
        const val ACTION_RESUME = "app.wifiautoswitch.action.RESUME"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ShizukuConnector.init(applicationContext)
        Notifications.ensureChannels(this)
        Notifications.cancelResumePrompt(this)
        val repo = SettingsRepository(applicationContext)
        setContent {
            AppTheme { HomeScreen(repo) }
        }
    }
}
