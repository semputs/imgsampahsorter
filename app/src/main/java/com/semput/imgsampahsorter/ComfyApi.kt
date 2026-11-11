package com.semput.imgsampahsorter

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/** Talks to the ImgSampah Bridge on the PC (which passes ComfyUI's own API through). */
object ComfyApi {
    const val PORT = 8190

    class HttpError(val code: Int, val body: String) : IOException("HTTP $code: ${body.take(200)}")

    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    /** Run [work] off the main thread, deliver the result (or error) back on it. */
    fun <T> bg(work: () -> T, done: (Result<T>) -> Unit) {
        pool.execute {
            val r = runCatching(work)
            main.post { done(r) }
        }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("comfy", Context.MODE_PRIVATE)

    fun address(ctx: Context): String = prefs(ctx).getString("address", "") ?: ""
    fun code(ctx: Context): String = prefs(ctx).getString("code", "") ?: ""
    fun isPaired(ctx: Context) = address(ctx).isNotBlank() && code(ctx).isNotBlank()

    fun savePairing(ctx: Context, address: String, code: String) {
        prefs(ctx).edit().putString("address", cleanAddress(address)).putString("code", code.trim().uppercase()).apply()
    }

    fun forget(ctx: Context) = prefs(ctx).edit().remove("address").remove("code").apply()

    fun cleanAddress(a: String): String {
        var s = a.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
        if (!s.contains(':')) s = "$s:$PORT"
        return s
    }

    fun base(ctx: Context) = "http://" + address(ctx)

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun open(ctx: Context, method: String, path: String, timeoutMs: Int): HttpURLConnection {
        val c = URL(base(ctx) + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 8000
        c.readTimeout = timeoutMs
        c.setRequestProperty("X-ISB-Token", code(ctx))
        c.useCaches = false
        return c
    }

    /** Returns the response body; throws HttpError for non-2xx. */
    fun request(ctx: Context, method: String, path: String, body: String? = null, timeoutMs: Int = 20000): String {
        val c = open(ctx, method, path, timeoutMs)
        try {
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            if (code !in 200..299) throw HttpError(code, text)
            return text
        } finally {
            c.disconnect()
        }
    }

    fun get(ctx: Context, path: String) = request(ctx, "GET", path)
    fun post(ctx: Context, path: String, body: String) = request(ctx, "POST", path, body)

    /** Download to [dest] via a .part file; returns bytes written. */
    fun download(ctx: Context, path: String, dest: File): Long {
        val c = open(ctx, "GET", path, 60000)
        try {
            val code = c.responseCode
            if (code !in 200..299) throw HttpError(code, c.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: "")
            dest.parentFile?.mkdirs()
            val part = File(dest.parentFile, ".${dest.name}.part")
            var n = 0L
            c.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(65536)
                    while (true) {
                        val r = input.read(buf)
                        if (r < 0) break
                        out.write(buf, 0, r)
                        n += r
                    }
                }
            }
            if (!part.renameTo(dest)) {
                part.delete()
                throw IOException("Couldn't save ${dest.name}")
            }
            return n
        } finally {
            c.disconnect()
        }
    }

    /** Friendly message for an error. */
    fun explain(e: Throwable?): String = when (e) {
        is HttpError -> when (e.code) {
            401 -> "Wrong pairing code."
            403 -> "The PC refused the connection. Is Tailscale on, on both phone and PC?"
            503 -> "ComfyUI is still starting on the PC…"
            else -> "PC said: HTTP ${e.code} ${e.body.take(150)}"
        }
        is java.net.SocketTimeoutException, is java.net.ConnectException, is java.net.NoRouteToHostException ->
            "Can't reach the PC. Check Tailscale is on (phone + PC) and the PC is awake."
        is java.net.UnknownHostException -> "Unknown address. Use the 100.x.x.x address from the PC setup."
        null -> "Unknown error"
        else -> e.message ?: e.javaClass.simpleName
    }
}
