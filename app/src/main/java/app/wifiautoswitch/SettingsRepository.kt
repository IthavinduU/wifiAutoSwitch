package app.wifiautoswitch

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.wifiautoswitch.core.Role
import app.wifiautoswitch.core.Thresholds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class Settings(
    val upstairsSsid: String = "",
    val downstairsSsid: String = "",
    val autoSwitch: Boolean = false,
    val resumeAfterReboot: Boolean = true,
    val weakDbm: Int = DEFAULT.weakDbm,
    val minImprovementDb: Int = DEFAULT.minImprovementDb,
    val cooldownSec: Int = (DEFAULT.cooldownMs / 1000).toInt(),
    val requiredObservations: Int = DEFAULT.requiredObservations,
    /** Wait until a phone/VoIP call ends before switching (a switch drops the connection briefly). */
    val pauseDuringCalls: Boolean = true,
    /** Set once Shizuku has worked; enables the "Shizuku stopped" alert. */
    val shizukuSetUp: Boolean = false,
    /** Move to the other home network when the current one has no internet. */
    val failover: Boolean = true,
    /** While charging, check for unknown devices every ~15 minutes. */
    val newDeviceAlerts: Boolean = true,
) {
    val networksConfigured: Boolean
        get() = upstairsSsid.isNotBlank() && downstairsSsid.isNotBlank() && upstairsSsid != downstairsSsid

    fun ssidFor(role: Role): String = if (role == Role.UPSTAIRS) upstairsSsid else downstairsSsid

    fun roleOf(ssid: String?): Role? = when {
        ssid.isNullOrEmpty() -> null
        ssid == upstairsSsid -> Role.UPSTAIRS
        ssid == downstairsSsid -> Role.DOWNSTAIRS
        else -> null
    }

    fun thresholds(): Thresholds = DEFAULT.copy(
        weakDbm = weakDbm,
        minImprovementDb = minImprovementDb,
        cooldownMs = cooldownSec * 1000L,
        requiredObservations = requiredObservations,
    ).sanitized()

    fun withDefaultThresholds(): Settings = copy(
        weakDbm = DEFAULT.weakDbm,
        minImprovementDb = DEFAULT.minImprovementDb,
        cooldownSec = (DEFAULT.cooldownMs / 1000).toInt(),
        requiredObservations = DEFAULT.requiredObservations,
    )

    companion object {
        val DEFAULT = Thresholds()
    }
}

private val Context.dataStore by preferencesDataStore(name = "settings")

/** All settings stay on the device (DataStore file in app-private storage; backups disabled). */
class SettingsRepository(context: Context) {
    private val store = context.applicationContext.dataStore

    private object Keys {
        val UP = stringPreferencesKey("upstairs_ssid")
        val DOWN = stringPreferencesKey("downstairs_ssid")
        val AUTO = booleanPreferencesKey("auto_switch")
        val RESUME = booleanPreferencesKey("resume_after_reboot")
        val WEAK = intPreferencesKey("weak_dbm")
        val IMPROVE = intPreferencesKey("min_improvement_db")
        val COOLDOWN = intPreferencesKey("cooldown_sec")
        val OBS = intPreferencesKey("required_observations")
        val CALLS = booleanPreferencesKey("pause_during_calls")
        val SHIZUKU = booleanPreferencesKey("shizuku_set_up")
        /** Auto-tune counts; kept outside [Settings] so saving them doesn't look like a settings change. */
        val TUNING = stringPreferencesKey("tuning_histogram")
        /** Who's on my Wi-Fi: MAC addresses already seen per network (to flag new devices). */
        val KNOWN_DEVICES = stringPreferencesKey("known_devices")
        val FAILOVER = booleanPreferencesKey("failover")
        val DEVICE_ALERTS = booleanPreferencesKey("new_device_alerts")
        val OUTAGES = stringPreferencesKey("outage_log")
        val SPEED = stringPreferencesKey("speed_history")
    }

    val settings: Flow<Settings> = store.data
        .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
        .map { it.toSettings() }
        .distinctUntilChanged()

    suspend fun update(transform: (Settings) -> Settings) {
        store.edit { prefs ->
            val s = transform(prefs.toSettings())
            prefs[Keys.UP] = s.upstairsSsid.trim()
            prefs[Keys.DOWN] = s.downstairsSsid.trim()
            prefs[Keys.AUTO] = s.autoSwitch
            prefs[Keys.RESUME] = s.resumeAfterReboot
            prefs[Keys.WEAK] = s.weakDbm
            prefs[Keys.IMPROVE] = s.minImprovementDb
            prefs[Keys.COOLDOWN] = s.cooldownSec
            prefs[Keys.OBS] = s.requiredObservations
            prefs[Keys.CALLS] = s.pauseDuringCalls
            prefs[Keys.SHIZUKU] = s.shizukuSetUp
            prefs[Keys.FAILOVER] = s.failover
            prefs[Keys.DEVICE_ALERTS] = s.newDeviceAlerts
        }
    }

    private suspend fun loadText(key: Preferences.Key<String>): String =
        runCatching { store.data.first()[key] }.getOrNull() ?: ""

    private suspend fun saveText(key: Preferences.Key<String>, value: String) {
        runCatching { store.edit { it[key] = value } }
    }

    suspend fun loadOutages(): String = loadText(Keys.OUTAGES)
    suspend fun saveOutages(data: String) = saveText(Keys.OUTAGES, data)
    suspend fun loadSpeedHistory(): String = loadText(Keys.SPEED)
    suspend fun saveSpeedHistory(data: String) = saveText(Keys.SPEED, data)

    suspend fun loadTuning(): String = runCatching { store.data.first()[Keys.TUNING] }.getOrNull() ?: ""

    suspend fun loadKnownDevices(): String = runCatching { store.data.first()[Keys.KNOWN_DEVICES] }.getOrNull() ?: ""

    suspend fun saveKnownDevices(data: String) {
        runCatching { store.edit { it[Keys.KNOWN_DEVICES] = data } }
    }

    suspend fun saveTuning(data: String) {
        runCatching { store.edit { it[Keys.TUNING] = data } }
    }

    private fun Preferences.toSettings(): Settings {
        val d = Settings()
        return Settings(
            upstairsSsid = this[Keys.UP] ?: d.upstairsSsid,
            downstairsSsid = this[Keys.DOWN] ?: d.downstairsSsid,
            autoSwitch = this[Keys.AUTO] ?: d.autoSwitch,
            resumeAfterReboot = this[Keys.RESUME] ?: d.resumeAfterReboot,
            weakDbm = this[Keys.WEAK] ?: d.weakDbm,
            minImprovementDb = this[Keys.IMPROVE] ?: d.minImprovementDb,
            cooldownSec = this[Keys.COOLDOWN] ?: d.cooldownSec,
            requiredObservations = this[Keys.OBS] ?: d.requiredObservations,
            pauseDuringCalls = this[Keys.CALLS] ?: d.pauseDuringCalls,
            shizukuSetUp = this[Keys.SHIZUKU] ?: d.shizukuSetUp,
            failover = this[Keys.FAILOVER] ?: d.failover,
            newDeviceAlerts = this[Keys.DEVICE_ALERTS] ?: d.newDeviceAlerts,
        )
    }
}
