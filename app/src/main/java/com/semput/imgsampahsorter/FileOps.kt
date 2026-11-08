package com.semput.imgsampahsorter

import android.content.Context
import android.media.MediaScannerConnection
import java.io.File

object FileOps {
    const val GOOD = "good"
    const val TRASH = "trash"

    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")

    val nameOrder: Comparator<File> = compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }

    fun isImage(f: File): Boolean =
        f.isFile && !f.name.startsWith(".") && f.extension.lowercase() in IMAGE_EXT

    fun listImages(dir: File): MutableList<File> =
        (dir.listFiles()?.filter { isImage(it) } ?: emptyList())
            .sortedWith(nameOrder)
            .toMutableList()

    fun listSubfolders(dir: File): List<File> =
        (dir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") } ?: emptyList())
            .sortedWith(nameOrder)

    /** Files sitting in the trash folder (ignores the hidden .nomedia marker). */
    fun trashFiles(dir: File): List<File> =
        File(dir, TRASH).listFiles()?.filter { it.isFile && !it.name.startsWith(".") } ?: emptyList()

    fun ensureDir(parent: File, name: String, hideFromGallery: Boolean): File {
        val d = File(parent, name)
        if (!d.exists()) d.mkdirs()
        if (hideFromGallery) {
            val marker = File(d, ".nomedia")
            if (!marker.exists()) {
                try { marker.createNewFile() } catch (_: Exception) { }
            }
        }
        return d
    }

    /** A path in [dir] named [name] that doesn't exist yet (adds _1, _2... if needed). */
    fun uniqueTarget(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.')
        val ext = if (name.contains('.')) name.substringAfterLast('.') else ""
        var i = 1
        while (true) {
            f = File(dir, if (ext.isEmpty()) "${base}_$i" else "${base}_$i.$ext")
            if (!f.exists()) return f
            i++
        }
    }

    fun move(src: File, dest: File): Boolean {
        if (src.renameTo(dest)) return true
        return try {
            src.copyTo(dest)
            if (src.delete()) true else { dest.delete(); false }
        } catch (_: Exception) {
            false
        }
    }

    /** Tell the phone's gallery that these paths changed. */
    fun scan(ctx: Context, files: Collection<File>) {
        if (files.isEmpty()) return
        MediaScannerConnection.scanFile(
            ctx.applicationContext,
            files.map { it.absolutePath }.toTypedArray(),
            null,
            null
        )
    }
}
