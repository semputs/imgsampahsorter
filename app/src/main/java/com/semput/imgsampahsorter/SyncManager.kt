package com.semput.imgsampahsorter

import android.content.Context
import android.os.Environment
import org.json.JSONObject
import java.io.File

/**
 * While the app is open, keeps pulling finished images from the PC's chosen output
 * folders into Pictures/ComfyUI/<folder>/ on the phone. The PC deletes its copy only
 * after the phone confirms it has the whole file.
 */
object SyncManager {
    @Volatile var downloaded = 0; private set
    @Volatile var waitingOnPc = 0; private set
    @Volatile var lastError: String? = null; private set
    @Volatile var lastFile: String? = null; private set
    @Volatile private var thread: Thread? = null

    @Volatile var lastDir: File? = null; private set

    fun phoneRoot(): File = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "ComfyUI")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("comfy", Context.MODE_PRIVATE)

    /** Phone folder that images from PC output folder [pcFolder] go into. */
    fun destFor(ctx: Context, pcFolder: String): File =
        prefs(ctx).getString("dest:$pcFolder", null)?.let { File(it) } ?: File(phoneRoot(), pcFolder)

    fun isCustomDest(ctx: Context, pcFolder: String) = prefs(ctx).contains("dest:$pcFolder")

    fun setDest(ctx: Context, pcFolder: String, dir: File?) {
        val e = prefs(ctx).edit()
        if (dir == null) e.remove("dest:$pcFolder") else e.putString("dest:$pcFolder", dir.absolutePath)
        e.apply()
    }

    fun start(ctx: Context) {
        if (!ComfyApi.isPaired(ctx)) return
        if (thread?.isAlive == true) return
        val app = ctx.applicationContext
        thread = Thread({ loop(app) }, "comfy-sync").apply { isDaemon = true; start() }
    }

    private fun loop(ctx: Context) {
        var idleMs = 4000L
        while (ComfyApi.isPaired(ctx)) {
            val worked = try {
                val n = cycle(ctx)
                lastError = null
                idleMs = 4000L
                n > 0
            } catch (e: Throwable) {
                lastError = ComfyApi.explain(e)
                idleMs = (idleMs * 2).coerceAtMost(30000L)
                false
            }
            try { Thread.sleep(if (worked) 200 else idleMs) } catch (_: InterruptedException) { return }
        }
        thread = null
    }

    /** Moves up to a batch of files. Returns how many were moved. */
    private fun cycle(ctx: Context): Int {
        val res = JSONObject(ComfyApi.get(ctx, "/isb/pending"))
        val files = res.getJSONArray("files")
        waitingOnPc = res.optInt("total", files.length())
        var moved = 0
        for (i in 0 until minOf(files.length(), 20)) {
            val f = files.getJSONObject(i)
            val rel = f.getString("path")
            val size = f.getLong("size")
            if (rel.split('/').any { it == ".." || it.isEmpty() }) continue
            val parts = rel.split('/')
            var dest = File(destFor(ctx, parts[0]), parts.drop(1).joinToString("/"))
            val alreadyHave = dest.exists() && dest.length() == size
            if (!alreadyHave) {
                if (dest.exists()) dest = FileOps.uniqueTarget(dest.parentFile!!, dest.name)
                val n = ComfyApi.download(ctx, "/isb/file?path=" + ComfyApi.enc(rel), dest)
                if (n != size) {
                    dest.delete()
                    throw java.io.IOException("Incomplete download of ${dest.name}")
                }
                FileOps.scan(ctx, listOf(dest))
            }
            ComfyApi.post(ctx, "/isb/ack", JSONObject().put("path", rel).put("size", size).toString())
            downloaded++
            moved++
            waitingOnPc = (waitingOnPc - 1).coerceAtLeast(0)
            lastFile = rel
            lastDir = dest.parentFile
        }
        return moved
    }
}
