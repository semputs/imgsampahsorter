package com.semput.imgsampahsorter

import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.util.zip.InflaterInputStream

/**
 * Reads the text chunks ComfyUI stores in its PNGs: "workflow" (the editable graph)
 * and "prompt" (what was actually run).
 */
object PngMeta {
    private val SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

    fun readText(file: File): Map<String, String> {
        val out = HashMap<String, String>()
        DataInputStream(file.inputStream().buffered()).use { input ->
            val sig = ByteArray(8)
            input.readFully(sig)
            if (!sig.contentEquals(SIGNATURE)) return out
            while (true) {
                val len = try { input.readInt() } catch (_: IOException) { break }
                if (len < 0) break
                val typeBytes = ByteArray(4)
                input.readFully(typeBytes)
                val type = String(typeBytes, Charsets.ISO_8859_1)
                if (type == "tEXt" || type == "iTXt" || type == "zTXt") {
                    if (len > 64 * 1024 * 1024) break
                    val data = ByteArray(len)
                    input.readFully(data)
                    runCatching { parse(type, data) }.getOrNull()?.let { (k, v) -> out[k] = v }
                } else {
                    var left = len.toLong()
                    while (left > 0) {
                        val skipped = input.skip(left)
                        if (skipped <= 0) { input.readByte(); left-- } else left -= skipped
                    }
                }
                input.readInt() // CRC
                if (type == "IEND") break
            }
        }
        return out
    }

    private fun parse(type: String, d: ByteArray): Pair<String, String> {
        val z = d.indexOf(0)
        val key = String(d, 0, z, Charsets.ISO_8859_1)
        return when (type) {
            "tEXt" -> key to String(d, z + 1, d.size - z - 1, Charsets.ISO_8859_1)
            "zTXt" -> key to inflate(d.copyOfRange(z + 2, d.size)).toString(Charsets.ISO_8859_1)
            else -> { // iTXt: key\0 flag method lang\0 translated\0 text
                val compressed = d[z + 1].toInt() != 0
                var p = z + 3
                p = d.indexOf(0, p) + 1      // after language tag
                p = d.indexOf(0, p) + 1      // after translated keyword
                val body = d.copyOfRange(p, d.size)
                key to (if (compressed) inflate(body) else body).toString(Charsets.UTF_8)
            }
        }
    }

    private fun ByteArray.indexOf(b: Int, from: Int = 0): Int {
        for (i in from until size) if (this[i].toInt() == b) return i
        throw IOException("bad text chunk")
    }

    private fun inflate(b: ByteArray) = InflaterInputStream(b.inputStream()).use { it.readBytes() }
}
