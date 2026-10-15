package app.wifiautoswitch.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import app.wifiautoswitch.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume

/**
 * Optional enhanced switching through the official Shizuku API.
 *
 * The privileged process is started only for the few seconds a switch (or the saved-network list)
 * needs and is torn down straight after, so nothing extra runs in the background.
 * Permission is only ever requested from an explicit user tap, never by the background service.
 */
object ShizukuConnector {

    enum class State(val label: String) {
        NOT_INSTALLED("Shizuku is not installed"),
        NOT_RUNNING("Shizuku is not running"),
        UNSUPPORTED("Shizuku version is too old"),
        PERMISSION_NEEDED("Shizuku access not granted yet"),
        PERMISSION_DENIED("Shizuku access was denied"),
        READY("Shizuku ready"),
    }

    sealed interface ConnectResult {
        data class Requested(val networkId: Int) : ConnectResult
        data object NotSaved : ConnectResult
        data class Failed(val reason: String) : ConnectResult
        data object Unavailable : ConnectResult
    }

    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val REQUEST_CODE = 4127

    private val _state = MutableStateFlow(State.NOT_RUNNING)
    val state: StateFlow<State> = _state.asStateFlow()

    private var appContext: Context? = null
    @Volatile private var deniedThisSession = false

    private val binderReceived = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDead = Shizuku.OnBinderDeadListener { refresh() }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQUEST_CODE) {
            deniedThisSession = grantResult != PackageManager.PERMISSION_GRANTED
            refresh()
        }
    }

    @Synchronized
    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
            runCatching {
                Shizuku.addBinderReceivedListenerSticky(binderReceived)
                Shizuku.addBinderDeadListener(binderDead)
                Shizuku.addRequestPermissionResultListener(permissionResult)
            }
        }
        refresh()
    }

    fun refresh() {
        val ctx = appContext ?: return
        _state.value = try {
            when {
                !Shizuku.pingBinder() -> if (isInstalled(ctx)) State.NOT_RUNNING else State.NOT_INSTALLED
                Shizuku.isPreV11() -> State.UNSUPPORTED
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> {
                    deniedThisSession = false
                    State.READY
                }
                // In Shizuku's API this returns true when the user chose "deny and don't ask again".
                Shizuku.shouldShowRequestPermissionRationale() -> State.PERMISSION_DENIED
                deniedThisSession -> State.PERMISSION_DENIED
                else -> State.PERMISSION_NEEDED
            }
        } catch (t: Throwable) {
            State.NOT_RUNNING
        }
    }

    val isReady: Boolean get() = _state.value == State.READY

    /** Call only from a user action. Returns false if Shizuku can't show its dialog (open the app instead). */
    fun requestPermission(): Boolean {
        refresh()
        val s = _state.value
        if (s != State.PERMISSION_NEEDED && s != State.PERMISSION_DENIED) return false
        return try {
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                false
            } else {
                Shizuku.requestPermission(REQUEST_CODE)
                true
            }
        } catch (t: Throwable) {
            false
        }
    }

    fun openShizukuApp(context: Context): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE) ?: return false
        return runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }

    private fun isInstalled(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private fun serviceArgs(ctx: Context) =
        Shizuku.UserServiceArgs(ComponentName(ctx.packageName, PrivilegedWifiService::class.java.name))
            .daemon(false)
            .processNameSuffix("wifi")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)

    /** Starts the privileged process, runs [block], then removes the process again. Main thread only. */
    private suspend fun <T> withService(timeoutMs: Long = 15_000, block: (IPrivilegedWifi) -> T): T? {
        val ctx = appContext ?: return null
        refresh()
        if (_state.value != State.READY) return null
        val args = serviceArgs(ctx)
        var connection: ServiceConnection? = null
        try {
            val binder = withTimeout(timeoutMs) {
                suspendCancellableCoroutine<IBinder> { cont ->
                    val conn = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                            if (service != null && service.pingBinder() && cont.isActive) cont.resume(service)
                        }

                        override fun onServiceDisconnected(name: ComponentName?) = Unit
                    }
                    connection = conn
                    Shizuku.bindUserService(args, conn)
                }
            }
            val api = IPrivilegedWifi.Stub.asInterface(binder) ?: return null
            return withContext(Dispatchers.IO) { block(api) }
        } finally {
            connection?.let { conn -> runCatching { Shizuku.unbindUserService(args, conn, true) } }
        }
    }

    suspend fun connectSaved(ssid: String): ConnectResult {
        refresh()
        if (!isReady) return ConnectResult.Unavailable
        return try {
            val reply = withService { it.connectSaved(ssid) } ?: return ConnectResult.Unavailable
            when {
                reply.startsWith("ok:") -> ConnectResult.Requested(reply.removePrefix("ok:").toIntOrNull() ?: -1)
                reply == "notfound" -> ConnectResult.NotSaved
                else -> ConnectResult.Failed(reply.removePrefix("error:"))
            }
        } catch (e: TimeoutCancellationException) {
            ConnectResult.Failed("Shizuku service did not start in time")
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            refresh()
            ConnectResult.Failed(t.message ?: t.javaClass.simpleName)
        }
    }

    suspend fun listSavedSsids(): List<String> = try {
        withService { it.listSavedSsids() }?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    } catch (e: TimeoutCancellationException) {
        emptyList()
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        emptyList()
    }
}
