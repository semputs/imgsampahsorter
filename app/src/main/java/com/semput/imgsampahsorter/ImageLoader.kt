package com.semput.imgsampahsorter

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.File
import java.util.Collections
import java.util.concurrent.Executors

/**
 * Decodes images off the main thread at roughly screen size and keeps a few in memory,
 * so swiping to the next picture is instant.
 */
class ImageLoader(private val target: Int) {

    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 4).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount / 1024
    }
    private val pending = Collections.synchronizedSet(HashSet<String>())
    private val exec = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    fun cached(file: File): Bitmap? = cache.get(file.absolutePath)

    fun load(file: File, callback: (Bitmap?) -> Unit) {
        cached(file)?.let { callback(it); return }
        exec.execute {
            val bmp = decodeAndCache(file)
            main.post { callback(bmp) }
        }
    }

    fun prefetch(file: File) {
        val key = file.absolutePath
        if (cache.get(key) != null || !pending.add(key)) return
        exec.execute {
            try { decodeAndCache(file) } finally { pending.remove(key) }
        }
    }

    fun shutdown() {
        exec.shutdownNow()
        cache.evictAll()
    }

    private fun decodeAndCache(file: File): Bitmap? {
        cache.get(file.absolutePath)?.let { return it }
        val bmp = try { decode(file) } catch (_: Throwable) { null }
        if (bmp != null) cache.put(file.absolutePath, bmp)
        return bmp
    }

    private fun sampleFor(w: Int, h: Int): Int {
        var s = 1
        while (w / (s * 2) >= target && h / (s * 2) >= target) s *= 2
        while ((w / s).toLong() * (h / s).toLong() > 16_000_000L) s *= 2
        return s
    }

    private fun decode(file: File): Bitmap? {
        if (Build.VERSION.SDK_INT >= 28) {
            val src = ImageDecoder.createSource(file)
            return ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                decoder.setTargetSampleSize(sampleFor(info.size.width, info.size.height))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight)
        }
        val bmp = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
        val degrees = try {
            when (ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (_: Exception) { 0f }
        if (degrees == 0f) return bmp
        val m = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (rotated != bmp) bmp.recycle()
        return rotated
    }
}
