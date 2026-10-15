package app.wifiautoswitch

import android.content.Context
import android.net.Network
import app.wifiautoswitch.core.SpeedMath
import app.wifiautoswitch.core.SpeedResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Speed and ping test over the current Wi-Fi (forced onto Wi-Fi even if mobile data is the default).
 *
 *  - Router ping: time to reach your router. Shows Wi-Fi quality on its own; great for comparing floors.
 *  - Internet ping: time to reach Cloudflare's speed-test server.
 *  - Download / upload: 4 parallel streams for ~8 s each using Cloudflare's public speed-test endpoints.
 *    The first second is ignored (connection warm-up). Capped at ~100 MB down / ~50 MB up.
 *
 * Runs only when the user taps "Start test".
 */
class SpeedTester(context: Context) {

    private val app = context.applicationContext

    sealed interface Result {
        data class Ok(val result: SpeedResult, val dataUsedMb: Double) : Result
        data class Failed(val message: String) : Result
    }

    private companion object {
        const val HOST = "speed.cloudflare.com"
        const val STREAMS = 4
        const val PHASE_MS = 8_000L
        const val WARMUP_MS = 1_000L
        const val DOWN_BYTES_PER_STREAM = 25_000_000L
        const val UP_BYTES_PER_STREAM = 12_500_000L
        const val TIMEOUT_MS = 5_000
    }

    /** [networkName] is stored with the result (e.g. "Upstairs"). [progress] gets short status texts. */
    suspend fun run(networkName: String, progress: suspend (String) -> Unit): Result {
        val link = WifiLink.current(app) ?: return Result.Failed("Connect to Wi-Fi first.")
        val network = link.network
        return withContext(Dispatchers.IO) {
            progress("Pinging your router…")
            val routerPing = link.gateway?.let { gw ->
                runCatching { InetAddress.getByName(gw) }.getOrNull()?.let { tcpPingMedian(network, it, 80) }
            }

            progress("Pinging the internet…")
            val server = runCatching { network.getByName(HOST) }.getOrNull()
                ?: return@withContext Result.Failed("No internet on this Wi-Fi (couldn't reach the test server).")
            val ping = tcpPingMedian(network, server, 443)

            progress("Testing download…")
            val down = measure(network, upload = false)
            progress("Testing upload…")
            val up = measure(network, upload = true)

            if (down == null && up == null && ping == null) {
                return@withContext Result.Failed("The test server didn't respond. Check the internet connection.")
            }
            val result = SpeedResult(
                timeMs = System.currentTimeMillis(),
                network = networkName,
                downMbps = down?.first,
                upMbps = up?.first,
                pingMs = ping?.toInt(),
                routerPingMs = routerPing?.toInt(),
            )
            val usedMb = ((down?.second ?: 0L) + (up?.second ?: 0L)) / 1_000_000.0
            Result.Ok(result, usedMb)
        }
    }

    /** Median of 5 TCP handshakes. A "connection refused" reply still measures the round trip. */
    private suspend fun tcpPingMedian(network: Network, host: InetAddress, port: Int): Long? {
        val samples = ArrayList<Long>()
        repeat(5) {
            tcpPing(network, host, port)?.let { samples += it }
            delay(100)
        }
        return SpeedMath.median(samples)
    }

    private fun tcpPing(network: Network, host: InetAddress, port: Int): Long? {
        val socket = runCatching { network.socketFactory.createSocket() }.getOrNull() ?: return null
        val start = System.nanoTime()
        return try {
            socket.connect(InetSocketAddress(host, port), 1_500)
            (System.nanoTime() - start) / 1_000_000
        } catch (e: ConnectException) {
            val ms = (System.nanoTime() - start) / 1_000_000
            if (e.message?.contains("refused", ignoreCase = true) == true) ms else null
        } catch (e: Exception) {
            null
        } finally {
            runCatching { socket.close() }
        }
    }

    /** Returns (Mbps, bytes transferred) or null if nothing could be transferred. */
    private suspend fun measure(network: Network, upload: Boolean): Pair<Double, Long>? = coroutineScope {
        val bytes = AtomicLong(0)
        val stop = AtomicBoolean(false)
        val connections = java.util.Collections.synchronizedList(ArrayList<HttpURLConnection>())
        val start = System.currentTimeMillis()

        val jobs = (1..STREAMS).map {
            async(Dispatchers.IO) {
                runCatching { if (upload) uploadStream(network, bytes, stop, connections) else downloadStream(network, bytes, stop, connections) }
            }
        }
        val watcher = async(Dispatchers.IO) {
            delay(WARMUP_MS)
            val warmBytes = bytes.get()
            val warmAt = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < PHASE_MS && jobs.any { it.isActive }) delay(100)
            stop.set(true)
            val endAt = System.currentTimeMillis()
            synchronized(connections) { connections.forEach { c -> runCatching { c.disconnect() } } }
            Triple(warmBytes, warmAt, endAt)
        }
        val (warmBytes, warmAt, endAt) = watcher.await()
        jobs.awaitAll()
        val total = bytes.get()
        if (total <= 0) return@coroutineScope null
        // Prefer the post-warm-up window; fall back to the whole run for very fast or very short tests.
        val windowBytes = total - warmBytes
        val windowMs = endAt - warmAt
        val mbps = if (windowBytes > 0 && windowMs >= 500) SpeedMath.mbps(windowBytes, windowMs)
        else SpeedMath.mbps(total, (endAt - start).coerceAtLeast(1))
        mbps to total
    }

    private fun open(network: Network, path: String): HttpURLConnection =
        (network.openConnection(URL("https://$HOST$path")) as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            useCaches = false
            setRequestProperty("User-Agent", "WiFiAutoSwitch")
        }

    private fun downloadStream(network: Network, bytes: AtomicLong, stop: AtomicBoolean, list: MutableList<HttpURLConnection>) {
        val c = open(network, "/__down?bytes=$DOWN_BYTES_PER_STREAM")
        list += c
        try {
            val input: InputStream = c.inputStream
            val buf = ByteArray(64 * 1024)
            while (!stop.get()) {
                val n = input.read(buf)
                if (n < 0) break
                bytes.addAndGet(n.toLong())
            }
        } finally {
            runCatching { c.disconnect() }
        }
    }

    private fun uploadStream(network: Network, bytes: AtomicLong, stop: AtomicBoolean, list: MutableList<HttpURLConnection>) {
        val c = open(network, "/__up")
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/octet-stream")
        c.setChunkedStreamingMode(64 * 1024)
        list += c
        try {
            val buf = ByteArray(64 * 1024) { (it * 31).toByte() }
            var sent = 0L
            c.outputStream.use { out ->
                while (!stop.get() && sent < UP_BYTES_PER_STREAM) {
                    out.write(buf)
                    sent += buf.size
                    bytes.addAndGet(buf.size.toLong())
                }
            }
            runCatching { c.responseCode }
        } finally {
            runCatching { c.disconnect() }
        }
    }
}
