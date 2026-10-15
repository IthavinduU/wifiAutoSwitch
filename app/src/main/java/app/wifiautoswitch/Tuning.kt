package app.wifiautoswitch

import app.wifiautoswitch.core.ThresholdTuner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-wide auto-tune data shared by the service (adds readings) and the UI (shows/apply/reset). */
object Tuning {
    data class State(val readings: Int = 0, val suggestion: Int? = null)

    private const val SAVE_EVERY = 10
    private val tuner = ThresholdTuner()
    private val lock = Any()
    private var loaded = false
    private var unsaved = 0

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    suspend fun load(repo: SettingsRepository) {
        if (loaded) return
        val data = repo.loadTuning()
        synchronized(lock) {
            if (loaded) return
            tuner.load(data)
            loaded = true
            publish()
        }
    }

    /** Ignored until stored data has been loaded, so nothing is overwritten. */
    fun add(currentRssi: Int, otherRssi: Int, minImprovementDb: Int) {
        synchronized(lock) {
            if (!loaded) return
            if (tuner.add(currentRssi, otherRssi, minImprovementDb)) {
                unsaved++
                publish()
            }
        }
    }

    suspend fun saveIfNeeded(repo: SettingsRepository, force: Boolean = false) {
        val data = synchronized(lock) {
            if (unsaved == 0 || (!force && unsaved < SAVE_EVERY)) return
            unsaved = 0
            tuner.serialize()
        }
        repo.saveTuning(data)
    }

    suspend fun reset(repo: SettingsRepository) {
        synchronized(lock) {
            tuner.reset()
            unsaved = 0
            loaded = true
            publish()
        }
        repo.saveTuning("")
    }

    private fun publish() {
        _state.value = State(tuner.total, tuner.suggest())
    }
}
